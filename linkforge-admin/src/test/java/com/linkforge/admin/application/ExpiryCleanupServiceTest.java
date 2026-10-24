package com.linkforge.admin.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.linkforge.core.infrastructure.cache.RedirectCacheService;
import com.linkforge.core.repository.LinkRepository;
import com.linkforge.core.repository.LinkRepository.ExpiredLink;
import com.linkforge.stats.infrastructure.RedisDistributedLock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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
 * {@link ExpiryCleanupService} 的行为契约（阶段 4）。
 *
 * <h2>⚠️ 本类锁的是四条红线</h2>
 * <ol>
 *   <li><b>@Scheduled 抛异常 = 该 Trigger 被永久取消且不重建</b>（阶段 3 踩过）</li>
 *   <li><b>不修改数据库</b>：短链的 status 保持 ACTIVE，过期判定交给读路径（保住 410）</li>
 *   <li><b>扫描用注入的 Clock</b>，不取服务器当前时间（ARCHITECTURE §13）</li>
 *   <li><b>用独立锁</b>，不与统计 flush 抢同一把（两个任务操作不同的数据）</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ExpiryCleanupServiceTest {

    private static final Instant NOW = Instant.parse("2026-03-20T10:00:00Z");

    private SimpleMeterRegistry registry;

    @Mock
    private LinkRepository linkRepository;
    @Mock
    private LinkExpiryService expiryService;
    @Mock
    private RedirectCacheService cache;
    @Mock
    private RedisDistributedLock lock;

    private ExpiryCleanupService service;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        // 默认：抢到锁且仍持有
        when(lock.tryLock(anyString(), any())).thenReturn(true);
        when(lock.holdsLock(anyString())).thenReturn(true);
        // 默认：没有过期短链
        when(linkRepository.scanExpired(anyInt(), any())).thenReturn(List.of());
        service = newService();
    }

    private ExpiryCleanupService newService() {
        return new ExpiryCleanupService(linkRepository, expiryService, lock,
                Clock.fixed(NOW, ZoneOffset.UTC), registry, 1000, 5, 60);
    }

    private double counter(String name) {
        var c = registry.find(name).counter();
        return c == null ? 0.0 : c.count();
    }

    private static ExpiredLink expired(long id, String code) {
        return new ExpiredLink(id, code, LocalDateTime.of(2026, 3, 19, 0, 0));
    }

    // ============================================ 扫描

    @Test
    @DisplayName("🔍⭐⭐ 扫描用注入的 Clock，**不取服务器当前时间**（可测试）")
    void scanUsesInjectedClock() {
        service.cleanup();

        verify(linkRepository).scanExpired(anyInt(), eq(NOW));
    }

    @Test
    @DisplayName("🔍b 没有过期短链时什么都不做（不扫缓存、不动数据库）")
    void noExpiredLinksDoesNothing() {
        service.cleanup();

        verify(expiryService, never()).clearCaches(any());
        assertThat(counter("shortlink.cleanup.scanned")).isZero();
    }

    @Test
    @DisplayName("🔍c 单轮最多 maxBatchesPerRun 批（无上限会长期占着锁）")
    void respectsMaxBatchesPerRun() {
        // ⚠️ 用 batchSize=2 的服务实例：mock 返回 2 条正好是**满批**，
        //    才会继续下一批。默认的 batchSize=1000 下 mock 只返回 2 条
        //    会被判为「库里没有更多了」而提前收敛。
        ExpiryCleanupService smallBatch = new ExpiryCleanupService(
                linkRepository, expiryService, lock, Clock.fixed(NOW, ZoneOffset.UTC),
                registry, 2, 5, 60);
        when(linkRepository.scanExpired(anyInt(), any()))
                .thenReturn(List.of(expired(1L, "c1"), expired(2L, "c2")));

        smallBatch.cleanup();

        // 5 批 × 每批 2 条
        verify(linkRepository, times(5)).scanExpired(anyInt(), any());
    }

    @Test
    @DisplayName("🔍d 批次不满时提前收敛（库里没有更多过期数据了）")
    void stopsEarlyWhenBatchIsNotFull() {
        when(linkRepository.scanExpired(anyInt(), any()))
                .thenReturn(List.of(expired(1L, "c1")));

        service.cleanup();

        verify(linkRepository, times(1)).scanExpired(anyInt(), any());
    }

    // ============================================ 核心契约：不改数据库

    @Test
    @DisplayName("🗑️⭐⭐⭐ **不修改数据库**——只调 clearCaches，绝不调 markDeleted")
    void neverModifiesDatabase() {
        when(linkRepository.scanExpired(anyInt(), any()))
                .thenReturn(List.of(expired(1L, "c1"), expired(2L, "c2")));
        when(expiryService.clearCaches(any())).thenReturn(2);

        service.cleanup();

        // ⚠️ 本任务的**核心契约**：短链的 status 保持 ACTIVE，
        //    过期判定交给读路径的 LinkState.resolve（返回 410）。
        //    若这里出现任何写库调用，过期短链就会被软删除成 DELETED，
        //    对外状态码会从 410 变成 404。
        verify(expiryService).clearCaches(List.of(expired(1L, "c1"), expired(2L, "c2")));
        verify(linkRepository, never()).markDeleted(anyLong());
    }

    @Test
    @DisplayName("🗑️b 清理成功的条数记在 cleared 指标上（**不是 deleted**）")
    void clearedCounterTracksSuccess() {
        when(linkRepository.scanExpired(anyInt(), any()))
                .thenReturn(List.of(expired(1L, "c1"), expired(2L, "c2")));
        when(expiryService.clearCaches(any())).thenReturn(2);

        service.cleanup();

        assertThat(counter("shortlink.cleanup.cleared")).isEqualTo(2.0);
        assertThat(counter("shortlink.cleanup.scanned")).isEqualTo(2.0);
    }

    @Test
    @DisplayName("🗑️c 清理异常时整批放弃并计 failed（不逐条重试）")
    void cleanupFailureAbortsBatch() {
        when(linkRepository.scanExpired(anyInt(), any()))
                .thenReturn(List.of(expired(1L, "c1")));
        doThrow(new RuntimeException("缓存层炸了")).when(expiryService).clearCaches(any());

        service.cleanup();

        assertThat(counter("shortlink.cleanup.failed")).isEqualTo(1.0);
        assertThat(counter("shortlink.cleanup.cleared")).isZero();
    }

    // ============================================ 分布式锁

    @Test
    @DisplayName("🔒️ 清理用**自己的锁**（不与统计 flush 抢同一把）")
    void usesItsOwnLock() {
        service.cleanup();

        verify(lock).tryLock(eq("shortlink:stats:cleanup:lock"), any());
        verify(lock).unlock("shortlink:stats:cleanup:lock");
    }

    @Test
    @DisplayName("🔒️b 抢不到锁就整轮跳过（多实例下正常，不算错误）")
    void skipsWhenLockUnavailable() {
        when(lock.tryLock(anyString(), any())).thenReturn(false);

        service.cleanup();

        verify(linkRepository, never()).scanExpired(anyInt(), any());
        verifyNoInteractions(expiryService);
    }

    @Test
    @DisplayName("🔒️c 执行前二次校验失败也放弃本轮（防并发清理）")
    void skipsWhenLockLostBeforeExecution() {
        when(lock.holdsLock(anyString())).thenReturn(false);

        service.cleanup();

        verify(linkRepository, never()).scanExpired(anyInt(), any());
    }

    @Test
    @DisplayName("🔒️d 即使抛异常也释放锁（finally）")
    void lockReleasedEvenOnFailure() {
        doThrow(new RuntimeException("boom")).when(linkRepository)
                .scanExpired(anyInt(), any());

        service.cleanup();

        verify(lock).unlock("shortlink:stats:cleanup:lock");
    }

    // ============================================ 调度健壮性

    @Test
    @DisplayName("🛡️⭐⭐⭐ cleanup() 遇到任何异常都不外抛（外抛会让调度永久失效）")
    void cleanupNeverThrows() {
        doThrow(new RuntimeException("Redis 炸了")).when(linkRepository)
                .scanExpired(anyInt(), any());
        doThrow(new NoSuchMethodError("Error 的近亲")).when(lock)
                .tryLock(anyString(), any());

        // ⚠️ 关键：不 assertThatThrownBy，「能正常返回」就是通过
        service.cleanup();

        assertThat(counter("shortlink.cleanup.failed")).isZero();
    }
}
