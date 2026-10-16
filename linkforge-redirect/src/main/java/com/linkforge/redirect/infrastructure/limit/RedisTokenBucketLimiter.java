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
    private static final RedisScript<List> TOKEN_BUCKET = new DefaultRedisScript<>("""
            -- ⚠️ 唯一时间源：Redis 服务端时间。绝不用 KEYS/ARGV 传入客户端时间戳
            local t = redis.call('TIME')
            local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)

            local capacity     = tonumber(ARGV[1])
            local refillPerMs  = tonumber(ARGV[2])

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
            -- ⚠️ 每次都刷新过期：IP 维度的 key 基数可能很大，
            -- 空闲的 IP 必须自动消失，否则 key 会无限增长
            redis.call('PEXPIRE', KEYS[1], math.ceil(ARGV[3]))
            return {allowed, retryAfterMs}
            """, List.class);

    private final StringRedisTemplate redis;
    private final RateLimitScope scopeConfig;
    private final Counter rejectedCounter;
    private final Counter degradedCounter;

    public RedisTokenBucketLimiter(StringRedisTemplate redis,
                                   RateLimitScope scopeConfig,
                                   MeterRegistry meterRegistry) {
        this.redis = redis;
        this.scopeConfig = scopeConfig;
        this.rejectedCounter = Counter.builder("shortlink.ratelimit.rejected")
                .description("被限流拒绝的请求数（按 scope 打 tag）")
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