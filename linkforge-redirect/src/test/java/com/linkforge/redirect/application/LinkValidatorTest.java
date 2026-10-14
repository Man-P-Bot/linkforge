package com.linkforge.redirect.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.linkforge.core.domain.ReservedCodes;
import com.linkforge.core.error.ErrorCode;
import com.linkforge.core.error.LinkForgeException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 业务规则校验测试（docs/API.md §5.1/§5.2）。
 *
 * <p>用**固定时钟**而不是真实时间：{@code expireAt} 的「将来性」检查依赖当前时间，
 * 若用 {@code Clock.systemUTC()}，用例会随执行时刻漂移（PRD §7.3 要求时间可注入）。
 */
class LinkValidatorTest {

    /** 固定「现在」：2026-01-15T02:30:00Z。 */
    private static final Instant FIXED_NOW = Instant.parse("2026-01-15T02:30:00Z");

    private final LinkValidator validator = new LinkValidator(
            ReservedCodes.parse(ReservedCodes.DEFAULT_CODES),
            Clock.fixed(FIXED_NOW, ZoneOffset.UTC));

    @Nested
    @DisplayName("targetUrl：协议白名单（安全边界）")
    class TargetUrl {

        @ParameterizedTest
        @ValueSource(strings = {
                "http://example.com",
                "https://example.com",
                "https://example.com/very/long/path?q=1&x=2",
                "http://example.com:8080/p",
        })
        @DisplayName("http/https 通过")
        void acceptsHttpAndHttps(String url) {
            assertThatCode(() -> validator.validateTargetUrl(url)).doesNotThrowAnyException();
        }

        @ParameterizedTest
        @ValueSource(strings = {"HTTP://example.com", "HTTPS://example.com", "HtTpS://example.com"})
        @DisplayName("scheme 大小写不敏感：HTTP:// 通过（RFC 3986）")
        void schemeCaseInsensitive(String url) {
            assertThatCode(() -> validator.validateTargetUrl(url)).doesNotThrowAnyException();
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "javascript:alert(1)",
                "data:text/html,<script>alert(1)</script>",
                "file:///etc/passwd",
                "ftp://example.com/x",
                "//example.com",
        })
        @DisplayName("⭐ 白名单外的 scheme 一律拒绝，错误码是独立的 400-02")
        void rejectsNonWhitelistedSchemes(String url) {
            assertThatThrownBy(() -> validator.validateTargetUrl(url))
                    .isInstanceOf(LinkForgeException.class)
                    .extracting(e -> ((LinkForgeException) e).errorCode())
                    .isEqualTo(ErrorCode.UNSUPPORTED_PROTOCOL);
        }

