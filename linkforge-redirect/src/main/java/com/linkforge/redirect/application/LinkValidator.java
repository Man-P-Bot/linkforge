package com.linkforge.redirect.application;

import com.linkforge.core.domain.IdSpace;
import com.linkforge.core.domain.ReservedCodes;
import com.linkforge.core.domain.ShortCodeFormat;
import com.linkforge.core.error.ErrorCode;
import com.linkforge.core.error.LinkForgeException;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 创建短链的业务规则校验（docs/API.md §5.1/§5.2）。
 *
 * <h2>为什么这些规则不用注解，而要手写</h2>
 * 字段级**格式**约束（非空、长度）用注解就够了；但下列三条是**业务规则**：
 * <ol>
 *   <li><b>协议白名单</b>：需要解析 URI 并覆盖畸形输入（无 scheme、{@code javascript:}、
 *       大小写混合 {@code HTTP://}），注解表达式写不下；且这是**安全边界**，
 *       要求能被单独测试与单独观测（错误码 {@code 400-02}）；</li>
 *   <li><b>保留字黑名单</b>：来自**配置**（{@code RESERVED_CODES}），注解读不到配置；</li>
 *   <li><b>{@code expireAt} 必须是将来</b>：依赖「当前时间」，而时间必须**可注入**
 *       （PRD §7.3），否则该规则无法写单测。因此本类注入 {@link Clock}。</li>
 * </ol>
 *
 * <p>本类**不依赖 Spring Web**：不做 HTTP 映射，只抛 {@link LinkForgeException}。
 */
@Component
public class LinkValidator {

    /** 自定义短码：4~8 位 Base62（比自动生成的固定 7 位更宽松，便于用户取有意义的名字）。 */
    private static final Pattern CUSTOM_CODE_PATTERN = Pattern.compile("^[a-zA-Z0-9]{4,8}$");

    /** 目标地址长度上界，与数据库列 {@code VARCHAR(2048)} 一致（DATABASE §4.1）。 */
    static final int TARGET_URL_MAX_LENGTH = 2048;

    private final ReservedCodes reservedCodes;
    private final Clock clock;

    public LinkValidator(ReservedCodes reservedCodes, Clock clock) {
        this.reservedCodes = reservedCodes;
        this.clock = clock;
    }

    // ---------------------------------------------------------------------
    // targetUrl
    // ---------------------------------------------------------------------

    /**
     * 校验目标地址。
     *
     * @throws LinkForgeException {@link ErrorCode#INVALID_PARAMETER} 为空/超长/含控制字符/畸形；
     *                            {@link ErrorCode#UNSUPPORTED_PROTOCOL} scheme 不在白名单
     */
    public void validateTargetUrl(String targetUrl) {
        if (targetUrl == null || targetUrl.isBlank()) {
            throw new LinkForgeException(ErrorCode.INVALID_PARAMETER, "targetUrl 不能为空");
        }
        if (targetUrl.length() > TARGET_URL_MAX_LENGTH) {
            // 明确**拒绝**而不是截断：截断会产生指向错误地址的短链，比拒绝危险得多（DATABASE §4.1）
            throw new LinkForgeException(ErrorCode.INVALID_PARAMETER,
                    "targetUrl 长度 " + targetUrl.length() + " 超过上限 " + TARGET_URL_MAX_LENGTH);
        }

        // ⚠️ 控制字符必须在 URI 解析**之前**显式拒绝（DR-050）。
        // 见下方 findControlChar 的说明：URI 解析虽然也会拒，但那是依赖 JDK 实现的
        // 偶然行为，不是我们自己的契约。显式拒绝才是契约。
        char controlChar = findControlChar(targetUrl);
        if (controlChar != 0) {
            throw new LinkForgeException(ErrorCode.INVALID_PARAMETER,
                    "targetUrl 不能包含控制字符（U+0000~U+001F）：发现 U+"
                            + String.format("%04X", (int) controlChar));
        }

        // ⚠️ 先做「有没有 http/https 前缀」这个纯字符串判断，**再做 URI 解析**。
        // 原因：像 `//example.com` 这种输入会让 URI 解析直接抛 URISyntaxException，
        // 若让异常分支来处理，它会被归为 400-01「格式错误」，
        // 而事实是**协议缺失**（安全语义，应为 400-02）。
        // 从安全视角看，「没有白名单 scheme」与「scheme 不在白名单」是同一类问题。
        String lower = targetUrl.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            throw new LinkForgeException(ErrorCode.UNSUPPORTED_PROTOCOL,
                    "targetUrl 必须以 http:// 或 https:// 开头");
        }

        URI uri;
        try {
            uri = new URI(targetUrl);
        } catch (URISyntaxException e) {
            throw new LinkForgeException(ErrorCode.INVALID_PARAMETER, "targetUrl 不是合法 URI");
        }

        String scheme = uri.getScheme();
        if (scheme == null || scheme.isBlank()) {
            throw new LinkForgeException(ErrorCode.UNSUPPORTED_PROTOCOL,
                    "targetUrl 缺少 scheme，仅允许 http/https");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new LinkForgeException(ErrorCode.INVALID_PARAMETER, "targetUrl 缺少 host");
        }
    }

    /**
     * 查找第一个 C0 控制字符（U+0000~U+001F）。
     *
     * <h2>⚠️ 为什么必须显式检查，而不是依赖 {@link URI} 解析</h2>
     * 第 2 周实现缓存序列化时发现一个缺陷：{@code targetUrl} 用 {@code \n} 分隔存储会串位。
     * 追查后发现更根本的问题——<b>校验器从未显式排除控制字符</b>。
     *
     * <p>⚠️ <b>实测：当前并没有真的漏过去</b>。JDK 17 的 {@code java.net.URI}
     * 对 {@code \n \r \t \0} 及其它 C0 控制字符一律抛 {@code URISyntaxException}
     * （已实测验证）。所以这次修复<b>不是堵安全漏洞</b>，而是把一件
     * 「碰巧被第三方实现兜住的事」变成「我们自己的契约」。
     *
     * <p>这在三个场景下会失效：
     * <ol>
     *   <li><b>JDK 行为变化</b>：不同 JDK 版本、不同 URI 实现的严格程度不同，
     *       依赖它等于把正确性押在第三方实现细节上；</li>
     *   <li><b>绕过 URI 解析的代码路径</b>：当前顺序是「先协议白名单（纯字符串
     *       {@code startsWith}）→ 再 URI 解析」，而缓存序列化、统计落库等路径
     *       可能不经 URI 解析；</li>
     *   <li><b>错误码不对</b>：靠 URISyntaxException 兜住时，消息是
     *       「targetUrl 不是合法 URI」，调用方无法区分「有个控制字符」
     *       与「URI 结构写错了」。</li>
     * </ol>
     *
     * <p>⚠️ <b>不拦截百分号编码形式</b>：{@code %0A} 是<b>合法</b>的 URL，
     * 符合 RFC 3986 的 percent-encoding 规则。它在 HTTP 传输层是安全的
     * （不会真的产生 header 注入），<b>刻意放行</b>——
     * 过度拦截合法 URL 比放过一个编码形式更有害。回归测试
     * {@code encodedFormsAreAllowed} 锁住这个决定。
     *
     * <p>⚠️ <b>不拦截 U+007F（DEL）</b>：它不在 C0 段（U+0000~U+001F）内。
     * 实际上 {@code java.net.URI} 也会拒它，但那是 URI 解析的行为，
     * 与本检查无关。若将来要扩展，改的是这里的边界。
     *
     * @return 第一个控制字符；没有则返回 {@code 0}（{@code 0} 本身是合法控制字符，
     *         所以用 {@code 0} 表示「没找到」是安全的：真找到 {@code \u0000} 时
     *         调用方也会因消息含控制字符而立即抛异常，不会继续往下走）
     */
    private static char findControlChar(String targetUrl) {
        for (int i = 0; i < targetUrl.length(); i++) {
            char c = targetUrl.charAt(i);
            if (c <= 0x1F) {
                return c;
            }
        }
        return 0;
    }

    // ---------------------------------------------------------------------
    // customCode
    // ---------------------------------------------------------------------

    /**
     * 判断候选短码是否为保留字（**大小写不敏感**）。
     *
     * <p>⚠️ 本方法**只回答「是不是保留字」**，不回答「是否合法」——格式合法性由
     * {@link #validateCustomCodeFormat(String)} 负责。
     *
     * <p><b>为什么必须拆成两个调用点</b>：黑名单里有 {@code favicon.ico}（含点），
     * 它**不匹配** {@code ^[a-zA-Z0-9]{4,8}$}。若把两个检查塞进同一个
     * {@link #validateCustomCode(String)} 里、且格式检查在前，那么
     * {@code API}、{@code STATIC} 这类**保留字**会因为长度不足而先被判为「格式非法」，
     * 返回 {@code 400-03} 而不是 {@code 400-04} —— **错误码与事实不符**，
     * 且掩盖了「调用方在猜系统路径」这个更重要的信号（DR-024 拆分错误码的初衷）。
     * 因此 Service 必须**先查保留字，再查格式**，两个检查各自独立可测。
     */
    public boolean isReserved(String customCode) {
        return customCode != null && reservedCodes.contains(customCode);
    }

    /**
     * 只校验自定义短码的**格式**（{@code ^[a-zA-Z0-9]{4,8}$}）。
     *
     * @throws LinkForgeException {@link ErrorCode#INVALID_CUSTOM_CODE_FORMAT}
     */
    public void validateCustomCodeFormat(String customCode) {
        if (customCode == null || customCode.isBlank()) {
            return;
        }
        if (!CUSTOM_CODE_PATTERN.matcher(customCode).matches()) {
            throw new LinkForgeException(ErrorCode.INVALID_CUSTOM_CODE_FORMAT,
                    "customCode 必须匹配 " + CUSTOM_CODE_PATTERN.pattern());
        }
    }

    /**
     * 只校验自定义短码是否为**保留字**（大小写不敏感，DR-025）。
     *
     * @throws LinkForgeException {@link ErrorCode#RESERVED_CUSTOM_CODE}
     */
    public void validateCustomCodeNotReserved(String customCode) {
        if (customCode == null || customCode.isBlank()) {
            return;
        }
        if (isReserved(customCode)) {
            throw new LinkForgeException(ErrorCode.RESERVED_CUSTOM_CODE,
                    "customCode 是保留字: " + customCode);
        }
    }

    /**
     * 校验自定义短码：**保留字优先于格式**。
     *
     * <p>顺序理由见 {@link #isReserved(String)}：保留字命中是更强的信号
     * （调用方在探测系统路径），且黑名单含 {@code favicon.ico} 这类必然不满足格式的值。
     *
     * @throws LinkForgeException {@link ErrorCode#RESERVED_CUSTOM_CODE} 或
     *                            {@link ErrorCode#INVALID_CUSTOM_CODE_FORMAT}
     */
    public void validateCustomCode(String customCode) {
        validateCustomCodeNotReserved(customCode);
        validateCustomCodeFormat(customCode);
    }

    // ---------------------------------------------------------------------
    // 跳转路径参数 {code}
    // ---------------------------------------------------------------------

    /**
     * 校验跳转接口的路径参数 {@code {code}}（docs/API.md §4.1）。
     *
     * <h2>⚠️ 判定顺序不可调换</h2>
     * <pre>
     *   空 / 尾斜杠        → 400-01（请求格式错误）
     *   含非 Base62 字符   → 400-01（请求格式错误）
     *   长度不是 7 位      → 404-01（一定查不到，不是「参数非法」）
     * </pre>
     * 字符检查必须在长度检查<b>之前</b>：{@code abc_} 是 4 位，若先判长度会返回 404，
     * 调用方会以为自己只是「没这条链接」，从而反复重试同一个拼接 bug 的请求；
     * 返回 400 才能立刻暴露「你用的字符集不对」。
     *
     * <p>尾斜杠也归入 400 而<b>不</b>重定向纠正：自动纠正会让一个短码对应多个可访问 URL，
     * 破坏一一对应关系，也让缓存与统计出现双份记录（API.md §4.1）。
     *
     * <p>⚠️ <b>自定义短码（4~8 位）在这里会被判为 404</b>，这是契约的既定行为而非遗漏：
     * 跳转接口按默认短码长度定位，自定义短码是否可跳转属于待确认项，
     * 在契约改写之前不擅自放宽。
     *
     * @throws LinkForgeException {@link ErrorCode#INVALID_PARAMETER} 字符非法或带尾斜杠；
     *                            {@link ErrorCode#LINK_NOT_FOUND} 长度不符
     */
    public void validateForRedirect(String rawCode) {
        if (ShortCodeFormat.isBlank(rawCode)) {
            throw new LinkForgeException(ErrorCode.INVALID_PARAMETER, "短码不能为空");
        }
        if (ShortCodeFormat.hasTrailingSlash(rawCode)) {
            throw new LinkForgeException(ErrorCode.INVALID_PARAMETER,
                    "短码路径不允许尾斜杠: \"" + rawCode + "\"");
        }
        if (!ShortCodeFormat.hasValidChars(rawCode)) {
            throw new LinkForgeException(ErrorCode.INVALID_PARAMETER,
                    "短码只能包含 Base62 字符（0-9A-Za-z）: \"" + rawCode + "\"");
        }
        if (!ShortCodeFormat.hasValidLength(rawCode)) {
            // 长度不符不构成「参数非法」——7 位空间里不可能存在这种短码，因此按「不存在」处理
            throw new LinkForgeException(ErrorCode.LINK_NOT_FOUND,
                    "短码长度必须为 " + IdSpace.SHORT_CODE_LENGTH + " 位: " + rawCode.length() + " 位");
        }
    }

    // ---------------------------------------------------------------------
    // expireAt
    // ---------------------------------------------------------------------

    /**
     * 校验过期时间必须是**将来**。
     *
     * @param expireAt {@code null} 表示永久有效，直接通过
     * @throws LinkForgeException {@link ErrorCode#EXPIRE_AT_NOT_IN_FUTURE}
     */
    public void validateExpireAt(OffsetDateTime expireAt) {
        if (expireAt == null) {
            return;
        }
        // ⚠️ 用 Instant 语义比较，不比较 LocalDateTime：
        // expireAt 可能带任意偏移（+05:30 等），直接比 LocalDateTime 会得出错误结论（DR-027）
        if (!expireAt.toInstant().isAfter(clock.instant())) {
            throw new LinkForgeException(ErrorCode.EXPIRE_AT_NOT_IN_FUTURE,
                    "expireAt 必须是将来时间");
        }
    }
}
