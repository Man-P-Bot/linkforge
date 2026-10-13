package com.linkforge.redirect.error;

import com.linkforge.core.error.ErrorCode;
import com.linkforge.core.error.LinkForgeException;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

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

    private final ZoneId displayZone;

    public GlobalExceptionHandler(ZoneId displayZone) {
        this.displayZone = displayZone;
    }

    /** 业务异常：状态码取错误码自带的 httpStatus。 */
    @ExceptionHandler(LinkForgeException.class)
    public ResponseEntity<Map<String, Object>> handleLinkForge(LinkForgeException e) {
        ErrorCode code = e.errorCode();
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

    /** 兜底：未预期异常一律 500，**不泄露内部细节**。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception e) {
        log.error("未预期异常", e);
        // 只返回通用文案：异常 message 可能含内部类名、SQL 片段、字段路径
        return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.httpStatus())
                .body(errorBody(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.defaultMessage(), null));
    }

    private Map<String, Object> errorBody(ErrorCode code, String message, Map<String, Object> details) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code.code());
        body.put("message", message == null ? code.defaultMessage() : message);
        body.put("timestamp", OffsetDateTime.now(displayZone).toString());
        if (details != null && !details.isEmpty()) {
            body.put("details", details);
        }
        return body;
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
