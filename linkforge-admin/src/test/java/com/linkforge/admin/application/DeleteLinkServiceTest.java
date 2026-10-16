package com.linkforge.admin.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.linkforge.core.domain.Link;
import com.linkforge.core.domain.LinkStatus;
import com.linkforge.core.infrastructure.cache.RedirectCacheService;
import com.linkforge.core.repository.LinkRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 删除短链用例测试（docs/API.md §7.1，DR-005）。
 *
 * <p>方案场景 13~16、18：幂等语义、清缓存顺序、清缓存失败、提交后执行。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DeleteLinkServiceTest {

    private static final String CODE = "0Q1A2b3";

    @Mock
    private LinkRepository linkRepository;

    @Mock
    private RedirectCacheService cache;

    private SimpleMeterRegistry registry;
    private DeleteLinkService service;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        service = new DeleteLinkService(linkRepository, cache, registry);
    }

    private static Link link(LinkStatus status) {
        return new Link(1L, CODE, "https://example.com/target", status, false,
                LocalDateTime.of(2026, 1, 1, 0, 0), LocalDateTime.of(2026, 1, 1, 0, 0), null);
    }

    /** 在「有事务」的上下文中调用，并执行提交后回调。 */
    private DeleteLinkService.DeleteResult callWithinTransaction(
            java.util.function.Supplier<DeleteLinkService.DeleteResult> body) {
        TransactionSynchronizationManager.initSynchronization();
        try {
            DeleteLinkService.DeleteResult result = body.get();
            // 模拟事务提交：触发所有 afterCommit 回调
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit);
            return result;
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private DeleteLinkService.DeleteResult callWithoutTransaction(
            java.util.function.Supplier<DeleteLinkService.DeleteResult> body) {
        return body.get();
    }

    @Test
    @DisplayName("⭐⭐ 场景 13：删已存在 → 200 deleted=true，且清缓存")
    void deletesExistingLink() {
        given(linkRepository.findByShortCode(CODE)).willReturn(Optional.of(link(LinkStatus.ACTIVE)));
        given(linkRepository.markDeleted(1L)).willReturn(1);

        DeleteLinkService.DeleteResult result = callWithinTransaction(
                () -> service.delete(CODE));

        assertThat(result.deleted()).isTrue();
        assertThat(result.alreadyDeleted()).isFalse();
        verify(linkRepository).markDeleted(1L);
        verify(cache).evict(CODE);
    }

    @Test
    @DisplayName("⭐⭐ 场景 14：**重复删** → 200 + alreadyDeleted=true，**不是 404**")
    void repeatedDeleteIsIdempotent() {
        given(linkRepository.findByShortCode(CODE))
                .willReturn(Optional.of(link(LinkStatus.DELETED)));

        DeleteLinkService.DeleteResult result = callWithinTransaction(
                () -> service.delete(CODE));

        // ⚠️ DELETE 的语义是「确保它不存在」。返回 404 会让客户端以为失败而重试，
        // 而库里其实已经删掉了
        assertThat(result.deleted()).isTrue();
        assertThat(result.alreadyDeleted()).isTrue();
        verify(linkRepository, never()).markDeleted(anyLong());
    }

    @Test
    @DisplayName("⭐ 场景 15：删不存在的短码 → 200 + alreadyDeleted=true")
    void deletingMissingLinkSucceeds() {
        given(linkRepository.findByShortCode(CODE)).willReturn(Optional.empty());

        DeleteLinkService.DeleteResult result = callWithinTransaction(
                () -> service.delete(CODE));

        assertThat(result.deleted()).isTrue();
        assertThat(result.alreadyDeleted()).isTrue();
        verify(cache, never()).evict(CODE);
    }

    @Test
    @DisplayName("⭐ 场景：已是删除态时**不重复清缓存**")
    void alreadyDeletedDoesNotEvictAgain() {
        given(linkRepository.findByShortCode(CODE))
                .willReturn(Optional.of(link(LinkStatus.DELETED)));

        callWithinTransaction(() -> service.delete(CODE));

        verify(cache, never()).evict(CODE);
    }

    @Test
    @DisplayName("⭐⭐ 场景 18：清缓存在**事务提交后**才执行")
    void evictRunsAfterCommit() {
        given(linkRepository.findByShortCode(CODE)).willReturn(Optional.of(link(LinkStatus.ACTIVE)));
        given(linkRepository.markDeleted(1L)).willReturn(1);

        TransactionSynchronizationManager.initSynchronization();
        try {
            service.delete(CODE);
            // ⚠️ 事务还没提交：此时缓存**必须还没清**
            // 若此时清了，并发请求会在「缓存已删但事务未提交」的窗口回源，
            // 读到未删除的旧值并重新写入缓存，删除就静默失效了
            verify(cache, never()).evict(CODE);

            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit);
            verify(cache).evict(CODE);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("⭐ 无事务上下文时退化为立即清缓存（不能不清）")
    void evictsImmediatelyWithoutTransaction() {
        given(linkRepository.findByShortCode(CODE)).willReturn(Optional.of(link(LinkStatus.ACTIVE)));
        given(linkRepository.markDeleted(1L)).willReturn(1);

        service.delete(CODE);

        verify(cache).evict(CODE);
    }

    @Test
    @DisplayName("⭐⭐ 场景 16：清缓存失败 → **不抛异常**，指标累加")
    void evictFailureIsRecordedNotThrown() {
        given(linkRepository.findByShortCode(CODE)).willReturn(Optional.of(link(LinkStatus.ACTIVE)));
        given(linkRepository.markDeleted(1L)).willReturn(1);
        doThrow(new IllegalStateException("redis down")).when(cache).evict(CODE);

        // ⚠️ 库已改成功，删除这件事是完成的。
        // 若因此抛异常，客户端会以为失败而重试，而库里已经是删的了
        DeleteLinkService.DeleteResult result = callWithoutTransaction(() -> service.delete(CODE));

        assertThat(result.deleted()).isTrue();
        assertThat(registry.get("shortlink.cache.evict.failed").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("⭐ 清缓存成功时 evict.failed 指标保持 0")
    void evictSuccessDoesNotIncrementFailureCounter() {
        given(linkRepository.findByShortCode(CODE)).willReturn(Optional.of(link(LinkStatus.ACTIVE)));
        given(linkRepository.markDeleted(1L)).willReturn(1);

        callWithoutTransaction(() -> service.delete(CODE));

        // ⚠️ Micrometer 的 registry.get() 在指标未注册时返回的是
        // 一个 RequiredSearch 对象而不是 null，直接 isNull() 会失败（踩过两次）。
        // 计数器在构造时已 register，因此 count 为 0 就是「一次都没失败」
        assertThat(registry.get("shortlink.cache.evict.failed").counter().count())
                .isEqualTo(0.0);
    }

    @Test
    @DisplayName("⭐ markDeleted 影响 0 行（并发下被抢先）→ 也算目标达成")
    void concurrentDeleteStillReportsSuccess() {
        given(linkRepository.findByShortCode(CODE)).willReturn(Optional.of(link(LinkStatus.ACTIVE)));
        given(linkRepository.markDeleted(1L)).willReturn(0);

        DeleteLinkService.DeleteResult result = callWithoutTransaction(
                () -> service.delete(CODE));

        assertThat(result.deleted()).isTrue();
        assertThat(result.alreadyDeleted()).isTrue();
    }

    @Test
    @DisplayName("⭐ 清的是**库里的短码**而非请求里的字符串（防编码绕过）")
    void evictsCodeFromDatabaseNotFromRequest() {
        // 请求串大小写不同，但库里存的是另一个值
        Link stored = new Link(1L, CODE, "https://example.com/target", LinkStatus.ACTIVE,
                false, LocalDateTime.of(2026, 1, 1, 0, 0), LocalDateTime.of(2026, 1, 1, 0, 0), null);
        given(linkRepository.findByShortCode("0q1a2b3")).willReturn(Optional.of(stored));
        given(linkRepository.markDeleted(1L)).willReturn(1);

        callWithoutTransaction(() -> service.delete("0q1a2b3"));

        verify(cache).evict(CODE);
    }
}