package com.linkforge.core.domain;

/**
 * 路径短码 {@code {code}} 的格式规则（docs/API.md §4.1）。
 *
 * <h2>为什么把「字符合法」与「长度合法」分成两个方法</h2>
 * 二者映射到<b>不同的错误码</b>：
 * <ul>
 *   <li>字符非法（含中文、{@code %}、{@code .}、{@code _}、{@code -}、空格、尾斜杠）→ <b>400</b>
 *       {@code LINKFORGE-400-01}：这是<b>请求格式错误</b>，调用方一眼能看出自己拼错了字符；</li>
 *   <li>长度不符 → <b>404</b> {@code LINKFORGE-404-01}：长度不符<b>不构成「参数非法」</b>，
 *       而「一定查不到」——7 位空间里的短码不可能是 3 位或 12 位，
 *       让调用方去找「为什么我的短码不合法」是误导。</li>
 * </ul>
 * 合成一个 {@code isValid} 会丢掉这个区分，而区分正是 API.md 明确要求的。
 *
 * <p>⚠️ <b>顺序不可调换</b>：{@code abc_}（5 位、含非法字符）必须返回 400 而不是 404——
 * 先判字符才能让「拼接 bug」暴露出来；若先判长度，它会被归为「不存在」，
 * 调用方会反复重试同一个错误请求。
 *
 * <p>本类只做**格式判定**，不查库、不抛业务异常：错误码的选择属于接口层，
 * 由 {@code LinkValidator.validateForRedirect} 决定（core 保持零 web 依赖，DR-021）。
 */
public final class ShortCodeFormat {

    private ShortCodeFormat() {
    }

    /**
     * 字符是否合法：必须<b>全部</b>由 Base62 字符组成。
     *
     * <p>注意这里<b>不</b>检查长度——{@link IdSpace#SHORT_CODE_LENGTH} 的判定是另一条规则。
     */
    public static boolean hasValidChars(String code) {
        return Base62.isBase62(code);
    }

    /** 长度是否为默认短码长度（7 位）。自定义短码允许 4~8 位，跳转接口只认 7 位。 */
    public static boolean hasValidLength(String code) {
        return code != null && code.length() == IdSpace.SHORT_CODE_LENGTH;
    }

    /**
     * 是否带尾斜杠。
     *
     * <p>之所以要单独识别：{@code /abc1234/} 在多数容器里会被归一化成 {@code /abc1234}
     * 而「正常跳转」，这就让一个短码对应<b>多个</b>可访问 URL，破坏一一对应关系，
     * 也会让缓存与统计出现双份记录（API.md §4.1）。因此<b>明确返回 400，不做重定向纠正</b>。
     */
    public static boolean hasTrailingSlash(String code) {
        return code != null && code.endsWith("/");
    }

    /** 空（null / 空串 / 全空白）视为格式错误。 */
    public static boolean isBlank(String code) {
        return code == null || code.isBlank();
    }
}
