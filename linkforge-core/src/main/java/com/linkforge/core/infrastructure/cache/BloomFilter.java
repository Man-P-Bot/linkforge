package com.linkforge.core.infrastructure.cache;

/**
 * 布隆过滤器的存在性判断（docs/ARCHITECTURE.md §7.1，DR-007）。
 *
 * <h2>为什么接口只暴露「可能存在」而不暴露「一定存在」</h2>
 * 布隆的两个性质决定了接口只能这么设计：
 * <ul>
 *   <li><b>false positive（误判存在）</b>：不存在的短码被判为「可能存在」，
 *       后果只是多一次回源查库——**安全**；</li>
 *   <li><b>false negative（误判不存在）</b>：真实存在的短码被判为「不存在」，
 *       后果是返回 404——**是真实故障**，必须在设计上杜绝（双缓冲重建）。</li>
 * </ul>
 *
 * <p>所以接口叫 {@code mightContain} 而不是 {@code contains}，
 * 命名本身就提醒调用方：这个结果是<b>「可能」不是「一定」</b>，
 * <b>返回 true 也必须继续查缓存/DB</b>。
 */
public interface BloomFilter {

    /**
     * 判断短码是否「可能存在」。
     *
     * <p>⚠️ <b>返回 {@code false} 是唯一可以直接 404 的情况</b>——
     * 意味着这个短码一定不存在，不必查缓存也不必查库。
     *
     * <p>返回 {@code true} 时**必须继续往下查**（缓存 → DB），
     * 不能因为「布隆说有」就直接302。
     *
     * @param shortCode 已通过格式校验的短码
     * @return {@code false} = 一定不存在；{@code true} = 可能存在
     */
    boolean mightContain(String shortCode);

    /**
     * 写入一个短码（创建时写穿）。
     *
     * <p>⚠️ <b>失败只记日志，不阻塞创建</b>——布隆是性能优化，
     * 不是正确性来源。写失败的后果是「这个新短码下次会被布隆挡掉 → 返回 404」，
     * 直到下次重建自愈。
     * <b>反过来做（写失败就回滚创建）会让布隆从优化变成单点依赖</b>。
     */
    void put(String shortCode);

    /** 布隆是否已初始化（key 存在）。未初始化时检查必须<b>放行</b>。 */
    boolean isReady();

    /** 容量上界 n（docs/ARCHITECTURE.md §7.1）。 */
    long expectedInsertions();

    /** 位数组大小 m（位）。 */
    long bitSize();

    /** 哈希函数个数 k。 */
    int hashFunctions();
}