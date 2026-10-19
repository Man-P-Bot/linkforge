package com.linkforge.stats.infrastructure;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * 异步统计队列：跳转链路与统计写入之间的**单向解耦**（DR-058）。
 *
 * <h2>⚠️ 为什么不能「队列满了就同步写」</h2>
 * 队列满，说明消费端跟不上。此时<b>最稀缺的资源是主线程的时间</b>。
 * 为了记一个统计数字而让跳转变慢，是本末倒置。
 * 统计丢失可以事后从日志补，跳转延迟无法挽回。
 *
 * <p>这与限流「Redis 不可用时降级放行」是同一个原则的镜像：
 * <b>保护措施失败时，宁可放弃保护的目标，也不能影响被保护的对象。</b>
 *
 * <h2>⚠️ 为什么绝不能用 {@code CallerRunsPolicy}</h2>
 * {@code ThreadPoolExecutor.CallerRunsPolicy} 会让<b>调用方线程亲自执行任务</b>
 * ——而任务正是「写 Redis」这个慢操作。那等价于「队列满了就退化成同步写」，
 * 恰好是上面要避免的行为，而且<b>连指标都看不到</b>（只有延迟，没有丢弃计数）。
 *
 * <h2>为什么不用 {@code @Async}</h2>
 * 三个已知坑：自调用失效（同类内部调用不生效）、异常被吞、
 * 线程池满时的行为不明确。而我们需要的是
 * 「<b>明确的丢弃语义 + 可观测的丢弃计数</b>」。
 *
 * <h2>⚠️ 为什么实现 {@link SmartLifecycle} 而不是在构造器里 start 线程</h2>
 * 早期版本在构造函数里 {@code new Thread(...).start()}，有三个问题：
 * <ol>
 *   <li>构造函数后续抛异常时线程已经起来，对象处于半初始化状态</li>
 *   <li>没有优雅停机：Spring 关闭时消费线程还在跑</li>
 *   <li>单元测试无法控制生命周期（每次 new 都会起一个线程）</li>
 * </ol>
 * {@code SmartLifecycle} 把启停交给容器，
 * 且 {@link #stop(Runnable)} 能保证容器等到消费线程真正退出。
 *
 * <h2>⚠️ Gauge 必须持有强引用</h2>
 * Micrometer 的 {@code Gauge.builder(name, obj, valueFunction)} 对 obj 是
 * <b>弱引用</b>。若队列被 GC 回收，指标值会变成 {@code NaN}。
 * {@link #queueRef} 就是为此存在的强引用。
 */
@Component
public class AsyncStatsQueue implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(AsyncStatsQueue.class);

    /** 消费线程的轮询间隔。10ms 足够——队列满时能快速排空。 */
    private static final long POLL_INTERVAL_MS = 10L;

    /** 每批最多取多少条。太小则往返多，太大则单次延迟长。 */
    private static final int BATCH_SIZE = 500;

    private final ArrayBlockingQueue<StatsEvent> queue;

    /**
     * ⚠️ **强引用**：Micrometer 的 {@code Gauge.builder(name, obj, fn)} 对 obj 是弱引用，
     * 队列若被GC 回收，指标值会变成 {@code NaN}。这个字段专门用来钉住队列实例。
     */
    @SuppressWarnings("unused") // 由 Micrometer 反射读取，静态分析会误报未使用
    private final ArrayBlockingQueue<StatsEvent> queueRef;

    private final Counter offeredCounter;
    private final Counter droppedFullCounter;
    private final Counter droppedErrorCounter;

    /**
     * ⚠️ {@code drained} = <b>已从队列取出</b>，<b>不代表已持久化</b>。
     * 取出后可能还没写 Redis（阶段 2 才有写入），甚至可能写失败进死信。
     * 叫「consumed」会让人误以为「已统计完成」——
     * 压测里 171 万 drained 若被读成「已统计 171 万次」就是统计幻觉。
     */
    private final Counter drainedCounter;

    private final Thread consumer;
    private volatile boolean running = false;

    public AsyncStatsQueue(MeterRegistry meterRegistry,
                           @Value("${linkforge.stats.queue-capacity:10000}") int capacity) {
        this.queue = new ArrayBlockingQueue<>(capacity);
        this.queueRef = queue;

        this.offeredCounter = Counter.builder("shortlink.stats.offered")
                .description("成功进入统计队列的事件数")
                .register(meterRegistry);

        // ⚠️ dropped 必须按 reason 拆 tag：两种丢弃原因的处置方式完全不同
        //   full  → 消费端跟不上（要扩容量或提速消费）
        //   error → offer 抛异常（要查 bug）
        // 混在一个计数器里，前者会掩盖后者的存在。
        this.droppedFullCounter = Counter.builder("shortlink.stats.dropped")
                .tag("reason", "full")
                .description("队列满被丢弃的事件数（reason=full：消费端跟不上）")
                .register(meterRegistry);
        this.droppedErrorCounter = Counter.builder("shortlink.stats.dropped")
                .tag("reason", "error")
                .description("入队异常被丢弃的事件数（reason=error：offer 抛异常）")
                .register(meterRegistry);

        this.drainedCounter = Counter.builder("shortlink.stats.drained")
                .description("已从队列取出并处理的事件数。"
                        + "⚠️**不代表已持久化**——取出时尚未写入 Redis")
                .register(meterRegistry);

        Gauge.builder("shortlink.stats.queue.size", queue, ArrayBlockingQueue::size)
                .description("统计队列当前积压深度。持续 > 0 说明消费端跟不上生产端")
                .register(meterRegistry);

        this.consumer = new Thread(this::consumeLoop, "stats-async-consumer");
        this.consumer.setDaemon(true);
    }

    // ------------------------------------------------------------ 生命周期

    @Override
    public void start() {
        if (running) {
            return;
        }
        running = true;
        consumer.start();
        log.info("统计队列已启动：容量={}", capacity());
    }

    /**
     * 优雅停机：停循环 → 中断 → {@code join} → drainTo 剩余。
     *
     * <p>⚠️ drainTo 后的处理<b>当前只打 WARN</b>：阶段 2 才写 Redis/DB。
     * 现在**没有任何持久化通道**，若记成 drained 会造成「已统计」的幻觉。
     */
    @Override
    public void stop(Runnable callback) {
        stop();
        callback.run();
    }

    @Override
    public void stop() {
        if (!running) {
            return;
        }
        running = false;
        consumer.interrupt();
        try {
            consumer.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        List<StatsEvent> remaining = new ArrayList<>();
        queue.drainTo(remaining);
        if (!remaining.isEmpty()) {
            log.warn("统计队列停机时残留 {} 条未处理"
                            + "（阶段 2 前无持久化通道，数据丢弃）",
                    remaining.size());
        }
        log.info("统计队列已停止");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    // ------------------------------------------------------------ 投递

    /**
     * 非阻塞投递统计事件。
     *
     * <p>⚠️ <b>本方法绝不能阻塞、绝不能抛异常</b>——
     * 它在跳转主链路上，任何阻塞都会直接增加跳转延迟（DR-056）。
     *
     * <p>实现要点：
     * <ul>
     *   <li>用 {@link ArrayBlockingQueue#offer} 而非 {@code put}：满时立即返回 false</li>
     *   <li><b>不设 {@code CallerRunsPolicy}</b>：那会让调用方线程执行写 Redis 的慢操作</li>
     *   <li>吞掉所有异常：统计失败绝不能影响跳转</li>
     * </ul>
     *
     * @param event 统计事件
     * @return true = 已入队；false = 已丢弃（已累加对应的 dropped 指标）
     */
    public boolean offer(StatsEvent event) {
        try {
            boolean ok = queue.offer(event);
            if (ok) {
                offeredCounter.increment();
            } else {
                // ⚠️ 队列满：直接丢弃并计数，绝不降级为同步写
                droppedFullCounter.increment();
            }
            return ok;
        } catch (Exception e) {
            // ⚠️ 这里吞异常是**故意**的：统计是旁路，
            // 它的任何失败都不该让跳转失败。
            droppedErrorCounter.increment();
            log.warn("统计事件入队异常，已丢弃: {}", e.toString());
            return false;
        }
    }

    /**
     * 队列当前积压深度。
     *
     * <p>⚠️ 命名从 {@code pendingCount()} 改为 {@code queueSize()}：
     * 原名暗示「有个 pending 计数器」，但实现读的是 {@code queue.size()}。
     * 早期版本真有一个 {@code AtomicLong pending}，但它<b>只增不减</b>——
     * 是个只写不读的僵尸字段，已删除。
     */
    public int queueSize() {
        return queue.size();
    }

    /** 当前容量（配置是否生效的快速自检）。 */
    public int capacity() {
        return queue.size() + queue.remainingCapacity();
    }

    /**
     * 消费线程主循环。
     *
     * <p>⚠️ 当前为<b>空壳</b>：只取出、只计数、<b>不写 Redis</b>。阶段 2 在这里加批量写。
     */
    private void consumeLoop() {
        while (running) {
            try {
                StatsEvent first = queue.poll(POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
                if (first == null) {
                    continue;
                }
                int batch = 1;
                StatsEvent next;
                while (batch < BATCH_SIZE && (next = queue.poll()) != null) {
                    batch++;
                }
                // ⚠️ drained ≠ 已持久化。阶段 2 会在此处写 Redis。
                drainedCounter.increment(batch);
                log.debug("统计消费一批：{} 条", batch);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.debug("统计消费线程被中断，退出");
                return;
            } catch (Exception e) {
                // ⚠️ 绝不让异常逃出：否则消费线程会死，队列很快满
                log.error("统计消费线程异常（已继续）: {}", e.toString());
            }
        }
    }
}