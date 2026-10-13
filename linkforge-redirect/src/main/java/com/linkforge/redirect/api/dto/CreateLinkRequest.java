package com.linkforge.redirect.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;

/**
 * 创建短链请求（docs/API.md §5.1）。
 *
 * <p>字段级**格式**约束用注解（{@code @NotBlank} / {@code @Size}）；
 * **业务规则**（协议白名单、保留字黑名单、{@code expireAt} 是否将来）由
 * {@code LinkValidator} 手写校验——理由见 D4 设计：那些规则要读配置、要解析 URI、要可注入时间，
 * 放进注解会让「改规则就得改 DTO」。
 *
 * <p>{@code expireAt} 用 {@link OffsetDateTime}：**保留调用方传入的偏移**，
 * 不做隐式时区转换（DR-027）。不带时区的输入会在 Jackson 反序列化阶段失败，
 * 由 {@code GlobalExceptionHandler} 映射为 {@code LINKFORGE-400-01}。
 */
public record CreateLinkRequest(

        @NotBlank(message = "targetUrl 不能为空")
        @Size(max = 2048, message = "targetUrl 长度不能超过 2048")
        String targetUrl,

        /** 可选。非空时受 {@code ^[a-zA-Z0-9]{4,8}$} 与保留字黑名单约束。 */
        String customCode,

        /** 可选。不传表示永久有效；必须是将来时间。 */
        OffsetDateTime expireAt
) {
}
