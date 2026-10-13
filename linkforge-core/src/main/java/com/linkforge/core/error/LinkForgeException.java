package com.linkforge.core.error;

/**
 * 携带 {@link ErrorCode} 的业务异常。
 *
 * <p>由 {@code GlobalExceptionHandler}（redirect 模块）统一翻译成 HTTP 响应：
 * 状态码取 {@link ErrorCode#httpStatus()}，body 为统一错误体。
 * 因此**抛异常的地方不需要知道 HTTP**——这是 core 保持零 web 依赖的前提。
 *
 * <p>刻意**不覆盖** {@code fillInStackTrace} 之外的栈信息处理：业务异常在启动期与请求期都可能出现，
 * 保留完整栈便于排查配置错误（如 BASE_URL 非法）。
 */
public class LinkForgeException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ErrorCode errorCode;

    public LinkForgeException(ErrorCode errorCode) {
        super(errorCode.defaultMessage());
        this.errorCode = errorCode;
    }

    public LinkForgeException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public LinkForgeException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
