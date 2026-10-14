package com.linkforge.redirect.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.linkforge.core.error.ErrorCode;
import com.linkforge.core.error.LinkForgeException;
import com.linkforge.redirect.application.RedirectService;
import com.linkforge.redirect.error.GlobalExceptionHandler;
import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 跳转接口的 HTTP 契约测试（docs/API.md §4）。
 *
 * <p>只加载 Web 层：Service 用 mock 替身，因此这里只验证协议层行为
 * ——状态码、{@code Location}、{@code Cache-Control}、错误体结构，
 * 不重复验证业务规则（那在 {@code RedirectServiceTest} 与 {@code LinkValidatorTest}）。
 */
@WebMvcTest(controllers = RedirectController.class)
@ContextConfiguration(classes = {RedirectController.class, GlobalExceptionHandler.class,
        RedirectControllerTest.TestConfig.class})
class RedirectControllerTest {

    @TestConfiguration
    static class TestConfig {
        @Bean
        ZoneId displayZone() {
            return ZoneId.of("+08:00");
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RedirectService redirectService;

    @Nested
    @DisplayName("成功：302 Found")
    class Redirect {

        @Test
        @DisplayName("⭐ Location 是目标绝对 URL，Cache-Control 为 no-store，body 为空")
        void returns302WithLocationAndNoStore() throws Exception {
            given(redirectService.resolveTargetUrl("abc1234"))
                    .willReturn("https://example.com/very/long/path");

            mockMvc.perform(get("/abc1234"))
                    .andExpect(status().isFound())
                    .andExpect(header().string("Location", "https://example.com/very/long/path"))
                    .andExpect(header().string("Cache-Control", "no-store"))
                    // 成功响应无 body（API.md §3.1）
                    .andExpect(content().string(""));
        }

        @Test
        @DisplayName("⭐ Location 不是 shortUrl（否则浏览器会跳回自己）")
        void locationIsTargetNotShortUrl() throws Exception {
            given(redirectService.resolveTargetUrl("abc1234"))
                    .willReturn("https://example.com/x");

            mockMvc.perform(get("/abc1234"))
                    .andExpect(header().string("Location",
                            org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("localhost"))));
        }

        @Test
        @DisplayName("成功响应不带任何业务错误体")
        void noBodyOnSuccess() throws Exception {
            given(redirectService.resolveTargetUrl("abc1234")).willReturn("https://example.com/x");

            mockMvc.perform(get("/abc1234"))
                    .andExpect(content().string(""));
        }
    }

    @Nested
    @DisplayName("失败：统一错误体")
    class Failures {

        @Test
        @DisplayName("不存在 → 404 + LINKFORGE-404-01")
        void notFound() throws Exception {
            given(redirectService.resolveTargetUrl(any()))
                    .willThrow(new LinkForgeException(ErrorCode.LINK_NOT_FOUND, "短码不存在"));

            mockMvc.perform(get("/zzzzzzz"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("LINKFORGE-404-01"))
                    .andExpect(header().doesNotExist("Location"));
        }

        @Test
        @DisplayName("已过期 → 410 + LINKFORGE-410-01")
        void expired() throws Exception {
            given(redirectService.resolveTargetUrl(any()))
                    .willThrow(new LinkForgeException(ErrorCode.LINK_EXPIRED, "短码已过期"));

            mockMvc.perform(get("/abc1234"))
                    .andExpect(status().isGone())
                    .andExpect(jsonPath("$.code").value("LINKFORGE-410-01"));
        }

        @Test
        @DisplayName("非法字符 → 400 + LINKFORGE-400-01")
        void illegalChars() throws Exception {
            given(redirectService.resolveTargetUrl(any()))
                    .willThrow(new LinkForgeException(ErrorCode.INVALID_PARAMETER, "字符非法"));

            mockMvc.perform(get("/abc_123"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("LINKFORGE-400-01"));
        }

        @Test
        @DisplayName("⭐ 尾斜杠 → 400 + LINKFORGE-400-01，且由 Controller 直接拒绝（不重定向、不查库）")
        void trailingSlashRejected() throws Exception {
            mockMvc.perform(get("/abc1234/"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("LINKFORGE-400-01"))
                    .andExpect(header().doesNotExist("Location"));
            verifyNoInteractions(redirectService);
        }
    }
}
