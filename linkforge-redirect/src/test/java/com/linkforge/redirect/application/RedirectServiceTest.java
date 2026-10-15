package com.linkforge.redirect.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.linkforge.core.domain.Link;
import com.linkforge.core.domain.LinkStatus;
import com.linkforge.core.domain.ReservedCodes;
import com.linkforge.core.error.ErrorCode;
import com.linkforge.core.infrastructure.cache.CacheEntry;
import com.linkforge.core.infrastructure.cache.CachedLink;
import com.linkforge.core.infrastructure.cache.RedirectCacheService;
import com.linkforge.core.repository.LinkRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 跳转用例测试（docs/API.md §4）。
 *
 * <p>用<b>固定时钟</b>而不是真实时间：过期判定完全取决于「与哪一刻比较」，
 * 用真实时间会让边界用例随机失败，且失败原因与代码无关。
 */
@ExtendWith(MockitoExtension.class)
class RedirectServiceTest {

    /** 固定「现在」：2026-01-15T02:30:00Z。 */
    private static final Instant NOW = Instant.parse("2026-01-15T02:30:00Z");

    @Mock
    private LinkRepository linkRepository;

    private final LinkValidator linkValidator = new LinkValidator(
            ReservedCodes.parse(ReservedCodes.DEFAULT_CODES),
            Clock.fixed(NOW, ZoneOffset.UTC));

    private RedirectService service(Clock clock) {
        return new RedirectService(linkRepository, linkValidator, ALWAYS_MISS, clock);
    }

    private RedirectService service() {
        return service(Clock.fixed(NOW, ZoneOffset.UTC));
    }

    /**
     * 恒定未命中的缓存替身。
     *
     * <p>刻意<b>不用 mock</b>：本类验证的是「查库 → 判定」这条纯逻辑路径，
     * 缓存一律未命中才能保证每次都真的回源。若用 {@code @Mock} + 全局 stub，
     * Mockito 的 strict 模式会对「某些用例没用上这个 stub」报错，
     * 反而把注意力从被测逻辑上引开。
     *
     * <p>缓存自身的行为由 {@code TwoLevelRedirectCacheTest} 与
     * {@code RedirectServiceCacheTest} 覆盖。
     */
    private static final RedirectCacheService ALWAYS_MISS = new RedirectCacheService() {
        @Override
        public Optional<CacheEntry> get(String shortCode) {
            return Optional.empty();
        }

        @Override
        public void put(String shortCode, CachedLink link) {
            // 不回填：本类每次都应回源
        }

        @Override
        public void putEmpty(String shortCode) {
            // 不回填
        }

        @Override
        public void evict(String shortCode) {
            // 无内容可失效
        }
    };

    private static Link link(LinkStatus status, LocalDateTime expireAtUtc) {
        return new Link(1L, "abc1234", "https://example.com/very/long/path", status, false,
                LocalDateTime.of(2026, 1, 1, 0, 0), LocalDateTime.of(2026, 1, 1, 0, 0), expireAtUtc);
    }

    @Nested
    @DisplayName("成功：可跳转")
    class Success {

        @Test
        @DisplayName("ACTIVE 且未过期 → 返回 targetUrl 原样（不归一化、不加参数）")
        void returnsTargetUrlUnchanged() {
            given(linkRepository.findByShortCode("abc1234"))
                    .willReturn(Optional.of(link(LinkStatus.ACTIVE, LocalDateTime.parse("2027-01-15T00:00:00"))));

            assertThat(service().resolveTargetUrl("abc1234"))
                    .isEqualTo("https://example.com/very/long/path");
        }

        @Test
        @DisplayName("expire_at 为 null（永久有效）→ 302")
        void nullExpireAtRedirects() {
            given(linkRepository.findByShortCode("abc1234"))
                    .willReturn(Optional.of(link(LinkStatus.ACTIVE, null)));

            assertThat(service().resolveTargetUrl("abc1234"))
                    .isEqualTo("https://example.com/very/long/path");
        }
    }

    @Nested
    @DisplayName("失效判定：404 与 410 严格区分")
    class Invalidation {

        @Test
        @DisplayName("查不到 → 404 LINKFORGE-404-01")
        void notFound() {
            given(linkRepository.findByShortCode("zzzzzzz")).willReturn(Optional.empty());

            assertThatThrownBy(() -> service().resolveTargetUrl("zzzzzzz"))
                    .extracting(e -> ((com.linkforge.core.error.LinkForgeException) e).errorCode())
                    .isEqualTo(ErrorCode.LINK_NOT_FOUND);
        }

        @Test
        @DisplayName("DELETED → 404（不是 410）")
        void deletedIs404() {
            given(linkRepository.findByShortCode("abc1234"))
                    .willReturn(Optional.of(link(LinkStatus.DELETED, null)));

            assertThatThrownBy(() -> service().resolveTargetUrl("abc1234"))
                    .extracting(e -> ((com.linkforge.core.error.LinkForgeException) e).errorCode())
                    .isEqualTo(ErrorCode.LINK_NOT_FOUND);
        }

