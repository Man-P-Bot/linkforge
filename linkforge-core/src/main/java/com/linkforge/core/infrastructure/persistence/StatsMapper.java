package com.linkforge.core.infrastructure.persistence;

import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * {@code link_stats_daily} 表的 MyBatis 映射（docs/DECISIONS.md DR-021 的同构做法）。
 *
 * <p>放在 {@code core} 而不是 {@code stats}：后台统计查询（阶段 5，admin 模块）
 * 也要读这张表，而 {@code stats} 不允许被 admin 依赖（DR-018）。
 * Mapper 放 core，{@code stats} 与 {@code admin} 各自通过 core 的接口访问。
 *
 * <p>SQL 写在 {@code core/src/main/resources/mapper/StatsMapper.xml}，
 * 不写注解 SQL：本项目要讲清楚的是「哪条 SQL 走哪个索引」，XML 里能把这个意图
 * 直接写在语句旁边。
 */
@Mapper
public interface StatsMapper {

    /**
     * 批量累加 PV。
     *
     * <p>⚠️ <b>必须是累加</b>：每次落库携带的是「自上次落库以来新增的浏览量」，
     * 多次落库要叠加。改成覆盖会导致刷新期间丢失的 PV 永远补不回来。
     *
     * <p>⚠️ <b>入参必须非空</b>：{@code VALUES} 后接空列表会渲染出 {@code IN ()} 式的
     * 语法错误，Repository 层已做空集合短路。
     */
    int accumulatePvBatch(@Param("rows") List<PvRow> rows);

    /**
     * 批量写入 UV 估算值。
     *
     * <p>⚠️ <b>不能累加</b>：HLL 的 {@code PFCOUNT} 是「当日至今的独立访客估算」，
     * 不是增量。累加会把同一个访客按 flush 次数重复计（一分钟 flush 一次 = 计 1440 遍）。
     * 取 {@code GREATEST} 既符合语义（当日值只增不减），又让重试天然幂等。
     */
    int mergeUvBatch(@Param("rows") List<UvRow> rows);

    /**
     * PV 增量行。
     *
     * <p>⚠️ 必须提供 getter 而不只是 record 访问器：
     * MyBatis 的默认对象映射走 <b>setter</b>，record 的访问器 {@code delta()}
     * 不被识别（除非显式配 {@code argNameBasedConstructorAutoMapping}），
     * 结果是静默写入 {@code null/0} 而不是报错——很难排查。
     * 同样的坑在 {@link LinkMapper.IdShortCode} 上已经踩过一次。
     */
    class PvRow {
        private long linkId;
        private LocalDate statDate;
        private long delta;

        public long getLinkId() {
            return linkId;
        }

        public void setLinkId(long linkId) {
            this.linkId = linkId;
        }

        public LocalDate getStatDate() {
            return statDate;
        }

        public void setStatDate(LocalDate statDate) {
            this.statDate = statDate;
        }

        public long getDelta() {
            return delta;
        }

        public void setDelta(long delta) {
            this.delta = delta;
        }
    }

    /** UV 估算值行。 */
    class UvRow {
        private long linkId;
        private LocalDate statDate;
        private long estimate;

        public long getLinkId() {
            return linkId;
        }

        public void setLinkId(long linkId) {
            this.linkId = linkId;
        }

        public LocalDate getStatDate() {
            return statDate;
        }

        public void setStatDate(LocalDate statDate) {
            this.statDate = statDate;
        }

        public long getEstimate() {
            return estimate;
        }

        public void setEstimate(long estimate) {
            this.estimate = estimate;
        }
    }
}
