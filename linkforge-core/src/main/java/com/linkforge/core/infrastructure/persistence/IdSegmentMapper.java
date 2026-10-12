package com.linkforge.core.infrastructure.persistence;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * {@code id_segment} 表的 MyBatis 接口（docs/DATABASE.md §4.4）。
 *
 * <p>用途：Redis 不可用时的**降级发号**路径（docs/DECISIONS.md DR-011 / DR-022）。
 * 发号区间由 {@link com.linkforge.core.domain.IdSpace} 统一定义，本表初始 {@code max_id}
 * 必须是 {@code SEGMENT_FIRST_SEQUENCE - 1}，即 {@code 999999999}——
 * **不是 100000**：那会让数据库号段从 100001 起，与 Redis 区间重叠并导致重复发号。
 */
@Mapper
public interface IdSegmentMapper {

    /**
     * 幂等地创建号段行。
     *
     * <p>用 {@code INSERT IGNORE}：Flyway V2 已插入初始行，本方法只是启动兜底
     * （例如有人手工清空了表）。已存在时不覆盖——**覆盖会重置 max_id 并导致重复发号**。
     *
     * @param bizTag 业务标识，本项目的短码发号固定用 {@code short_code}
     * @param maxId  初始 max_id，取 {@code IdSpace.SEGMENT_MAX_ID_INIT_VALUE}
     * @param step   号段步长
     */
    void insertIgnore(@Param("bizTag") String bizTag,
                      @Param("maxId") long maxId,
                      @Param("step") int step);

    /**
     * 原子推进号段并取走一整段，返回推进后的 {@code max_id}。
     *
     * <p>{@code max_id = max_id + step} 在单条 UPDATE 内完成，依赖 InnoDB 行锁保证原子性，
     * 因此多实例并发取段不会拿到重叠区间。
     *
     * <p>⚠️ 调用方必须让本语句运行在**独立事务**并立即提交
     * （见 {@code SegmentShortCodeGenerator}）：如果与业务事务共用，
     * 创建短链失败回滚时号段也会跟着回滚，多实例下会重复分配同一段号。
     *
     * @return 推进后的 max_id（即本次号段的右端点）
     */
    long advanceAndGetMaxId(@Param("bizTag") String bizTag);

    /** 只读当前 max_id，用于启动自检与排查。 */
    Long selectMaxId(@Param("bizTag") String bizTag);
}
