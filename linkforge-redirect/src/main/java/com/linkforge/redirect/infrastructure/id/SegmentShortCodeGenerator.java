package com.linkforge.redirect.infrastructure.id;

import com.linkforge.core.domain.Base62;
import com.linkforge.core.domain.IdSpace;
import com.linkforge.core.repository.ShortCodeGenerator;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 降级路径发号器：数据库号段（docs/DECISIONS.md DR-011 / DR-022）。
 *
 * <p>区间：{@code [IdSpace.SEGMENT_FIRST_SEQUENCE, IdSpace.MAX_SEQUENCE]}，
 * 与 Redis 区间不重叠，因此降级期间发出的号不会与主路径重复。
 *
 * <h2>取段与推进</h2>
 * <ol>
 *   <li>进程内维护 {@code [current, limit)} 区间；{@code current < limit} 时直接自增，不访问数据库；</li>
 *   <li>段用尽时取下一段：{@code UPDATE id_segment SET max_id = max_id + step}
 *       → 本次可用区间为 {@code [oldMaxId + 1, newMaxId]}。</li>
 * </ol>
 * 取段委托给 {@link IdSegmentAllocator}，由它保证「独立事务、立即提交」。
 *
 * <h2>⚠️ 自增必须「先取后判」，不能「先判后取」</h2>
 * <pre>
 *   ❌ if (current.get() &lt; limit) { return current.incrementAndGet(); }   // 判断与自增之间有竞态窗口
 *   ✅ long next = current.getAndIncrement(); if (next &gt;= limit) { 取下一段 }
 * </pre>
 * 错误写法下两个线程可能同时通过判断，于是**同一个号被发出两次**。
 * 这是本类评审时最该盯的一行。
 */
public class SegmentShortCodeGenerator implements ShortCodeGenerator {

    private static final Logger log = LoggerFactory.getLogger(SegmentShortCodeGenerator.class);

    private final IdSegmentAllocator allocator;
    private final int step;

    /** 下一个待发出的号。从 {@code SEGMENT_FIRST_SEQUENCE - 1} 起，使首个 {@code getAndIncrement} 返回区间起点。 */
    private final AtomicLong current = new AtomicLong(IdSpace.SEGMENT_FIRST_SEQUENCE - 1);

    /** 当前段上界（不含）：{@code current < limit} 时无需取段。初值使首次调用必然取段。 */
    private final AtomicLong limit = new AtomicLong(IdSpace.SEGMENT_FIRST_SEQUENCE - 1);

    public SegmentShortCodeGenerator(IdSegmentAllocator allocator) {
        this(allocator, IdSpace.DEFAULT_SEGMENT_STEP);
    }

    public SegmentShortCodeGenerator(IdSegmentAllocator allocator, int step) {
        if (step <= 0) {
            throw new IllegalArgumentException("号段步长必须为正数: " + step);
        }
        this.allocator = allocator;
        this.step = step;
    }

    /**
     * 启动自检：确保号段行存在，并校验其 {@code max_id} 不落在 Redis 区间内。
     *
     * <p>后半条校验是必需的：若 {@code id_segment.max_id} 被误设为 100000，
     * 号段会从 100001 起发出，落进 Redis 区间并与之重叠——这是重复短码的直接成因。
     * 与其等线上撞唯一索引，不如启动时就暴露配置错误。
     */
    public void ensureInitialized() {
        allocator.ensureRow();

        Long maxId = allocator.currentMaxId();
        if (maxId == null) {
            throw new IllegalStateException("id_segment 中不存在 biz_tag=" + IdSegmentAllocator.BIZ_TAG + " 的行，无法发号");
        }
        if (maxId < IdSpace.SEGMENT_MAX_ID_INIT_VALUE) {
            log.error("id_segment.max_id={} 小于期望初始值 {}，号段可能与 Redis 区间重叠（重复短码风险）",
                    maxId, IdSpace.SEGMENT_MAX_ID_INIT_VALUE);
            throw new IllegalStateException(
                    "id_segment.max_id=" + maxId + " 非法：不得小于 " + IdSpace.SEGMENT_MAX_ID_INIT_VALUE);
        }
        log.info("号段发号就绪：biz_tag={} max_id={} step={}，下一段从 {} 起",
                IdSegmentAllocator.BIZ_TAG, maxId, step, maxId + 1);
    }

    @Override
    public String nextShortCode() {
        long sequence = nextSequence();
        IdSpace.requireInRange(sequence);
        return Base62.encodePadded(sequence);
    }

    private long nextSequence() {
        long next = current.getAndIncrement();
        if (next < limit.get()) {
            return next;
        }
        return refillAndTake(next);
    }

    /**
     * 段用尽：取下一段，并从新段中取一个号。
     *
     * <p>{@code next} 是本次已经消耗掉的自增值。段用尽时它必然 {@code >=} 旧 limit，
     * 因此要丢掉它、从新段重新取，否则会发出一个未被号段记录覆盖的号
     * （不影响唯一性，但会让号段语义混乱、难以排查）。
     *
     * <p>段用尽是低频事件（每 {@code step} 次调用一次），因此这里用 {@code synchronized}
     * 简化并发处理是合理的；热点路径（{@code getAndIncrement} 分支）无锁。
     */
    private long refillAndTake(long next) {
        synchronized (this) {
            // 双重检查：可能已有其它线程取到了新段并推进了 limit
            if (next < limit.get()) {
                return next;
            }
            long newLimit = allocator.advanceAndGetMaxId();
            if (newLimit > IdSpace.MAX_SEQUENCE) {
                // 号空间用尽：绝不回绕（回绕会让短码重复）。按 DR-022 记 ERROR 并让调用失败。
                log.error("号段区间已用尽：max_id={} 超过 7 位 Base62 上界 {}，必须缩短码长度或扩容",
                        newLimit, IdSpace.MAX_SEQUENCE);
                throw new ShortCodeGenerationException(
                        "号段区间用尽（max_id=" + newLimit + " > " + IdSpace.MAX_SEQUENCE + "）");
            }

            limit.set(newLimit);
            // 至少推进到「刚浪费的那个号之后」，避免把旧段里已消耗的号再发一次
            current.set(Math.max(newLimit - step, next + 1));

            long taken = current.getAndIncrement();
            if (taken >= limit.get()) {
                throw new ShortCodeGenerationException(
                        "号段分配异常：taken=" + taken + " limit=" + limit.get() + "（请检查 step 与并发）");
            }
            return taken;
        }
    }

    /** 仅供测试断言：当前段上界（不含）。 */
    long currentLimit() {
        return limit.get();
    }

    /** 仅供测试断言：号段步长。 */
    int step() {
        return step;
    }
}
