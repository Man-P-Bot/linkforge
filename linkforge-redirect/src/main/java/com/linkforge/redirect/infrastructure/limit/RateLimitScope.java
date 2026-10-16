package com.linkforge.redirect.infrastructure.limit;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 限流作用域的运行时配置（docs/ARCHITECTURE.md §96、§211）。
 *
 * <h2>⚠️ 完整 scope 命名规范（第 4 周扩充）</h2>
 * <pre>
 *   shortlink:limit:{ip}:{scope}
 *   scope ∈ { redirect, create, query, admin }
 * </pre>
 *
 * <p>⚠️ <b>不同 scope 必须用不同 key，不能共用</b>。
 * 跳转是 20次/秒，创建是 10 次/分钟——两者量级差 **120 倍**。
 * 共用一个 key 会让创建接口的配额被跳转流量吃光，
 * 表现为「跳转一压，创建全挂」。
 *
 * <table border="1">
 *   <caption>scope 一览</caption>
 *   <tr><th>scope</th><th>阈值</th><th>实现周次</th></tr>
 *   <tr><td>{@code redirect}</td><td>20 次/秒</td><td>第 3 周</td></tr>
 *   <tr><td>{@code create}</td><td>10 次/分钟</td><td>第 3 周</td></tr>
 *   <tr><td>{@code query}</td><td>60 次/秒</td><td>第 4 周（查询接口）</td></tr>
 *   <tr><td>{@code admin}</td><td>30 次/分钟</td><td>第 4 周（后台接口）</td></tr>
 * </table>
 *
 * <p>⚠️ <b>第 4 周新增 scope 时必须复用本类</b>，不要在调用点裸拼字符串——
 * 裸拼出来的 scope 会与本枚举不一致，导致限流悄悄失效（限流失效是静默故障，
 * 没有任何错误信号）。
 *
 * <h2>⚠️ 为什么阈值来自配置而不是硬编码</h2>
 * 压测需要临时放宽阈值（8 万 QPS 的压测流量必然触发 20/s 的限流），
 * 而「限流阈值是 20」这个约定写在 {@code .env} 与 {@code docker-compose.yml} 里。
 * 硬编码会让「压测时改阈值」变成「改代码 + 重新构建镜像 + 重新验证」，
 * 而且压完还得记得改回来——极容易漏。
 */
@Component
public class RateLimitScope {

    private final int redirectPerSec;
    private final int createPerMin;
    private final int queryPerSec;
    private final int adminPerMin;

    public RateLimitScope(
            @Value("${RATE_LIMIT_REDIRECT_PER_SEC:20}") int redirectPerSec,
            @Value("${RATE_LIMIT_CREATE_PER_MIN:10}") int createPerMin,
            @Value("${RATE_LIMIT_QUERY_PER_SEC:60}") int queryPerSec,
            @Value("${RATE_LIMIT_ADMIN_PER_MIN:30}") int adminPerMin) {
        this.redirectPerSec = redirectPerSec;
        this.createPerMin = createPerMin;
        this.queryPerSec = queryPerSec;
        this.adminPerMin = adminPerMin;
    }

    // ---- scope 常量 ----
    // ⚠️ 用常量而非在调用点裸拼字符串：裸拼的 scope 一旦拼错（如 "Redirect"）
    // 会在 rate() 里抛「未知限流scope」——限流直接失效且只有 WARN 日志，极难排查。

    /** 跳转路径 scope。 */
    public static final String REDIRECT = "redirect";
    /** 创建短链 scope。 */
    public static final String CREATE = "create";
    /** 查询接口 scope（第 4 周）。 */
    public static final String QUERY = "query";
    /** 后台接口 scope（第 4 周）。 */
    public static final String ADMIN = "admin";

    /** 供测试与文档引用的默认阈值。 */
    public static final int DEFAULT_REDIRECT_PER_SEC = 20;
    public static final int DEFAULT_CREATE_PER_MIN = 10;
    public static final int DEFAULT_QUERY_PER_SEC = 60;
    public static final int DEFAULT_ADMIN_PER_MIN = 30;

    /**
     * 桶容量（窗口内的最大请求数）。
     *
     * @param scope 作用域名，见类注释
     */
    public int rate(String scope) {
        return switch (scope) {
            case REDIRECT -> redirectPerSec;
            case CREATE -> createPerMin;
            case QUERY -> queryPerSec;
            case ADMIN -> adminPerMin;
            default -> throw new IllegalArgumentException(
                    "未知限流scope: " + scope + "（第 4 周新增 scope 时必须先在这里登记）");
        };
    }

    /**
     * 补充令牌的窗口。
     *
     * <p>⚠️ <b>各 scope 的窗口不同</b>：redirect 与 query 是秒级（高 QPS 主路径），
     * create 与 admin 是分钟级（写路径，阈值低）。窗口与阈值必须成对配置，
     * 否则「10 次/秒」与「10 次/分钟」只差一个词，含义完全不同。
     */
    public Duration window(String scope) {
        return switch (scope) {
            case REDIRECT, QUERY -> Duration.ofSeconds(1);
            case CREATE, ADMIN -> Duration.ofMinutes(1);
            default -> throw new IllegalArgumentException("未知限流scope: " + scope);
        };
    }

    /**
     * 每毫秒补充的令牌数（Lua 脚本用）。
     *
     * <p>⚠️ <b>必须用浮点算，不用整数除法</b>：{@code rate / window.toMillis()} 在
     * 20/1000 时得到 0.02，若走整数路径得到 0，会导致<b>永远不补令牌</b>，
     * 限流从「每秒 20」退化成「桶空后永久拒绝」。
     */
    public double refillPerMillisecond(String scope) {
        return (double) rate(scope) / window(scope).toMillis();
    }

    /**
     * 限流 key。⚠️ IP 里的冒号需替换——{@code ::1} 会与分隔符冲突。
     */
    public String key(String clientId, String scope) {
        return "shortlink:limit:" + clientId.replace(':', '_') + ":" + scope;
    }

    /** 已登记的 scope 全集。 */
    public static java.util.List<String> allScopes() {
        return java.util.List.of(REDIRECT, CREATE, QUERY, ADMIN);
    }
}