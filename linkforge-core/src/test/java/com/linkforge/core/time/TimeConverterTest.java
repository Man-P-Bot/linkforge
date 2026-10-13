package com.linkforge.core.time;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@link TimeConverter} 边界测试（docs/DECISIONS.md DR-027）。
 *
 * <p>这些用例覆盖的不是「happy path」，而是最容易出错的三处：null 语义、
 * 非整点偏移、以及「同一瞬间不同偏移必须等价」。
 */
class TimeConverterTest {

    private static final ZoneId DISPLAY = ZoneId.of("+08:00");
    private final TimeConverter converter = TimeConverter.INSTANCE;

    @Nested
    @DisplayName("null 语义：null 进 null 出，不抛异常")
    class NullHandling {

        @Test
        @DisplayName("toUtc(null) 返回 null 而不抛异常")
        void toUtcNull() {
            assertThat(converter.toUtc(null)).isNull();
        }

        @Test
        @DisplayName("toOffset(null, zone) 返回 null 而不抛异常")
        void toOffsetNull() {
            assertThat(converter.toOffset(null, DISPLAY)).isNull();
        }

        @Test
        @DisplayName("理由是 expire_at 为 NULL 表示永久有效，属合法业务状态而非错误")
        void nullIsLegitimateBusinessState() {
            // 与「永久有效」对应的正是 null，因此调用方不必在每处写 null 判断
            assertThat(converter.toUtc(null)).isNull();
            assertThat(converter.toOffset(null, DISPLAY)).isNull();
        }
    }

    @Nested
    @DisplayName("toUtc：请求时间 → UTC 的 LocalDateTime")
    class ToUtc {

        @Test
        @DisplayName("+08:00 正确换算为 UTC")
        void plusEight() {
            OffsetDateTime source = OffsetDateTime.parse("2026-01-15T10:30:00+08:00");

            LocalDateTime utc = converter.toUtc(source);

            assertThat(utc).isEqualTo(LocalDateTime.of(2026, 1, 15, 2, 30, 0));
        }

        @Test
        @DisplayName("⭐ +05:30 这类非整点偏移换算正确（印度时区，最容易算错半小数）")
        void halfHourOffset() {
            OffsetDateTime source = OffsetDateTime.parse("2026-01-15T10:30:00+05:30");

            LocalDateTime utc = converter.toUtc(source);

            // 10:30 − 5:30 = 05:00
            assertThat(utc).isEqualTo(LocalDateTime.of(2026, 1, 15, 5, 0, 0));
        }

        @Test
        @DisplayName("⭐ 同一瞬间的不同偏移表示 → 转 UTC 后相等")
        void sameInstantDifferentOffsets() {
            OffsetDateTime shanghai = OffsetDateTime.parse("2026-01-15T10:30:00+08:00");
            OffsetDateTime utc = OffsetDateTime.parse("2026-01-15T02:30:00Z");

            assertThat(shanghai.toInstant()).isEqualTo(utc.toInstant());
            assertThat(converter.toUtc(shanghai)).isEqualTo(converter.toUtc(utc));
        }

        @Test
        @DisplayName("负偏移（-05:00）换算正确")
        void negativeOffset() {
            OffsetDateTime source = OffsetDateTime.parse("2026-01-15T10:30:00-05:00");

            assertThat(converter.toUtc(source)).isEqualTo(LocalDateTime.of(2026, 1, 15, 15, 30, 0));
        }

        @Test
        @DisplayName("已带 UTC 偏移的输入保持原值")
        void alreadyUtc() {
            OffsetDateTime source = OffsetDateTime.parse("2026-01-15T02:30:00Z");

            assertThat(converter.toUtc(source)).isEqualTo(LocalDateTime.of(2026, 1, 15, 2, 30, 0));
        }
    }

    @Nested
    @DisplayName("toOffset：UTC 的 LocalDateTime → 按展示时区渲染")
    class ToOffset {

        @Test
        @DisplayName("UTC → +08:00 渲染正确，且与存储的瞬间一致")
        void utcToShanghai() {
            LocalDateTime utc = LocalDateTime.of(2026, 1, 15, 2, 30, 0);

            OffsetDateTime rendered = converter.toOffset(utc, DISPLAY);

            assertThat(rendered).isEqualTo(OffsetDateTime.parse("2026-01-15T10:30:00+08:00"));
            assertThat(rendered.getOffset()).isEqualTo(ZoneOffset.ofHours(8));
            // 关键：渲染只改偏移，不改瞬间
            assertThat(rendered.toInstant()).isEqualTo(utc.atOffset(ZoneOffset.UTC).toInstant());
        }

        @Test
        @DisplayName("渲染到 UTC 时区时偏移为 Z")
        void renderAsUtc() {
            LocalDateTime utc = LocalDateTime.of(2026, 1, 15, 2, 30, 0);

            OffsetDateTime rendered = converter.toOffset(utc, ZoneOffset.UTC);

            assertThat(rendered.getOffset()).isEqualTo(ZoneOffset.UTC);
            assertThat(rendered.toInstant()).isEqualTo(utc.atOffset(ZoneOffset.UTC).toInstant());
        }

        @Test
        @DisplayName("⭐ 往返一致：toOffset(toUtc(x)) 等于 x 的同一瞬间（偏移可能不同）")
        void roundTripPreservesInstant() {
            OffsetDateTime original = OffsetDateTime.parse("2026-01-15T10:30:00+05:30");

            OffsetDateTime result = converter.toOffset(converter.toUtc(original), DISPLAY);

            assertThat(result.toInstant()).isEqualTo(original.toInstant());
        }
    }

    @Nested
    @DisplayName("nowUtc：判定用的当前时间")
    class NowUtc {

        @Test
        @DisplayName("返回的是 UTC 语义的 LocalDateTime（与 Instant 一致）")
        void nowUtcMatchesInstant() {
            LocalDateTime nowUtc = converter.nowUtc();

            // 允许少量执行耗时
            long diffSeconds = Math.abs(nowUtc.toEpochSecond(ZoneOffset.UTC) - java.time.Instant.now().getEpochSecond());
            assertThat(diffSeconds).isLessThan(5);
        }
    }
}
