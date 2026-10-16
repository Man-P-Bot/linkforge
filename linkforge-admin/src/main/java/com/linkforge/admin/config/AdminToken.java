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
        if (presented == null || presented.isEmpty()) {
            return false;
        }
        byte[] actual = presented.getBytes(StandardCharsets.UTF_8);
        // ⚠️ 不能在长度不同时直接返回 false —— 那也是可测量的时间差信号
        return MessageDigest.isEqual(expected, actual);
    }

    @Override
    public String toString() {
        // ⚠️ 绝不返回真实值
        return "AdminToken[****]";
    }
}