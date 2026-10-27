package com.linkforge.core.error;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 错误响应体的**唯一构造处**（DR-064）。
 *
 * <h2>⚠️ 为什么把它从 GlobalExceptionHandler 里抽出来</h2>
 * 后台鉴权被前移到 {@code AdminAuthFilter}（Servlet 过滤器）之后，
 * 它**不在 Spring MVC 的异常处理链上** ——
 * {@code @RestControllerAdvice} 只处理「DispatcherServlet 已经解析出 handler 之后」抛出的异常，
 * 过滤器里抛的异常根本走不到那里。
 *
 * <p>于是过滤器必须**自己写出** 401 响应体。如果它在本地另写一份 JSON 结构，
 * 就会出现两个「错误体格式」的定义 —— 迟早漂移成「有些接口的错误体少一个 timestamp」。
 * 所以把构造逻辑下沉到 core：
 * <ul>
 *   <li>{@code GlobalExceptionHandler}（redirect 模块）用它；</li>
 *   <li>{@code AdminAuthFilter}（admin 模块）也用它。</li>
 * </ul>
 *
 * <p>⚠️ <b>core 保持零 web 依赖</b>（DR-021）：本类只用 {@link Map} 与
 * {@link OffsetDateTime}，都是 JDK 类型，不引入任何 web 栈。
 *
 * <h2>字段顺序</h2>
 * 用 {@link LinkedHashMap} 而非 {@code Map.of} —— 后者的迭代顺序**不保证**，
 * 会让错误体的字段顺序随机变化，快照测试与人工比对都会变得难受。
 */
public final class ErrorBody {

    private ErrorBody() {
    }

    /**
     * 构造错误响应体。
     *
     * @param code      错误码（状态码的唯一来源，见 {@link ErrorCode#httpStatus()}）
     * @param message   面向调用方的文案；为 null 时用 {@link ErrorCode#defaultMessage()}
     * @param details   补充细节；null 或空 map 时**不出现**该字段
     * @param timestamp 时间戳（渲染时区由调用方决定 —— core 不关心展示时区）
     * @return 可直接序列化的响应体
     */
    public static Map<String, Object> of(ErrorCode code,
                                         String message,
                                         Map<String, Object> details,
                                         OffsetDateTime timestamp) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code.code());
        body.put("message", message == null ? code.defaultMessage() : message);
        body.put("timestamp", timestamp.toString());
        if (details != null && !details.isEmpty()) {
            body.put("details", details);
        }
        return body;
    }
}
