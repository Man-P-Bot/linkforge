package com.linkforge.redirect.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.linkforge.core.domain.Link;
import com.linkforge.core.domain.LinkStatus;
import com.linkforge.core.domain.ReservedCodes;
import com.linkforge.core.error.ErrorCode;
import com.linkforge.core.error.LinkForgeException;
import com.linkforge.core.infrastructure.cache.CacheEntry;
import com.linkforge.core.infrastructure.cache.CachedLink;
import com.linkforge.core.infrastructure.cache.RedirectCacheService;
import com.linkforge.core.repository.LinkRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * 跳转用例的缓存行为测试（方案 5.9 场景 8~12）。
 *
 * <p>与 {@code RedirectServiceTest} 的分工：那个类注入「恒未命中」的缓存替身，
 * 专注查库 → 判定这条纯逻辑；本类注入可观测的 mock 缓存，
 * 验证<b>什么时候不查库、什么时候回填、回填什么</b>。
 *
 * <h2>⚠️ 最重要的两条不变量</h2>
 * <ol>
 *   <li><b>回源异常绝不进缓存</b>——否则一次数据库抖动会被固化成 30 秒的 404 风暴；</li>
 *   <li><b>缓存命中也必须走状态判定</b>——否则 DELETE 后最长 5 分钟仍可跳转，
 *       违反 API.md §7.1。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RedirectServiceCacheTest {

    private static final Instant NOW = Instant.parse("2026-01-15T02:30:00Z");
    private static final String CODE = "0Q1A2b3";

    @Mock
    private LinkRepository linkRepository;

    @Mock
    private RedirectCacheService cache;

    private final LinkValidator validator = new LinkValidator(
            ReservedCodes.parse(ReservedCodes.DEFAULT_CODES),
            Clock.fixed(NOW, ZoneOffset.UTC));

    /** 记录写入的短码，用于断言「回填了哪些 key」。 */
    private final List<String> putKeys = new ArrayList<>();
    private final List<String> emptyKeys = new ArrayList<>();

    private RedirectService service() {
        RedirectCacheService spy = new RedirectCacheService() {
            @Override
            public Optional<CacheEntry> get(String shortCode) {
                return cache.get(shortCode);
            }

            @Override
            public void put(String shortCode, CachedLink link) {
                putKeys.add(shortCode);
                cache.put(shortCode, link);
            }

            @Override
            public void putEmpty(String shortCode) {
                emptyKeys.add(shortCode);
                cache.putEmpty(shortCode);
            }

            @Override
            public void evict(String shortCode) {
                cache.evict(shortCode);
            }
        };
        return new RedirectService(linkRepository, validator, spy, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static Link link(LinkStatus status, LocalDateTime expireAt) {
        return new Link(1L, CODE, "https://example.com/target", status, false,
                LocalDateTime.of(2026, 1, 1, 0, 0), LocalDateTime.of(2026, 1, 1, 0, 0), expireAt);
    }

    // ---------- 场景 8：缓存命中不查库 ----------

    @Test
    @DisplayName("⭐ 场景 8：缓存命中 → 仓储零调用")
    void cacheHitSkipsRepository() {
        given(cache.get(CODE)).willReturn(Optional.of(
                new CacheEntry.Hit(new CachedLink("https://example.com/cached", "ACTIVE", null))));

        assertThat(service().resolveTargetUrl(CODE)).isEqualTo("https://example.com/cached");

        verify(linkRepository, never()).findByShortCode(anyString());
    }

    @Test
    @DisplayName("⭐ NegativeHit 命中 → 404，且**不查库**（防穿透生效）")
    void negativeHitReturns404WithoutQuery() {
        given(cache.get(CODE)).willReturn(Optional.of(new CacheEntry.NegativeHit()));

        assertThatThrownBy(() -> service().resolveTargetUrl(CODE))
                .extracting(e -> ((LinkForgeException) e).errorCode())
                .isEqualTo(ErrorCode.LINK_NOT_FOUND);

        verify(linkRepository, never()).findByShortCode(anyString());
    }

    // ---------- 场景 9/10：回源与回填 ----------

    @Test
    @DisplayName("⭐ 场景 10：两级未命中 → 回源一次并回填，携带 status 与 expireAt")
    void missQueriesOnceAndBackfills() {
        given(cache.get(CODE)).willReturn(Optional.empty());
        given(linkRepository.findByShortCode(CODE)).willReturn(Optional.of(
                link(LinkStatus.ACTIVE, LocalDateTime.parse("2027-01-01T00:00:00"))));

        service().resolveTargetUrl(CODE);

        ArgumentCaptor<CachedLink> captor = ArgumentCaptor.forClass(CachedLink.class);
        verify(cache).put(eq(CODE), captor.capture());
        CachedLink cached = captor.getValue();
        assertThat(cached.targetUrl()).isEqualTo("https://example.com/target");
        // status 与 expireAt 必须一起缓存，否则命中缓存时无法做 404/410 判定
        assertThat(cached.status()).isEqualTo("ACTIVE");
        assertThat(cached.expireAt()).isEqualTo(LocalDateTime.parse("2027-01-01T00:00:00"));
    }

    @Test
    @DisplayName("⭐ 回源 404 → 只写空值缓存（防穿透）")
    void repositoryMissWritesEmptyCache() {
        given(cache.get(CODE)).willReturn(Optional.empty());
        given(linkRepository.findByShortCode(CODE)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service().resolveTargetUrl(CODE))
                .extracting(e -> ((LinkForgeException) e).errorCode())
                .isEqualTo(ErrorCode.LINK_NOT_FOUND);

        verify(cache).putEmpty(CODE);
        verify(cache, never()).put(anyString(), any());
    }

    // ---------- 场景 11：异常绝不进缓存 ----------

    @Test
    @DisplayName("⭐⭐ 场景 11：回源抛异常 → **两级都不写**，异常向上传播")
    void repositoryFailureIsNeverCached() {
        given(cache.get(CODE)).willReturn(Optional.empty());
        given(linkRepository.findByShortCode(CODE))
                .willThrow(new org.springframework.dao.QueryTimeoutException("db timeout"));

        assertThatThrownBy(() -> service().resolveTargetUrl(CODE))
                .isInstanceOf(org.springframework.dao.QueryTimeoutException.class);

        // ⚠️ 核心断言：若这里把异常当成「不存在」缓存，
        // 一次数据库抖动会变成 30 秒的 404 风暴，比不做缓存更糟
        verify(cache, never()).putEmpty(anyString());
        verify(cache, never()).put(anyString(), any());
    }

    // ---------- 场景 12：缓存命中也必须走状态判定 ----------

    @Test
    @DisplayName("⭐⭐ 场景 12：缓存命中 DELETED → 404（不因命中而跳过判定）")
    void cachedDeletedStillReturns404() {
        given(cache.get(CODE)).willReturn(Optional.of(
                new CacheEntry.Hit(new CachedLink("https://example.com/x", "DELETED", null))));

        assertThatThrownBy(() -> service().resolveTargetUrl(CODE))
                .extracting(e -> ((LinkForgeException) e).errorCode())
                .isEqualTo(ErrorCode.LINK_NOT_FOUND);
    }

    @Test
    @DisplayName("⭐⭐ 场景 12：缓存命中已过期 → 410")
    void cachedExpiredStillReturns410() {
        given(cache.get(CODE)).willReturn(Optional.of(new CacheEntry.Hit(
                new CachedLink("https://example.com/x", "ACTIVE",
                        LocalDateTime.parse("2026-01-15T01:00:00")))));

        assertThatThrownBy(() -> service().resolveTargetUrl(CODE))
                .extracting(e -> ((LinkForgeException) e).errorCode())
                .isEqualTo(ErrorCode.LINK_EXPIRED);
    }

    @Test
    @DisplayName("⭐ 回源查到的已失效链接**也要回填**（下次命中可省一次回源）")
    void invalidatedLinkIsStillBackfilled() {
        given(cache.get(CODE)).willReturn(Optional.empty());
        given(linkRepository.findByShortCode(CODE))
                .willReturn(Optional.of(link(LinkStatus.DELETED, null)));

        assertThatThrownBy(() -> service().resolveTargetUrl(CODE))
                .extracting(e -> ((LinkForgeException) e).errorCode())
                .isEqualTo(ErrorCode.LINK_NOT_FOUND);

        // 回填了，下次就能靠缓存判定 404 而不必回源
        verify(cache).put(eq(CODE), any());
    }

    // ---------- 格式校验仍先于缓存 ----------

    @Test
    @DisplayName("⭐ 格式非法 → 400，且缓存与仓储都零调用")
    void invalidFormatShortCircuitsBeforeCache() {
        assertThatThrownBy(() -> service().resolveTargetUrl("abc_123"))
                .extracting(e -> ((LinkForgeException) e).errorCode())
                .isEqualTo(ErrorCode.INVALID_PARAMETER);

        // 非法请求连缓存都不该查——短码里出现下划线，缓存里必然没有
        verify(cache, never()).get(anyString());
        verify(linkRepository, never()).findByShortCode(anyString());
    }

}