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
     * 原子推进号段（{@code max_id = max_id + step}），靠 InnoDB 行锁保证多实例并发取段不重叠。
     *
     * <p><b>返回受影响行数，而不是 {@code max_id}</b>（DR-042）。
     * 原实现把这一步声明成返回 {@code long} 并直接当 {@code max_id} 用，
     * 但 MyBatis 的 {@code <update>} 一律返回受影响行数（本语句恒为 1），
     * 于是号段右端点被算成 1，段首变成 {@code 1 - step + 1 = -998}，
     * 负序号一路传到 {@code IdSpace.requireInRange} 抛
     * {@code IllegalArgumentException: 序号不能为负}。
     *
     * <p>该缺陷能长期潜伏，是因为它只在**降级路径**上出现（Redis 不可用时），
     * 而 {@code SegmentShortCodeGeneratorTest} 把本 Mapper mock 掉了，
     * 返回的是真正的 {@code max_id}。
     *
     * @return 受影响行数；{@code 0} 表示 {@code biz_tag} 行不存在
     */
    int advanceMaxId(@Param("bizTag") String bizTag);

    /**
     * 读取当前 {@code max_id}。
     *
     * <p>用于启动自检、排查，以及 {@link #advanceMaxId} 之后在**同一事务内**读回
     * 推进结果（行锁保证读到的是自己那次 update 的值）。
     *
     * @return 当前 max_id；{@code null} 表示 {@code biz_tag} 行不存在
     */
    Long selectMaxId(@Param("bizTag") String bizTag);
}
