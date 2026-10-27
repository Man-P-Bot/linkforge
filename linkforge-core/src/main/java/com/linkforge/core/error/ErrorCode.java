package com.linkforge.core.error;

/**
 * 全项目错误码（docs/API.md §8，规则见 docs/DECISIONS.md DR-024）。
 *
 * <h2>格式</h2>
 * {@code LINKFORGE-<HTTP状态码(3位)>-<两位序号>}，例如 {@code LINKFORGE-400-02}。
 * 中段**就是**该响应的 HTTP 状态——由本枚举同时持有 {@link #code()} 与 {@link #httpStatus()} 保证，
 * 不允许从字符串反解（那样是脆弱的隐式约定）。
 *
 * <h2>⚠️ 本类刻意不 import {@code org.springframework.http.HttpStatus}</h2>
 * 状态码存 {@code int}，使 {@code linkforge-core} **保持零 web 依赖**（DR-021）：
 * core 里的持久化与契约不应该把 Spring Web 拖进来，否则 admin 也会被迫依赖 web 栈。
 * 映射到框架类型是 {@code GlobalExceptionHandler}（在 redirect 模块）的职责。
 *
 * <h2>400 段为什么拆成 5 个码</h2>
 * 判据是「**调用方需要采取的修复动作不同就拆**」：
 * <ul>
 *   <li>{@link #INVALID_PARAMETER}：纯用法错误（缺失/超长/格式），改字符串即可；</li>
 *   <li>{@link #UNSUPPORTED_PROTOCOL}：**安全边界**。若与「字段超长」共码，
 *       运维无法从错误码分布区分「有人在做开放重定向探测」与「调用方传了超长 URL」——
 *       前者需立刻告警。把安全事件混进用法错误等于放弃该信号；</li>
 *   <li>{@link #INVALID_CUSTOM_CODE_FORMAT}：写错字符集；</li>
 *   <li>{@link #RESERVED_CUSTOM_CODE}：调用方在**猜系统路径**（{@code /api}、{@code /admin}），
 *       与字符集错误是不同行为；</li>
 *   <li>{@link #EXPIRE_AT_NOT_IN_FUTURE}：需要**改时间点**（或对齐时钟），而非改格式。</li>
 * </ul>
 *
 * <p>本枚举**一次性定义完整**（含 D5 与 admin 才用到的码）：错误码是已定稿的对外契约，
 * 分批追加容易与文档预期不符。未使用的码不会产生任何运行时开销。
 */
public enum ErrorCode {

    /** 通用参数错误：缺失、超长（>2048）、时间格式非法（含不带时区）、日期范围非法、短码路径字符非法。 */
    INVALID_PARAMETER("LINKFORGE-400-01", 400, "invalid parameter"),

    /**
     * 协议不允许：目标地址的 scheme 不在 http/https 白名单内。
     * <p>**安全边界，必须独立成码**（见类注释）。
     */
    UNSUPPORTED_PROTOCOL("LINKFORGE-400-02", 400, "target url scheme is not allowed"),

    /** 自定义短码格式非法（不匹配 {@code ^[a-zA-Z0-9]{4,8}$}）。 */
    INVALID_CUSTOM_CODE_FORMAT("LINKFORGE-400-03", 400, "custom code format is invalid"),

    /** 自定义短码命中保留字黑名单（**大小写不敏感**，DR-025）。 */
    RESERVED_CUSTOM_CODE("LINKFORGE-400-04", 400, "custom code is reserved"),

    /** {@code expireAt} 不是将来时间（含恰好等于当前时间）。 */
    EXPIRE_AT_NOT_IN_FUTURE("LINKFORGE-400-05", 400, "expireAt must be in the future"),

    /** 后台接口未鉴权或 Token 无效（D3/第 4 周使用）。 */
    UNAUTHORIZED("LINKFORGE-401-01", 401, "unauthorized"),

    /** 短码不存在：从未创建 / 已删除（status = DELETED）/ 布隆判不存在（D5 使用）。 */
    LINK_NOT_FOUND("LINKFORGE-404-01", 404, "short code not found"),
    METHOD_NOT_ALLOWED("LINKFORGE-405-01", 405, "method not allowed"),

    /** 自定义短码已被占用（**不静默覆盖**）。 */
    SHORT_CODE_CONFLICT("LINKFORGE-409-01", 409, "short code already taken"),

    /** 短码已过期（D5 使用）。 */
    LINK_EXPIRED("LINKFORGE-410-01", 410, "short code expired"),

    /** 触发限流（第 3 周使用）。响应必须带 {@code Retry-After} 且 body 为极简体。 */
    RATE_LIMITED("LINKFORGE-429-01", 429, "rate limit exceeded"),

    /** 依赖不可用：MySQL 不可用且缓存未命中；或发号器（Redis 与数据库号段）均不可用。 */
    DEPENDENCY_UNAVAILABLE("LINKFORGE-503-01", 503, "dependency unavailable"),

    /**
     * 未预期的服务端错误（兜底）。
     *
     * <p>⚠️ 该码**不回显任何内部细节**（不返回异常 message、不返回栈），
     * 这不是「不可用」而是「代码 bug」，因此用 500 段而不是复用 503。
     * 它的存在是为了让「兜底路径」也走统一错误体，而不是手写一个游离的字符串。
     */
    INTERNAL_ERROR("LINKFORGE-500-01", 500, "internal server error");

    /** 错误码前缀。抽成常量避免各处手写拼错。 */
    public static final String PREFIX = "LINKFORGE-";

    private final String code;
    private final int httpStatus;
    private final String defaultMessage;

    ErrorCode(String code, int httpStatus, String defaultMessage) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
    }

    /** 对外错误码，如 {@code LINKFORGE-400-02}。 */
    public String code() {
        return code;
    }

    /** 该错误码**必须**配套的 HTTP 状态码（int，避免 core 依赖 Spring Web）。 */
    public int httpStatus() {
        return httpStatus;
    }

    /** 面向调用方的英文短句；不含内部类名、SQL、栈信息。 */
    public String defaultMessage() {
        return defaultMessage;
    }

    @Override
    public String toString() {
        return code;
    }
}
