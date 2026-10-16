package com.linkforge.redirect.infrastructure.cache;

import com.linkforge.core.infrastructure.cache.CacheKeys;
import com.linkforge.core.infrastructure.cache.CachedLink;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis 跳转缓存（docs/ARCHITECTURE.md §206-207）。
 *
 * <h2>TTL：1 小时 + 随机 0~5 分钟抖动</h2>
 * 抖动不是可有可无的润色。1 万条数据在压测开始后的头几十秒内被集中写入，
 * 若TTL 固定 1 小时，<b>1 小时后它们会在同一秒集体过期</b>，
 * 瞬间把全部流量打到 MySQL——这比不做缓存更糟（缓存击穿）。
 * 抖动把这波集中过期摊平到 5 分钟窗口内。
 *
 * <h2>空值缓存 30 秒，不加抖动</h2>
 * 空值缓存的量级取决于扫描流量（不是一次性写入 1 万条），
 * 天然不会形成集中过期；而 30 秒的短TTL 本身已经把风险窗口压得很小。
 *
 * <h2>⚠️ 序列化：JSON 字符串</h2>
 * 不用 JDK 序列化（二进制不可读、跨版本不兼容、反序列化有安全风险），
 * 不用 Protobuf（字段少、收益不足以抵消引入成本）。JSON 的可读性在排障时价值很高——
 * {@code redis-cli GET shortlink:code:0000Q0u} 能直接看出缓存了什么。
 *
 * <h2>⚠️ Redis 故障必须降级为「未命中」而不是抛异常</h2>
 * 缓存是加速器，不是依赖。Redis 挂了应该回源查库（慢一点但能服务），
 * 而不是让整个跳转链路 503。发号器路径对 Redis 故障敏感（那是它的工作前提），
 * 缓存路径不同——这个区别值得记。
 */
@Component
public class RedisRedirectCache {

    private static final Logger log = LoggerFactory.getLogger(RedisRedirectCache.class);

    /** 正常值 TTL：1 小时（ARCHITECTURE.md §206）。 */
    static final Duration TTL = Duration.ofHours(1);

    /** TTL 抖动上界：5 分钟。 */
    static final Duration JITTER_MAX = Duration.ofMinutes(5);

    /** 空值 TTL：30 秒（ARCHITECTURE.md §207，防穿透）。 */
    static final Duration EMPTY_TTL = Duration.ofSeconds(30);

    private final StringRedisTemplate redis;
    private final Counter hitCounter;
    private final Counter missCounter;

    public RedisRedirectCache(StringRedisTemplate redis, MeterRegistry meterRegistry) {
        this.redis = redis;
        this.hitCounter = Counter.builder("shortlink.cache.redis.hit")
                .description("Redis 跳转缓存命中次数（第 2 层）")
                .register(meterRegistry);
        this.missCounter = Counter.builder("shortlink.cache.miss")
                .description("Redis 跳转缓存未命中、回源 MySQL 的次数。"
                        + "本指标同时充当本地缓存未命中数的上界")
                .register(meterRegistry);
    }

    /**
     * 查询 Redis。
     *
     * @return empty=未命中；否则是 {@code Hit} 或 {@code NegativeHit}
     */
    public Optional<com.linkforge.core.infrastructure.cache.CacheEntry> get(String shortCode) {
        String key = CacheKeys.code(shortCode);
        String raw;
        try {
            raw = redis.opsForValue().get(key);
        } catch (DataAccessException e) {
            // ⚠️ 不抛出：缓存故障降级为未命中，由调用方回源
            log.warn("Redis 读取失败，降级为回源：key={} {}", key, e.getMessage());
            return Optional.empty();
        }
        if (raw == null) {
            missCounter.increment();
            return Optional.empty();
        }
        if (CacheKeys.EMPTY_SENTINEL.equals(raw)) {
            // 空值也算命中：它避免了回源，正是防穿透生效的证据
            hitCounter.increment();
            return Optional.of(new com.linkforge.core.infrastructure.cache.CacheEntry.NegativeHit());
        }
        CachedLink link = parse(raw, key);
        if (link == null) {
            // 数据损坏：当成未命中回源，并删掉坏数据避免反复解析失败
            missCounter.increment();
            evictQuietly(key);
            return Optional.empty();
        }
        hitCounter.increment();
        return Optional.of(new com.linkforge.core.infrastructure.cache.CacheEntry.Hit(link));
    }

    /** 回填正常值，TTL = 1 小时 + 随机 0~5 分钟。 */
    public void put(String shortCode, CachedLink link) {
        String key = CacheKeys.code(shortCode);
        try {
            redis.opsForValue().set(key, write(link), ttlWithJitter());
        } catch (DataAccessException e) {
            log.warn("Redis 写入失败，忽略（回源成功，不影响本次请求）：key={} {}", key, e.getMessage());
        }
    }

    /** 回填空值哨兵，TTL 固定 30 秒。 */
    public void putEmpty(String shortCode) {
        String key = CacheKeys.code(shortCode);
        try {
            redis.opsForValue().set(key, CacheKeys.EMPTY_SENTINEL, EMPTY_TTL);
        } catch (DataAccessException e) {
            log.warn("Redis 空值写入失败，忽略：key={} {}", key, e.getMessage());
        }
    }

