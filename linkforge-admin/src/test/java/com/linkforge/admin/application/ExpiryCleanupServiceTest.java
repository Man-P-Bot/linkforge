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
import org.springframework.data.redis.core.StringRedisTemplate;

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
    @Mock
    private StringRedisTemplate redis;
    @Mock
    private org.springframework.data.redis.core.ValueOperations<String, String> valueOps;

    private ExpiryCleanupService service;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        // 默认：抢到锁且仍持有
        when(lock.tryLock(anyString(), any())).thenReturn(true);
        when(lock.holdsLock(anyString())).thenReturn(true);
        // 默认：没有过期短链
        when(linkRepository.scanExpired(anyInt(), any(), anyLong())).thenReturn(List.of());
        // 默认：没有游标（从头开始扫）
        // ⚠️ 必须 stub opsForValue()：它返回 null 时链式调用直接 NPE，
        //    报错点会落在 setUp 而不是「没有游标」的语义上，很难定位。
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn(null);
        service = newService();
    }

    private ExpiryCleanupService newService() {
        return new ExpiryCleanupService(linkRepository, expiryService, lock, redis,
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

        verify(linkRepository).scanExpired(anyInt(), eq(NOW), eq(0L));
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
                linkRepository, expiryService, lock, redis, Clock.fixed(NOW, ZoneOffset.UTC),
                registry, 2, 5, 60);
        when(linkRepository.scanExpired(anyInt(), any(), anyLong()))
                .thenReturn(List.of(expired(1L, "c1"), expired(2L, "c2")));

        smallBatch.cleanup();

        // 5 批 × 每批 2 条
        verify(linkRepository, times(5)).scanExpired(anyInt(), any(), anyLong());
    }

    @Test
    @DisplayName("🔍d 批次不满时提前收敛（库里没有更多过期数据了）")
    void stopsEarlyWhenBatchIsNotFull() {
        when(linkRepository.scanExpired(anyInt(), any(), anyLong()))
                .thenReturn(List.of(expired(1L, "c1")));

        service.cleanup();

        verify(linkRepository, times(1)).scanExpired(anyInt(), any(), anyLong());
    }

    // ============================================ 核心契约：不改数据库

    @Test
    @DisplayName("🗑️⭐⭐⭐ **不修改数据库**——只调 clearCaches，绝不调 markDeleted")
    void neverModifiesDatabase() {
        when(linkRepository.scanExpired(anyInt(), any(), anyLong()))
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
        when(linkRepository.scanExpired(anyInt(), any(), anyLong()))
                .thenReturn(List.of(expired(1L, "c1"), expired(2L, "c2")));
        when(expiryService.clearCaches(any())).thenReturn(2);

        service.cleanup();

        assertThat(counter("shortlink.cleanup.cleared")).isEqualTo(2.0);
        assertThat(counter("shortlink.cleanup.scanned")).isEqualTo(2.0);
    }

    @Test
    @DisplayName("🗑️c 清理异常时整批放弃并计 failed（不逐条重试）")
    void cleanupFailureAbortsBatch() {
        when(linkRepository.scanExpired(anyInt(), any(), anyLong()))
                .thenReturn(List.of(expired(1L, "c1")));
        doThrow(new RuntimeException("缓存层炸了")).when(expiryService).clearCaches(any());

        service.cleanup();

        assertThat(counter("shortlink.cleanup.failed")).isEqualTo(1.0);
        assertThat(counter("shortlink.cleanup.cleared")).isZero();
    }

    // ============================================ 扫描游标（正确性必需）

    @Test
    @DisplayName("🎚️⭐⭐⭐ 游标推进：本批最大 id 被写回，**下一轮从它之后开始**")
    void cursorAdvancesToLastIdOfBatch() {
        when(linkRepository.scanExpired(anyInt(), any(), anyLong()))
                .thenReturn(List.of(expired(10L, "c1"), expired(20L, "c2")));
        when(expiryService.clearCaches(any())).thenReturn(2);

        service.cleanup();

        // ⚠️ 核心断言：游标写到**本批最大 id**。
        //    若不写游标，每轮都会扫到同一批（id 最小的那些），
        //    后面几万条过期短码的缓存**永远清不到**。
        verify(redis.opsForValue()).set(
                eq("shortlink:stats:cleanup:cursor:" + today()),
                eq("20"),
                eq(com.linkforge.stats.infrastructure.StatsKeys.KEY_TTL));
    }

    @Test
    @DisplayName("🎚️b 游标在**清缓存之后**推进（先推进再清，中途崩溃会跳过这一批）")
    void cursorIsWrittenAfterCacheEviction() {
        when(linkRepository.scanExpired(anyInt(), any(), anyLong()))
                .thenReturn(List.of(expired(10L, "c1")));
        when(expiryService.clearCaches(any())).thenReturn(1);

        service.cleanup();

        // ⚠️ 顺序断言：先清缓存，再写游标。
        //    若先写游标再清缓存，中途崩溃会跳过这一批 ——
        //    那批短码的缓存永远清不到（因为下次扫描会从游标之后开始）。
        var order = org.mockito.Mockito.inOrder(expiryService, redis.opsForValue());
        order.verify(expiryService).clearCaches(any());
        order.verify(redis.opsForValue()).set(anyString(), anyString(),
                any(java.time.Duration.class));
    }

    @Test
    @DisplayName("🎚️c 已有游标时，扫描**从游标之后**开始（不重复处理已扫过的）")
    void scanStartsFromExistingCursor() {
        when(redis.opsForValue().get(anyString())).thenReturn("500");
        when(linkRepository.scanExpired(anyInt(), any(), anyLong())).thenReturn(List.of());

        service.cleanup();

        verify(linkRepository).scanExpired(anyInt(), eq(NOW), eq(500L));
    }

    @Test
    @DisplayName("🎚️d 游标在 Redis 里**持久化**（不是内存）——重启后不从头扫")
    void cursorIsPersistedInRedis() {
        when(linkRepository.scanExpired(anyInt(), any(), anyLong()))
                .thenReturn(List.of(expired(10L, "c1")));
        when(expiryService.clearCaches(any())).thenReturn(1);

        service.cleanup();

        // ⚠️ 必须写 Redis（带 TTL），不能只放内存字段。
        //    存内存的话，进程重启后从头扫 —— id 最小的那批被反复清理，
        //    而中间那段永远扫不到。
        verify(redis.opsForValue()).set(
                eq("shortlink:stats:cleanup:cursor:" + today()),
                eq("10"),
                eq(com.linkforge.stats.infrastructure.StatsKeys.KEY_TTL));
    }

    @Test
    @DisplayName("🎚️e 读游标失败时**从头扫**（不能中断清理）")
    void cursorReadFailureFallsBackToZero() {
        when(redis.opsForValue().get(anyString()))
                .thenThrow(new RuntimeException("Redis 挂了"));
        when(linkRepository.scanExpired(anyInt(), any(), anyLong())).thenReturn(List.of());

        service.cleanup();

        verify(linkRepository).scanExpired(anyInt(), eq(NOW), eq(0L));
    }

    @Test
    @DisplayName("🎚️f 写游标失败只记日志，**不中断清理**（重复清一批幂等无害）")
    void cursorWriteFailureDoesNotAbortCleanup() {
        when(linkRepository.scanExpired(anyInt(), any(), anyLong()))
                .thenReturn(List.of(expired(10L, "c1")));
        when(expiryService.clearCaches(any())).thenReturn(1);
        // ⚠️ 对 valueOps 而不是 redis.opsForValue()：
        //    后者每次调用都返回 mock，再链式 .set() 会让 Mockito 认为
        //    前一个 stub 没完成（UnfinishedStubbing）。
        doThrow(new RuntimeException("Redis 挂了")).when(valueOps)
                .set(anyString(), anyString(), any(java.time.Duration.class));

        service.cleanup();

        // 缓存已经清了（清理目的达成），游标写失败只是下一轮会重复一批
        verify(expiryService).clearCaches(any());
    }

    private static String today() {
        return java.time.LocalDate.of(2026, 3, 20)
                .format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
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

        verify(linkRepository, never()).scanExpired(anyInt(), any(), anyLong());
        verifyNoInteractions(expiryService);
    }

    @Test
    @DisplayName("🔒️c 执行前二次校验失败也放弃本轮（防并发清理）")
    void skipsWhenLockLostBeforeExecution() {
        when(lock.holdsLock(anyString())).thenReturn(false);

        service.cleanup();

        verify(linkRepository, never()).scanExpired(anyInt(), any(), anyLong());
    }

    @Test
    @DisplayName("🔒️d 即使抛异常也释放锁（finally）")
    void lockReleasedEvenOnFailure() {
        doThrow(new RuntimeException("boom")).when(linkRepository)
                .scanExpired(anyInt(), any(), anyLong());

        service.cleanup();

        verify(lock).unlock("shortlink:stats:cleanup:lock");
    }

    // ============================================ 调度健壮性

    @Test
    @DisplayName("🛡️⭐⭐⭐ cleanup() 遇到任何异常都不外抛（外抛会让调度永久失效）")
    void cleanupNeverThrows() {
        doThrow(new RuntimeException("Redis 炸了")).when(linkRepository)
                .scanExpired(anyInt(), any(), anyLong());
        doThrow(new NoSuchMethodError("Error 的近亲")).when(lock)
                .tryLock(anyString(), any());

        // ⚠️ 关键：不 assertThatThrownBy，「能正常返回」就是通过
        service.cleanup();

        assertThat(counter("shortlink.cleanup.failed")).isZero();
    }
}
