package com.linkforge.redirect.infrastructure.cache;

import com.linkforge.core.infrastructure.cache.BloomFilter;
import com.linkforge.core.infrastructure.cache.BloomHash;
import com.linkforge.core.infrastructure.cache.CacheKeys;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Redis 布隆过滤器（docs/ARCHITECTURE.md §7.1，DR-007）。
 *
 * <h2>规格</h2>
 * n=1000万 / p=0.01 / m=95,850,584 位 ≈ 11.43 MiB≈ 12 MB / k=7。
 * 推导见 ARCHITECTURE.md §7.1「规格推导」。
 *
 * <h2>⚠️ 为什么用 Lua 而不是 pipelining（ARCHITECTURE.md §147 要求）</h2>
 * pipelining 是「一次网络发送、多次响应」，客户端仍要等两次响应才能决定下一步；
 * Lua 在 Redis 服务端执行完再返回，才能真正做到「按布隆结果分支」，
 * 把「布隆 + 缓存查询」压成<b>一次网络往返</b>。
 *
 * <p>⚠️ <b>bit 下标在 Java 侧算好再传进 Lua</b>，不在 Lua 里算。
 * Lua 里算哈希需要 {@code redis.sha1hex} 这类命令，每个短码要多几次调用，
 * 反而更慢；Java 侧算好还能复用。
 */
@Component
public class RedisBloomFilter implements BloomFilter {

    private static final Logger log = LoggerFactory.getLogger(RedisBloomFilter.class);

    /**
     * 布隆检查 + 缓存查询合并脚本。
     *
     * <p>返回：
     * <ul>
     *   <li>{@code {0, nil}} —— 一定不存在（布隆判否），<b>调用方应直接 404</b></li>
     *   <li>{@code {1, value}} —— 可能存在且缓存有值</li>
     *   <li>{@code {1, false}} —— 可能存在但缓存无值，需回源</li>
     * </ul>
     *
     * <p>⚠️ 布隆 key 不存在时（首次部署前或重建期间）返回 {@code {1, false}}——
     * <b>放行</b>。若返回「不存在」，第一次部署后所有请求都会404。
     */
    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> CHECK_AND_GET = new DefaultRedisScript<>("""
            local bloomExists = redis.call('EXISTS', KEYS[1])
            if bloomExists == 0 then
              -- 布隆未初始化：放行，让它继续查缓存/回源。
              -- 首次部署或重建窗口期都走这里，绝不能返回「不存在」。
              return {1, false}
            end

            -- ARGV 全部是 bit 下标，**没有占位符**。
            -- 曾用 'for i = 2' 配合一个占位元素，结果只检查了 k-1 = 6 个位：
            -- 少查一位不会崩溃，Lua 也不报错——它只是静默地少判一位。
            -- ⚠️ 真实危害是**k 与设计规格不符**（文档 7、代码 6），
            --    不是误判率恶化：n=1000万/p=0.01 时 k=7 是 1.0039%、k=6 是 1.0143%，
            --    差异仅 0.01 个百分点。详见 DR-054。
            for i = 1, #ARGV do
              if redis.call('GETBIT', KEYS[1], ARGV[i]) == 0 then
                return {0, false}
              end
            end

            -- 7 位全为 1：可能存在，查 L2
            local cached = redis.call('GET', KEYS[2])
            if cached then
              return {1, cached}
            end
            return {1, false}
            """, List.class);

    private final StringRedisTemplate redis;
    private final Counter blockedCounter;
    private final Counter putFailedCounter;

    public RedisBloomFilter(StringRedisTemplate redis, MeterRegistry meterRegistry) {
        this.redis = redis;
        this.blockedCounter = Counter.builder("shortlink.bloom.blocked")
                .description("被布隆过滤器挡掉（判定为一定不存在）的请求数")
                .register(meterRegistry);
        this.putFailedCounter = Counter.builder("shortlink.bloom.put.failed")
                .description("布隆写穿失败次数。失败不阻塞创建，"
                        + "后果是该新短码下次会被布隆挡掉（404）直到下次重建自愈")
                .register(meterRegistry);
    }

