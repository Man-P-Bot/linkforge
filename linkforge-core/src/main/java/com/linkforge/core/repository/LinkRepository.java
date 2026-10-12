package com.linkforge.core.repository;

import com.linkforge.core.domain.Link;
import java.util.List;
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
}
