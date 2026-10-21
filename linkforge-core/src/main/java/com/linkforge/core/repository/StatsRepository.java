package com.linkforge.core.repository;

import java.time.LocalDate;
import java.util.Collection;

/**
 * 每日统计聚合的读写接口（docs/DESIGN-W4.md §5、docs/DATABASE.md §4.3）。
 *
 * <h2>为什么放在 {@code core} 而不是 {@code stats}</h2>
 * {@code link_stats_daily} 将来会被**后台统计查询**（admin 模块）读，
 * 而 {@code stats} 模块**不允许被 admin 依赖**（DR-018，ModuleBoundaryArchTest 强制）。
 * 若把 Mapper 放在 {@code stats}，admin 就只能反过来依赖 stats，
 * 或者走一条「core → stats → core」的环——两种都不可接受。
 * 放在 core 与 {@link LinkRepository} 同构：core 提供数据访问能力，各上层模块按需调用。
 *
 * <h2>⚠️ 为什么 PV 与 UV 是<b>两个独立方法</b>，而不是一个</h2>
 * 两者的累加语义根本不同：
 * <ul>
 *   <li><b>PV 是增量累加</b>：{@code pv = pv + VALUES(pv)}。每次落库携带的是
 *       「自上次落库以来新增的浏览量」，多次落库必须叠加。</li>
 *   <li><b>UV 是当日估算值的覆盖</b>：{@code uv_estimated = GREATEST(uv_estimated, VALUES(uv_estimated))}。
 *       HyperLogLog 的 {@code PFCOUNT} 返回的是「当日至今的独立访客估算」，
 *       <b>不是增量</b>；若也用累加，一天 flush 60 次就会把同一个访客重复计 60 遍。
 *       用 {@code GREATEST} 既符合语义（当日值只增不减），又让重试天然幂等。</li>
 * </ul>
 *
 * <p>拆成两个方法还有第二个好处：<b>PV 写成功、UV 写失败时可以只重试 UV</b>。
 * 若合并成一条语句，重试就会把已成功的 PV 再累加一遍——
 * 这是阶段 3 明确禁止的「一次重试同时更新 PV 和 UV」。
 */
public interface StatsRepository {

    /**
     * 累加 PV。
     *
     * <p>语义：{@code pv = pv + VALUES(pv)}。
     * 调用方必须保证传入的 {@code delta} 是「自上次落库以来新增的量」，
     * 重复提交同一份 delta 会导致重复累加。
     *
     * @param rows 待累加的行
     * @return 受影响行数
     */
    int accumulatePv(Collection<PvRow> rows);

    /**
     * 写入 UV 估算值。
     *
     * <p>语义：{@code uv_estimated = GREATEST(uv_estimated, VALUES(uv_estimated))}。
     * 取「当日已写入值」与「本次估算值」的较大者——HLL 基数只增不减，
     * 所以这个语义与「取最新值」等价，但额外获得了<b>重试幂等性</b>。
     *
     * @param rows 待写入的行
     * @return 受影响行数
     */
    int mergeUv(Collection<UvRow> rows);

    /**
     * 一行 PV 增量。
     *
     * @param linkId   短链主键
     * @param statDate 统计归属日
     * @param delta    本次新增的浏览量
     */
    record PvRow(long linkId, LocalDate statDate, long delta) {
    }

    /**
     * 一行 UV 估算值。
     *
     * @param linkId    短链主键
     * @param statDate  统计归属日
     * @param estimate 当日独立访客估算（HyperLogLog {@code PFCOUNT} 的结果）
     */
    record UvRow(long linkId, LocalDate statDate, long estimate) {
    }
}
