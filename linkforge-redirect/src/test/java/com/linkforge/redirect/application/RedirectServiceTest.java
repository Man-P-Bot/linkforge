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
import com.github.benmanes.caffeine.cache.Caffeine;
import com.linkforge.core.infrastructure.cache.BloomFilter;
import com.linkforge.core.repository.LinkRepository;
import com.linkforge.redirect.infrastructure.cache.RedisRedirectCache;
import com.linkforge.redirect.infrastructure.cache.TwoLevelRedirectCache;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
        return new RedirectService(linkRepository, linkValidator, alwaysMiss(),
                BLOOM_ALWAYS_PRESENT, clock);
    }

    private RedirectService service() {
        return service(Clock.fixed(NOW, ZoneOffset.UTC));
    }

    /**
     * 恒定未命中的 L2。
     *
     * <p>⚠️ 用 Mockito 的 {@code RETURNS_SMART_NULLS} 不够——它仍会返回 null 而不是
     * {@code Optional.empty()}。最稳的做法是写一个匿名实现明确返回 empty，
     * 这样「未命中」是**代码事实**而不是「碰巧不是 null」。
     */
    private static final RedisRedirectCache MISSING_REDIS = new RedisRedirectCache(
            null, new SimpleMeterRegistry()) {
        @Override
        public java.util.Optional<com.linkforge.core.infrastructure.cache.CacheEntry> get(
                String shortCode) {
            return java.util.Optional.empty();
        }

        @Override
        public void put(String shortCode,
                        com.linkforge.core.infrastructure.cache.CachedLink link) {
            // ⚠️ 必须 override：门面在 loader 回源成功后调 putToRedisOnly，
            // 不 override 就会走到父类的 redis.opsForValue()，对 null 抛 NPE
        }

        @Override
        public void putEmpty(String shortCode) {
            // 同上：本类不关心缓存写入
        }
    };

    /**
     * 恒定「未命中」的缓存门面。
     *
    /**
     * ⚠️ <b>刻意不用 static 字段</b>：Caffeine 实例内部有状态，
     * 静态共享会让上一个用例写入的条目被下一个用例读到
     *（表现为「短码已删除: abc1234」出现在本该 302 的用例里——
     * 错误信息完全指不到「测试之间共享了状态」这个真正的原因）。
     * 因此每次调用都新建一个空缓存的门面。
     */
    private static TwoLevelRedirectCache alwaysMiss() {
        return new TwoLevelRedirectCache(
                Caffeine.newBuilder().maximumSize(1).build(),
                MISSING_REDIS, new SimpleMeterRegistry());
    }


    /** 布隆恒定「可能存在」——本类验证的是判定逻辑，不是布隆的拦截行为。 */
    private static final BloomFilter BLOOM_ALWAYS_PRESENT = new BloomFilter() {
        @Override
        public boolean mightContain(String shortCode) {
            return true;
        }

        @Override
        public void put(String shortCode) {
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public long expectedInsertions() {
            return 0L;
        }

        @Override
        public long bitSize() {
            return 0L;
        }

        @Override
        public int hashFunctions() {
            return 0;
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
