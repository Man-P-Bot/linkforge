package com.linkforge.redirect.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.linkforge.core.domain.ShortLinkBaseUrl;
import com.linkforge.core.error.ErrorCode;
import com.linkforge.core.error.LinkForgeException;
import com.linkforge.redirect.api.dto.CreateLinkResponse;
import com.linkforge.redirect.application.LinkCreationService;
import com.linkforge.redirect.infrastructure.limit.RedisTokenBucketLimiter;
import com.linkforge.redirect.error.GlobalExceptionHandler;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 创建接口的 HTTP 契约测试（docs/API.md §5）。
 *
 * <p>只加载 Web 层（{@code @WebMvcTest}）：Service 用 mock 替身，
 * 因此这里**只验证协议层行为** —— 状态码、错误码映射、响应体结构，
 * 不重复验证业务规则（那在 {@code LinkCreationServiceTest} 与 {@code LinkValidatorTest}）。
 *
 * <p>⚠️ 必须显式给 {@code @ContextConfiguration}：{@code @WebMvcTest} 会自动向上搜索
 * {@code @SpringBootConfiguration}，而启动类在 {@code linkforge-app}、本测试在
 * {@code linkforge-redirect}——两个模块不共享包路径，搜索必然失败。因此这里指定
 * {@link LinkController} 作为被测配置，并用 {@link TestConfig} 补上 Web 层所需的 Bean。
 */
@WebMvcTest(controllers = LinkController.class)
@ContextConfiguration(classes = {LinkController.class, GlobalExceptionHandler.class,
        LinkControllerTest.TestConfig.class})
class LinkControllerTest {

