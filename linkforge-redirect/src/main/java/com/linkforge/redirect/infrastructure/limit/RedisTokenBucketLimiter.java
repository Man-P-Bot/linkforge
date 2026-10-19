package com.linkforge.redirect.infrastructure.limit;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Redis + Lua 令牌桶限流（docs/ARCHITECTURE.md §11，DR-002）。
 *
 * <h2>⚠️ 为什么必须是 Lua，不能用 GET + SET 两步</h2>
 * 令牌桶的逻辑是「读当前令牌 → 按时间补充 → 判断是否够 → 扣减 → 写回」。
 * 拆成多条命令时，两个并发请求会读到**同一个令牌数**，各自判断通过，
 * 实际放行了双倍流量——限流失效是<b>静默故障</b>，没有任何错误信号。
 *
 * <p>Redis 单线程执行 Lua 脚本，整个脚本期间不会被其它命令插入，
 * 这才是原子性的真正来源（不是任何锁机制）。
 *
 * <h2>⚠️ 时间源必须是 {@code redis.call('TIME')}，不能传Java 侧时间戳</h2>
 * 多实例部署时各机器时钟有偏差（可达秒级），而补充令牌的逻辑对时间精度敏感：
 * 时钟偏快的实例会算出「经过了更久」，从而**多补令牌**。
 * {@code TIME} 是 Redis 单点权威时间。
 *
 * <h2>⚠️ 降级策略：Redis 不可用时**放行**</h2>
 * 限流是保护措施，不是正确性来源。保护措施自己变成故障源是本末倒置。
 * 失败时放行并记 WARN + 计数（ROADMAP 第 3 周任务 5）。
 */
