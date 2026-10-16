package com.linkforge.redirect.infrastructure.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.linkforge.core.infrastructure.cache.CacheEntry;
import com.linkforge.core.infrastructure.cache.CachedLink;
import com.linkforge.core.infrastructure.cache.RedirectCacheService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Optional;
import java.util.function.Function;
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

    /**
     * 带单飞（single-flight）的查询（DR-052）。
     *
     * <p>顺序：本地 → Redis → {@code loader} 回源。
     * <b>同一key 的并发回源只执行一次 {@code loader}</b>，其余请求同步等待其结果。
     *
     * <h2>⚠️ 为什么用 Caffeine 的 loader 而不是 Redis SETNX 锁</h2>
     * <ul>
     *   <li><b>零额外往返</b>：SETNX 方案需要「SETNX 抢锁 → 抢到写锁 → 没抢到轮询等结果」，
     *       至少多 2 次 Redis 往返，而单飞的目的是把缓存未命中的开销降到最低；</li>
     *   <li><b>锁的生命周期与本地 TTL天然一致</b>，不需要额外维护锁的过期；</li>
     *   <li><b>不支持分布式</b>——多实例下每个实例各自单飞。这不是缺陷：
     *       布隆已挡住「一定不存在」的穿透，剩余的「可能存在」是真实存在的短码，
     *       N 个实例各回源一次的压力可接受。见 DR-052。</li>
     * </ul>
     *
     * <h2>⚠️ loader 的三条约束</h2>
     * <ol>
     *   <li><b>只能是查库</b>：100 个并发请求会同步等待 loader 完成，
     *       loader 里做慢操作会把整个本地缓存卡住；</li>
     *   <li><b>异常不被缓存</b>：Caffeine 不会缓存 loader 抛出的异常，
     *       下次调用会重新执行 loader。这是对的（DR-048：查询失败不能当「不存在」缓存）；</li>
     *   <li><b>返回类型只能是 {@link CacheEntry.Hit}</b>——这把DR-047 的约束带进了 loader：
     *       {@code NegativeHit} 在编译期就出不来。</li>
     * </ol>
     *
     * @param loader 回源函数；返回 {@code null} 表示「确认不存在」
     * @return 缓存/回源结果；{@link CacheEntry.NegativeHit} 表示已确认不存在
     */
    public CacheEntry getOrLoad(String shortCode, Function<String, CacheEntry.Hit> loader) {
        // ⚠️ 顺序：**三段式——L1（本地）→ L2（Redis）→ 回源**。
        //
        // ⚠️ 曾一度写成「先问 L2 再查 L1」，那是**缺陷不是取舍**（DR-051）：
        // 那样写的话 L1 命中这一分支永远走不到，每次请求都要多一次 Redis 往返，
        // 本地缓存形同虚设。第 3 周压测实测 QPS 从81289 掉到 3153，
        // 当时初判为「布隆多一次往返」，实为 L1 完全失效。
        //
        //⚠️ 为什么不能把 loader 直接挂在 {@code local.get(key, loader)} 上：
        //它**只在 L1 miss 时执行 loader**，不给「先问 L2 再决定要不要 loader」的机会。
        // 所以必须先 getIfPresent 探一次 L1，miss 后再问 L2，
        // L2 也miss 才用 get(key, loader) 做单飞。
        //
        // ✅ **两次 L1 查找之间没有竞态**：getIfPresent 只读不写，
        // 第二次 get(key, loader) 的加载不依赖第一次的结果。
        // 这一点曾被误当作「L2 优先的理由」，实际上 L1 优先一样满足
        // 「回源次数与实例数无关」这个真正重要的性质。
        //
        // 回归测试：localHitSkipsRedisCompletely（锁住 L1 命中时 Redis 零调用）
        // ---- 第 1 段：L1 本地缓存 ----
        // ⚠️ 必须在查 L2 **之前**。放在之后会让本地缓存完全失效：
        // 每次请求都要多一次 Redis 往返，8 万 QPS 下 Redis 直接成为瓶颈。
        CacheEntry.Hit localHit = local.getIfPresent(shortCode);
        if (localHit != null) {
            localHitCounter.increment();
            // L1 命中：0 次 Redis 往返
            return localHit;
        }

        // ---- 第 2 段：L2（Redis）----
        Optional<CacheEntry> fromRedis = redis.get(shortCode);
        if (fromRedis.isPresent()) {
            CacheEntry entry = fromRedis.get();
            if (entry instanceof CacheEntry.Hit redisHit) {
                // 正向结果可以下沉到 L1（DR-047 只禁止 NegativeHit 下沉）
                local.put(shortCode, redisHit);
            }
            // NegativeHit 不下沉，直接返回
            return entry;
        }

        // ---- 第 3 段：两级都未命中 -> 单飞回源 ----
        //
        // ⚠️ local.get(key, loader) 在这里的语义是**「取或加载」**：
        // 它内部若L1 已有值就直接返回（loader 不跑），否则执行 loader 并写入。
        //
        // 🚫 **不要在 hit != null 时累加 localHitCounter**：
        // 此时无论走a) 还是有 b) 分支，本次请求要么访问了 Redis（L2 刚问过）、
        // 要么访问了数据库（loader 执行了），**都不是「本地缓存零成本供给了本次读取」**。
        // local.hit 的口径必须是「本次响应完全由 L1 供给，未访问 Redis/DB」，
        // 那已经在第 1 段累加了。
        //
        // 回归测试：loaderExecutionIsNotCountedAsLocalHit
        //若把第 1 段的 getIfPresent 挪到 redis.get 之后，
        // 这个方法就会退化成「每次都问 Redis」，而本段又不再计数——
        // local.hit 会重新变成恒 0，且没有任何测试报警。
        // 锁住核心行为的是 localHitSkipsRedisCompletely。
        CacheEntry.Hit hit = local.get(shortCode, loader);
        if (hit != null) {
            return hit;
        }
        // loader 返回 null -> 确认不存在。只写 Redis 空值，不写本地（DR-047）
        redis.putEmpty(shortCode);
        return new CacheEntry.NegativeHit();
    }

    /**
     * 只回填 Redis（L2），不写本地（L1）。
     *
     * <p>供单飞 loader 使用：{@code local.get(key, loader)} 会自动把返回值写进 L1，
     * loader 内若再写一次 L1 就是重复写。
     *
     * @param shortCode 短码
     * @param link 已回源的链接数据
     */
    public void putToRedisOnly(String shortCode, CachedLink link) {
        redis.put(shortCode, link);
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