package com.linkforge.redirect.api;

import com.linkforge.redirect.api.dto.ApiResponse;
import com.linkforge.redirect.api.dto.CreateLinkRequest;
import com.linkforge.redirect.api.dto.CreateLinkResponse;
import com.linkforge.redirect.application.LinkCreationService;
import com.linkforge.redirect.infrastructure.limit.RateLimitScope;
import com.linkforge.redirect.infrastructure.limit.RedisTokenBucketLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.ZoneId;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 创建短链接口（docs/API.md §5）。
 *
 * <p><b>零业务逻辑</b>：只做协议转换 —— 参数绑定、{@code @Valid} 触发字段级校验、
 * 调用用例、返回状态码。校验规则、发号、拼接、事务都在
 * {@link LinkCreationService}（ARCHITECTURE §4 的分层约束）。
 *
 * <p>响应体用统一外壳 {@link ApiResponse} 包裹（API.md §3.3）：
 * {@code {"data": {...}, "timestamp": "..."}}。失败响应<b>不套</b>这一层，
 * 走 {@code GlobalExceptionHandler} 的 {@code {code, message, timestamp, details}}。
 *
 * <p>返回 <b>201 Created</b>（API.md §5.3 定稿）。
 * <b>刻意不加 {@code Location} 头</b>（DR-028）：指向 {@code shortUrl} 语义混乱
 * （那是给最终用户点击的跳转入口，不是本 API 的资源端点），而指向
 * {@code /api/v1/links/{code}} 的资源端点 D5 才存在——指向一个尚不存在的 URL 比不返回更糟。
 * 响应体里已有 {@code shortUrl}，客户端所需信息完整。
 */
@RestController
@RequestMapping("/api/v1/links")
public class LinkController {

    private final LinkCreationService linkCreationService;
    private final RedisTokenBucketLimiter limiter;
    private final ZoneId displayZone;

    public LinkController(LinkCreationService linkCreationService,
                          RedisTokenBucketLimiter limiter,
                          ZoneId displayZone) {
        this.linkCreationService = linkCreationService;
        this.limiter = limiter;
        this.displayZone = displayZone;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<CreateLinkResponse>> create(
            @Valid @RequestBody CreateLinkRequest request,
            HttpServletRequest httpRequest) {
        // ⚠️ 限流在业务逻辑之前。⚠️ scope 必须用 CREATE 而非 REDIRECT——
        // 两者阈值差 120 倍（20/s vs 10/min），共用 key 会让创建配额被跳转流量吃光
        RedisTokenBucketLimiter.Decision decision =
                limiter.tryAcquire(resolveClientId(httpRequest), RateLimitScope.CREATE);
        if (!decision.allowed()) {
            throw new com.linkforge.core.error.LinkForgeException(
                    com.linkforge.core.error.ErrorCode.RATE_LIMITED,
                    "创建过于频繁，请 " + decision.retryAfter().toMillis() + "ms 后重试");
        }

        CreateLinkResponse response = linkCreationService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.of(response, displayZone));
    }

    /**
     * 解析客户端标识（与 {@code RedirectController} 同逻辑）。
     *
     * <p>⚠️ 信任 X-Forwarded-For 有被伪造的风险（伪造不同 IP 即可绕过限流）。
     * 当前项目直连，只取 XFF 第一个值作为最靠近客户端的那一跳。
     * 取不到时回退到 {@code remoteAddr}，再取不到则用 {@code "unknown"}——
     * **所有拿不到 IP 的请求共享一个桶**是保守方向，比各自独立（等于不限流）安全。
     */
    private String resolveClientId(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            String first = comma > 0 ? xff.substring(0, comma) : xff;
            first = first.trim();
            if (!first.isEmpty()) {
                return first;
            }
        }
        String remote = request.getRemoteAddr();
        return (remote == null || remote.isBlank()) ? "unknown" : remote;
    }
}
