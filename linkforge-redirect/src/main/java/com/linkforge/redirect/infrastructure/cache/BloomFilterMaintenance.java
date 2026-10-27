package com.linkforge.redirect.infrastructure.cache;

import com.linkforge.core.repository.LinkRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 布隆过滤器维护：创建时写穿 + 启动重建 + 定时全量重建（docs/ARCHITECTURE.md §7.1）。
 *
 * <h2>⚠️ 重建的双缓冲约束（DR-007）</h2>
 * 绝对禁止「先 DEL 现行 key 再建新 key」——
 * 窗口期内所有真实短码都会被判「不存在」→ 返回 404，<b>那是真实故障</b>。
 *
 * <p>正确做法是「写 :building → 填完 → {@code RENAME} 原子切换」，
 * 全程请求读的都是现行 key，<b>不存在任何窗口期查不到</b>。
 *
 * <h2>⚠️⚠️ 重建顺序：开窗口 → 扫描 → 切换（DR-065）</h2>
 * 双缓冲只保证「重建期间现行 key 不被删」，<b>不保证「重建期间的写穿不被吃掉」</b>：
 * 新短码在重建期间落库时，写穿把它的位写进现行 key，
 * 而紧接着的 RENAME 会用「切换前的快照」替换现行 key——快照里没有它，
 * 它的位随旧 key 一起消失 → <b>该短码从此 404，直到下一次全量重建才自愈</b>。
 *
 * <p>所以本类的 {@link #rebuild()} 严格按这个顺序执行：
 * <pre>
 *   ① bloomFilter.beginRebuild()  // 删 :building + 打开写穿窗口
 *   ② 扫描数据库                   // 扫描期间新建的短码会同时写穿到 :building
 *   ③ bloomFilter.rebuild(all)     // 写位 + RENAME 原子切换
 *   ④ bloomFilter.endRebuild()    // 关窗口 + 清理残留
 * </pre>
 * ①②的顺序颠倒会重新引入 404 缺陷，详见 {@link RedisBloomFilter} 的类注释。
 *
 * <h2>⚠️ 启动即重建（DR-065）</h2>
 * 重建此前只靠 {@code @Scheduled} 的 {@code initialDelay}（30 秒）触发，
 * 叠加实测 28 秒的重建耗时 → <b>应用启动后近 1 分钟布隆都是旧的</b>，
 * 这段时间里创建的短码正好落在重建窗口内 → 404。
 * 现在由 {@link ApplicationReadyEvent} 在启动完成后立即重建一次，
 * 定时任务只负责之后的周期性维护。
 */
@Component
public class BloomFilterMaintenance {

    private static final Logger log = LoggerFactory.getLogger(BloomFilterMaintenance.class);

    /** 分批拉取的批大小。1000 万条一次拉完会 OOM。 */
    private static final int BATCH_SIZE = 1000;

    private final RedisBloomFilter bloomFilter;
    private final LinkRepository linkRepository;
    private final Counter rebuiltCounter;
    private final Counter failedCounter;

    /**
     * 重建互斥。
     *
     * <p>⚠️ 定时任务与启动重建可能撞车（启动后立刻重建 + 定时任务同时触发）。
     * 两个重建并发时：后一个的 {@code beginRebuild()} 会把前一个写的 {@code :building}
     * 删掉 → 前一个的快照作废。虽然不至于产生假阴性（快照会被重新扫一遍），
     * 但会白白多扫一遍全库，且日志会互相打架。
     */
    private final AtomicBoolean rebuilding = new AtomicBoolean(false);

    public BloomFilterMaintenance(RedisBloomFilter bloomFilter,
                                 LinkRepository linkRepository,
                                 MeterRegistry meterRegistry) {
        this.bloomFilter = bloomFilter;
        this.linkRepository = linkRepository;
        this.rebuiltCounter = Counter.builder("shortlink.bloom.rebuild")
                .description("布隆全量重建完成次数（含定时与手动）")
                .register(meterRegistry);
        this.failedCounter = Counter.builder("shortlink.bloom.rebuild.failed")
                .description("布隆全量重建失败次数。失败时现行 key 未受影响，仍服务旧数据")
                .register(meterRegistry);
    }

    /**
     * 创建短链后写穿布隆。
     *
     * <p>⚠️ <b>失败只记日志不阻塞创建</b>（ARCHITECTURE.md §259）：
     * 布隆是性能优化而非正确性来源。写失败的后果是「新短码下次被布隆挡掉 → 404」，
     * 直到下次重建自愈。反过来做（写失败就回滚创建）会让布隆从优化变成单点依赖。
     */
    public void writeThrough(String shortCode) {
        bloomFilter.put(shortCode);
    }

    /**
     * 启动完成后立即重建一次（DR-065）。
     *
     * <p>⚠️ 为什么不能只靠定时任务：{@code @Scheduled} 的 {@code initialDelay}
     * 让重建推迟到启动后 30 秒，而重建本身还要几十秒——
     * 「重启后访问旧短码」在这段时间里依赖的是**上一个实例留下的旧布隆**，
     * 一旦那个布隆是空的（比如 Redis 也重启了），旧短码就会集体 404。
     *
     * <p>⚠️ 失败只记 ERROR，**不阻止启动**：布隆是优化，缺了它服务仍可用
     * （检查脚本在 key 不存在时放行，见 {@link RedisBloomFilter}）。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void rebuildOnStartup() {
        log.info("应用启动完成，开始布隆全量重建（初始化）");
        try {
            long count = rebuild();
            if (count == 0) {
                log.info("布隆初始化重建：库中没有有效短码，布隆保持未初始化状态"
                        + "（检查时会放行，不影响跳转）");
            }
        } catch (RuntimeException e) {
            failedCounter.increment();
            log.error("布隆初始化重建失败，现行 key 未受影响（仍服务旧数据）: {}", e.getMessage(), e);
        }
    }

    /**
     * 定时全量重建（默认每小时一次，ARCHITECTURE.md §259）。
     *
     * <p>⏰ 用<b>固定间隔</b>而非 cron：本项目只有单实例，
     * 固定间隔在容器重启后的行为更可预测（cron 在容器里依赖时区，容易踩坑）。
     *
     * <p>⚠️ {@code initialDelay} 默认取<b>重建间隔</b>（1 小时）而不是 0：
     * 启动后的第一次重建由 {@link #rebuildOnStartup()} 负责，
     * 这里再立刻跑一次只是白扫一遍全库。
     */
    @Scheduled(fixedDelayString = "${linkforge.bloom.rebuild-interval:3600000}",
            initialDelayString = "${linkforge.bloom.rebuild-initial-delay:3600000}")
    public void scheduledRebuild() {
        try {
            rebuild();
        } catch (RuntimeException e) {
            // ⚠️ 定时任务抛异常会让 Spring 取消后续调度，必须自己吞掉并记录
            failedCounter.increment();
            log.error("布隆定时重建失败（现行key 未受影响，仍服务旧数据）: {}", e.getMessage(), e);
        }
    }

    /**
     * 全量重建（双缓冲 + 原子切换）。
     *
     * @return 实际写入的短码数；{@code 0} 表示「库中没有有效短码，已跳过切换」
     */
    public long rebuild() {
        if (!rebuilding.compareAndSet(false, true)) {
            log.warn("已有布隆重建在进行中，跳过本次重建");
            return 0;
        }
        long started = System.nanoTime();
        try {
            try {
                // ⚠️ ① 必须**先**开窗口再扫描（DR-065）：
                //    窗口期内的写穿会同时落到 :building，RENAME 切换后不会丢。
                // ⚠️ beginRebuild 放在 try **内部**：它自己若抛异常（Redis 不可达），
                //    endRebuild 也必须执行 —— 否则窗口标志永远停在 true，
                //    之后**每一次**写穿都会双写，:building 无限增长。
                //    endRebuild 是幂等的（置 false + 删 key），先执行也无害。
                bloomFilter.beginRebuild();
                List<String> all = scanAllActiveCodes();
                // ⚠️ ③ 写位 + RENAME 原子切换
                long written = bloomFilter.rebuild(all);
                if (written == 0) {
                    return 0;
                }
                rebuiltCounter.increment();
                long costMs = (System.nanoTime() - started) / 1_000_000;
                log.info("布隆全量重建完成：{} 条，耗时 {} ms（双缓冲 + RENAME 原子切换）",
                        written, costMs);
                return written;
            } finally {
                // ⚠️ ④ 关窗口 + 清理残留 :building
                bloomFilter.endRebuild();
            }
        } finally {
            rebuilding.set(false);
        }
    }

    /**
     * 分批扫描全部有效短码。
     *
     * <p>⚠️ 走 scanActiveWithId（返回 id + shortCode）而不是 scanActiveShortCodes：
     * 只有前者能拿到「本批最大 id」并把它作为下一轮的游标。
     * 若用只返回短码的版本，游标只能靠批大小猜——猜错会漏扫，
     * 漏扫的布隆会把那批短码判为不存在 → 真实用户 404（DR-007 假阴性故障）。
     * 该方法只 select (id, short_code) 两列，仍走覆盖索引，不回表读 target_url。
     */
    private List<String> scanAllActiveCodes() {
        List<String> all = new ArrayList<>();
        long afterId = 0;
        while (true) {
            List<LinkRepository.IdShortCode> batch =
                    linkRepository.scanActiveWithId(afterId, BATCH_SIZE);
            if (batch.isEmpty()) {
                break;
            }
            for (LinkRepository.IdShortCode row : batch) {
                all.add(row.shortCode());
            }
            // ⚠️ 游标必须是**本批最大 id**（ORDER BY id 保证升序，取最后一个）
            afterId = batch.get(batch.size() - 1).id();
            if (batch.size() < BATCH_SIZE) {
                break;
            }
        }
        return all;
    }

    /**
     * 重建间隔默认值（1 小时），供文档与测试引用。
     */
    public static Duration rebuildIntervalDefault() {
        return Duration.ofHours(1);
    }
}
