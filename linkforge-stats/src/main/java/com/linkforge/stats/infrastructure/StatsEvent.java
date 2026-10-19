package com.linkforge.stats.infrastructure;

/**
 * 一条统计事件（第 4 周）。
 *
 * <h2>⚠️ 为什么放在 {@code infrastructure} 而不是 {@code domain}</h2>
 * 它不是领域模型——没有行为、不参与业务规则判定，
 * 只是「从队列传给消费线程的载荷」。
 * 放 domain 会让 {@code com.linkforge.stats.domain} 被
 * 「领域概念」的语义绑架，而它只是 DTO。
 *
 * <p>对比：真正的领域模型是第 4 周要写的「每日统计聚合」，
 * 它有计算规则（UV 合并、累加），才配放domain。
 *
 * <h2>字段说明</h2>
 * @param code    短码（PV 按它分片）
 * @param visitor 访客标识（XFF 首跳，UV 用它做 HLL 去重）。
 *                 **阶段 2 才填充**，当前为 {@code null}
 * @param ts      事件发生时刻（主线程取，避免消费端用处理时间代替发生时间）
 */
public record StatsEvent(String code, String visitor, long ts) {
}
