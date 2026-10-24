package com.linkforge.admin.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.linkforge.core.infrastructure.cache.RedirectCacheService;
import com.linkforge.core.repository.LinkRepository;
import com.linkforge.core.repository.LinkRepository.ExpiredLink;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * {@link LinkExpiryService} 的行为契约（阶段 4）。
 *
 * <h2>⚠️ 本类锁的是三条红线</h2>
 * <ol>
 *   <li><b>不改数据库</b>：短链的 status 保持 ACTIVE，
 *       过期判定交给读路径的 {@code LinkState.resolve}（保住 410 语义）</li>
 *   <li><b>清缓存失败不抛异常</b>：失败的后果只是「缓存条目多留到 TTL 到期」，
 *       <b>不影响任何正确性</b>——缓存里存的是带 status/expireAt 的
 *       {@code CachedLink}，命中后仍要走一遍状态判定</li>
 *   <li><b>一个短码失败不影响其它短码</b></li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LinkExpiryServiceTest {

    private SimpleMeterRegistry registry;

    @Mock
    private LinkRepository linkRepository;
    @Mock
    private RedirectCacheService cache;

    private LinkExpiryService service;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        service = new LinkExpiryService(cache, registry);
    }

    private static ExpiredLink expired(long id, String code) {
        return new ExpiredLink(id, code, LocalDateTime.of(2026, 3, 19, 0, 0));
    }

    private double counter(String name) {
        var c = registry.find(name).counter();
        return c == null ? 0.0 : c.count();
    }

    // ============================================ 核心契约：不改数据库

    @Test
    @DisplayName("🗑️⭐⭐⭐ **不写数据库**——只删缓存")
    void neverWritesToDatabase() {
        service.clearCaches(List.of(expired(1L, "c1")));

        // ⚠️ 核心契约：过期清理**不修改 status**。
        //    若这里软删了，对外状态码会从 410 变成 404。
        verify(linkRepository, never()).markDeleted(any(Long.class));
    }

    @Test
    @DisplayName("🗑️b 清缓存：每个短码一次 evict")
    void evictsCacheForEachLink() {
        service.clearCaches(List.of(expired(1L, "c1"), expired(2L, "c2")));

        verify(cache).evict("c1");
        verify(cache).evict("c2");
    }

    @Test
    @DisplayName("🗑️c 返回成功清理的条数")
    void returnsClearedCount() {
        assertThat(service.clearCaches(
                List.of(expired(1L, "c1"), expired(2L, "c2")))).isEqualTo(2);
    }

    @Test
    @DisplayName("🗑️d 空集合直接返回 0")
    void emptyBatchReturnsZero() {
        assertThat(service.clearCaches(List.of())).isZero();
        verify(cache, never()).evict(any());
    }

    // ============================================ 缓存清理失败

    @Test
    @DisplayName("🗑️🗑️⭐⭐ 清缓存失败**不抛异常**（失败只是多留到 TTL 到期）")
    void cacheEvictionFailureDoesNotThrow() {
        doThrow(new RuntimeException("Redis 挂了")).when(cache).evict(any());

        // ⚠️ 不抛异常就是通过
        assertThat(service.clearCaches(List.of(expired(1L, "c1")))).isZero();

        assertThat(counter("shortlink.cleanup.cache.evict.failed"))
                .as("⚠️ 清缓存失败必须可观测：运维不翻日志就看不到")
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("🗑️🗑️b 一个短码清缓存失败**不影响**其它短码")
    void oneEvictionFailureDoesNotBlockOthers() {
        doThrow(new RuntimeException("Redis 挂了")).when(cache).evict("c1");

        assertThat(service.clearCaches(
                List.of(expired(1L, "c1"), expired(2L, "c2")))).isEqualTo(1);

        verify(cache).evict("c2");
        assertThat(counter("shortlink.cleanup.cache.evict.failed")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("🗑️🗑️c 成功清理的条数记在 cache.evicted 指标上")
    void evictedCounterTracksSuccess() {
        service.clearCaches(List.of(expired(1L, "c1"), expired(2L, "c2")));

        assertThat(counter("shortlink.cleanup.cache.evicted")).isEqualTo(2.0);
    }
}
