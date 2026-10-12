package com.linkforge.redirect.infrastructure.id;

import com.linkforge.core.domain.IdSpace;
import com.linkforge.core.infrastructure.persistence.IdSegmentMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 号段取段器：把「推进 {@code id_segment.max_id} 并提交」这一步单独成类，让事务真正生效。
 *
 * <h2>⚠️ 为什么不能把 {@code @Transactional} 写在 {@link SegmentShortCodeGenerator} 自己身上</h2>
 * Spring 的 {@code @Transactional} 依赖**代理调用**。同一个类内部的自调用
 * （{@code this.fetchNextSegment()}）不经过代理，注解**完全失效**，事务不会开启——
 * 这类 bug 不报错、只在并发或回滚场景下表现为「号段重复分配」，极难排查。
 * 把取段逻辑抽到独立 Bean 后，调用天然经过代理。
 *
 * <h2>为什么事务必须独立提交（REQUIRES_NEW）</h2>
 * 取段与业务事务分开：如果共用同一个事务，创建短链失败回滚时号段推进也会回滚，
 * 但进程内的 {@code current} 已经向前走了——于是同一段号会被再次取到并重复发出。
 * {@code REQUIRES_NEW} 保证号段一取走就落库，**只跳号、不重号**。
 */
public class IdSegmentAllocator {

    /** 本项目短码发号的业务标识（对应 {@code id_segment.biz_tag}）。 */
    public static final String BIZ_TAG = "short_code";

    private static final Logger log = LoggerFactory.getLogger(IdSegmentAllocator.class);

    private final IdSegmentMapper idSegmentMapper;
    private final String bizTag;
    private final int step;

    public IdSegmentAllocator(IdSegmentMapper idSegmentMapper, String bizTag, int step) {
        this.idSegmentMapper = idSegmentMapper;
        this.bizTag = bizTag;
        this.step = step;
    }

    /**
     * 幂等创建号段行。用 INSERT IGNORE，**不覆盖已有 max_id**——
     * 覆盖会重置计数器并导致已发出的号被重新发出。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void ensureRow() {
        idSegmentMapper.insertIgnore(bizTag, IdSpace.SEGMENT_MAX_ID_INIT_VALUE, step);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long currentMaxId() {
        return idSegmentMapper.selectMaxId(bizTag);
    }

    /**
     * 原子推进号段并返回本次号段的右端点（含）。
     *
     * <p>{@code UPDATE ... SET max_id = max_id + step} 单条语句完成，靠 InnoDB 行锁保证
     * 多实例并发取段不会拿到重叠区间。独立事务，立即提交。
     *
     * @throws IllegalStateException 号段行不存在（推进影响 0 行）
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long advanceAndGetMaxId() {
        long newLimit = idSegmentMapper.advanceAndGetMaxId(bizTag);
        if (newLimit <= 0) {
            throw new IllegalStateException("号段推进失败：id_segment 中 biz_tag=" + bizTag + " 可能不存在");
        }
        log.debug("取到新号段：bizTag={} 区间 [{}, {}]", bizTag, newLimit - step + 1, newLimit);
        return newLimit;
    }
}
