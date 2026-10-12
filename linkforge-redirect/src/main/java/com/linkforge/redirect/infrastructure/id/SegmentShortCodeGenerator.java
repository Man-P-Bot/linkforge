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

    /**
     * 下一个待发出的号。用 {@code 0} 表示「尚未取段」（正常号都 ≥ SEGMENT_FIRST_SEQUENCE）。
     *
     * <p>⚠️ 初值不能是 {@code SEGMENT_FIRST_SEQUENCE - 1}，也不能用 {@code Long.MIN_VALUE} 之类的哨兵
     * 配合「加一后比较」：{@code Long.MIN_VALUE + 1} 仍小于 limit 初值，哨兵会漏进业务逻辑
     * （实测报错「序号不能为负: -9223372036854775808」）。这里用 {@code 0} 与一个显式的
     * {@link #initialized} 标记配合，语义直白且没有溢出风险。
     */
    private final AtomicLong current = new AtomicLong();

    /**
     * 当前段上界（含）：{@code claimed <= limit} 时才允许发出。
     *
     * <p>这个「闸门」是并发正确性的关键：段用尽瞬间可能有多个线程同时越过旧上界，
     * 它们各自 {@code getAndIncrement} 得到的值都不可信。只有重新读取到
     * {@code <= limit} 的值才允许发出，否则必须重新取号。
     */
    private final AtomicLong limit = new AtomicLong();

    /** 是否已取过第一段。取段是惰性的，构造时不取——否则「只为装配而创建的实例」也会消耗一段号。 */
    private volatile boolean initialized;

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
        // 首次使用：双检锁保证只取一段，且只由第一个线程推进 current。
        if (!initialized) {
            synchronized (this) {
                if (!initialized) {
                    refillSegment();
                }
            }
        }

        while (true) {
            long observed = current.get();
            if (observed <= limit.get()) {
                // ✅ 用 CAS 抢占，而不是 getAndIncrement。
                // 取段时 current 会被**重置**回新段段首；若在快路径上用 getAndIncrement，
                // 一个读到旧值、却在重置之后才写回的线程会把计数器的写覆盖掉
                // （经典的「先读后写被中间的重置冲掉」），导致区间内的号被跳过。
                // 实测：用 getAndIncrement 时连发 8000 个号只得到 7947 个唯一值。
                // CAS 在值已被他人改动时会失败，从而不会用陈旧值覆盖 current。
                if (current.compareAndSet(observed, observed + 1)) {
                    return observed;
                }
                continue;
            }

            // 段用尽：在锁内重新判定并取下一段（不允许两个线程同时推进 current）
            synchronized (this) {
                if (current.get() <= limit.get()) {
                    continue; // 双检：期间已有其它线程取到新段
                }
                refillSegment();
            }
        }
    }

    /**
     * 取下一段：段区间为 {@code (newLimit - step, newLimit]}，并把 {@code current} 定位到段首。
     *
     * <p>必须持锁调用，保证同一时刻只有一个线程推进 {@code current}。
     */
    private void refillSegment() {
        long newLimit = allocator.advanceAndGetMaxId();
        if (newLimit > IdSpace.MAX_SEQUENCE) {
            // 号空间用尽：绝不回绕（回绕会让短码重复）。按 DR-022 记 ERROR 并让调用失败。
            log.error("号段区间已用尽：max_id={} 超过 7 位 Base62 上界 {}，必须缩短码长度或扩容",
                    newLimit, IdSpace.MAX_SEQUENCE);
            throw new ShortCodeGenerationException(
                    "号段区间用尽（max_id=" + newLimit + " > " + IdSpace.MAX_SEQUENCE + "）");
        }

        long segmentStart = newLimit - step + 1;
        current.set(segmentStart);
        limit.set(newLimit);
        initialized = true;

        log.debug("取到新号段：bizTag={} 段区间 [{}, {}]", IdSegmentAllocator.BIZ_TAG, segmentStart, newLimit);
    }

    /** 仅供测试断言：当前段上界（含）。 */
    long currentLimit() {
        return limit.get();
    }

    /** 仅供测试断言：号段步长。 */
    int step() {
        return step;
    }
}
