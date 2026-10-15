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
     * <p><b>两条语句、一个事务</b>（DR-042）：
     * <ol>
     *   <li>{@code UPDATE id_segment SET max_id = max_id + step}——单条语句内完成自增，
     *       靠 InnoDB 行锁保证多实例并发取段不拿到重叠区间；</li>
     *   <li>{@code SELECT max_id}——读回推进后的值。行锁在事务提交前一直持有，
     *       因此这一步读到的一定是自己那次 update 的结果。</li>
     * </ol>
     *
     * <p>⚠️ <b>为什么不合成一条 SQL</b>：MySQL 没有 {@code UPDATE ... RETURNING}，
     * 常见技巧是 {@code SET max_id = LAST_INSERT_ID(max_id + step)} 再
     * {@code SELECT LAST_INSERT_ID()}。但它依赖 MySQL 特有行为，
     * 且读的是**连接级**变量——从连接池拿到的连接若不是同一条，结果就是错的。
     * 降级发号是低频路径（仅 Redis 不可用时触发），多一条 SELECT 的开销可以忽略，
     * 通用性与可读性更重要。
     *
     * <p><b>为什么必须 {@code REQUIRES_NEW}</b>：取号段要独立于业务事务——
     * 号段一旦取走，无论业务成功失败都<b>不还回去</b>（只跳号、不重号）。
     * 若与业务事务共用，创建失败回滚时号段也回滚，而进程内 {@code current}
     * 已经向前走，同一段号会被再次取到并重复发出。本类是独立 Bean，
     * 调用经过 Spring 代理，注解才会真正生效（自调用会完全失效）。
     *
     * @return 推进后的 max_id
     * @throws IllegalStateException 号段行不存在，或推进后读不到值
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long advanceAndGetNewMaxId() {
        int affected = idSegmentMapper.advanceMaxId(bizTag);
        if (affected != 1) {
            throw new IllegalStateException(
                    "号段推进失败：id_segment 中 biz_tag=" + bizTag + " 受影响行数=" + affected
                    + "（0 表示该行不存在）");
        }
        Long newMaxId = idSegmentMapper.selectMaxId(bizTag);
        if (newMaxId == null) {
            throw new IllegalStateException(
                    "号段推进后读不到 max_id：biz_tag=" + bizTag);
        }
        // 防御性断言：推进量必须恰好是一个 step。
        // 少了说明 SQL 被改坏（比如忘了写 step），多了说明有并发把步长改了——
        // 两种情况都会让段区间算错，进而发出越界或重复的号。
        long newLimit = newMaxId;
        if (newLimit <= 0) {
            throw new IllegalStateException("号段推进结果非法：max_id=" + newLimit);
        }
        log.debug("取到新号段：bizTag={} 区间 [{}, {}]", bizTag, newLimit - step + 1, newLimit);
        return newLimit;
    }
}
