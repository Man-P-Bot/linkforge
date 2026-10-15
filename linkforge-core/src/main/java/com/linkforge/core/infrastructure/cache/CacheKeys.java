package com.linkforge.core.infrastructure.cache;

/**
 * Redis Key 命名规范（docs/ARCHITECTURE.md §206-211）。
 *
 * <h2>统一 {@code shortlink:} 前缀</h2>
 * 全部 Redis key 共享 {@code shortlink:} 前缀，便于：
 * <ul>
 *   <li>按前缀 SCAN 做运维排查（不影响其它业务占用同一实例）；</li>
 *   <li>第3 周引入 {@code shortlink:bloom}、{@code shortlink:stats:*}、
 *       {@code shortlink:limit:*} 时无需再讨论命名规则。</li>
 * </ul>
 *
 * <p>⚠️ <b>为什么不叫 {@code link:}</b>：{@code link} 过于通用，在共享 Redis 实例上
 * 无法与其他业务的 key 区分；{@code shortlink} 与项目名、领域名一致，语义边界清晰。
 *
 * <p>⚠️ <b>为什么不加 {@code {}} hash tag</b>：本项目是单实例部署，
 * Cluster 场景下 {@code {code}} hash tag 能让同一短码固定到同一 slot，
 * 但跳转路径**没有任何多 key 操作**，不需要 slot 亲和性。
 * 加 hash tag 反而会让 key 集中到少数 slot，损失分布性。**若将来出现按短码批量
 * 操作（Pipeline / Lua / 事务）再引入。**
 *
 * @see com.linkforge.core.domain.Link
 */
public final class CacheKeys {

    /** 全局前缀。不要单独使用这个常量，用具体的 key 方法。 */
    public static final String PREFIX = "shortlink:";

    /**
     * 跳转缓存 key：{@code shortlink:code:{shortCode}}。
     *
     * <p>与 ARCHITECTURE.md §206 的定义逐字一致。空值缓存（防穿透）**复用同一个 key**，
     * 用不同的 value 区分——见 {@link #EMPTY_SENTINEL}。刻意不另开{@code shortlink:empty:} 前缀：
     * 空值与正常值对应的是同一个短码，分开存会让「删除该key」需要同时删两处，
     * 而 Cache Aside 的失效路径必须一次删干净。
     */
    public static String code(String shortCode) {
        return PREFIX + "code:" + shortCode;
    }

    /**
     * 空值哨兵：Redis 中存的字面量，表示「已确认该短码不存在」。
     *
     * <p>⚠️ <b>为什么用固定字符串而不是 {@code {} JSON}</b>：
     * 短码是 7 位 Base62（{@code 0-9A-Za-z}），{@code __EMPTY__} 含下划线，
     * <b>在短码值域内不可能出现</b>，因此不需要担心与正常序列化结果撞车。
     * 若将来缓存的 value 形式变了（例如直接存裸targetUrl），必须重新确认这个哨兵仍然不可达。
     */
    public static final String EMPTY_SENTINEL = "__EMPTY__";

    private CacheKeys() {
        throw new AssertionError("常量类不应被实例化");
    }
}