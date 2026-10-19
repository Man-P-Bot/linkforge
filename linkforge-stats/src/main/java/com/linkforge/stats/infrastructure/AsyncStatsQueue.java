package com.linkforge.stats.infrastructure;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.springframework.data.redis.core.StringRedisTemplate;
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
 * <h2>⚠️ Gauge 绑定的对象必须是强可达的</h2>
 * Micrometer 的 {@code Gauge.builder(name, obj, valueFunction)} 对 obj 用<b>弱引用</b>。
 * 但那只在 obj <b>没有其他强引用</b>时才会NaN——
 * 本类的 {@link #queue} 是 final 字段、被 Spring 容器持有，天然安全。
 * ⚠️ 曾为此加过一个冗余的 {@code queueRef} 字段，属错误归因，已删除。
 */
@Component
public class AsyncStatsQueue implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(AsyncStatsQueue.class);

    /** 消费线程的轮询间隔。10ms 足够——队列满时能快速排空。 */
    private static final long POLL_INTERVAL_MS = 10L;

    /** 每批最多取多少条。太小则往返多，太大则单次延迟长。 */
    private static final int BATCH_SIZE = 500;

    /**
     * 默认队列容量。可被 {@code linkforge.stats.queue-capacity} 覆盖。
     *
     * <p>⚠️ 默认值按「1 万条短码的压测语料 ×少量突发缓冲」定的，
     * 不是拍脑袋的整数。调大前先看 {@code shortlink.stats.queue.size}：
     * 若它长期贴近容量而 {@code dropped{reason=full}} 仍为 0，说明消费端跟得上，
     * 加容量只是浪费内存。
     */
    public static final int DEFAULT_CAPACITY = 10000;

    /**
     * 统计 key 的 TTL。
     *
     * <p>⚠️ <b>必须有 TTL，否则 Redis 内存只增不减</b>：
     * key 是 {@code shortlink:pv:{code}:{yyyymmdd}}，
     * 一天一个 code 一个 key，不设过期会让历史数据永久堆积。
     *
     * <p>为什么是 35 天：覆盖「当月 + 上月」（月初查上月报表仍能读到），
     * 另留 5 天缓冲应对跨月边界的写入延迟。
     */
    private static final long KEY_TTL_DAYS = 35L;

    private static final String PV_KEY_PREFIX = "shortlink:pv:";
    private static final String UV_KEY_PREFIX = "shortlink:uv:";
    private static final String DLQ_KEY_PREFIX = "shortlink:stats:dl:";

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.BASIC_ISO_DATE;

    /**
     * 统计队列。
     *
     * <p>⚠️ <b>本字段是 {@code final}，天然被 Spring 容器强引用</b>，
     * 因此 {@link Gauge} 绑定它不会变成 {@code NaN}。
     *
     * <p>⚠️ <b>不要为了「防 NaN」再加一个同类型的冗余字段</b>：
     * Micrometer 的 {@code Gauge.builder(name, obj, fn)} 确实对 obj 用弱引用，
     * 但那只在被观测对象<b>没有其他强引用</b>时才有风险
     *（典型场景：绑一个临时new 出来、方法内就返回的对象）。
     * 本类不需要那种保护——曾经加过一个 {@code queueRef} 字段做这件事，
     * 它只重复赋值、从不被读，是纯冗余，已删除。
     */
    private final ArrayBlockingQueue<StatsEvent> queue;

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

    private final StringRedisTemplate redis;

    /**
     * flushed：<b>写入 Redis 的结果</b>，与 {@code drained} 严格分层。
     *
     * <p>两个维度都要拆tag：
     * <ul>
     *   <li>{@code target=pv|uv} —— 哪条通道失败，两者可独立重试</li>
     *   <li>{@code result=ok|failed} —— 对账基准：
     *       {@code drained == flushed{ok} + flushed{failed} + 仍在队列中的}
     *       这个等式不成立就说明有数据在丢</li>
     * </ul>
     */
    private final Counter flushedPvOk;
    private final Counter flushedPvFailed;
    private final Counter flushedUvOk;
    private final Counter flushedUvFailed;
    private final Timer flushPvTimer;
    private final Timer flushUvTimer;

    private final Thread consumer;
    private volatile boolean running = false;

    /**
     * @param meterRegistry 指标注册表
     * @param capacity      队列容量，由 {@code linkforge.stats.queue-capacity} 注入，
     *                      默认 {@link #DEFAULT_CAPACITY}。
     *                      ⚠️ 测试可直接传小值（如 1）造出「队列满」的场景，
     *                      不必真的new 一万个元素的队列。
     */
    public AsyncStatsQueue(MeterRegistry meterRegistry,
                           StringRedisTemplate redis,
                           @Value("${linkforge.stats.queue-capacity:"
                                   + DEFAULT_CAPACITY + "}") int capacity) {
        this.redis = redis;
        if (capacity <= 0) {
            // ⚠️ ArrayBlockingQueue(0) 能建但永远 offer 失败（等于统计全丢），
            //负数则直接抛 IllegalArgumentException。两种都是配置错误，
            // 必须在启动期暴露，而不是让统计静默失效。
            throw new IllegalArgumentException(
                    "linkforge.stats.queue-capacity 必须 > 0，当前值：" + capacity);
        }
        this.queue = new ArrayBlockingQueue<>(capacity);

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

        this.flushedPvOk = Counter.builder("shortlink.stats.flushed")
                .tag("target", "pv").tag("result", "ok")
                .description("PV 成功写入 Redis 的次数")
                .register(meterRegistry);
        this.flushedPvFailed = Counter.builder("shortlink.stats.flushed")
                .tag("target", "pv").tag("result", "failed")
                .description("PV 写入失败并进死信的次数")
                .register(meterRegistry);
        this.flushedUvOk = Counter.builder("shortlink.stats.flushed")
                .tag("target", "uv").tag("result", "ok")
                .description("UV 成功写入 Redis 的次数")
                .register(meterRegistry);
        this.flushedUvFailed = Counter.builder("shortlink.stats.flushed")
                .tag("target", "uv").tag("result", "failed")
                .description("UV 写入失败并进死信的次数")
                .register(meterRegistry);
        this.flushPvTimer = Timer.builder("shortlink.stats.flush.duration")
                .tag("target", "pv")
                .description("单批 PV 刷写耗时")
                .publishPercentileHistogram()
                .register(meterRegistry);
        this.flushUvTimer = Timer.builder("shortlink.stats.flush.duration")
                .tag("target", "uv")
                .description("单批 UV 刷写耗时")
                .publishPercentileHistogram()
                .register(meterRegistry);

        Gauge.builder("shortlink.stats.queue.size", queue, ArrayBlockingQueue::size)
                .description("统计队列当前积压深度。持续 > 0 说明消费端跟不上生产端")
                .register(meterRegistry);

        this.consumer = new Thread(this::consumeLoop, "stats-async-consumer");
        this.consumer.setDaemon(true);
    }

    // ------------------------------------------------------------ 生命周期

    /**
     * 启动消费线程。
     *
     * <p>⚠️ <b>本类不可重启</b>：{@link Thread} 只能 {@code start()} 一次，
     * 对已 {@link #stop()} 的实例再调 {@code start()} 会抛
     * {@code IllegalThreadStateException}。
     * 阶段 3 的 {@code @Scheduled} 调用方<b>必须先检查 {@link #isRunning()}</b>，
     * 否则应用上下文中途重启后会持续抛异常。
     */
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
            // ⚠️ 阶段 2 起已有 Redis 通道：这里应当同步刷写剩余数据。
            //    当前只告警不刷写——停机路径的补写留到阶段 3（与定时落库一起做），
            //    因为它需要和「取出即删除」的幂等策略配套，不能各写一半。
            log.warn("统计队列停机时残留 {} 条未处理（未刷写 Redis，数据将丢失）",
                    remaining.size());
        }
        log.info("统计队列已停止");
    }

    /**
     * @return 消费线程是否在运行。
     *         ⚠️ <b>阶段 3 的 {@code @Scheduled} 调用方必须先检查本方法</b>
     *         （「不可重启」的完整说明写在 {@link #start()} 上）。
     */
    @Override
    public boolean isRunning() {
        return running;
    }

    /**
     * 生命周期阶段：<b>最晚启动、最早停止</b>。
     *
     * <p>⚠️ 显式声明而非依赖 {@code SmartLifecycle} 的默认值
     * {@code DEFAULT_PHASE = Integer.MAX_VALUE}——虽然两者当前相等，
     * 但依赖「默认值恰好等于我想要的值」是隐患：
     * 一旦将来有更晚启动的 Lifecycle 组件加入，顺序就会静默改变。
     *
     * <p>本组件需要最晚启动（Redis 客户端就绪后才消费）、
     * 最早停止（Redis 连接池关闭前把队列排空）。
     */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
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
     * 消费线程主循环：取出 → 按 code 聚合 → 写 Redis（PV/UV 双通道）。
     *
     * <p>⚠️ <b>失败绝不重试</b>：写失败的数据进死信队列（{@code shortlink:stats:dl:{date}}），
     * 消费线程立刻处理下一批。重试会阻塞消费线程，而队列满了会反压到主线程——
     * 那正是DR-058 要避免的「统计拖慢跳转」。
     */
    private void consumeLoop() {
        while (running) {
            try {
                List<StatsEvent> batch = pollBatch();
                if (batch.isEmpty()) {
                    continue;
                }
                drainedCounter.increment(batch.size());
                flushBatch(batch);
                log.debug("统计刷写一批：{} 条", batch.size());
            } catch (InterruptedException e) {
                // ⚠️ 必须重新设置中断状态：否则 stop() 里的 join(2000)
                // 可能在中断被清除的情况下等满 2 秒才返回（DR-058 阶段 2）。
                Thread.currentThread().interrupt();
                log.debug("统计消费线程被中断，退出");
                return;
            } catch (Exception e) {
                // ⚠️ 绝不让异常逃出：否则消费线程会死，队列很快满
                log.error("统计消费线程异常（已继续）: {}", e.toString());
            }
        }
    }

    /** 取出最多 {@link #BATCH_SIZE} 条；队列空时阻塞至多 {@link #POLL_INTERVAL_MS}。 */
    private List<StatsEvent> pollBatch() throws InterruptedException {
        StatsEvent first = queue.poll(POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
        if (first == null) {
            return List.of();
        }
        List<StatsEvent> batch = new ArrayList<>(BATCH_SIZE);
        batch.add(first);
        StatsEvent next;
        while (batch.size() < BATCH_SIZE && (next = queue.poll()) != null) {
            batch.add(next);
        }
        return batch;
    }

    /**
     * 把一批事件写进Redis。
     *
     * <p><b>PV</b>：同 code 合并成一次 {@code INCRBY}，
     * 100 条同一短码 = 1 次往返而非 100 次。
     *
     * <p><b>UV</b>：按 code 收集 visitor 数组，一次 {@code PFADD}。
     */
    private void flushBatch(List<StatsEvent> batch) {
        String today = today();
        Map<String, Long> pvByCode = new LinkedHashMap<>();
        Map<String, List<String>> uvByCode = new LinkedHashMap<>();

        for (StatsEvent e : batch) {
            pvByCode.merge(e.code(), 1L, Long::sum);
            // ⚠️ visitor 为 null 时不能进 UV：PFADD 会 NPE。
            //    但 PV 不受影响——「访问发生了」与「能不能去重」是两件事。
            if (e.visitor() != null) {
                uvByCode.computeIfAbsent(e.code(), k -> new ArrayList<>()).add(e.visitor());
            }
        }

        flushPv(pvByCode, today, batch);
        flushUv(uvByCode, today, batch);
    }

    private void flushPv(Map<String, Long> pvByCode, String today, List<StatsEvent> batch) {
        flushPvTimer.record(() -> {
            try {
                pvByCode.forEach((code, n) -> {
                    String key = PV_KEY_PREFIX + code + ":" + today;
                    // ⚠️ INCRBY + EXPIRE 非原子，但可接受：
                    //    EXPIRE 幂等，重复执行无害；
                    //    最坏情况是「计数成功但没设上TTL」——
                    //    而这需要进程恰好在两条命令之间崩溃。
                    //    若要严格，用 Lua 合并（阶段 3 与定时落库时统一处理）。
                    redis.opsForValue().increment(key, n);
                    redis.expire(key, KEY_TTL_DAYS, TimeUnit.DAYS);
                });
                // ⚠️ 计数单位是「批」，不是「条」：一次 flush 处理多个 code
                flushedPvOk.increment();
            } catch (Exception e) {
                flushedPvFailed.increment();
                pushToDlq(batch, "pv", e);
                log.warn("PV 刷写失败，本批 {} 条已进死信: {}", batch.size(), e.toString());
            }
        });
    }

    private void flushUv(Map<String, List<String>> uvByCode, String today, List<StatsEvent> batch) {
        flushUvTimer.record(() -> {
            try {
                uvByCode.forEach((code, visitors) -> {
                    String key = UV_KEY_PREFIX + code + ":" + today;
                    // ⚠️⚠️ **DR-054 回归点**：
                    //   opsForHyperLogLog().add(String key, String... values) 是**变长参数**。
                    //   传 String[] 会展开成多个值；
                    //   传 List 会被当成**单个元素**序列化——
                    //   结果是每次 PFADD 只记了一个 "[a, b, c]" 字符串，UV 恒等于 1。
                    redis.opsForHyperLogLog()
                            .add(key, visitors.toArray(new String[0]));
                    redis.expire(key, KEY_TTL_DAYS, TimeUnit.DAYS);
                });
                flushedUvOk.increment();
            } catch (Exception e) {
                flushedUvFailed.increment();
                pushToDlq(batch, "uv", e);
                log.warn("UV 刷写失败，本批 {} 条已进死信: {}", batch.size(), e.toString());
            }
        });
    }

    /**
     * 死信：整批 LPUSH 到 {@code shortlink:stats:dl:{date}}。
     *
     * <p>⚠️ <b>死信写入本身也可能失败</b>（Redis 挂了）。
     * 此时只记日志——不能再往 Redis 写，那会无限递归。
     */
    private void pushToDlq(List<StatsEvent> batch, String target, Exception cause) {
        try {
            String dlqKey = DLQ_KEY_PREFIX + today();
            List<String> payload = new ArrayList<>(batch.size());
            for (StatsEvent e : batch) {
                payload.add(target + "|" + e.code() + "|" + e.visitor() + "|" + e.ts());
            }
            redis.opsForList().rightPushAll(dlqKey, payload);
            redis.expire(dlqKey, KEY_TTL_DAYS, TimeUnit.DAYS);
        } catch (Exception dlqFailure) {
            // ⚠️ 到这里数据是真的没了。只能记日志 + 让指标说话。
            log.error("死信写入也失败，本批 {} 条**永久丢失**: target={} 原因={} / {}",
                    batch.size(), target, cause.toString(), dlqFailure.toString());
        }
    }

    /**
     * 统计 key 的日期后缀。
     *
     * <p>⚠️ <b>当前用服务器当前日期</b>，不是 {@link StatsEvent#ts()} 反推的日期。
     * 跨零点的批次会被算到「处理当天」而非「发生当天」，
     * 极端情况下误差 1 天。阶段 3 做定时落库时再改成按 ts 分桶。
     */
    private String today() {
        return LocalDate.now().format(DATE_FMT);
    }
}