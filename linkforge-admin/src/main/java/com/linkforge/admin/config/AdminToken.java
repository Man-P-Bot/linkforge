package com.linkforge.admin.config;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 后台 Token（值对象，只做「比对」这一件事）。
 *
 * <h2>⚠️ 为什么必须用常量时间比较</h2>
 * {@link String#equals} 的实现会**逐字符比较并在首个不同处提前返回**，
 * 比较耗时与「前多少个字符相同」成正比。攻击者可以用响应时间差
 * 逐字节爆破 Token。
 *
 * <p>{@link MessageDigest#isEqual} 是常量时间：无论在第几个字节不同，
 * 耗时都相同。
 *
 * <p>⚠️ 本类**不可变**且不提供 getter —— Token 一旦泄漏到日志或
 * {@code toString()} 就等于没设防。
 */
public final class AdminToken {

    /**
     * {@code Authorization} 头的认证方案前缀。
     *
     * <p>⚠️ **带尾随空格** —— RFC 7235 的 {@code auth-scheme} 与 {@code token} 之间
     * 由**一个或多个空格**分隔。本类只接受恰好一个空格（{@code "Bearer "}），
     * 这是最常见也最严格的写法；多空格/制表符一律判为格式错误。
     */
    public static final String BEARER_PREFIX = "Bearer ";

    private final byte[] expected;

    public AdminToken(String token) {
        this.expected = token.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 常量时间比对。
     *
     * @param presented 调用方提交的 Token 原文
     * @return 相同返回 true
     */
    public boolean matches(String presented) {
        // ⚠️ null / 空串必须在这里挡住：
        //   - null → 下面 getBytes 会抛 NPE；
        //   - 空串 → MessageDigest.isEqual 会因长度不同提前返回，虽然不出错，
        //     但「长度不同就返回」本身就是可测量的时间差信号（见下）。
        if (presented == null || presented.isEmpty()) {
            return false;
        }
        byte[] actual = presented.getBytes(StandardCharsets.UTF_8);
        // ⚠️ 不能在长度不同时直接返回 false —— 那也是可测量的时间差信号
        return MessageDigest.isEqual(expected, actual);
    }

    /**
     * 校验完整的 {@code Authorization} 头。
     *
     * <h2>⚠️ 为什么把「拆头 + 比对」收进这一个方法</h2>
     * 鉴权要在**两个地方**发生：Servlet 过滤器（{@link AdminAuthFilter}，
     * 在路由解析之前）与 Controller（在业务逻辑之前）。
     * 两处若各写一遍「取 {@code Bearer } 之后的部分再比对」，
     * 迟早会出现**一处放宽、一处收紧**的不一致 ——
     * 而那种不一致的典型症状就是「过滤器放行、控制器 401」或反过来。
     *
     * <p>所以这里是**唯一**的判定入口，返回值只有 true / false，
     * **永不抛异常**：任何输入（null、空串、缺前缀、乱码、超长）都必须得到确定的 false。
     *
     * @param header 原始 {@code Authorization} 头；可为 null
     * @return 通过鉴权返回 true
     */
    public boolean matchesAuthorizationHeader(String header) {
        // ⚠️ 任何一步都不许抛异常：
        // 未捕获的 RuntimeException 会穿透到全局兜底处理器变成 500，
        // 把「客户端没带对 Token」这个**调用方问题**伪装成**服务故障**。
        if (header == null || header.isEmpty()) {
            return false;
        }
        if (!header.startsWith(BEARER_PREFIX)) {
            // 缺前缀 / 前缀大小写错 / 用了 Basic 等其它方案 → 格式错误
            return false;
        }
        // 前缀之后可能是空串（"Bearer " 后面什么都没有）→ matches 会挡住
        String presented = header.substring(BEARER_PREFIX.length());
        return matches(presented);
    }

    @Override
    public String toString() {
        // ⚠️ 绝不返回真实值
        return "AdminToken[****]";
    }
}