        @Test
        @DisplayName("ACTIVE 且 expire_at <= now → 410 LINKFORGE-410-01")
        void expiredIs410() {
            given(linkRepository.findByShortCode("abc1234"))
                    .willReturn(Optional.of(link(LinkStatus.ACTIVE, LocalDateTime.parse("2026-01-15T02:00:00"))));

            assertThatThrownBy(() -> service().resolveTargetUrl("abc1234"))
                    .extracting(e -> ((com.linkforge.core.error.LinkForgeException) e).errorCode())
                    .isEqualTo(ErrorCode.LINK_EXPIRED);
        }

        @Test
        @DisplayName("⭐ 用例 4：DELETED 且已过期 → 404，优先级不反")
        void deletedAndExpiredIsStill404() {
            given(linkRepository.findByShortCode("abc1234"))
                    .willReturn(Optional.of(link(LinkStatus.DELETED, LocalDateTime.parse("2026-01-15T01:00:00"))));

            assertThatThrownBy(() -> service().resolveTargetUrl("abc1234"))
                    .extracting(e -> ((com.linkforge.core.error.LinkForgeException) e).errorCode())
                    .isEqualTo(ErrorCode.LINK_NOT_FOUND);
        }

        @Test
        @DisplayName("⭐ 用例 6：expire_at 恰好等于 now → 410（含等号）")
        void exactlyNowIsExpired() {
            LocalDateTime expireAt = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);
            given(linkRepository.findByShortCode("abc1234"))
                    .willReturn(Optional.of(link(LinkStatus.ACTIVE, expireAt)));

            assertThatThrownBy(() -> service().resolveTargetUrl("abc1234"))
                    .extracting(e -> ((com.linkforge.core.error.LinkForgeException) e).errorCode())
                    .isEqualTo(ErrorCode.LINK_EXPIRED);
        }

        @Test
        @DisplayName("⭐ 用例 7：expire_at 比 now 晚 1 秒 → 仍可跳转")
        void oneSecondBeforeExpiryStillRedirects() {
            given(linkRepository.findByShortCode("abc1234"))
                    .willReturn(Optional.of(link(LinkStatus.ACTIVE, LocalDateTime.parse("2026-01-15T02:30:01"))));

            assertThat(service().resolveTargetUrl("abc1234"))
                    .isEqualTo("https://example.com/very/long/path");
        }

        @Test
        @DisplayName("⭐ 用例 17：同一瞬间、Clock 偏移不同时结论一致（-05:00 时钟）")
        void clockZoneDoesNotChangeResult() {
            Clock newYorkClock = Clock.fixed(NOW, ZoneOffset.ofHours(-5));
            given(linkRepository.findByShortCode("abc1234"))
                    .willReturn(Optional.of(link(LinkStatus.ACTIVE, LocalDateTime.parse("2026-01-15T02:30:01"))));

            assertThat(service(newYorkClock).resolveTargetUrl("abc1234"))
                    .isEqualTo("https://example.com/very/long/path");

            given(linkRepository.findByShortCode("abc1234"))
                    .willReturn(Optional.of(link(LinkStatus.ACTIVE, LocalDateTime.parse("2026-01-15T02:29:59"))));
            assertThatThrownBy(() -> service(newYorkClock).resolveTargetUrl("abc1234"))
                    .extracting(e -> ((com.linkforge.core.error.LinkForgeException) e).errorCode())
                    .isEqualTo(ErrorCode.LINK_EXPIRED);
        }
    }

    @Nested
    @DisplayName("格式校验先行")
    class FormatGuard {

        @Test
        @DisplayName("⭐ 非 Base62 字符 → 400，且**不查库**（非法请求不该消耗一次查询）")
        void illegalCharsRejectedBeforeQuery() {
            assertThatThrownBy(() -> service().resolveTargetUrl("abc_123"))
                    .extracting(e -> ((com.linkforge.core.error.LinkForgeException) e).errorCode())
                    .isEqualTo(ErrorCode.INVALID_PARAMETER);
            verify(linkRepository, never()).findByShortCode(org.mockito.ArgumentMatchers.any());
        }

        @Test
        @DisplayName("尾斜杠 → 400，且不查库")
        void trailingSlashRejectedBeforeQuery() {
            assertThatThrownBy(() -> service().resolveTargetUrl("abc1234/"))
                    .extracting(e -> ((com.linkforge.core.error.LinkForgeException) e).errorCode())
                    .isEqualTo(ErrorCode.INVALID_PARAMETER);
            verify(linkRepository, never()).findByShortCode(org.mockito.ArgumentMatchers.any());
        }

        @Test
        @DisplayName("⭐ 长度不是 7 位 → 404（不是 400），但同样不查库")
        void wrongLengthIs404() {
            assertThatThrownBy(() -> service().resolveTargetUrl("abc123"))
                    .extracting(e -> ((com.linkforge.core.error.LinkForgeException) e).errorCode())
                    .isEqualTo(ErrorCode.LINK_NOT_FOUND);
            verify(linkRepository, never()).findByShortCode(org.mockito.ArgumentMatchers.any());
        }
    }
}
