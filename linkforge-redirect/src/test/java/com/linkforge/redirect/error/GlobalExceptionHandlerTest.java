package com.linkforge.redirect.error;

import static org.assertj.core.api.Assertions.assertThat;

import com.linkforge.core.error.ErrorBody;
import com.linkforge.core.error.ErrorCode;
import java.time.ZoneId;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;

/**
 * 全局异常处理器测试（DR-064）。
 *
 * <h2>⚠️ 本类针对的是「客户端用错方法却返回 500」这个真实缺陷</h2>
 * 2026-10-27 本地验收实测：
 * <pre>
 *   GET /admin/api/v1/links/0000VF0   （Token 是错的）
 *   → 500 {"code":"LINKFORGE-500-01","message":"internal server error"}
 * </pre>
 *
 * <p>根因的第二层：该异常<b>没有被任何 {@code @ExceptionHandler} 映射</b>，
 * 于是掉进兜底分支 → 500。
 * 而 500 的语义是「服务有 bug，需要人介入」——
 * 这里只是「客户端用错了 HTTP 方法」，报 500 会污染错误率指标与告警。
 *
 * <h2>为什么单独测处理器</h2>
 * 端到端测试（{@code AdminAuthEndToEndTest}）证明「整条链路对」，
 * 但它起真实容器、耗时长；本类直接断言映射结果，**快且精确**。
 * 两者互补：一个是「链路通不通」，一个是「映射准不准」。
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler(ZoneId.of("+08:00"));

    @Test
    @DisplayName("⭐⭐⭐ 方法不被支持 → 405 + LINKFORGE-405-01（不是 500）")
    void methodNotSupportedMapsTo405() {
        HttpRequestMethodNotSupportedException e =
                new HttpRequestMethodNotSupportedException("GET", java.util.List.of("DELETE"));

        ResponseEntity<Map<String, Object>> resp = handler.handleMethodNotSupported(e, null);

        assertThat(resp.getStatusCode())
                .as("客户端用错方法必须 405。500 会让运维去查代码，而问题在调用方")
                .isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(resp.getBody()).containsEntry("code", "LINKFORGE-405-01");
        assertThat(resp.getBody()).containsEntry("message", "method not allowed");
        assertThat(resp.getBody()).containsKey("timestamp");
    }

    @Test
    @DisplayName("⭐⭐ 405 必须带 Allow 头（RFC 7231）")
    void methodNotAllowedCarriesAllowHeader() {
        HttpRequestMethodNotSupportedException e =
                new HttpRequestMethodNotSupportedException("GET", java.util.List.of("DELETE"));

        ResponseEntity<Map<String, Object>> resp = handler.handleMethodNotSupported(e, null);

        assertThat(resp.getHeaders().getFirst(HttpHeaders.ALLOW))
                .as("405 必须告知客户端该资源支持哪些方法")
                .contains("DELETE");
    }

    @Test
    @DisplayName("⭐⭐ 未知方法（无 supported 列表）→ 仍是 405，不抛 NPE")
    void methodNotAllowedWithoutSupportedListStill405() {
        HttpRequestMethodNotSupportedException e = new HttpRequestMethodNotSupportedException("TRACE");

        ResponseEntity<Map<String, Object>> resp = handler.handleMethodNotSupported(e, null);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(resp.getBody()).containsEntry("code", "LINKFORGE-405-01");
    }

    @Test
    @DisplayName("⭐⭐⭐ 错误体与 core 的 ErrorBody **逐字段一致**（DR-064）")
    void errorBodyIsBuiltByCoreErrorBody() {
        // 过滤器（AdminAuthFilter）与全局处理器必须产出**完全相同**的结构，
        // 否则「有些接口的错误体少一个 timestamp」这种漂移迟早出现。
        Map<String, Object> fromCore = ErrorBody.of(
                ErrorCode.UNAUTHORIZED, "缺少或无效的后台 Token", null, java.time.OffsetDateTime.now());

        assertThat(fromCore)
                .containsEntry("code", "LINKFORGE-401-01")
                .containsEntry("message", "缺少或无效的后台 Token")
                .containsKey("timestamp");
        // ⚠️ details 为空时**不应出现**该字段
        assertThat(fromCore).doesNotContainKey("details");
    }

    @Test
    @DisplayName("⭐ 业务异常 → 状态码取自 ErrorCode（唯一来源）")
    void businessExceptionUsesErrorCodeStatus() {
        ResponseEntity<Map<String, Object>> resp = handler.handleLinkForge(
                new com.linkforge.core.error.LinkForgeException(ErrorCode.UNAUTHORIZED, "缺少或无效的后台 Token"),
                null);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(resp.getBody()).containsEntry("code", "LINKFORGE-401-01");
    }

    @Test
    @DisplayName("⭐ 未匹配路由 → 404（不是 500）")
    void noResourceMapsTo404() {
        ResponseEntity<Map<String, Object>> resp = handler.handleNoResource(
                new org.springframework.web.servlet.resource.NoResourceFoundException(HttpMethod.GET, "/nope"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(resp.getBody()).containsEntry("code", "LINKFORGE-404-01");
    }

    // ------------------------------------------------------------ DR-064 噪声

    @Test
    @DisplayName("⭐⭐ favicon.ico → 状态码照给，但**不刷错误日志**（浏览器噪声）")
    void faviconRequestIsTreatedAsNoise() {
        ResponseEntity<Map<String, Object>> resp = handler.handleLinkForge(
                new com.linkforge.core.error.LinkForgeException(
                        ErrorCode.INVALID_PARAMETER, "短码只能包含 Base62 字符（0-9A-Za-z）: \"favicon.ico\""),
                requestWithUri("/favicon.ico"));

        // ⚠️ 状态码不变（契约不因日志级别而改变），只是日志级别降到 DEBUG
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody()).containsEntry("code", "LINKFORGE-400-01");
    }

    @Test
    @DisplayName("⭐⭐ 非噪声路径的 400 行为完全不变（不能被「静默」逻辑误伤）")
    void normalBadRequestStillBehavesTheSame() {
        ResponseEntity<Map<String, Object>> resp = handler.handleLinkForge(
                new com.linkforge.core.error.LinkForgeException(
                        ErrorCode.INVALID_PARAMETER, "短码只能包含 Base62 字符（0-9A-Za-z）: \"bad.code\""),
                requestWithUri("/bad.code"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody()).containsEntry("code", "LINKFORGE-400-01");
    }

    /** 造一个只提供 URI 的请求桩。 */
    private static jakarta.servlet.http.HttpServletRequest requestWithUri(String uri) {
        jakarta.servlet.http.HttpServletRequest request =
                org.mockito.Mockito.mock(jakarta.servlet.http.HttpServletRequest.class);
        org.mockito.Mockito.when(request.getRequestURI()).thenReturn(uri);
        return request;
    }
}
