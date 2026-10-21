package com.linkforge.core.repository;

import com.linkforge.core.domain.Link;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 短链仓储接口（docs/DATABASE.md、docs/ARCHITECTURE.md §4）。
 *
 * <p>放在 {@code core} 而不是 {@code redirect}，因为 **redirect 与 admin 都需要它**：
 * 跳转要按短码读，后台删除要改状态（docs/DECISIONS.md DR-021）。
 *
 * <p>本接口**只描述数据访问语义**，不承载业务判定。失效判定优先级
 * （先 status 再 expire_at，docs/DATABASE.md §4.2）由上层用例实现，
 * 因为「查不到」「已删除」「已过期」要映射成不同状态码（404 / 410），
 * 这是接口层的事，不是仓储层的事。
 *
 * <p>实现由 MyBatis Mapper 提供（{@code core.infrastructure.persistence}）。
 */
public interface LinkRepository {

    /**
     * 按短码查询。跳转回源与自定义短码占用判定都走它。
     *
     * <p>注意：**不做状态过滤**——已删除与已过期的记录也要返回，
     * 由上层决定返回 404 还是 410。
     *
     * @return 命中则返回记录；未命中返回 {@link Optional#empty()}
     */
    Optional<Link> findByShortCode(String shortCode);

    /**
     * 按内部主键查询。统计查询与后台操作使用。
     */
    Optional<Link> findById(long id);

    /**
     * 插入一条新短链。
     *
     * <p>{@code created_at} / {@code updated_at} 由数据库生成，调用方不需要也不应该赋值。
     * 短码唯一性由唯一索引 {@code uk_short_code} 兜底：发号器保证正常路径不碰撞，
     * 一旦真的冲突，应作为异常暴露而不是静默重试掩盖问题（docs/DATABASE.md §6）。
     *
     * @param link 待插入记录，其 {@code id} 应为 {@code null}
     */
    void insert(Link link);

    /**
     * 逻辑删除：把 {@code status} 置为 {@code DELETED}。
     *
     * <p>刻意**不做物理删除**（docs/DATABASE.md §7.2）：
     * 布隆过滤器不支持删除，物理删除不会改变它仍判「可能存在」的事实；
     * 且保留记录才能回答「这条短链曾经存在、何时被删」。
     *
     * <p>本方法**只改数据库状态**。删缓存是 Cache Aside 的第二步，
     * 必须在事务提交后由上层执行（docs/ARCHITECTURE.md §10），不在仓储层职责内。
     *
     * @return 实际影响的函数行数：1 表示本次删除生效；0 表示记录不存在或已是删除态，
     *         调用方据此区分 404 与「已删除（200 + alreadyDeleted）」（docs/API.md §7.1）
     */
    int markDeleted(long id);

    /**
     * 批量把短码解析为主键，供统计落库使用（阶段 3）。
     *
     * <p>⚠️ <b>为什么需要这一步</b>：Redis 里的统计 key 以<b>短码</b>命名
     * （{@code shortlink:pv:{code}:{date}}），而 {@code link_stats_daily} 按
     * <b>{@code link_id}</b> 聚合（V1 迁移的设计：「短码编码规则若变化，历史统计无需回填」）。
     * 落库前必须做一次短码 → 主键的映射。
     *
     * <p>放在 {@code LinkRepository} 而不是统计仓储里：查的是 {@code short_link} 表，
     * 属于短链的读，不该让 stats 模块知道「短码和主键的关系由 short_link 表定义」。
     *
     * <p>⚠️ <b>不过滤 status</b>：已软删除的短链仍有历史访问量，那部分统计不该丢
     * （口径与 {@link #findByShortCode} 一致）。
     *
     * @param shortCodes 待解析的短码集合，可为空
     * @return {@code shortCode -> id} 映射；<b>查不到的短码不会出现在结果里</b>，
     *         调用方需自行统计缺失数（对应「短链已物理不存在」的场景）
     */
    Map<String, Long> resolveIdsByShortCodes(Collection<String> shortCodes);

    /**
     * 扫描全部有效短码，供布隆过滤器重建使用（docs/ARCHITECTURE.md §7.1）。
     *
     * <p>只返回 {@code status = ACTIVE} 且**只取必要列**（短码），
     * 走覆盖索引 {@code idx_status_code(status, short_code)}，
     * 避免把 2048 字节的 URL 列也读出来——1000 万行的重建不能是全表扫。
     *
     * @param limit 单批上限，分批拉取避免一次性把全量短码读进内存
     * @param afterId 游标：只取 {@code id > afterId} 的记录，配合主键有序推进
     */
    List<String> scanActiveShortCodes(long afterId, int limit);

    /**
     * 扫描有效短码，**同时返回主键**（供布隆重建推进游标）。
     *
     * <p>⚠️ 为什么不能只用 {@link #scanActiveShortCodes}：
     * 只返回短码时调用方不知道本批最大 {@code id}，无法正确推进 {@code afterId}，
     * 只能「按批大小猜」。猜错会漏扫，重建出的布隆缺一批短码——
     * 那些短码会被判「不存在」→ **真实用户收到 404**（DR-007 假阴性故障）。
     *
     * @param afterId 游标：只取 {@code id > afterId} 的记录
     * @param limit 单批上限
     * @return 按 {@code id} 升序的行列表；本批最大 {@code id} 即下一轮的游标
     */
    List<IdShortCode> scanActiveWithId(long afterId, int limit);

    /**
     * {@code (id, shortCode)} 行。
     */
    record IdShortCode(long id, String shortCode) {
    }
}
