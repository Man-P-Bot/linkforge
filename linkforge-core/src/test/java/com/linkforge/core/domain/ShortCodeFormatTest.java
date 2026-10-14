package com.linkforge.core.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 路径短码格式规则测试（docs/API.md §4.1）。
 *
 * <p>本类只锁「格式判定」，<b>不锁错误码</b>——错误码映射在
 * {@code LinkValidator.validateForRedirect} 的测试里。
 */
class ShortCodeFormatTest {

    @Test
    @DisplayName("字符判定：全 Base62 通过；含 _ - . % 空格 中文 任一即不通过")
    void charRules() {
        assertThat(ShortCodeFormat.hasValidChars("abc1234")).isTrue();
        assertThat(ShortCodeFormat.hasValidChars("0000Q0u")).isTrue();
        assertThat(ShortCodeFormat.hasValidChars("abc_123")).isFalse();
        assertThat(ShortCodeFormat.hasValidChars("abc-123")).isFalse();
        assertThat(ShortCodeFormat.hasValidChars("abc.123")).isFalse();
        assertThat(ShortCodeFormat.hasValidChars("abc%2F")).isFalse();
        assertThat(ShortCodeFormat.hasValidChars("abc 123")).isFalse();
        assertThat(ShortCodeFormat.hasValidChars("中文短码")).isFalse();
        // 空串不是合法 Base62 串
        assertThat(ShortCodeFormat.hasValidChars("")).isFalse();
        assertThat(ShortCodeFormat.hasValidChars(null)).isFalse();
    }

    @Test
    @DisplayName("长度判定：只认 7 位（自定义短码 4~8 位在这里也算长度不符）")
    void lengthRules() {
        assertThat(ShortCodeFormat.hasValidLength("abc1234")).isTrue();
        assertThat(ShortCodeFormat.hasValidLength("abc123")).isFalse();
        assertThat(ShortCodeFormat.hasValidLength("abc12345")).isFalse();
        // 自定义短码 promo26 恰好也是 7 位——跳转接口按默认长度定位，它与自动短码共用同一空间
        assertThat(ShortCodeFormat.hasValidLength("promo26")).isTrue();
        assertThat(ShortCodeFormat.hasValidLength("promo")).isFalse();
        assertThat(ShortCodeFormat.hasValidLength(null)).isFalse();
    }

    @Test
    @DisplayName("尾斜杠与空值判定")
    void trailingSlashAndBlank() {
        assertThat(ShortCodeFormat.hasTrailingSlash("abc1234/")).isTrue();
        assertThat(ShortCodeFormat.hasTrailingSlash("abc1234")).isFalse();
        assertThat(ShortCodeFormat.hasTrailingSlash(null)).isFalse();
        assertThat(ShortCodeFormat.isBlank(null)).isTrue();
        assertThat(ShortCodeFormat.isBlank("")).isTrue();
        assertThat(ShortCodeFormat.isBlank("   ")).isTrue();
        assertThat(ShortCodeFormat.isBlank("abc1234")).isFalse();
    }
}
