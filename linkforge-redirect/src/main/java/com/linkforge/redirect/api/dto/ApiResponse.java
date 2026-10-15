package com.linkforge.redirect.api.dto;

import java.time.OffsetDateTime;

/**
 * 统一成功响应外壳（docs/API.md §3.3）。
 *
 * <p>API.md §3.3 定义的统一结构是：
 * <pre>
 * {
 *   "data": { ... },
 *   "timestamp": "2026-01-15T10:30:00+08:00"
 * }
 * </pre>
 *
 * <h2>为什么失败响应不套这一层</h2>
 * 失败走 {@code GlobalExceptionHandler} 的 {@code {code, message, timestamp, details}}——
 * 失败时<b>没有业务数据</b>，硬套一个空的 {@code data: null} 只会让调用方多写一层判空。
 * 成功与失败因此是两种形状，这是 API.md §3.3 的原文约定，不是实现偷懒。
 *
 * <h2>为什么 {@code timestamp} 在外壳上而不是 data 里</h2>
 * 它标记的是「这条响应被生成的时间」，与业务数据无关。放进 data 会让它看起来像业务字段，
 * 各接口的 data 内容就会不一致（有的有、有的没有）。
 *
 * <p>⚠️ 外壳与具体 DTO 分离：外壳是<b>协议</b>，DTO 是<b>业务</b>。
 * 这样第 4 周的查询接口、第 3 周的 admin 接口只需换 DTO，外壳复用同一份。
 *
 * @param data业务数据，不可为 {@code null}（无返回值的接口用 204，不走本外壳）
 * @param timestamp 服务端生成时间，按展示时区渲染（DR-027）
 */
public record ApiResponse<T>(T data, OffsetDateTime timestamp) {

    /**
     * 工厂方法：自动填入当前时间。
     *
     * @param data业务数据
     * @param displayZone 展示时区（配置项 {@code linkforge.display-zone}）
     */
    public static <T> ApiResponse<T> of(T data, java.time.ZoneId displayZone) {
        return new ApiResponse<>(data, OffsetDateTime.now(displayZone));
    }
}