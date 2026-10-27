package com.linkforge.admin.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import java.io.IOException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 后台鉴权过滤器测试（DR-064）。
 *
 * <h2>⚠️ 本类针对的是「500 而非 401」这个真实缺陷</h2>
 * 2026-10-27 本地验收实测：
 * <pre>
 *   GET /admin/api/v1/links/0000VF0   （Authorization 头是错的）
 *   → 500 {"code":"LINKFORGE-500-01","message":"internal server error"}
 * </pre>
 *
 * <p>根因<b>不是</b> Token 比对写错（{@link AdminToken#matches(String)} 一直是对的），
 * 而是<b>鉴权的位置</b>：它写在 Controller 方法体内，
 * 而 {@code GET} 在<b>路由解析阶段</b>就抛了
 * {@code HttpRequestMethodNotSupportedException} —— 控制器根本没被执行到，
 * 异常掉进全局兜底分支变成 500。
 *
 * <p>本类因此断言的是「过滤器的判定结果」而不是「HTTP 状态码」——
 * 状态码那一层由 {@code AdminAuthEndToEndTest}（真实 HTTP）覆盖。
 *
 * <h2>为什么用 MockHttpServletRequest 而不是 MockMvc</h2>
 * 过滤器在 Servlet 容器链上，MockMvc 的 standalone setup 对过滤器的覆盖不完整。
 * 直接构造 request/response + 一个记录型 FilterChain，断言更直接也更可信。
 */
class AdminAuthFilterTest {

    private static final String REAL_TOKEN = "s3cret-admin-token";

    private final ObjectMapper mapper = new ObjectMapper();

    /** 记录过滤器链是否被调用 —— 「是否放行」的唯一可观测证据。 */
    private static final class RecordingChain implements FilterChain {
        private final List<String> invoked = new ArrayList<>();
        private boolean called;

        @Override
        public void doFilter(jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response) {
            called = true;
            invoked.add(((MockHttpServletRequest) request).getRequestURI());
        }
    }

    private AdminAuthFilter filter() {
        return new AdminAuthFilter(new AdminToken(REAL_TOKEN), ZoneId.of("+08:00"), mapper);
    }

    private MockHttpServletRequest requestWith(String authHeader) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/admin/api/v1/links/0000VF0");
        if (authHeader != null) {
            request.addHeader(HttpHeaders.AUTHORIZATION, authHeader);
        }
        return request;
    }

    // ------------------------------------------------------------------
    // 拒绝路径
    // ------------------------------------------------------------------

    @Test
    @DisplayName("⭐⭐ Token 缺失 → 401，且下游链**不被调用**")
    void missingTokenIs401AndChainNotInvoked() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        RecordingChain chain = new RecordingChain();

        filter().doFilter(requestWith(null), response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(chain.called).as("鉴权失败时绝不能放行到下游").isFalse();
    }

    @Test
    @DisplayName("⭐⭐ Token 格式错误（没有 Bearer 前缀）→ 401")
    void malformedTokenIs401() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        RecordingChain chain = new RecordingChain();

        filter().doFilter(requestWith(REAL_TOKEN), response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(chain.called).isFalse();
    }

    @Test
    @DisplayName("⭐⭐ Token 内容不匹配（乱码 / 中文）→ 401")
    void mismatchedTokenIs401() throws Exception {
        for (String garbage : List.of(
                "Bearer wrong-token",
                "Bearer 你的ADMIN_API_TOKEN",
                "Bearer ??????",
                "Bearer ",
                "Bearer",
                "bearer " + REAL_TOKEN,          // 小写方案名
                "BEARER " + REAL_TOKEN,          // 大写方案名
                "Basic " + REAL_TOKEN,           // 其它认证方案
                REAL_TOKEN + " extra",           // 方案名位置放错
                "Bearer " + REAL_TOKEN + "x")) { // 多一位
            MockHttpServletResponse response = new MockHttpServletResponse();
            RecordingChain chain = new RecordingChain();

            filter().doFilter(requestWith(garbage), response, chain);

            assertThat(response.getStatus())
                    .as("Token「%s」应得 401", garbage)
                    .isEqualTo(401);
            assertThat(chain.called).as("Token「%s」不应放行", garbage).isFalse();
        }
    }

    @Test
    @DisplayName("⭐⭐ 空 Authorization 头 → 401（而不是抛异常）")
    void emptyAuthorizationHeaderIs401() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThatCode(() -> filter().doFilter(requestWith(""), response, new RecordingChain()))
                .doesNotThrowAnyException();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("⭐⭐⭐ 响应体格式与全局异常处理器完全一致：code / message / timestamp")
    void unauthorizedBodyMatchesContract() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter().doFilter(requestWith("Bearer garbage"), response, new RecordingChain());

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).contains("application/json");

        JsonNode json = mapper.readTree(response.getContentAsString());
        assertThat(json.path("code").asText()).isEqualTo("LINKFORGE-401-01");
        assertThat(json.path("message").asText()).isNotBlank();
        assertThat(json.path("timestamp").asText()).isNotBlank();
        // ⚠️ details 为空时**不应出现**该字段（与全局处理器的规则一致）
        assertThat(json.has("details")).isFalse();
    }

    @Test
    @DisplayName("⭐ 401 必须带 WWW-Authenticate 头（RFC 7235）")
    void unauthorizedHasWwwAuthenticateHeader() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter().doFilter(requestWith(null), response, new RecordingChain());

        assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo("Bearer");
    }

    // ------------------------------------------------------------------
    // 放行路径
    // ------------------------------------------------------------------

    @Test
    @DisplayName("⭐⭐ 正确 Token → 放行，下游链被调用，响应体不被写")
    void correctTokenPassesThrough() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        RecordingChain chain = new RecordingChain();

        filter().doFilter(requestWith("Bearer " + REAL_TOKEN), response, chain);

        assertThat(chain.called).as("正确 Token 必须放行").isTrue();
        assertThat(chain.invoked).containsExactly("/admin/api/v1/links/0000VF0");
        assertThat(response.getContentAsString()).isEmpty();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("⭐ 判定入口永不抛异常（任何输入都得到确定的 false）")
    void matchesAuthorizationHeaderNeverThrows() {
        AdminToken token = new AdminToken(REAL_TOKEN);

        List<String> inputs = new ArrayList<>();
        inputs.add(null);
        inputs.add("");
        inputs.add(" ");
        inputs.add("Bearer");
        inputs.add("Bearer ");
        inputs.add("Bearer \u0000");
        inputs.add("Bearer " + "x".repeat(10_000));
        inputs.add("\u4f60\u7684ADMIN_API_TOKEN");

        for (String input : inputs) {
            assertThatCode(() -> token.matchesAuthorizationHeader(input))
                    .as("输入「%s」不应抛异常", input)
                    .doesNotThrowAnyException();
            assertThat(token.matchesAuthorizationHeader(input))
                    .as("输入「%s」应判为不通过", input)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("⭐ 正确 Token 通过 matchesAuthorizationHeader")
    void correctTokenMatchesAuthorizationHeader() {
        AdminToken token = new AdminToken(REAL_TOKEN);

        assertThat(token.matchesAuthorizationHeader("Bearer " + REAL_TOKEN)).isTrue();
    }
}
