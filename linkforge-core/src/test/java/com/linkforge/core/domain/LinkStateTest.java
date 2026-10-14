package com.linkforge.core.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 跳转视角状态推导测试（docs/DATABASE.md §4.2、docs/API.md §4.4）。
 *
 * <p>全部使用<b>显式传入的 {@code now}</b> 而不是真实时间：
 * 过期判定的正确性完全取决于「比较的是哪一刻」，一旦用了真实时间，
 * 用例就可能在某个时刻开始失败，且失败原因与代码缺陷无关。
 */
class LinkStateTest {

    /** 固定「现在」：2026-01-15T02:30:00Z（= 10:30 +08:00）。 */
    private static final Instant NOW = Instant.parse("2026-01-15T02:30:00Z");

    private static Link link(LinkStatus status, LocalDateTime expireAtUtc) {
        return new Link(1L, "abc1234", "https://example.com/x", status, false,
                LocalDateTime.of(2026, 1, 1, 0, 0), LocalDateTime.of(2026, 1, 1, 0, 0), expireAtUtc);
    }

    private static LocalDateTime utc(String isoLocal) {
        return LocalDateTime.parse(isoLocal);
    }

    @Test
    @DisplayName("ACTIVE 且未过期 → ACTIVE")
    void activeAndNotExpired() {
        assertThat(LinkState.resolve(link(LinkStatus.ACTIVE, utc("2026-01-15T03:00:00")), NOW))
                .isEqualTo(LinkState.ACTIVE);
    }

    @Test
    @DisplayName("expire_at 为 null（永久有效）→ ACTIVE，永不判过期")
    void nullExpireAtNeverExpires() {
        assertThat(LinkState.resolve(link(LinkStatus.ACTIVE, null), NOW))
                .isEqualTo(LinkState.ACTIVE);
        assertThat(LinkState.isExpired(null, NOW)).isFalse();
    }

    @Test
    @DisplayName("⭐ 用例 4：DELETED 且已过期 → 仍是 DELETED（优先级不反，删除先于过期）")
    void deletedWinsOverExpired() {
        // expire_at 在 NOW 之前——若先判过期就会返回 EXPIRED（410），这是错的
        Link deletedAndExpired = link(LinkStatus.DELETED, utc("2026-01-15T01:00:00"));
        assertThat(LinkState.resolve(deletedAndExpired, NOW)).isEqualTo(LinkState.DELETED);
        // 单独确认它确实已过期，确保本用例不是因为「其实没过期」而通过
        assertThat(LinkState.isExpired(deletedAndExpired.expireAt(), NOW)).isTrue();
    }

    @Test
    @DisplayName("DELETED 但未过期 → DELETED（删除不看 expire_at）")
    void deletedWithoutExpiry() {
        assertThat(LinkState.resolve(link(LinkStatus.DELETED, utc("2027-01-15T00:00:00")), NOW))
                .isEqualTo(LinkState.DELETED);
    }

    @Test
    @DisplayName("⭐ 用例 6：expire_at 恰好等于 now → EXPIRED（含等号，精度到秒）")
    void exactlyNowIsExpired() {
        LocalDateTime expireAt = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);
        assertThat(LinkState.resolve(link(LinkStatus.ACTIVE, expireAt), NOW))
                .isEqualTo(LinkState.EXPIRED);
    }

    @Test
    @DisplayName("⭐ 用例 7：expire_at 比 now 晚 1 秒 → ACTIVE；早 1 秒 → EXPIRED")
    void oneSecondAroundBoundary() {
        assertThat(LinkState.resolve(link(LinkStatus.ACTIVE, utc("2026-01-15T02:30:01")), NOW))
                .isEqualTo(LinkState.ACTIVE);
        assertThat(LinkState.resolve(link(LinkStatus.ACTIVE, utc("2026-01-15T02:29:59")), NOW))
                .isEqualTo(LinkState.EXPIRED);
    }

    @Test
    @DisplayName("⭐ 用例 17：不同 Clock 偏移（UTC / +08:00 / -05:00）表示同一刻 → 结论一致")
    void resultIsIndependentOfClockZone() {
        LocalDateTime expireAtUtc = utc("2026-01-15T02:30:01");
        for (ZoneOffset zone : new ZoneOffset[] {ZoneOffset.UTC, ZoneOffset.ofHours(8), ZoneOffset.ofHours(-5)}) {
            Clock clock = Clock.fixed(NOW, zone);
            // 同一瞬间、不同偏移，LinkState 结果必须相同
            assertThat(LinkState.resolve(link(LinkStatus.ACTIVE, expireAtUtc), clock.instant()))
                    .as("clock zone = %s", zone)
                    .isEqualTo(LinkState.ACTIVE);
            assertThat(LinkState.resolve(link(LinkStatus.ACTIVE, utc("2026-01-15T02:29:59")), clock.instant()))
                    .as("clock zone = %s", zone)
                    .isEqualTo(LinkState.EXPIRED);
        }
        // 顺带锁住「expire_at 必须按 UTC 还原」：若实现误把本地墙上时钟 10:30 当 UTC 解释，
        // 它会变成 10:30Z —— 比 now 晚 8 小时，一条**此刻已过期**的链接会被判为仍然有效。
        OffsetDateTime wallClock = OffsetDateTime.parse("2026-01-15T10:30:00+08:00");
        assertThat(LinkState.isExpired(wallClock.toLocalDateTime(), NOW)).isFalse();
        // 正确写法：先转成 UTC 的 LocalDateTime 存库，此刻恰好到期 → 过期
        assertThat(LinkState.isExpired(wallClock.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime(), NOW))
                .isTrue();
    }
}
