package com.linkforge.redirect.error;

import com.linkforge.core.error.ErrorBody;
import com.linkforge.core.error.ErrorCode;
import com.linkforge.core.error.LinkForgeException;
import com.linkforge.core.repository.ShortCodeGenerator.ShortCodeGenerationException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 统一异常处理（docs/API.md §3.3）。
 *
 * <h2>为什么放在 {@code redirect} 而不是 {@code core}</h2>
 * 它需要 {@code spring-web}（{@code @RestControllerAdvice}、{@code ResponseEntity}），
 * 而 {@code core} 必须**保持零 web 依赖**（DR-021）：core 里的错误码与契约不该把 web 栈
 * 拖给所有下游模块。因此 {@code ErrorCode}/{@code LinkForgeException} 在 core（纯契约），
 * 框架映射在第一个需要它的模块。
 *
 * <h2>⚠️ 状态的唯一来源是 {@link ErrorCode#httpStatus()}</h2>
 * 本类**不做**「从错误码字符串反解状态码」（那是脆弱的隐式约定），而是读枚举字段。
 * 由于枚举同时持有 code 与 httpStatus，**状态码与错误码不可能不一致**。
 *
 * <p>第 3 / 4 周 admin 也需要错误处理时，按 DR-018 的双向隔离**不得**把 handler 上移到 core
 * 让 admin 依赖 redirect —— 届时的选项是新建 {@code linkforge-web} 公共层，或 admin 自写一份。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 浏览器噪声路径（DR-064）。
     *
     * <p>这些路径由浏览器自动请求，与业务无关：
     * 命中时一律<b>降级为 DEBUG 日志</b>，不刷 WARN/ERROR。
     */
    private static final Set<String> NOISE_PATHS = Set.of("/favicon.ico", "/robots.txt");

    private final ZoneId displayZone;

    public GlobalExceptionHandler(ZoneId displayZone) {
        this.displayZone = displayZone;
    }

    /** 业务异常：状态码取错误码自带的 httpStatus。 */
    @ExceptionHandler(LinkForgeException.class)
    public ResponseEntity<Map<String, Object>> handleLinkForge(LinkForgeException e,
                                                               HttpServletRequest request) {
        ErrorCode code = e.errorCode();
        // ⚠️ 浏览器噪声请求（favicon.ico 等）不该在错误日志里刷屏：
        //    它们是「每个浏览器标签页都会发一次」的正常流量，不是故障。
        if (isNoisePath(request)) {
            log.debug("噪声请求被拒绝：path={} code={} message={}",
                    request.getRequestURI(), code.code(), e.getMessage());
            return ResponseEntity.status(code.httpStatus())
                    .body(errorBody(code, e.getMessage(), null));
        }
        // 4xx 是调用方问题，记 WARN 即可；5xx 是自身问题，记 ERROR 带栈
        if (code.httpStatus() >= 500) {
            log.error("业务异常（{}）：{}", code.code(), e.getMessage(), e);
        } else {
            log.warn("业务异常（{}）：{}", code.code(), e.getMessage());
        }
        return ResponseEntity.status(code.httpStatus())
                .body(errorBody(code, e.getMessage(), null));
    }

    /**
     * 是否属于「浏览器噪声请求」。
     *
     * <p>⚠️ <b>为什么需要它</b>（DR-064）：
     * {@code /favicon.ico} 会落到跳转路径 {@code /{code}} 上，
     * 而 {@code favicon.ico} 不是合法短码（含 {@code .}）→ 400。
     * 浏览器每个标签页都会请求一次 favicon，于是错误日志里全是
     * 「短码只能包含 Base62 字符: favicon.ico」——<b>噪声淹没了真故障</b>
     * （D6 实测教训：一次真实缺陷能在 2 分钟内刷出 12 万条 ERROR）。
     *
     * <p>这类请求<b>静默处理</b>：状态码照给（日志与排障仍然看得到），
     * 但不占用 WARN/ERROR 级别——它是流量的一部分，不是故障。
     */
    private boolean isNoisePath(HttpServletRequest request) {
        if (request == null) {
            return false;
        }
        String uri = request.getRequestURI();
        return NOISE_PATHS.contains(uri);
    }

    /**
     * 字段级注解校验失败（{@code @Valid}）。
     *
     * <p>统一映射为 {@link ErrorCode#INVALID_PARAMETER} 并在 {@code details} 中指出具体字段——
     * 注解负责的只有「格式/长度」这类纯用法错误，因此不存在与业务规则错误码冲突的问题。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException e) {
        Map<String, Object> details = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors().forEach(fieldError ->
                details.putIfAbsent(fieldError.getField(), fieldError.getDefaultMessage()));
        log.warn("参数校验失败：{}", details);
        return ResponseEntity.status(ErrorCode.INVALID_PARAMETER.httpStatus())
                .body(errorBody(ErrorCode.INVALID_PARAMETER, "请求参数校验失败", details));
    }

    /**
     * 请求体无法解析。
     *
     * <p>典型触发：{@code expireAt} 没有带时区偏移（DR-027 要求必须带），
     * 或 JSON 语法错误。归为 {@link ErrorCode#INVALID_PARAMETER}（格式问题）。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadable(HttpMessageNotReadableException e) {
        log.warn("请求体无法解析：{}", e.getMessage());
        // ⚠️ 不回显 e.getMessage()：里面可能包含 Jackson 的内部类名与字段路径细节
        return ResponseEntity.status(ErrorCode.INVALID_PARAMETER.httpStatus())
                .body(errorBody(ErrorCode.INVALID_PARAMETER,
                        "请求体格式非法（时间必须带时区偏移，如 2026-01-15T10:30:00+08:00）", null));
    }

    /**
     * 发号失败（DR-043）。
     *
     * <p>显式映射到 503 {@code LINKFORGE-503-01}，让基础设施异常不再漏到兜底 handler。
     *
     * <p>⚠️ <b>为什么必须单独映射，而不是让兜底 handler 处理</b>：
     * 兜底 handler 返回 500，语义是「服务有 bug，需要人介入」；
     * 503 的语义是「依赖暂时不可用，调用方可以重试且会自愈」。
     * 号段发号失败通常是数据库暂时不可达——把它报成 500 会误导告警与排查方向
     * （让人去查代码，而实际问题在依赖）。
     *
     * <p>本类之所以能引用它：{@code ShortCodeGenerationException} 定义在 core
     * （纯契约，不依赖 web 栈），框架映射在这里，符合 DR-021 对 core 的零 web 依赖要求。
     */
    @ExceptionHandler(ShortCodeGenerationException.class)
    public ResponseEntity<Map<String, Object>> handleShortCodeGeneration(ShortCodeGenerationException e) {
        ErrorCode code = ErrorCode.DEPENDENCY_UNAVAILABLE;
        log.error("发号失败（{}）：{}", code.code(), e.getMessage(), e);
        return ResponseEntity.status(code.httpStatus())
                .body(errorBody(code, code.defaultMessage(), null));
    }

    /**
     * 路径能匹配上，但 HTTP 方法不被支持（{@code HttpRequestMethodNotSupportedException}）。
     *
     * <h2>⚠️ 这是一个真实的 500 缺陷（DR-064）</h2>
     * 2026-10-27 本地验收实测：
     * <pre>
     *   GET /admin/api/v1/links/0000VF0  （Token 是错的）
     *   → 500 {"code":"LINKFORGE-500-01","message":"internal server error"}
     * </pre>
     *
     * <p>根因有两层：
     * <ol>
     *   <li>该路径只定义了 {@code DELETE}，Spring 在<b>路由解析阶段</b>就抛了这个异常；</li>
     *   <li>异常<b>没有被任何 {@code @ExceptionHandler} 映射</b>，
     *       于是掉进兜底分支 → 500。</li>
     * </ol>
     *
     * <p>⚠️ <b>500 的语义是「服务有 bug，需要人介入」</b>，
     * 而这里只是「客户端用错了 HTTP 方法」——
     * 把它报成 500 会污染错误率指标与告警，让运维去查代码，而问题在调用方。
     * 正确的语义是 <b>405 Method Not Allowed</b>，并带上 {@code Allow} 头告知支持哪些方法。
     *
     * <p>⚠️ 顺带说明：后台路径的<b>鉴权</b>已前移到 Servlet 过滤器（{@code AdminAuthFilter}），
     * 所以「Token 不对」现在一律 401（早于路由解析）；
     * 只有「Token 正确但方法不对」才会走到这里 → 405。
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException e, HttpServletRequest request) {
        String method = e.getMethod();
        if (isNoisePath(request)) {
            // ⚠️ 噪声路径不刷 WARN（DR-064）：浏览器自动请求而已，不是故障
            log.debug("噪声请求方法不被支持：{} {}", method, e.getSupportedHttpMethods());
        } else {
            log.warn("方法不被支持：{} {}", method, e.getSupportedHttpMethods());
        }
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(ErrorCode.METHOD_NOT_ALLOWED.httpStatus());
        // ⚠️ RFC 7231：405 必须带 Allow 头，说明该资源支持哪些方法
        if (e.getSupportedHttpMethods() != null && !e.getSupportedHttpMethods().isEmpty()) {
            // ⚠️ HttpMethod 不是 CharSequence，不能直接 String.join —— 要先取 name()
            builder.header(HttpHeaders.ALLOW, e.getSupportedHttpMethods().stream()
                    .map(HttpMethod::name)
                    .collect(java.util.stream.Collectors.joining(", ")));
        }
        return builder.body(errorBody(ErrorCode.METHOD_NOT_ALLOWED,
                ErrorCode.METHOD_NOT_ALLOWED.defaultMessage(), null));
    }

    /**
     * 未匹配到任何路由（{@code NoResourceFoundException}）。
     *
     * <p>映射到 404 {@code LINKFORGE-404-01}，与短码查询路径的「短码不存在」用同一个错误码——
     * 调用方看到 404 就知道「这个地址没有可跳转的东西」，不需要区分是路径写错还是短码不存在。
     *
     * <p>⚠️ <b>为什么必须显式映射</b>：Spring 6 起用 {@code NoResourceFoundException}
     * 替代已废弃的 {@code NoHandlerFoundException}，它由静态资源处理器抛出，
     * <b>不会</b>被 {@code @ExceptionHandler(Exception.class)} 之外的任何默认机制接住，
     * 于是掉进兜底 handler 变成 500。500 的语义是「服务有 bug」——
     * 而用户只是敲错了一个地址，把服务标成故障会污染告警与可用性指标。
     *
     * <p>⚠️ <b>为什么记 404 而不是降级为「不处理」</b>：不处理的话 Spring 会走
     * {@code /error} 分发再进兜底，结果一样是 500 + 一次多余的转发。
     *
     * <p>D6 实测教训：修复前该缺陷在压测间隙的 2 分钟内产生了 122042 条 ERROR 级日志
     * （每次请求 50 行堆栈），把错误日志彻底淹掉。404 属于正常业务语义，记 WARN 不记栈。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNoResource(NoResourceFoundException e) {
        log.warn("未匹配到路由：{}", e.getResourcePath());
        return ResponseEntity.status(ErrorCode.LINK_NOT_FOUND.httpStatus())
                .body(errorBody(ErrorCode.LINK_NOT_FOUND, ErrorCode.LINK_NOT_FOUND.defaultMessage(), null));
    }

    /** 兜底：未预期异常一律 500，**不泄露内部细节**。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception e) {
        log.error("未预期异常", e);
        // 只返回通用文案：异常 message 可能含内部类名、SQL 片段、字段路径
        return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.httpStatus())
                .body(errorBody(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.defaultMessage(), null));
    }

    /**
     * 错误体构造（委托给 core 的 {@link ErrorBody}）。
     *
     * <p>⚠️ **为什么下沉到 core**（DR-064）：
     * 后台鉴权过滤器 {@code AdminAuthFilter} 在 Servlet 过滤链上，
     * <b>不在</b> {@code @RestControllerAdvice} 的处理范围内，
     * 必须自己写 401 响应体。若两处各写一份，格式迟早漂移。
     * core 里的 {@code ErrorBody} 是<b>唯一</b>的错误体构造处，
     * 且只用 JDK 类型，不给 core 引入 web 依赖（DR-021）。
     */
    private Map<String, Object> errorBody(ErrorCode code, String message, Map<String, Object> details) {
        // ⚠️ details 的「为空则不出现该字段」规则也下沉到 ErrorBody —— 两处各判一次会漂移
        return ErrorBody.of(code, message, details, OffsetDateTime.now(displayZone));
    }

    /** 供 429 使用：极简 body（只含 code/message/retryAfter）+ {@code Retry-After} 头（DR-013）。 */
    public static ResponseEntity<Map<String, Object>> rateLimited(int retryAfterSeconds, ZoneId zone) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.RETRY_AFTER, Integer.toString(retryAfterSeconds));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", ErrorCode.RATE_LIMITED.code());
        body.put("message", ErrorCode.RATE_LIMITED.defaultMessage());
        body.put("retryAfter", retryAfterSeconds);
        return new ResponseEntity<>(body, headers, HttpStatus.TOO_MANY_REQUESTS);
    }
}