    /** 删除 key。供 Cache Aside 写路径使用（第 3 周）。 */
    public void evict(String shortCode) {
        evictQuietly(CacheKeys.code(shortCode));
    }

    /**
     * 序列化格式（v1）。
     *
     * <p>格式：{@code v1|status|expireAt|targetUrl 长度|targetUrl}
     *
     * <p>⚠️ <b>为什么 targetUrl 放最后且带长度前缀</b>：
     * {@code targetUrl} 是用户输入，{@code LinkValidator} 只校验协议与长度，
     * <b>不排除换行与分隔符</b>。若用 {@code \n} 或 {@code |} 直接拼接 targetUrl，
     * 一个形如 {@code https://a.com/%0A} 的输入就会让段数错位或字段串位。
     * 长度前缀让解析变成「前 3 段定长切分 + 第 4 段按长度取 remainder」，
     * <b>无论 targetUrl 里有什么都能正确还原</b>。
     *
     * <p>⚠️ <b>为什么不用 Jackson</b>：需要额外配置 {@code JavaTimeModule} 与
     * {@code WRITE_DATES_AS_TIMESTAMPS}，跨版本格式可能漂移（缓存格式一变，
     * 滚动发布期间新旧实例写出两种格式）。这个格式是自解释的、无外部依赖。
     *
     * <p>解析失败一律按「未命中」处理并删掉坏数据——最坏结果是多查一次库，
     * <b>绝不会返回错误数据</b>。这是选择自定义格式的前提。
     */
    static final String FORMAT_VERSION = "v1";

    private String write(CachedLink link) {
        String url = link.targetUrl();
        return FORMAT_VERSION
                + "|" + link.status()
                + "|" + (link.expireAt() == null ? "" : link.expireAt())
                + "|" + url.length()
                + "|" + url;
    }

    /**
     * 解析缓存值（供 {@link TwoLevelRedirectCache#getOrLoadWithBloom} 复用）。
     *
     * <p>Lua 合并脚本把原始 value 直接带回来了，这里不重复实现一遍格式解析——
     * <b>两处各写一份解析是缓存格式演进时最容易出错的写法</b>：
     * 改了一处忘了另一处，同一个 value 在两条路径上行为不一致。
     *
     * @param shortCode 短码，仅用于错误日志定位
     * @param raw 原始缓存值
     * @return 解析结果；格式损坏返回 {@code null}（调用方当未命中处理）
     */
    public CachedLink parseCached(String shortCode, String raw) {
        if (raw == null || CacheKeys.EMPTY_SENTINEL.equals(raw)) {
            return null;
        }
        return parse(raw, CacheKeys.code(shortCode));
    }

    private CachedLink parse(String raw, String key) {
        // 限制 5 段：targetUrl 作为最后一段完整保留，不受其中分隔符影响
        String[] head = raw.split("\\|", 5);
        if (head.length != 5) {
            log.error("缓存值格式损坏，按未命中处理并删除：key={} 段数={}", key, head.length);
            return null;
        }
        if (!FORMAT_VERSION.equals(head[0])) {
            // 版本号不匹配：说明发布期间格式变了，直接当损坏处理，不要猜着解析
            log.error("缓存值版本不匹配，按未命中处理并删除：key={} 版本={}", key, head[0]);
            return null;
        }
        String status = head[1];
        String expireAt = head[2];
        int declaredLen;
        try {
            declaredLen = Integer.parseInt(head[3]);
        } catch (NumberFormatException e) {
            log.error("缓存值长度前缀非法，按未命中处理并删除：key={} 长度={}", key, head[3]);
            return null;
        }
        String targetUrl = head[4];
        if (targetUrl.length() != declaredLen) {
            // 长度校验是关键：段数对但内容被截断/串位的情况只有这里能发现
            log.error("缓存值长度不符（可能被截断），按未命中处理并删除：key={} 声明={} 实际={}",
                    key, declaredLen, targetUrl.length());
            return null;
        }
        if (targetUrl.isEmpty() || status.isEmpty()) {
            log.error("缓存值关键字段为空，按未命中处理并删除：key={}", key);
            return null;
        }
        try {
            java.time.LocalDateTime expire = expireAt.isEmpty()
                    ? null : java.time.LocalDateTime.parse(expireAt);
            return new CachedLink(targetUrl, status, expire);
        } catch (java.time.format.DateTimeParseException e) {
            log.error("缓存值时间字段解析失败，按未命中处理并删除：key={} expireAt={}", key, expireAt);
            return null;
        }
    }

    private Duration ttlWithJitter() {
        long jitterSeconds = ThreadLocalRandom.current().nextLong(0, JITTER_MAX.toSeconds());
        return TTL.plusSeconds(jitterSeconds);
    }

    private void evictQuietly(String key) {
        try {
            redis.delete(key);
        } catch (DataAccessException e) {
            log.warn("Redis 删除失败，忽略：key={} {}", key, e.getMessage());
        }
    }
}