@Component
public class RedisTokenBucketLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisTokenBucketLimiter.class);

    /**
     * 令牌桶脚本。
     *
     * <p>返回 {@code {allowed, retryAfterMs}}：
     * <ul>
     *   <li>{@code allowed=1} 放行；{@code allowed=0} 拒绝，retryAfterMs 建议等待毫秒</li>
     * </ul>
     */
    @SuppressWarnings("rawtypes")
    /**
     * ⚠️ 包级可见（不是 private）：测试需要读脚本源码，
     * 断言「PEXPIRE 被包在首次创建的 if 里」——
     * 这是防**改代码**的回归（有人把 PEXPIRE 挪回无条件执行），
     * 运行时 TTL 那种断言属于层 3 真实 Redis 的职责。
     */
    static final RedisScript<List> TOKEN_BUCKET = new DefaultRedisScript<>("""
            -- ⚠️ 唯一时间源：Redis 服务端时间。绝不用 KEYS/ARGV 传入客户端时间戳
            local t = redis.call('TIME')
            local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)

            local capacity     = tonumber(ARGV[1])
            local refillPerMs  = tonumber(ARGV[2])

            -- HMGET 未命中时返回 {false, false}（不是 nil），
            -- 这是下面「首次创建才设 TTL」的判据
            local state = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
            local tokens = tonumber(state[1])
            local ts= tonumber(state[2])

            if tokens == nil or ts == nil then
              -- 首次访问：给满桶。与「从 0 开始」相比，
              -- 给满桶不会误伤单个用户的第一个请求
              tokens = capacity
              ts = now
            end

            -- 按经过的时间补充令牌，上限为桶容量
            local elapsed = now - ts
            if elapsed > 0 then
              tokens = math.min(capacity, tokens + elapsed * refillPerMs)
            end

            local allowed = 0
            local retryAfterMs = 0
            if tokens >= 1 then
              allowed = 1
              tokens = tokens - 1
            else
              -- 还差多少令牌，以及补一个令牌要多久
              local deficit = 1 - tokens
              retryAfterMs = math.ceil(deficit / refillPerMs)
            end

            redis.call('HSET', KEYS[1], 'tokens', tokens, 'ts', now)

            -- ⚠️ PEXPIRE **只在首次创建 key 时设置一次**，不每次刷新（DR-056）：
            -- 原实现每次请求都 PEXPIRE，等于每请求多一条**写命令**。
            -- 压测实测：限流开启时 QPS 2.1 万，关闭时 5.7 万——
            -- 每请求一次同步 Redis 往返（约 0.5ms）在 Tomcat 线程里累积，
            -- 把吞吐吃掉 63%。⚠️ 但 CPU 采样显示 **Redis 只有 0.4% 占用**，
            -- 说明瓶颈是**应用线程同步等待**，不是 Redis CPU——
            -- 去掉 PEXPIRE 省的是一次网络往返延迟，**不是** Redis 计算资源。
            --
            -- 为什么可以不每次刷新：
            --   · key 里存了 ts，补令牌逻辑本来就靠 elapsed 判断，
            --     即使 key 活得比 TTL 久，超期的桶也会被「补满」而非「拒绝」
            --   · 真正的风险是**空闲 IP 的 key 永不消失**，
            --     由 Redis 的 maxmemory 淘汰策略兜底（部署配置 allkeys-lru）
            if state[1] == false or state[2] == false then
              redis.call('PEXPIRE', KEYS[1], math.ceil(ARGV[3]))
            end
            return {allowed, retryAfterMs}
            """, List.class);

    private final StringRedisTemplate redis;
    private final RateLimitScope scopeConfig;
    private final boolean enabled;
    private final Counter rejectedCounter;
    private final Counter degradedCounter;
    private final Counter bypassedCounter;

    public RedisTokenBucketLimiter(StringRedisTemplate redis,
                                   RateLimitScope scopeConfig,
                                   MeterRegistry meterRegistry,
                                   @org.springframework.beans.factory.annotation.Value(
                                           "${linkforge.ratelimit.enabled:true}") boolean enabled) {
        this.redis = redis;
        this.scopeConfig = scopeConfig;
        this.enabled = enabled;
        this.rejectedCounter = Counter.builder("shortlink.ratelimit.rejected")
                .description("被限流拒绝的请求数（按 scope 打 tag）")
                .register(meterRegistry);
        this.bypassedCounter = Counter.builder("shortlink.ratelimit.bypassed")
                .description("限流被显式关闭（linkforge.ratelimit.enabled=false）时跳过的请求数。"
                        + "非零说明该实例**没有限流保护**，不可用于生产")
                .register(meterRegistry);
        this.degradedCounter = Counter.builder("shortlink.ratelimit.degraded")
                .description("限流器不可用时**放行**的次数。持续增长说明 Redis 有问题，"
                        + "此时系统正暴露在无限流状态")
                .register(meterRegistry);
    }

    /**
     * 尝试获取一个令牌。
     *
     * @return 允许时返回 {@link Decision#permit()}；
     *         拒绝时返回带建议等待时间的 {@link Decision#reject(Duration)}
     */
    public Decision tryAcquire(String clientId, String scope) {
        // ⚠️ 开关：linkforge.ratelimit.enabled=false 时**完全不执行 Lua**。
        // 它的存在不只是为了压测——压测需要 A/B 对照「限流本身占多少 QPS」，
        // 而把阈值调到很大**做不到这一点**：Lua 每次仍要执行，
        // 只是不再拒绝。只有真正跳过 EVAL 才能隔离出它的成本。
        if (!enabled) {
            bypassedCounter.increment();
            return Decision.permit();
        }

        String key = scopeConfig.key(clientId, scope);
        // PEXPIRE = 窗口的 2 倍：足够覆盖一次完整的补充周期，又不会让 key 长期滞留
        long ttlMs = scopeConfig.window(scope).toMillis() * 2;

        List<Object> raw;
        try {
            raw = redis.execute(TOKEN_BUCKET, List.of(key),
                    String.valueOf(scopeConfig.rate(scope)),
                    String.valueOf(scopeConfig.refillPerMillisecond(scope)),
                    String.valueOf(ttlMs));
        } catch (RuntimeException e) {
            // ⚠️ 降级放行。限流是保护措施，它自己变成故障源是本末倒置。
            // ⚠️ 捕获 RuntimeException 而非 DataAccessException：Redis Lua 脚本
            // 在语法错误、类型不匹配时抛的是 RedisSystemException/RedisCommandExecutionException
            // 等其它 RuntimeException，只catch DataAccessException 会让异常漏到 Controller，
            // 把「限流不可用」变成「跳转 500」——正好是我们要避免的结果。
            degradedCounter.increment();
            log.warn("限流器不可用，本请求放行（未限流）: scope={} ip={} {}",
                    scope, clientId, e.toString());
            return Decision.permit();
        }

        List<?> r = (List<?>) raw;
        int allowed = ((Number) r.get(0)).intValue();
        long retryAfterMs = ((Number) r.get(1)).longValue();
        if (allowed == 1) {
            return Decision.permit();
        }
        rejectedCounter.increment();
        return Decision.reject(Duration.ofMillis(Math.max(retryAfterMs, 1)));
    }

    /**
     * 限流判定结果。
     *
     * @param allowed 是否放行
     * @param retryAfter 建议等待时长（放行时为 {@link Duration#ZERO}）
     */
    // ⚠️ 静态工厂刻意不叫 allowed()/rejected()——那会与 record 的访问器
    // allowed() 同名，Java 不允许两者共存（组件名必须与访问器返回类型匹配）
    public record Decision(boolean allowed, Duration retryAfter) {
        public static Decision permit() {
            return new Decision(true, Duration.ZERO);
        }

        public static Decision reject(Duration retryAfter) {
            return new Decision(false, retryAfter);
        }
    }
}