    @Override
    public boolean mightContain(String shortCode) {
        try {
            if (!Boolean.TRUE.equals(redis.hasKey(BloomHash.PREFIX))) {
                // 未初始化 → 放行。首次部署前若判「不存在」，所有请求都会 404
                return true;
            }
            int[] bits = new int[BloomHash.HASH_COUNT];
            BloomHash.bitIndexes(shortCode, bits);
            for (int bit : bits) {
                if (!Boolean.TRUE.equals(redis.opsForValue().getBit(BloomHash.PREFIX, bit))) {
                    // 任一位为 0 → 一定不存在。这是唯一可以放心 404 的情况
                    blockedCounter.increment();
                    return false;
                }
            }
            return true;
        } catch (DataAccessException e) {
            // ⚠️ 布隆不可用 → 放行。降级查库，服务可用（ARCHITECTURE.md §384）
            log.warn("布隆检查失败，跳过（降级查库）: code={} {}", shortCode, e.getMessage());
            return true;
        }
    }

    @Override
    public boolean isReady() {
        try {
            return redis.hasKey(BloomHash.PREFIX);
        } catch (DataAccessException e) {
            return false;
        }
    }

    @Override
    public void put(String shortCode) {
        try {
            int[] bits = new int[BloomHash.HASH_COUNT];
            BloomHash.bitIndexes(shortCode, bits);
            for (int bit : bits) {
                redis.opsForValue().setBit(BloomHash.PREFIX, bit, true);
            }
        } catch (DataAccessException e) {
            // ⚠️ 失败只记日志 + 计数，**绝不抛出**（ARCHITECTURE.md §259）
            // 布隆是优化不是正确性来源，写失败就回滚创建会让它变成单点依赖
            putFailedCounter.increment();
            log.warn("布隆写穿失败（不阻塞创建，重建后自愈）: code={} {}", shortCode, e.getMessage());
        }
    }

    /**
     * 布隆 + 缓存的合并查询（DR-007 要求的一次往返）。
     *
     * @return {@code BloomCheckResult}
     */
    public BloomCheckResult checkAndGet(String shortCode) {
        int[] bits = new int[BloomHash.HASH_COUNT];
        BloomHash.bitIndexes(shortCode, bits);

        List<Object> raw;
        try {
            raw = redis.execute(CHECK_AND_GET, List.of(BloomHash.PREFIX, CacheKeys.code(shortCode)),
                    toArgs(bits));
        } catch (RuntimeException e) {
            // ⚠️ 捕获 RuntimeException 而非 DataAccessException：
            // 参数序列化/类型转换类异常（如 ClassCastException）不是 DataAccessException，
            // 只 catch DataAccessException 会让降级兜底失效，异常穿透到 Controller → 500。
            // 布隆是**保护措施**，它自己出错不该让跳转挂掉（DR-054）。
            log.warn("布隆合并查询失败，降级为回源: code={} {}", shortCode, e.toString(), e);
            return BloomCheckResult.source();
        }

        // ⚠️ 形态校验先行：Lua 某个分支不带第二项、或是完全未知的返回类型时，
        // 直接 r.get(0)/r.get(1) 会抛 IndexOutOfBounds / ClassCastException。
        // 布隆是**保护措施**，它自己出问题不该让跳转挂掉——降级为「需回源」（DR-054）。
        if (raw == null || raw.isEmpty() || !(raw.get(0) instanceof Number flagNum)) {
            log.warn("布隆合并查询返回形态异常，降级为回源: raw={} code={}",
                    raw == null ? "null" : raw.getClass().getName(), shortCode);
            return BloomCheckResult.source();
        }
        List<?> r = raw;
        int verdict = flagNum.intValue();
        // ⚠️ 元素 1 可能缺失（脚本某分支没带第二项），不能直接 r.get(1)
        Object value = r.size() > 1 ? r.get(1) : null;
        if (verdict == 0) {
            blockedCounter.increment();
            return BloomCheckResult.absent();
        }
        Object cached = value;
        if (cached instanceof byte[] bytes) {
            cached = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        }
        if (cached instanceof String s) {
            return BloomCheckResult.cached(s);
        }
        // ⚠️ 走到这里说明：verdict==1 且第二项既不是 String/byte[] 也不是 null。
        //    null 是 Lua 的正常「无值」（{1, false} / {1, nil}），**不是异常**；
        //    其它类型（实测遇到过 ArrayList）才是真异常，必须留 WARN——
        //    否则「静默降级」会让人以为布在工作，而它其实在持续返回错误结果。
        if (cached != null) {
            log.warn("布隆合并查询返回形态异常（第二项类型未知），降级为回源: "
                            + "type={} code={}",
                    cached.getClass().getName(), shortCode);
        }
        return BloomCheckResult.source();
    }

