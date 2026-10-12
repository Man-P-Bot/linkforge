package com.linkforge.redirect.infrastructure.id;

import com.linkforge.core.repository.ShortCodeGenerator;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 组合发号器：优先 Redis，失败即永久降级到数据库号段（docs/DECISIONS.md DR-022）。
 *
 * <h2>单向降级（有意设计）</h2>
 * 一旦 Redis 发号失败，本实例**永久**切到号段发号，**不做恢复探测、不切回**。
 * 理由：
 * <ol>
 *   <li>切回 Redis 需要把号段已用到的位置同步回 Redis 计数器，否则 Redis 会从旧值继续
 *       ——而那段区间已被号段用过，**必然重复发号**。跨存储同步的复杂度远高于收益；</li>
 *   <li>不引入熔断器的三态状态机（DR-010 同思路）：每次请求独立尝试、失败即降级，
 *       逻辑最简单、故障面最小。</li>
 * </ol>
 *
 * ⚠️ <b>副作用（已知并接受）</b>：一次短暂的 Redis 抖动，会让**该实例之后的所有发号
 * 永久走数据库号段**，代价是每次约每 {@code step} 个号一次数据库往返（写路径低频，可接受）。
 * 重启实例即可回到 Redis 路径。
 *
 * <h2>降级不影响唯一性</h2>
 * 两个发号器使用 {@link com.linkforge.core.domain.IdSpace} 划分的**互不重叠区间**，
 * 因此降级期间发出的号不会与 Redis 已发出的号重复，无需任何同步。
 */
public class FallbackShortCodeGenerator implements ShortCodeGenerator {

    private static final Logger log = LoggerFactory.getLogger(FallbackShortCodeGenerator.class);

    private final ShortCodeGenerator primary;
    private final ShortCodeGenerator fallback;

    /** 是否已永久降级。用 volatile 保证多线程可见性（读写都是原子操作，无需加锁）。 */
    private volatile boolean degraded = false;

    /** 降级发生次数，仅用于日志与排查（真正的告警指标是插入冲突计数）。 */
    private final AtomicLong degradeCount = new AtomicLong();

    public FallbackShortCodeGenerator(ShortCodeGenerator primary, ShortCodeGenerator fallback) {
        this.primary = primary;
        this.fallback = fallback;
    }

    @Override
    public String nextShortCode() {
        if (degraded) {
            return fallback.nextShortCode();
        }
        try {
            return primary.nextShortCode();
        } catch (RuntimeException e) {
            // 并发下多个线程可能同时走到这里，导致多打几条日志、浪费几个号段内的号，
            // 但不影响正确性：唯一性由区间划分 + 唯一索引保证。
            degradeCount.incrementAndGet();
            degraded = true;
            log.error("Redis 发号失败，已永久降级到数据库号段发号。"
                    + "该实例重启前不再尝试 Redis（见 DR-022）。原因: {}", e.getMessage(), e);
            return fallback.nextShortCode();
        }
    }

    /** 当前是否处于降级状态。 */
    public boolean isDegraded() {
        return degraded;
    }

    /** 降级发生次数。 */
    public long degradeCount() {
        return degradeCount.get();
    }
}
