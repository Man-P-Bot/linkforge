package com.linkforge.redirect.infrastructure.cache;

import com.linkforge.core.infrastructure.cache.BloomFilter;
import com.linkforge.core.repository.LinkRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 布隆过滤器维护：创建时写穿 + 定时全量重建（docs/ARCHITECTURE.md §7.1）。
 *
 * <h2>⚠️ 重建的双缓冲约束（DR-007）</h2>
 * 绝对禁止「先 DEL 现行 key 再建新 key」——
 * 窗口期内所有真实短码都会被判「不存在」→ 返回 404，<b>那是真实故障</b>。
 *
 * <p>正确做法是「写 :building → 填完 → {@code RENAME} 原子切换」，
 * 全程请求读的都是现行 key，<b>不存在任何窗口期查不到</b>。
 */
@Component
public class BloomFilterMaintenance {

    private static final Logger log = LoggerFactory.getLogger(BloomFilterMaintenance.class);

    /** 分批拉取的批大小。1000 万条一次拉完会 OOM。 */
    private static final int BATCH_SIZE = 1000;

    private final RedisBloomFilter bloomFilter;
    private final LinkRepository linkRepository;
    private final Counter rebuiltCounter;

    public BloomFilterMaintenance(RedisBloomFilter bloomFilter,
                                 LinkRepository linkRepository,
                                 MeterRegistry meterRegistry) {
        this.bloomFilter = bloomFilter;
        this.linkRepository = linkRepository;
        this.rebuiltCounter = Counter.builder("shortlink.bloom.rebuild")
                .description("布隆全量重建完成次数（含定时与手动）")
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
     * 定时全量重建（默认每小时一次，ARCHITECTURE.md §259）。
     *
     * <p>⏰ 用<b>固定间隔</b>而非 cron：本项目只有单实例，
     * 固定间隔在容器重启后的行为更可预测（cron 在容器里依赖时区，容易踩坑）。
     */
    @Scheduled(fixedDelayString = "${linkforge.bloom.rebuild-interval:3600000}",
            initialDelayString = "${linkforge.bloom.rebuild-initial-delay:30000}")
    public void scheduledRebuild() {
        try {
            rebuild();
        } catch (RuntimeException e) {
            // ⚠️ 定时任务抛异常会让 Spring 取消后续调度，必须自己吞掉并记录
            log.error("布隆定时重建失败（现行key 未受影响，仍服务旧数据）: {}", e.getMessage(), e);
        }
    }

    /**
     * 全量重建（双缓冲 + 原子切换）。
     *
     * @return 实际写入的短码数
     */
    public long rebuild() {
        long started = System.nanoTime();
        List<String> all = new ArrayList<>();
        long afterId = 0;
        // ⚠️ 走 scanActiveWithId（返回 id + shortCode）而不是 scanActiveShortCodes：
        // 只有前者能拿到「本批最大 id」并把它作为下一轮的游标。
        // 若用只返回短码的版本，游标只能靠批大小猜——猜错会漏扫，
        // 漏扫的布隆会把那批短码判为不存在 → 真实用户 404（DR-007 假阴性故障）。
        // 该方法只 select (id, short_code) 两列，仍走覆盖索引，不回表读 target_url。
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
        bloomFilter.rebuild(all);
        rebuiltCounter.increment();
        long costMs = (System.nanoTime() - started) / 1_000_000;
        log.info("布隆全量重建完成：{} 条，耗时 {} ms（双缓冲 + RENAME 原子切换）",
                all.size(), costMs);
        return all.size();
    }

    /**
     * 重建间隔默认值（1 小时），供文档与测试引用。
     */
    public static Duration rebuildIntervalDefault() {
        return Duration.ofHours(1);
    }
}