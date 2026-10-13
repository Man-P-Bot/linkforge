package com.linkforge.core.domain;

import com.linkforge.core.error.ErrorCode;
import com.linkforge.core.error.LinkForgeException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * 对外基地址 {@code SHORTLINK_BASE_URL} 的值对象（docs/DECISIONS.md DR-014 / DR-026）。
 *
 * <h2>为什么需要这个类，而不直接注入 String</h2>
 * 直接用 String 意味着**每个使用点都要自己拼接**，而拼接方式一旦不一致就会出现
 * {@code http://x:8080//abc1234}（双斜杠）这类对外可见的低级错误。
 * 把「校验 + 归一化 + 拼接」收进一个值对象，非法配置**在构造时就失败**。
 *
 * <h2>启动即失败（DR-026）</h2>
 * 以下情况一律抛 {@link LinkForgeException}，**不允许带病启动**：
 * <ul>
 *   <li>null / 空字符串 / 全空白；</li>
 *   <li><b>纯斜杠</b>（{@code /}、{@code //}）——看起来「有值」，不会被必填校验拦住，
 *       但无法推断 scheme 与 host，拼出来的短链对外不可用；</li>
 *   <li><b>无 scheme</b>（{@code localhost:8080}、{@code example.com}）——本地开发时很自然
 *       （人习惯了写 {@code localhost:8080}），但拼出的 URL 非法；</li>
 *   <li>scheme 不是 {@code http}/{@code https}（大小写不敏感）；</li>
 *   <li>有 scheme 但缺 host。</li>
 * </ul>
 *
 * <h2>末尾斜杠归一化</h2>
 * {@code http://localhost:8080/} 与 {@code http://localhost:8080} 必须产出**同一个**结果；
 * 末尾多写几个斜杠也一样，拼接结果中**不得出现 {@code //}**。
 */
public final class ShortLinkBaseUrl {

    private final String normalized;

    private ShortLinkBaseUrl(String normalized) {
        this.normalized = normalized;
    }

    /**
     * 解析并校验配置值。
     *
     * @param raw 配置原始值（来自环境变量）
     * @throws LinkForgeException 配置非法，携带 {@link ErrorCode#DEPENDENCY_UNAVAILABLE} 之外
     *         的语义——这里用 {@link ErrorCode#INVALID_PARAMETER} 不合适（那是请求侧错误），
     *         因此复用 {@link ErrorCode#DEPENDENCY_UNAVAILABLE} 的
     *         「配置不可用」含义由调用方在启动期处理；详见 {@link #parse} 的异常说明。
     */
    public static ShortLinkBaseUrl parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw invalid("SHORTLINK_BASE_URL 不能为空（缺失或全空白）");
        }
        String trimmed = raw.trim();

        // 纯斜杠：看起来「有值」，但推不出 scheme/host
        if (trimmed.chars().allMatch(c -> c == '/' || c == '\\')) {
            throw invalid("SHORTLINK_BASE_URL 不能是纯斜杠: \"" + trimmed + "\"");
        }

        URI uri;
        try {
            uri = new URI(trimmed);
        } catch (URISyntaxException e) {
            throw invalid("SHORTLINK_BASE_URL 不是合法 URI: \"" + trimmed + "\"（" + e.getReason() + "）");
        }

        String scheme = uri.getScheme();
        if (scheme == null || scheme.isBlank()) {
            throw invalid("SHORTLINK_BASE_URL 必须带 scheme（如 http://localhost:8080）: \"" + trimmed + "\"");
        }
        String lowerScheme = scheme.toLowerCase(Locale.ROOT);
        if (!"http".equals(lowerScheme) && !"https".equals(lowerScheme)) {
            throw invalid("SHORTLINK_BASE_URL 的 scheme 只能是 http 或 https: \"" + scheme + "\"");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw invalid("SHORTLINK_BASE_URL 缺少 host: \"" + trimmed + "\"");
        }

        // 归一化：去掉末尾所有斜杠（只保留 scheme://host[:port] 形式）
        String withoutTrailingSlashes = trimmed;
        while (withoutTrailingSlashes.endsWith("/") || withoutTrailingSlashes.endsWith("\\")) {
            withoutTrailingSlashes = withoutTrailingSlashes.substring(0, withoutTrailingSlashes.length() - 1);
        }
        if (withoutTrailingSlashes.isEmpty()) {
            throw invalid("SHORTLINK_BASE_URL 归一化后为空: \"" + trimmed + "\"");
        }
        return new ShortLinkBaseUrl(withoutTrailingSlashes);
    }

    /** 归一化后的基地址（**不含**末尾斜杠）。 */
    public String value() {
        return normalized;
    }

    /**
     * 拼接短链地址：{@code <base>/<shortCode>}。
     *
     * <p>保证结果中不出现 {@code //}（除 scheme 后的那一个）。
     */
    public String toShortUrl(String shortCode) {
        if (shortCode == null || shortCode.isBlank()) {
            throw new IllegalArgumentException("shortCode 不能为空");
        }
        // base 已在 parse 时去掉末尾斜杠，code 由发号器保证不含斜杠，因此直接拼即可
        return normalized + "/" + shortCode;
    }

    @Override
    public String toString() {
        return normalized;
    }

    /**
     * 配置错误。
     *
     * <p>⚠️ 这里用 {@link ErrorCode#DEPENDENCY_UNAVAILABLE} 是**刻意的妥协**：
     * 现有错误码表（API.md §8）描述的是**请求侧**错误，而「应用自身配置非法」既不属于 4xx
     * 也不该伪装成 503。真正正确的做法是**启动期直接抛异常终止启动**，由 `main` 打印清晰原因，
     * 而不是让它变成某个 HTTP 响应。因此本异常**只在启动装配阶段传播**，
     * 不应出现在请求处理路径上（见 D4 设计的启动校验约定）。
     */
    private static LinkForgeException invalid(String message) {
        return new LinkForgeException(ErrorCode.DEPENDENCY_UNAVAILABLE, message);
    }
}
