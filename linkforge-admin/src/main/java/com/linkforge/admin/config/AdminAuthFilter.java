package com.linkforge.admin.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linkforge.core.error.ErrorBody;
import com.linkforge.core.error.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 后台鉴权过滤器（DR-064）。
 *
 * <h2>⚠️ 为什么鉴权必须在「路由解析之前」，而不能留在 Controller 里</h2>
 * <b>真实事故（2026-10-27 本地验收实测）：</b>
 * <pre>
 *   GET /admin/api/v1/links/0000VF0   （Authorization 头是错的）
 *   → HTTP 500  {"code":"LINKFORGE-500-01","message":"internal server error"}
 * </pre>
 *
 * <p>表面看是「Token 校验失败没抛对异常」，<b>真实原因完全不同</b>：
 * <ol>
 *   <li>{@code /admin/api/v1/links/{code}} 只定义了 {@code DELETE}，
 *       没有任何 handler 能处理 {@code GET}；</li>
 *   <li>Spring 在**路由解析阶段**就抛了
 *       {@code HttpRequestMethodNotSupportedException}；</li>
 *   <li>那时的鉴权检查写在 <b>Controller 方法体内</b>，<b>根本没被执行到</b>；</li>
 *   <li>该异常没有被任何 {@code @ExceptionHandler} 映射，
 *       于是掉进兜底分支 → <b>500</b>。</li>
 * </ol>
 *
 * <p>所以「Token 比对逻辑写错了」是误判 ——
 * {@link AdminToken#matches(String)} 本身是对的（null / 空串都已挡住）。
 * <b>错的是鉴权的位置</b>：只要鉴权在 Controller 里，
 * 「路由不匹配」这个分支就永远绕过鉴权，
 * 而绕过鉴权的表现不是「放行」，是「500」——
 * 一个**客户端问题**被伪装成**服务故障**，会污染错误率指标与告警。
 *
 * <h2>过滤器为什么能解决</h2>
 * Servlet 过滤器在 {@code DispatcherServlet} <b>之前</b>执行，
 * 因此<b>先于</b>路由解析：只要请求路径在 {@code /admin/**} 下，
 * 就先判鉴权，判不过直接 401，<b>根本不会走到路由解析</b>。
 *
 * <h2>⚠️ 为什么 Controller 里仍然保留一次鉴权</h2>
 * 纵深防御：过滤器依赖「URL 模式注册」，Controller 依赖「代码位置」。
 * 两者都留着，任何一侧失效都不会导致后台接口失去保护。
 * 两处调用的是<b>同一个</b> {@link AdminToken#matchesAuthorizationHeader(String)}，
 * 不存在「一处放宽、一处收紧」的不一致。
 *
 * <h2>⚠️ 为什么过滤器自己写响应体，而不是抛异常</h2>
 * {@code @RestControllerAdvice} 只处理 DispatcherServlet 内部抛出的异常；
 * <b>过滤器里抛的异常走不到那里</b>，最终会变成 Spring Boot 默认的
 * {@code /error} 转发 + 一个**格式不一致**的错误体。
 * 所以过滤器直接写响应体，并用 core 的 {@link ErrorBody} 保证格式与
 * 全局异常处理器**完全一致**（DR-064）。
 */
public class AdminAuthFilter extends OncePerRequestFilter {

    private final AdminToken adminToken;
    private final ZoneId displayZone;
    private final ObjectMapper objectMapper;

    public AdminAuthFilter(AdminToken adminToken, ZoneId displayZone, ObjectMapper objectMapper) {
        this.adminToken = adminToken;
        this.displayZone = displayZone;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);

        // ⚠️ matchesAuthorizationHeader 对任何输入都返回确定结果，**永不抛异常**
        if (!adminToken.matchesAuthorizationHeader(header)) {
            writeUnauthorized(response);
            return;
        }
        chain.doFilter(request, response);
    }

    /**
     * 写出 401 响应。
     *
     * <p>⚠️ 响应体与 {@code GlobalExceptionHandler} 的结构**逐字段一致**：
     * {@code code} / {@code message} / {@code timestamp}（{@code details} 为空时不出现）。
     */
    private void writeUnauthorized(HttpServletResponse response) throws IOException {
        Map<String, Object> body = ErrorBody.of(
                ErrorCode.UNAUTHORIZED, "缺少或无效的后台 Token", null, OffsetDateTime.now(displayZone));

        response.setStatus(ErrorCode.UNAUTHORIZED.httpStatus());
        // ⚠️ 先设字符集再设 content type —— 顺序反了会导致中文 message 变成乱码
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        // ⚠️ RFC 7235：401 必须带 WWW-Authenticate，告诉客户端该怎么重试
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        objectMapper.writeValue(response.getWriter(), body);
    }
}
