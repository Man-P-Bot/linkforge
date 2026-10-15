package com.linkforge.redirect.infrastructure.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.linkforge.core.infrastructure.cache.CacheEntry;
import com.linkforge.core.infrastructure.cache.CachedLink;
import com.linkforge.core.infrastructure.cache.RedirectCacheService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 两级跳转缓存门面：Caffeine（第1 层，本地）→ Redis（第 2 层）→ 回源 MySQL。
 *
 * <h2>为什么编排收在这里，而不是让 RedirectService 自己管</h2>
 * 「查本地 → 查 Redis → 回源 → 回填」这套顺序和它的统计口径必须<b>只有一份</b>。
 * 散在调用方意味着改一处漏一处，命中率指标也会因各处统计时机不同而对不上。
 *
 * <h2>三个指标（第 2 周建，第 5 周 Prometheus 直接暴露）</h2>
 * <ul>
 *   <li>{@code shortlink.cache.local.hit}——第 1 层命中</li>
 *   <li>{@code shortlink.cache.redis.hit}——第 2 层命中（含空值命中）</li>
 *   <li>{@code shortlink.cache.miss}——两级都没中，实际回源 MySQL 的次数</li>
 * </ul>
 * 压测时直接从 {@code /actuator/metrics/shortlink.cache.local.hit} 读，
 * 不从日志数——日志会被采样、会丢、会被人为关掉。
 *
 * <h2>⚠️ 本地缓存只存 Hit</h2>
 * {@link Cache} 的 value 类型是 {@link CacheEntry.Hit} 而不是 {@code CacheEntry}，
 * <b>用类型把 NegativeHit 挡在编译期之外</b>，而不是靠运行时判断。
 * 空值 TTL 30 秒而本地 TTL 5 分钟，若缓存了「不存在」，
 * 一个刚创建的短码会持续 404 长达 5 分钟（DR-046）。
 */
@Component
public class TwoLevelRedirectCache implements RedirectCacheService {

    private final Cache<String, CacheEntry.Hit> local;
    private final RedisRedirectCache redis;
    private final Counter localHitCounter;

    public TwoLevelRedirectCache(Cache<String, CacheEntry.Hit> localLinkCache,
                                 RedisRedirectCache redis,
                                 MeterRegistry meterRegistry) {
        this.local = localLinkCache;
        this.redis = redis;
        this.localHitCounter = Counter.builder("shortlink.cache.local.hit")
                .description("跳转本地缓存（Caffeine）命中次数（第 1 层）")
                .register(meterRegistry);
    }

    @Override
    public Optional<CacheEntry> get(String shortCode) {
        CacheEntry.Hit localHit = local.getIfPresent(shortCode);
        if (localHit != null) {
            localHitCounter.increment();
            return Optional.of(localHit);
        }
        // 第 1 层未命中 → 问第 2 层
        Optional<CacheEntry> fromRedis = redis.get(shortCode);
        if (fromRedis.isEmpty()) {
            // 未命中：Redis 层已累加 miss 指标，调用方据此回源
            return Optional.empty();
        }
        CacheEntry entry = fromRedis.get();
        // ⚠️ 只有 Hit 才回填本地；NegativeHit 绝不下沉（DR-046）
        if (entry instanceof CacheEntry.Hit hit) {
            local.put(shortCode, hit);
        }
        return fromRedis;
    }

    @Override
    public void put(String shortCode, CachedLink link) {
        local.put(shortCode, new CacheEntry.Hit(link));
        redis.put(shortCode, link);
    }

    @Override
    public void putEmpty(String shortCode) {
        // ⚠️ 只写 Redis，不写本地。理由见类注释与 DR-046。
        redis.putEmpty(shortCode);
    }

    @Override
    public void evict(String shortCode) {
        // ⚠️ 顺序不可调换（DR-045）：先删本地再删 Redis。
        // 反过来会出现「删 Redis 后、删本地前」的窗口，并发读回查到旧值
        // 并重新写入本地，让已失效的值在本地又活一个 TTL 周期。
        local.invalidate(shortCode);
        redis.evict(shortCode);
    }
}