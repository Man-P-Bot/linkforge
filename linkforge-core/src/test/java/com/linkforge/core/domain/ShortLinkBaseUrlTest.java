package com.linkforge.core.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.linkforge.core.error.LinkForgeException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@link ShortLinkBaseUrl} 校验与归一化测试（docs/DECISIONS.md DR-026）。
 *
 * <p>重点是**非法值必须在启动期失败**，以及**末尾斜杠归一化后不得出现双斜杠**——
 * 后者是最容易出的低级 bug，会造成对外可见的错误短链。
 */
class ShortLinkBaseUrlTest {

    @Nested
    @DisplayName("合法值")
    class Valid {

        @ParameterizedTest
        @ValueSource(strings = {
                "http://localhost:8080",
                "https://link.example.com",
                "https://link.example.com:8443",
                "http://127.0.0.1:8080",
        })
        @DisplayName("常见的合法基地址通过")
        void acceptsCommonValues(String raw) {
            assertThat(ShortLinkBaseUrl.parse(raw).value()).isEqualTo(raw);
        }

        @Test
        @DisplayName("scheme 大小写不敏感：HTTP:// 通过")
        void schemeCaseInsensitive() {
            assertThat(ShortLinkBaseUrl.parse("HTTP://localhost:8080").value())
                    .isEqualTo("HTTP://localhost:8080");
        }

        @Test
        @DisplayName("首尾空白被去掉")
        void trimsWhitespace() {
            assertThat(ShortLinkBaseUrl.parse("  http://localhost:8080  ").value())
                    .isEqualTo("http://localhost:8080");
        }
    }

    @Nested
    @DisplayName("末尾斜杠归一化")
    class TrailingSlash {

        @ParameterizedTest
        @ValueSource(strings = {
                "http://localhost:8080/",
                "http://localhost:8080//",
                "http://localhost:8080///",
        })
        @DisplayName("末尾斜杠（含多个）一律归一化掉")
        void stripsTrailingSlashes(String raw) {
            assertThat(ShortLinkBaseUrl.parse(raw).value()).isEqualTo("http://localhost:8080");
        }

        @Test
        @DisplayName("⭐ 归一化后拼接结果不出现双斜杠")
        void noDoubleSlashInShortUrl() {
            assertThat(ShortLinkBaseUrl.parse("http://localhost:8080/").toShortUrl("abc1234"))
                    .isEqualTo("http://localhost:8080/abc1234");
            assertThat(ShortLinkBaseUrl.parse("http://localhost:8080//").toShortUrl("abc1234"))
                    .isEqualTo("http://localhost:8080/abc1234");
            assertThat(ShortLinkBaseUrl.parse("http://localhost:8080").toShortUrl("abc1234"))
                    .isEqualTo("http://localhost:8080/abc1234");
        }

        @Test
        @DisplayName("带尾斜杠与不带尾斜杠产出完全相同的 shortUrl")
        void equivalentConfigsProduceSameUrl() {
            String withSlash = ShortLinkBaseUrl.parse("http://localhost:8080/").toShortUrl("0000Q0u");
            String withoutSlash = ShortLinkBaseUrl.parse("http://localhost:8080").toShortUrl("0000Q0u");

            assertThat(withSlash).isEqualTo(withoutSlash).isEqualTo("http://localhost:8080/0000Q0u");
        }

        @Test
        @DisplayName("归一化后 shortUrl 中除 scheme 外只有一个斜杠")
        void exactlyOneSlashAfterScheme() {
            String shortUrl = ShortLinkBaseUrl.parse("https://x.example.com///").toShortUrl("abc1234");

            assertThat(shortUrl).isEqualTo("https://x.example.com/abc1234");
            assertThat(shortUrl.substring("https://".length())).doesNotContain("//");
        }
    }

    @Nested
    @DisplayName("⭐ 非法值一律启动失败（不允许带病启动）")
    class Invalid {

        @Test
        @DisplayName("null 抛异常")
        void nullRejected() {
            assertThatThrownBy(() -> ShortLinkBaseUrl.parse(null))
                    .isInstanceOf(LinkForgeException.class)
                    .hasMessageContaining("不能为空");
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "   ", "\t"})
        @DisplayName("空字符串与全空白抛异常")
        void blankRejected(String raw) {
            assertThatThrownBy(() -> ShortLinkBaseUrl.parse(raw))
                    .isInstanceOf(LinkForgeException.class)
                    .hasMessageContaining("不能为空");
        }

        @ParameterizedTest
        @ValueSource(strings = {"/", "//", "///"})
        @DisplayName("⭐ 纯斜杠抛异常——它看起来「有值」，不会被必填校验拦住")
        void pureSlashesRejected(String raw) {
            assertThatThrownBy(() -> ShortLinkBaseUrl.parse(raw))
                    .isInstanceOf(LinkForgeException.class)
                    .hasMessageContaining("纯斜杠");
        }

        @ParameterizedTest
        @ValueSource(strings = {"localhost:8080", "example.com", "127.0.0.1:8080"})
        @DisplayName("⭐ 无 scheme 抛异常——本地开发时人习惯这么写，但拼出的 URL 非法")
        void missingSchemeRejected(String raw) {
            assertThatThrownBy(() -> ShortLinkBaseUrl.parse(raw))
                    .isInstanceOf(LinkForgeException.class)
                    .hasMessageContaining("scheme");
        }

        @ParameterizedTest
        @ValueSource(strings = {"ftp://example.com", "javascript:alert(1)", "file:///etc/passwd"})
        @DisplayName("非 http/https 的 scheme 抛异常")
        void unsupportedSchemeRejected(String raw) {
            assertThatThrownBy(() -> ShortLinkBaseUrl.parse(raw))
                    .isInstanceOf(LinkForgeException.class);
        }

        @Test
        @DisplayName("有 scheme 但缺 host 抛异常")
        void missingHostRejected() {
            assertThatThrownBy(() -> ShortLinkBaseUrl.parse("http://"))
                    .isInstanceOf(LinkForgeException.class);
        }
    }

    @Nested
    @DisplayName("toShortUrl 参数校验")
    class ToShortUrl {

        @Test
        @DisplayName("短码为空时抛异常（属编程错误，不是配置错误）")
        void blankCodeRejected() {
            ShortLinkBaseUrl base = ShortLinkBaseUrl.parse("http://localhost:8080");

            assertThatThrownBy(() -> base.toShortUrl(null)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> base.toShortUrl("")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> base.toShortUrl("  ")).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