    /**
     * 双缓冲重建（DR-007）。
     *
     * <p>⚠️ <b>核心约束：任何时候都不删除现行 key</b>。
     * 流程是「写 :building → 填完 → RENAME 原子切换」。
     * 重建期间请求读的是现行 key，完全不受影响；
     * 若先 DEL 现行key，从 DEL 到 RENAME 之间所有真实短码都会被判「不存在」→ 404。
     *
     * @param allShortCodes 全部有效短码（调用方负责分批扫描）
     */
    public void rebuild(Iterable<String> allShortCodes) {
        String buildingKey = BloomHash.PREFIX + BloomHash.BUILDING_SUFFIX;
        try {
            redis.delete(buildingKey);
            for (String code : allShortCodes) {
                int[] bits = new int[BloomHash.HASH_COUNT];
                BloomHash.bitIndexes(code, bits);
                for (int bit : bits) {
                    redis.opsForValue().setBit(buildingKey, bit, true);
                }
            }
            // ⚠️ RENAME 是原子的：切换瞬间不存在「两个都不对」的状态
            redis.rename(buildingKey, BloomHash.PREFIX);
            log.info("布隆重建完成：shortlink:bloom 由 :building 原子切换而来");
        } catch (DataAccessException e) {
            log.error("布隆重建失败，现行 key 未受影响（仍服务旧数据）: {}", e.getMessage());
        }
    }

    @Override
    public long expectedInsertions() {
        return BloomHash.EXPECTED_INSERTIONS;
    }

    @Override
    public long bitSize() {
        return BloomHash.BIT_SIZE;
    }

    @Override
    public int hashFunctions() {
        return BloomHash.HASH_COUNT;
    }

    /**
     * 把bit 下标转成 {@code redis.execute} 需要的 {@code Object[]}。
     *
     * <h2>⚠️ 为什么必须返回 {@code Object[]} 而不是 {@code List<String>}</h2>
     * {@code RedisTemplate.execute(script, keys, Object... args)} 是**变参**。
     * 传一个 {@code List} 时，Java 会把它当成**单个元素**包成 {@code Object[]{list}}，
     * 而 Spring 的 {@code DefaultScriptExecutor.keysAndArgs} 会对每个元素调
     * {@code StringRedisSerializer.serialize}——它内部直接 {@code (String) value} 强转，
     * 不做类型检查，于是抛 {@code ClassCastException}。
     *
     * <p>⚠️ 更隐蔽的是：{@code catch (DataAccessException)} 抓不住它
     * （CCE 是 {@code RuntimeException}），降级兜底失效，异常直接穿透到 Controller
     * → 跳转全 500（DR-054）。
     *
     * <p>⚠️ <b>不要加占位元素</b>：曾用「首元素放 BIT_SIZE、Lua 从 i=2 开始」，
     * 结果只检查 k-1 = 6 个位。Lua 不报错，只是静默地少判一位。
     * ⚠️ 危害是**k 与规格不符**，不是误判率恶化——实测两者仅差 0.01 个百分点
     * （n=1000 万时 k=7 是 1.0039%、k=6 是 1.0143%）。
     * 我曾在这里写「误判率从 1% 升到 1.3%」，那是用常识替代计算的错误结论（DR-054）。
     *
     * @param bits bit 下标；{@code null} 或空数组返回空 {@code Object[]}
     * @return 每个元素都是 {@code String} 的数组，可安全交给 {@code execute}
     */
    private static Object[] toArgs(int[] bits) {
        if (bits == null || bits.length == 0) {
            return new Object[0];
        }
        Object[] args = new Object[bits.length];
        for (int i = 0; i < bits.length; i++) {
            args[i] = String.valueOf(bits[i]);
        }
        return args;
    }

    /**
     * 布隆 + 缓存合并查询的结果。
     *
     * @param definitelyAbsent 布隆判「一定不存在」，可直接 404
     * @param cachedValue 缓存值（可能为 null，表示需要回源）
     */
    public record BloomCheckResult(boolean definitelyAbsent, String cachedValue) {
        public static BloomCheckResult absent() {
            return new BloomCheckResult(true, null);
        }

        public static BloomCheckResult cached(String value) {
            return new BloomCheckResult(false, value);
        }

        public static BloomCheckResult source() {
            return new BloomCheckResult(false, null);
        }

        /** 布隆说「可能存在」且缓存无值 -> 需要回源。 */
        public boolean requiresSource() {
            return !definitelyAbsent && cachedValue == null;
        }
    }
}