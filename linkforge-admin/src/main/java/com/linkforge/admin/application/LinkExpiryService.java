package com.linkforge.admin.application;

import com.linkforge.core.infrastructure.cache.RedirectCacheService;
import com.linkforge.core.repository.LinkRepository.ExpiredLink;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 过期短链的缓存清理（阶段 4）。
 *
 * <h2>⚠️ 为什么本类<b>不改数据库</b>，只清缓存</h2>
 * 早期版本会把过期短链<b>软删除</b>（{@code status = DELETED}）。
 * 那会引入一个<b>对外可见的行为回退</b>：
 * <pre>
 *   清理前：status=ACTIVE + expire_at 已过 → LinkState.resolve → EXPIRED → <b>410 Gone</b>
 *   清理后：status=DELETED                    → LinkState.resolve → DELETED → <b>404 Not Found</b>
 * </pre>
 * 而 410 的全部价值就在于告诉调用方「<b>这个码曾经有效</b>」（docs/API.md §4.4、PRD US-4）。
 * 软删除会把这个信息抹掉 —— 一个纯粹的过期链接，
 * 因为被后台任务扫过一次，对外就从「曾经存在但已失效」变成了「从未存在」。
 * <p>
 * 两者<b>不可兼得</b>：要么改状态（丢 410），要么不改状态（库里仍是 ACTIVE）。
 * 本项目选择<b>不改状态</b>：
 * <ul>
 *   <li><b>保住 410 语义</b>——这是对外契约，改动它需要业务方明确同意；</li>
 *   <li><b>不需要新增 {@code EXPIRED} 枚举值</b>——改动面极小，
 *       不动表结构、不动 {@code LinkStatusTypeHandler}、不动所有 {@code status} 过滤；</li>
 *   <li>清理目的<b>依然达成</b>——过期短链的缓存被清掉，
 *       Redis 内存被释放，且用户下次请求回源查库后<b>正常返回 410</b>。</li>
 * </ul>
 *
 * <h2>⚠️ 代价：布隆过滤器<b>不会</b>淘汰过期短码</h2>
 * 布隆的定时全量重建走 {@code scanActiveWithId}——只取 {@code status = 0} 的短码。
 * 过期短链的 status 仍是 {@code ACTIVE}，所以它<b>永远留在布隆里</b>。
 * <p>
 * 后果：请求一个过期的短码时，布隆判「可能存在」→ 缓存已清（miss）→ 回源查库 → 410。
 * 这条路径比「布隆直接判不存在 → 404」多一次查库，但<b>结果完全正确</b>。
 * 代价是：过期短码多的场景下，这些短码的请求会有额外的数据库读。
 * <p>
 * 彻底解决需要让布隆能区分「有效」与「已过期」，
 * 即给 {@code LinkStatus} 增加第三值 —— 那是更大的改动，本阶段不做。
 *
 * <h2>为什么这个类是<b>独立 bean</b></h2>
 * 早期版本把清理逻辑写成 {@code ExpiryCleanupService} 的私有方法。
 * 那时它带 {@code @Transactional}，而<b>同类自调用会绕过 Spring AOP 代理</b>，
 * 导致事务根本不生效、{@code afterCommit} 不触发，
 * 缓存清理退化成「事务内立即执行」——那正是 admin 的 {@code DeleteLinkService}
 * 早就踩过的坑（并发请求在「缓存已删但事务未提交」的窗口回源，
 * 读到旧值并重新写回缓存，删除静默失效）。
 * <p>
 * 现在本类<b>不涉及数据库写</b>，没有事务需求，独立成 bean 的理由变成：
 * <b>职责单一 + 可被其它模块复用</b>（例如将来若要提供「手动清理某条短链缓存」的接口）。
 */
@Service
public class LinkExpiryService {

    private static final Logger log = LoggerFactory.getLogger(LinkExpiryService.class);

    private final RedirectCacheService cache;
    private final Counter cacheEvictFailedCounter;
    private final Counter cacheEvictedCounter;

    public LinkExpiryService(RedirectCacheService cache, MeterRegistry meterRegistry) {
        this.cache = cache;
        this.cacheEvictFailedCounter = Counter.builder("shortlink.cleanup.cache.evict.failed")
                .description("清理过期短链缓存时失败。数据库状态未变，"
                        + "但缓存可能仍返回旧值直到 TTL 到期")
                .register(meterRegistry);
        this.cacheEvictedCounter = Counter.builder("shortlink.cleanup.cache.evicted")
                .description("成功清理的过期短链缓存条目数")
                .register(meterRegistry);
    }

    /**
     * 清理一批过期短链的缓存。
     *
     * <p>⚠️ <b>不修改数据库</b>：{@code status} 保持 {@code ACTIVE}，
     * 用户请求时回源查库，由 {@code LinkState.resolve} 判定为 EXPIRED → 返回 410。
     *
     * <p>⚠️ <b>清缓存失败只记指标 + WARN，不抛异常</b>：
     * 清理失败的后果是「缓存条目多留一会儿」（TTL 到期自然消失），
     * <b>不影响任何正确性</b>——因为缓存里存的是带 status 与 expireAt 的
     * {@code CachedLink}，跳转路径命中缓存后<b>仍然要走一遍状态判定</b>。
     * 若因此让整轮任务失败，已清的短码会反复被扫到（那一轮的工作量白费了）。
     *
     * @param expired 待清理的过期短码行
     * @return 成功清理的条数
     */
    public int clearCaches(List<ExpiredLink> expired) {
        int cleared = 0;
        for (ExpiredLink link : expired) {
            try {
                cache.evict(link.shortCode());
                cleared++;
                cacheEvictedCounter.increment();
            } catch (RuntimeException e) {
                cacheEvictFailedCounter.increment();
                // ⚠️ 日志必须带短码，否则无法定位是哪条链没清掉
                log.warn("过期短链**清缓存失败**（缓存条目将留到 TTL 到期，不影响 410 语义）: "
                                + "shortCode={} {}", link.shortCode(), e.getMessage(), e);
            }
        }
        return cleared;
    }
}
