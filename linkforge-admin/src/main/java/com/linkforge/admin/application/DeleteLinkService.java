package com.linkforge.admin.application;

import com.linkforge.core.domain.Link;
import com.linkforge.core.infrastructure.cache.RedirectCacheService;
import com.linkforge.core.repository.LinkRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 删除短链用例（docs/API.md §7.1，DR-005）。
 *
 * <h2>Cache Aside 顺序：先改库 → <b>事务提交后</b>删缓存</h2>
 * <pre>
 *   1. 事务内：UPDATE status = DELETED
 *   2. 事务提交 ← ⚠️ 必须在提交之后
 *   3. 删本地缓存   ← 先本地
 *   4. 删 Redis 缓存
 * </pre>
 *
 * <p>⚠️ <b>第 2 步的时机是本用例最容易错的地方</b>：
 * 若在事务内删缓存，并发请求会在「缓存已删但事务未提交」的窗口回源，
 * 读到**未删除的旧值**并重新写入缓存——删除就静默失效了，
 * 而监控上一切正常。
 *
 * <p>用 {@link TransactionSynchronization#afterCommit} 挂到提交回调上。
 *
 * <h2>为什么「不改缓存」而是删缓存（PRD D-7）</h2>
 * 逻辑删除只改 status，缓存里存的是整条 {@code CachedLink}。
 * 「改缓存」需要精确定位并修改序列化串里的 status 字段，
 * 还要处理「缓存里的值与库里的值不一致」的边界。
 * 删缓存让下次读走回源，代价是<b>一次多余查库</b>，简单且不会算错。
 */
@Service
public class DeleteLinkService {

    private static final Logger log = LoggerFactory.getLogger(DeleteLinkService.class);

    private final LinkRepository linkRepository;
    private final RedirectCacheService cache;
    private final Counter evictFailedCounter;

    public DeleteLinkService(LinkRepository linkRepository,
                             RedirectCacheService cache,
                             MeterRegistry meterRegistry) {
        this.linkRepository = linkRepository;
        this.cache = cache;
        // ⚠️ 删缓存失败必须可观测：库已改成功但缓存没清，会出现「删了还能跳」
        this.evictFailedCounter = Counter.builder("shortlink.cache.evict.failed")
                .description("删除短链后清理缓存失败。库已改成功，但缓存仍可能返回旧值，"
                        + "直到 TTL 到期。持续出现应告警")
                .register(meterRegistry);
    }

    /**
     * 删除短链（逻辑删除）。
     *
     * <h2>⚠️ 幂等语义：重复删除返回 <b>200</b>，不是 404</h2>
     * DELETE 的语义是「确保它不存在」。第一次删和第十次删，事后状态相同，
     * 因此都应成功。返回 404 会让客户端以为操作失败而重试——
     * 而库里其实已经删掉了。
     *
     * @param shortCode 短码
     * @return 删除结果
     */
    @Transactional
    public DeleteResult delete(String shortCode) {
        Link link = linkRepository.findByShortCode(shortCode).orElse(null);

        if (link == null) {
            // 记录不存在：满足「确保它不存在」的目标，返回 200 + alreadyDeleted
            return new DeleteResult(true, true);
        }
        if (link.status() == com.linkforge.core.domain.LinkStatus.DELETED) {
            // 已是删除态：同样满足目标，不重复写库、不重复清缓存
            return new DeleteResult(true, true);
        }

        int affected = linkRepository.markDeleted(link.id());
        if (affected != 1) {
            // 影响行数异常（并发下被别的请求先删了）：目标已达成
            return new DeleteResult(true, true);
        }

        registerCacheEvictionAfterCommit(shortCode, link.shortCode());
        return new DeleteResult(true, false);
    }

    /**
     * 把缓存清理挂到<b>事务提交后</b>。
     *
     * <p>⚠️ 若当前没有事务（理论上不会，{@code @Transactional} 保证有），
     * 退化为立即执行——宁可时机不完美，也不能不清缓存。
     */
    private void registerCacheEvictionAfterCommit(String requestedCode, String actualCode) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            evictCache(actualCode, requestedCode);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                evictCache(actualCode, requestedCode);
            }
        });
    }

    /**
     * 清缓存。
     *
     * <p>⚠️ <b>失败只记指标 + WARN，不抛异常</b>（用户决策）。
     * 库已改成功，删除这件事本身是完成的；若因此返回 5xx，
     * 客户端会重试，而库里已经是删的了——重试只会制造更多无效请求。
     * 代价是「删了还能跳一会儿」，由 TTL 兜底（第 5 周加告警规则监控该指标）。
     *
     * <p><b>三件事必做</b>：指标累加 + 含短码的 WARN 日志 + 第 5 周告警规则。
     */
    private void evictCache(String actualCode, String requestedCode) {
        try {
            cache.evict(actualCode);
            log.info("短链已删除并清缓存: {}", actualCode);
        } catch (RuntimeException e) {
            evictFailedCounter.increment();
            // ⚠️ 日志必须带短码，否则无法定位是哪条链没清掉
            log.warn("短链已删除但**清缓存失败**（库已改成功，缓存可能仍返回旧值直到 TTL 到期）: "
                            + "shortCode={} requested={} {}",
                    actualCode, requestedCode, e.getMessage(), e);
        }
    }

    /**
     * 删除结果。
     *
     * @param deleted 是否已处于删除态（true 表示已删除或本就是删除态）
     * @param alreadyDeleted 本次调用是否**没有实际改变**任何状态
     */
    public record DeleteResult(boolean deleted, boolean alreadyDeleted) {
    }
}