    @TestConfiguration
    static class TestConfig {
        /** 错误处理需要展示时区；真实实现由 RedirectConfig 提供。 */
        @Bean
        ZoneId displayZone() {
            return ZoneId.of("+08:00");
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LinkCreationService linkCreationService;

    /**
     * 限流器替身，默认放行。
     *
     * <p>⚠️ 本类测的是创建接口的 HTTP 契约，限流由
     * {@code RedisTokenBucketLimiterTest} 覆盖。
     * 不设桩时 Mockito 返回 null，{@code !decision.allowed()} 会 NPE。
     */
    @MockitoBean
    private RedisTokenBucketLimiter limiter;

    @BeforeEach
    void allowAll() {
        given(limiter.tryAcquire(any(), any())).willReturn(RedisTokenBucketLimiter.Decision.permit());
    }

    private static final String VALID_BODY = """
            {"targetUrl":"https://example.com/very/long/path"}
            """;

    @Nested
    @DisplayName("成功：201 Created")
    class Created {

        @Test
        @DisplayName("返回 201，body 为统一外壳 {data:{...}, timestamp}（API.md §3.3）")
        void returns201WithBody() throws Exception {
            given(linkCreationService.create(any())).willReturn(new CreateLinkResponse(
                    "0000Q0u",
                    "http://localhost:8080/0000Q0u",
                    "https://example.com/very/long/path",
                    false,
                    null,
                    OffsetDateTime.parse("2026-01-15T10:30:00+08:00")));

            mockMvc.perform(post("/api/v1/links")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(VALID_BODY))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.shortCode").value("0000Q0u"))
                    .andExpect(jsonPath("$.data.shortUrl").value("http://localhost:8080/0000Q0u"))
                    .andExpect(jsonPath("$.data.targetUrl").value("https://example.com/very/long/path"))
                    .andExpect(jsonPath("$.data.custom").value(false))
                    .andExpect(jsonPath("$.data.expireAt").doesNotExist())
                    // 外壳自带 timestamp（API.md §3.3 成功响应含 timestamp）
                    .andExpect(jsonPath("$.timestamp").exists());
        }

        @Test
        @DisplayName("⭐ 业务字段一律在 data 下，顶层只有 data 与 timestamp 两个键")
        void topLevelHasOnlyDataAndTimestamp() throws Exception {
            given(linkCreationService.create(any())).willReturn(new CreateLinkResponse(
                    "0000Q0u", "http://localhost:8080/0000Q0u", "https://example.com/x",
                    false, null, OffsetDateTime.parse("2026-01-15T10:30:00+08:00")));

            mockMvc.perform(post("/api/v1/links")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(VALID_BODY))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data").exists())
                    .andExpect(jsonPath("$.timestamp").exists())
                    // 旧版扁平结构已移除：业务字段不得再出现在顶层
                    .andExpect(jsonPath("$.shortCode").doesNotExist())
                    .andExpect(jsonPath("$.shortUrl").doesNotExist())
                    .andExpect(jsonPath("$.targetUrl").doesNotExist());
        }

        @Test
        @DisplayName("⭐ data 内不含数据库内部 id（API.md §5.3 明确要求不外泄）")
        void doesNotLeakInternalId() throws Exception {
            given(linkCreationService.create(any())).willReturn(new CreateLinkResponse(
                    "0000Q0u", "http://localhost:8080/0000Q0u", "https://example.com/x",
                    false, null, OffsetDateTime.parse("2026-01-15T10:30:00+08:00")));

            mockMvc.perform(post("/api/v1/links")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(VALID_BODY))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.id").doesNotExist())
                    .andExpect(jsonPath("$.data.status").doesNotExist())
                    // id 也不得泄漏到顶层
                    .andExpect(jsonPath("$.id").doesNotExist())
                    .andExpect(jsonPath("$.status").doesNotExist());
        }

        @Test
        @DisplayName("⭐ 不返回 Location 头（DR-028：指向 shortUrl 语义混乱，指向资源端点 D5 才有）")
        void doesNotReturnLocationHeader() throws Exception {
            given(linkCreationService.create(any())).willReturn(new CreateLinkResponse(
                    "0000Q0u", "http://localhost:8080/0000Q0u", "https://example.com/x",
                    false, null, OffsetDateTime.parse("2026-01-15T10:30:00+08:00")));

            mockMvc.perform(post("/api/v1/links")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(VALID_BODY))
                    .andExpect(status().isCreated())
                    .andExpect(result -> org.assertj.core.api.Assertions
                            .assertThat(result.getResponse().getHeader("Location")).isNull());
        }
    }

    @Nested
    @DisplayName("字段级校验失败：400-01")
    class Validation {

        @Test
        @DisplayName("缺 targetUrl → 400 + LINKFORGE-400-01 + details 指出字段")
        void missingTargetUrl() throws Exception {
            mockMvc.perform(post("/api/v1/links")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_PARAMETER.code()))
                    .andExpect(jsonPath("$.details.targetUrl").exists());
            // 校验失败不应进入 Service
            verify(linkCreationService, never()).create(any());
        }

        @Test
        @DisplayName("targetUrl 超 2048 → 400")
        void targetUrlTooLong() throws Exception {
            String tooLong = "https://example.com/" + "a".repeat(2100);

            mockMvc.perform(post("/api/v1/links")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"targetUrl\":\"" + tooLong + "\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_PARAMETER.code()));
        }

        @Test
        @DisplayName("⭐ expireAt 不带时区 → 400-01（DR-027 要求必须带偏移）")
        void expireAtWithoutOffset() throws Exception {
            mockMvc.perform(post("/api/v1/links")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"targetUrl\":\"https://example.com/x\","
                                    + "\"expireAt\":\"2026-12-31T23:59:59\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_PARAMETER.code()));
        }

        @Test
        @DisplayName("请求体不是合法 JSON → 400-01")
        void malformedJson() throws Exception {
            mockMvc.perform(post("/api/v1/links")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{not-json"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_PARAMETER.code()));
        }
    }

    @Nested
    @DisplayName("业务异常映射（错误码 → HTTP 状态）")
    class ErrorMapping {

        @Test
        @DisplayName("协议白名单 → 400 + LINKFORGE-400-02（安全边界独立成码）")
        void protocolError() throws Exception {
            given(linkCreationService.create(any()))
                    .willThrow(new LinkForgeException(ErrorCode.UNSUPPORTED_PROTOCOL, "scheme 不允许"));

            mockMvc.perform(post("/api/v1/links")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(VALID_BODY))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("LINKFORGE-400-02"));
        }

        @Test
        @DisplayName("保留字短码 → 400 + LINKFORGE-400-04")
        void reservedCode() throws Exception {
            given(linkCreationService.create(any()))
                    .willThrow(new LinkForgeException(ErrorCode.RESERVED_CUSTOM_CODE, "保留字"));

            mockMvc.perform(post("/api/v1/links")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(VALID_BODY))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("LINKFORGE-400-04"));
        }

        @Test
        @DisplayName("自定义短码冲突 → 409 + LINKFORGE-409-01")
        void conflict() throws Exception {
            given(linkCreationService.create(any()))
                    .willThrow(new LinkForgeException(ErrorCode.SHORT_CODE_CONFLICT, "已占用"));

            mockMvc.perform(post("/api/v1/links")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(VALID_BODY))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("LINKFORGE-409-01"));
        }

        @Test
        @DisplayName("依赖不可用 → 503 + LINKFORGE-503-01")
        void dependencyUnavailable() throws Exception {
            given(linkCreationService.create(any()))
                    .willThrow(new LinkForgeException(ErrorCode.DEPENDENCY_UNAVAILABLE, "发号失败"));

            mockMvc.perform(post("/api/v1/links")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(VALID_BODY))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("LINKFORGE-503-01"));
        }

        @Test
        @DisplayName("⭐ 错误体带 timestamp，且状态码与错误码中段一致")
        void errorBodyShape() throws Exception {
            given(linkCreationService.create(any()))
                    .willThrow(new LinkForgeException(ErrorCode.SHORT_CODE_CONFLICT, "已占用"));

            mockMvc.perform(post("/api/v1/links")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(VALID_BODY))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.timestamp").exists())
                    // 409 响应必须带 409 段的码，不允许出现「状态码与错误码不一致」
                    .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.startsWith("LINKFORGE-409-")));
        }

        @Test
        @DisplayName("未预期异常 → 500，且**不泄露内部细节**")
        void unexpectedError() throws Exception {
            given(linkCreationService.create(any()))
                    .willThrow(new IllegalStateException("内部实现细节：com.foo.Bar 连接池已满"));

            mockMvc.perform(post("/api/v1/links")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(VALID_BODY))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.code").value("LINKFORGE-500-01"))
                    .andExpect(jsonPath("$.message").value("internal server error"))
                    // 关键：不把异常 message 回显给调用方
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(
                            org.hamcrest.Matchers.containsString("连接池"))));
        }
    }
}
