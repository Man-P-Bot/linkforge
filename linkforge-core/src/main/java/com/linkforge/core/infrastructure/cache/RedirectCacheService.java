package com.linkforge.core.infrastructure.cache;

/**
 * 跳转缓存契约（docs/ARCHITECTURE.md §10，Cache Aside）。
 *
 * <p>放在 {@code core}（纯契约，零 Redis/Spring 依赖，DR-021）：
 * 第 3 周的 admin 删除操作同样需要失效缓存，两个模块都要用这个契约。
 *
 * <h2>⚠️ 本接口刻意不暴露实现类型</h2>
 * 第 2 周有两级实现（{@code CaffeineRedirectCache} 本地 + {@code RedisRedirectCache}），
 * 调用方拿到的是**组合后的门面**，而不是分别问「本地有没有、Redis 有没有」。
 * 让调用方自己编排两层会导致每个使用点都要写一遍「查本地→查Redis→回源→回填」，
 * 顺序与统计口径必然漂移。
 *
 * <p>命中统计（{@code shortlink.cache.*}）也由实现内部上报，调用方不感知——
 * 统计口径集中在一处，才不会因为改代码而变味。
 */
public interface RedirectCacheService {

    /**
     * 查询本层缓存。
     *
     * @return {@code Optional.empty()} 表示本层未命中（需回源）；
     *         {@code Hit} 表示命中有效短链；{@code NegativeHit} 表示已缓存「不存在」
     */
    java.util.Optional<CacheEntry> get(String shortCode);

    /**
     * 回填有效短链。
     *
     * <p>TTL 1 小时 + 随机 0~5 分钟抖动（防雪崩，见 docs/ARCHITECTURE.md §206）。
     * 本地层 TTL 5 分钟（由业务可接受的不一致窗口决定，不由 Redis TTL 倒推）。
     */
    void put(String shortCode, CachedLink link);

    /**
     * 回填空值（防穿透）。TTL 固定 30 秒，不加抖动。
     *
     * <p>⚠️ <b>绝不缓存「查询失败」</b>：数据库异常必须向上抛，
     * 不能当「不存在」缓存——那会把一次数据库抖动固化成 30 秒的 404 风暴。
     */
    void putEmpty(String shortCode);

    /**
     * 失效指定短码（Cache Aside 的写路径，第 3 周 admin 删除时调用）。
     *
     * <p>第 2 周**只定义不启用**：本期没有删除入口，实现可以先做成本地+Redis 双删，
     * 但不会有调用方。
     *
     * <p>⚠️ <b>实现顺序不可调换</b>：必须先删本地、再删 Redis。
     * 反过来会出现「删 Redis 后、删本地前」的窗口，并发读从 Redis 回查到旧值
     * 并重新写入本地，导致已失效的值在本地又活一个TTL 周期。
     */
    void evict(String shortCode);

    /**
     * 批量失效（Cache Aside 的写路径，阶段 4 过期清理用）。
     *
     * <p>⚠️ <b>为什么需要它</b>：逐条 {@link #evict} 是 N 次 Redis 往返。
     * 实测（真实 Redis，5000 个 key）：
     * <pre>
     *   逐条 delete：3174 ms（单条 635 µs）
     *   批量 delete：  60 ms
     *   加速比：52.9x
     * </pre>
     * 过期清理一轮要清几千条，逐条删会让这一轮逼近锁 TTL；
     * 批量删把它压到几十毫秒。
     *
     * <p>⚠️ <b>实现顺序不可调换</b>：与 {@link #evict} 同一个理由
     * ——先删本地、再删 Redis（DR-045）。
     *
     * @param shortCodes 待失效的短码集合，可为空
     */
    void evictAll(java.util.Collection<String> shortCodes);
}