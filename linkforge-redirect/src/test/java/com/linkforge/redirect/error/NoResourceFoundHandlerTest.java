package com.linkforge.redirect.error;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.linkforge.core.error.ErrorCode;
import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 未匹配路由的异常映射（D6 收尾修复的回归测试）。
 *
 * <p>缺陷现象：{@code GET /} 返回 500 + {@code LINKFORGE-500-01}，而按 API.md 应为 404。
 * 根因：Spring 6.1+ 用 {@link NoResourceFoundException} 取代已废弃的
 * {@code NoHandlerFoundException}，它由静态资源处理器抛出，落到兜底 handler 变成 500。
 *
 * <h2>⚠️ 为什么用 standaloneSetup 而不是 {@code @WebMvcTest}</h2>
 * 本测试要验证的是 <b>handler 对该异常类型的映射</b>，不是「Spring 路由解析在各种
 * 上下文装配下会抛哪种异常」——后者会随 {@code @ContextConfiguration} 里加载了哪些 Bean
 * 而变化（限定上下文时不加载静态资源处理器，异常类型会变成 {@code NoHandlerFoundException}），
 * 测的是框架装配细节而非本项目的映射逻辑。
 *
 * <p>真正的端到端验证（真实 {@code GET /} 返回 404）在
 * {@code linkforge-app} 的启动验证脚本与 curl 冒烟中完成。
 *
 * <h2>⚠️ 为什么必须显式 setControllerAdvice</h2>
 * {@code standaloneSetup} 默认<b>不</b>注册任何 {@code @RestControllerAdvice}，
 * 异常会直接抛出而不是被处理。不加这一行，测试会以
 * {@code NestedServletException} 失败而不是拿到 404 响应——那看起来像 bug，其实是装配漏了。
 */
class NoResourceFoundHandlerTest {

    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new ThrowingController())
            .setControllerAdvice(new GlobalExceptionHandler(ZoneId.of("+08:00")))
            .build();

    @Test
    @DisplayName("⭐ 未匹配路径 → 404 + LINKFORGE-404-01，**不是 500**")
    void unmappedPathReturns404Not500() throws Exception {
        mockMvc.perform(get("/boom"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LINKFORGE-404-01"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    @DisplayName("⭐ 与短码查询路径用同一个错误码：调用方看到 404 就知道没有可跳转的东西")
    void usesSameErrorCodeAsShortCodeLookup() throws Exception {
        mockMvc.perform(get("/boom"))
                .andExpect(jsonPath("$.code").value(ErrorCode.LINK_NOT_FOUND.code()));
    }

    @Test
    @DisplayName("错误体不泄露内部细节（不出现异常类名与堆栈信息）")
    void doesNotLeakInternals() throws Exception {
        mockMvc.perform(get("/boom"))
                .andExpect(jsonPath("$.message").value(ErrorCode.LINK_NOT_FOUND.defaultMessage()))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("NoResourceFound"))))
                .andExpect(jsonPath("$.stackTrace").doesNotExist())
                .andExpect(jsonPath("$.exception").doesNotExist());
    }

    @Test
    @DisplayName("404 响应体不含 data 外壳（失败响应不套 data，API.md §3.3）")
    void doesNotWrapFailureInDataEnvelope() throws Exception {
        mockMvc.perform(get("/boom"))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.code").exists());
    }

    /** 模拟静态资源处理器：路径无匹配时抛 {@link NoResourceFoundException}。 */
    @RestController
    static class ThrowingController {
        @GetMapping("/boom")
        void boom() throws NoResourceFoundException {
            throw new NoResourceFoundException(HttpMethod.GET, "/");
        }
    }
}