        @ParameterizedTest
        @ValueSource(strings = {"example.com", "localhost:8080", "www.example.com/path"})
        @DisplayName("无 scheme 归为「协议不允许」而不是「格式错误」——从安全视角它就是不能放行的地址")
        void missingSchemeIsProtocolError(String url) {
            assertThatThrownBy(() -> validator.validateTargetUrl(url))
                    .isInstanceOf(LinkForgeException.class)
                    .extracting(e -> ((LinkForgeException) e).errorCode())
                    .isEqualTo(ErrorCode.UNSUPPORTED_PROTOCOL);
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "   "})
        @DisplayName("空白 → 400-01")
        void blankIsInvalidParameter(String url) {
            assertThatThrownBy(() -> validator.validateTargetUrl(url))
                    .extracting(e -> ((LinkForgeException) e).errorCode())
                    .isEqualTo(ErrorCode.INVALID_PARAMETER);
        }

        @Test
        @DisplayName("null → 400-01")
        void nullIsInvalidParameter() {
            assertThatThrownBy(() -> validator.validateTargetUrl(null))
                    .extracting(e -> ((LinkForgeException) e).errorCode())
                    .isEqualTo(ErrorCode.INVALID_PARAMETER);
        }

        @Test
        @DisplayName("恰好 2048 字通过")
        void exactlyMaxLengthAccepted() {
            String url = "https://example.com/" + "a".repeat(2048 - "https://example.com/".length());
            assertThat(url).hasSize(2048);
            assertThatCode(() -> validator.validateTargetUrl(url)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("⭐ 2049 字拒绝（超长不截断——截断会产生指向错误地址的短链）")
        void oneOverMaxRejected() {
            String url = "https://example.com/" + "a".repeat(2049 - "https://example.com/".length());
            assertThat(url).hasSize(2049);
            assertThatThrownBy(() -> validator.validateTargetUrl(url))
                    .isInstanceOf(LinkForgeException.class)
                    .extracting(e -> ((LinkForgeException) e).errorCode())
                    .isEqualTo(ErrorCode.INVALID_PARAMETER);
        }
    }

    @Nested
    @DisplayName("customCode：格式与保留字（大小写不敏感）")
    class CustomCode {

        @ParameterizedTest
        @ValueSource(strings = {"abcd", "promo26", "abcd1234", "ABCD1234", "a1B2c3D4"})
        @DisplayName("4~8 位 Base62 通过")
        void acceptsValidFormats(String code) {
            assertThatCode(() -> validator.validateCustomCode(code)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("null / 空白表示不使用自定义短码，直接通过")
        void nullMeansNoCustomCode() {
            assertThatCode(() -> validator.validateCustomCode(null)).doesNotThrowAnyException();
            assertThatCode(() -> validator.validateCustomCode("")).doesNotThrowAnyException();
            assertThatCode(() -> validator.validateCustomCode("   ")).doesNotThrowAnyException();
        }

        @ParameterizedTest
        @ValueSource(strings = {"abc", "abcdefghi", "abc-123", "abc_123", "abc 123", "中文短码", "abc.123"})
        @DisplayName("格式非法 → 400-03")
        void rejectsInvalidFormats(String code) {
            assertThatThrownBy(() -> validator.validateCustomCode(code))
                    .isInstanceOf(LinkForgeException.class)
                    .extracting(e -> ((LinkForgeException) e).errorCode())
                    .isEqualTo(ErrorCode.INVALID_CUSTOM_CODE_FORMAT);
        }

        @ParameterizedTest
        @ValueSource(strings = {"api", "admin", "actuator", "health", "static", "error"})
        @DisplayName("命中保留字（小写）→ 400-04")
        void rejectsReservedLowerCase(String code) {
            assertThatThrownBy(() -> validator.validateCustomCode(code))
                    .isInstanceOf(LinkForgeException.class)
                    .extracting(e -> ((LinkForgeException) e).errorCode())
                    .isEqualTo(ErrorCode.RESERVED_CUSTOM_CODE);
        }

        @ParameterizedTest
        @ValueSource(strings = {"API", "Api", "aPi", "ADMIN", "Admin", "ACtuator", "HEALTH", "Static", "ERROR"})
        @DisplayName("⭐ 保留字比较大小写不敏感（DR-025）：Api/API 全拒绝，防路径冲突")
        void rejectsReservedCaseInsensitive(String code) {
            assertThatThrownBy(() -> validator.validateCustomCode(code))
                    .isInstanceOf(LinkForgeException.class)
                    .extracting(e -> ((LinkForgeException) e).errorCode())
                    .isEqualTo(ErrorCode.RESERVED_CUSTOM_CODE);
        }

        @Test
        @DisplayName("非保留字的相近写法通过：apiaa、myapi、admin1")
        void nonReservedLookalikesAccepted() {
            assertThatCode(() -> validator.validateCustomCode("apiaa")).doesNotThrowAnyException();
            assertThatCode(() -> validator.validateCustomCode("myapi")).doesNotThrowAnyException();
            assertThatCode(() -> validator.validateCustomCode("admin1")).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("跳转路径参数 {code}：字符 400 / 长度 404（docs/API.md §4.1）")
    class RedirectCode {

        @ParameterizedTest
        @ValueSource(strings = {"abc1234", "0000Q0u", "aBc1234", "zzzzzzz"})
        @DisplayName("7 位 Base62 通过")
        void acceptsSevenCharBase62(String code) {
            assertThatCode(() -> validator.validateForRedirect(code)).doesNotThrowAnyException();
        }

        @ParameterizedTest
        @ValueSource(strings = {"abc_123", "abc-123", "abc.123", "abc 123", "中文短码", "abc%2F"})
        @DisplayName("⭐ 非 Base62 字符 → 400-01（格式错误，调用方能立刻发现拼接 bug）")
        void illegalCharsAre400(String code) {
            assertThatThrownBy(() -> validator.validateForRedirect(code))
                    .isInstanceOf(LinkForgeException.class)
                    .extracting(e -> ((LinkForgeException) e).errorCode())
                    .isEqualTo(ErrorCode.INVALID_PARAMETER);
        }

        @ParameterizedTest
        @ValueSource(strings = {"abc123", "abc12345", "promo", "abcde"})
        @DisplayName("⭐ 长度不是 7 位 → 404-01（不是 400：一定查不到，不是「参数非法」）")
        void wrongLengthIs404(String code) {
            assertThatThrownBy(() -> validator.validateForRedirect(code))
                    .isInstanceOf(LinkForgeException.class)
                    .extracting(e -> ((LinkForgeException) e).errorCode())
                    .isEqualTo(ErrorCode.LINK_NOT_FOUND);
        }

        @Test
        @DisplayName("⭐ 尾斜杠 → 400-01（不做重定向纠正）")
        void trailingSlashIs400() {
            assertThatThrownBy(() -> validator.validateForRedirect("abc1234/"))
                    .extracting(e -> ((LinkForgeException) e).errorCode())
                    .isEqualTo(ErrorCode.INVALID_PARAMETER);
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "   "})
        @DisplayName("空 / 全空白 → 400-01")
        void blankIs400(String code) {
            assertThatThrownBy(() -> validator.validateForRedirect(code))
                    .extracting(e -> ((LinkForgeException) e).errorCode())
                    .isEqualTo(ErrorCode.INVALID_PARAMETER);
        }

        @Test
        @DisplayName("null → 400-01")
        void nullIs400() {
            assertThatThrownBy(() -> validator.validateForRedirect(null))
                    .extracting(e -> ((LinkForgeException) e).errorCode())
                    .isEqualTo(ErrorCode.INVALID_PARAMETER);
        }

        @Test
        @DisplayName("⭐ 顺序不可换：4 位且含非法字符 → 400 而不是 404")
        void charCheckPrecedesLengthCheck() {
            // 若先判长度，"abc_" 会被归为「不存在」，调用方将反复重试同一个拼接 bug 的请求
            assertThatThrownBy(() -> validator.validateForRedirect("abc_"))
                    .extracting(e -> ((LinkForgeException) e).errorCode())
                    .isEqualTo(ErrorCode.INVALID_PARAMETER);
        }
    }

    @Nested
    @DisplayName("expireAt：必须是将来")
    class ExpireAt {

        @Test
        @DisplayName("null 通过（永久有效）")
        void nullMeansPermanent() {
            assertThatCode(() -> validator.validateExpireAt(null)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("将来时间通过（严格晚于固定时钟的瞬间）")
        void futureAccepted() {
            // ⚠️ 必须**严格**晚于 FIXED_NOW（02:30Z）。原先用 10:30+08:00，它恰好等于 02:30Z，
            // 属于「不是将来」的边界，被实现正确拒绝——是测试数据选错了，不是实现有问题。
            OffsetDateTime future = OffsetDateTime.parse("2026-01-15T10:30:01+08:00");
            assertThat(future.toInstant()).isAfter(FIXED_NOW);
            assertThatCode(() -> validator.validateExpireAt(future)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("过去时间 → 400-05")
        void pastRejected() {
            assertThatThrownBy(() -> validator.validateExpireAt(OffsetDateTime.parse("2020-01-15T10:30:00+08:00")))
                    .isInstanceOf(LinkForgeException.class)
                    .extracting(e -> ((LinkForgeException) e).errorCode())
                    .isEqualTo(ErrorCode.EXPIRE_AT_NOT_IN_FUTURE);
        }

        @Test
        @DisplayName("⭐ 恰好等于当前时间 → 400-05（必须严格将来）")
        void exactlyNowRejected() {
            OffsetDateTime now = OffsetDateTime.ofInstant(FIXED_NOW, ZoneOffset.UTC);
            assertThatThrownBy(() -> validator.validateExpireAt(now))
                    .extracting(e -> ((LinkForgeException) e).errorCode())
                    .isEqualTo(ErrorCode.EXPIRE_AT_NOT_IN_FUTURE);
        }

        @Test
        @DisplayName("⭐ 时区正确性：本地看似「将来」但 UTC 已过去的时间必须拒绝")
        void offsetAwareness() {
            // 2026-01-15T01:00:00-05:00 = 2026-01-15T06:00:00Z，其实在 FIXED_NOW 之后
            assertThatCode(() -> validator.validateExpireAt(OffsetDateTime.parse("2026-01-15T01:00:00-05:00")))
                    .doesNotThrowAnyException();

            // 2026-01-14T23:00:00-05:00 = 2026-01-15T04:00:00Z，仍在之后
            assertThatCode(() -> validator.validateExpireAt(OffsetDateTime.parse("2026-01-14T23:00:00-05:00")))
                    .doesNotThrowAnyException();

            // 2026-01-14T20:00:00-05:00 = 2026-01-15T01:00:00Z，已过去
            assertThatThrownBy(() -> validator.validateExpireAt(OffsetDateTime.parse("2026-01-14T20:00:00-05:00")))
                    .extracting(e -> ((LinkForgeException) e).errorCode())
                    .isEqualTo(ErrorCode.EXPIRE_AT_NOT_IN_FUTURE);
        }
    }
}
