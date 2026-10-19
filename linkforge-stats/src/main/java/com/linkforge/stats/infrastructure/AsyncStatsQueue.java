package com.linkforge.stats.infrastructure;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
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
     * PV 原子脚本（INCRBY + EXPIRE，F4）。
     *
     * <p>⚠️ <b>为什么用 Lua 而不是两条命令</b>：
     * {@code increment()} + {@code expire()} 两条命令之间进程崩溃，
     * 会留下一个<b>永不过期</b>的 key。PV key 每天每 code 一个，
     * 累积起来会让 Redis 内存失控。
     *
     * <p>⚠️ <b>为什么在消费线程执行 Lua 完全无害</b>（与 DR-056 对照）：
     * DR-056 的教训是「<b>主线程</b>同步 I/O 拖垮 Tomcat 线程池」。
     * 本脚本跑在<b>独立的统计消费线程</b>上，与跳转请求完全隔离：
     * 消费线程慢只会让队列积压，最终表现为 {@code dropped} 上升——
     * 那是可观测、可接受的（统计可丢，跳转不能慢）。
     */
    private DefaultRedisScript<Long> pvScript;

    /** UV 原子脚本（PFADD + EXPIRE），同时规避 DR-054 的变长参数歧义。 */
    private DefaultRedisScript<Long> uvScript;

    /**
     * 从 classpath 加载 Lua 脚本并声明返回类型为 Long。
     *
     * <h2>⚠️⚠️ 这里踩了三个坑，顺序不能错</h2>
     * <ol>
     *   <li><b>{@code setLocation} 单独用不够</b>——它只记录位置，
     *       {@code getScriptSource()} 仍是 null，执行时报
     *       「script source must not be null」。
     *       而 Spring Data Redis 3.5 <b>没有 {@code ResourceScriptSource}</b>
     *       这个类（查过 jar：只有 DefaultRedisScript / ScriptUtils 等）。</li>
     *   <li><b>{@code new DefaultRedisScript<>(resource, Long.class)} 构造器不存在</b>——
     *       编译直接失败。</li>
     *   <li><b>最终方案：{@code setScriptText}</b>——把脚本内容读成字符串塞进去。
     *       它同时避开了上面两个坑，且没有额外依赖。</li>
     * </ol>
     */
    private static DefaultRedisScript<Long> luaScript(String path) {
        try (java.io.InputStream in = new ClassPathResource(path).getInputStream()) {
            String text = new String(in.readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
            DefaultRedisScript<Long> script = new DefaultRedisScript<>();
            script.setScriptText(text);
            script.setResultType(Long.class);
            return script;
        } catch (java.io.IOException e) {
            // ⚠️ 启动期就失败，比运行期「每次写入都报错」好得多
            throw new IllegalStateException("无法加载 Lua 脚本: " + path, e);
        }
    }

    /**
     * flushed：<b>写入 Redis 的结果</b>，与 {@code drained} 严格分层。
     *
     * <p><b>单位：条</b>（不是批）——阶段 2 误按批计数，导致
     * {@code drained}（条）与 {@code flushed}（批）对不上账，已在 F1 修正。
     *
     * <p>⚠️ <b>必须成立的不等式</b>：
     * {@code flushed{target=uv} <= flushed{target=pv}}。
     * UV 只统计 visitor 非 null 的条目，PV 统计全部条目，
     * 因此 UV 的 ok + failed 总和**永远不可能超过** PV。
     * 若出现 {@code uv > pv}，说明分组逻辑有 bug。
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

    /**
     * 死信二次丢失：<b>Redis 写失败且 DLQ 也写失败</b>，数据真的没了。
     *
     * <p>⚠️ 只有日志是不够的——运维不翻日志就看不到数据在丢。
     * {@code stage} 维度预留给阶段 3：Redis 侧死信失败记 {@code stage=redis}，
     * MySQL 落库重试耗尽记 {@code stage=mysql}。
     */
    private final Counter dlqLostRedisPv;
    private final Counter dlqLostRedisUv;

    private final Thread consumer;
    private volatile boolean running = false;

    /**
     * @param meterRegistry 指标注册表
     * @param capacity      队列容量，由 {@code linkforge.stats.queue-capacity} 注入，
     *                      默认 {@link #DEFAULT_CAPACITY}。
     *                      ⚠️ 测试可直接传小值（如 1）造出「队列满」的场景，
     *                      不必真的new 一万个元素的队列。
     */
    /**
     * ⚠️ 只能有**一个**构造器：Spring 的构造器注入在有多个构造器时
     * 会报「No default constructor found」（实测踩过）。
     * 测试需要替换 Lua 脚本时，用 {@link #withScripts} 静态工厂，
     * 而不是再加一个构造器。
     */
    public AsyncStatsQueue(MeterRegistry meterRegistry,
                           StringRedisTemplate redis,
                           @Value("${linkforge.stats.queue-capacity:"
                                   + DEFAULT_CAPACITY + "}") int capacity) {
        this.redis = redis;
        this.pvScript = luaScript("lua/pv_incr_by.lua");
        this.uvScript = luaScript("lua/uv_pfadd.lua");
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

        this.dlqLostRedisPv = Counter.builder("shortlink.stats.dlq.lost")
                .tag("target", "pv").tag("stage", "redis")
                .description("PV 写 Redis 失败且死信也写入失败——数据永久丢失")
                .register(meterRegistry);
        this.dlqLostRedisUv = Counter.builder("shortlink.stats.dlq.lost")
                .tag("target", "uv").tag("stage", "redis")
                .description("UV 写 Redis 失败且死信也写入失败——数据永久丢失")
                .register(meterRegistry);
        // ⚠️ stage=mysql 的两个计数器**故意不注册**：
        //   阶段 3 才会真正落库，那时才注册。提前注册一个恒为 0 的计数器，
        //   只会让人误以为「已经在监控落库丢失了」。

        Gauge.builder("shortlink.stats.queue.size", queue, ArrayBlockingQueue::size)
                .description("统计队列当前积压深度。持续 > 0 说明消费端跟不上生产端")
                .register(meterRegistry);

        this.consumer = new Thread(this::consumeLoop, "stats-async-consumer");
        this.consumer.setDaemon(true);
    }

    /**
     * 测试专用工厂：替换 Lua 脚本。
     *
     * <p>⚠️ 存在的理由：测「Redis 写失败」这条路径时，
     * mock {@code StringRedisTemplate.execute} 的泛型匹配极不可靠
     * （{@code RedisScript<T>} 擦除后 any(RedisScript.class) 匹配上，
     * 但 stub 不生效——实测两次都返回 null）。
     * 而「真实连不上的 Redis」也不行：Lettuce 会长时间重试连接，
     * 15 秒都到不了 flush 那一步。
     *
     * <p>换成「一个必然抛异常的脚本」就干净了：
     * 走的是**完全真实的** execute 路径（真实序列化、真实异常传播），
     * 只有脚本本身是坏的。
     */
    static AsyncStatsQueue withScripts(MeterRegistry meterRegistry,
                                      StringRedisTemplate redis,
                                      int capacity,
                                      DefaultRedisScript<Long> pvScript,
                                      DefaultRedisScript<Long> uvScript) {
        AsyncStatsQueue q = new AsyncStatsQueue(meterRegistry, redis, capacity);
        if (pvScript != null) {
            q.pvScript = pvScript;
        }
        if (uvScript != null) {
            q.uvScript = uvScript;
        }
        return q;
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
        // ⚠️ F2：**按 event.ts() 推导日期**，不用服务器当前日期。
        //   否则一批里跨零点的请求会被算到「处理当天」，
        //   导致 23:59 的访问记到第二天（或反之），报表差一天。
        Map<LocalDate, Map<String, Long>> pvByDay = new LinkedHashMap<>();
        Map<LocalDate, Map<String, List<String>>> uvByDay = new LinkedHashMap<>();
        int pvCount = 0;
        int uvCount = 0;

        for (StatsEvent e : batch) {
            LocalDate day = dayOf(e.ts());
            pvByDay.computeIfAbsent(day, d -> new LinkedHashMap<>())
                    .merge(e.code(), 1L, Long::sum);
            pvCount++;
            // ⚠️ visitor 为 null 时不能进 UV：PFADD 会 NPE。
            //    但 PV 不受影响——「访问发生了」与「能不能去重」是两件事。
            if (e.visitor() != null) {
                uvByDay.computeIfAbsent(day, d -> new LinkedHashMap<>())
                        .computeIfAbsent(e.code(), c -> new ArrayList<>())
                        .add(e.visitor());
                uvCount++;
            }
        }

        flushPv(pvByDay, pvCount, batch);
        flushUv(uvByDay, uvCount, batch);
    }

    private void flushPv(Map<LocalDate, Map<String, Long>> pvByDay, int pvCount,
                         List<StatsEvent> batch) {
        // ⚠️⚠️ **必须用 record(Supplier) 而不是 record(Runnable)**：
        //   Micrometer 的 record(Runnable) 内部会 **catch 掉异常**
        //   （交给 MeterRegistry 的异常处理器），于是下面flushPv 内层的
        //   catch 块**永远不会被执行**—— flushed{failed} 恒为 0，
        //   DLQ 也不会写。首轮实测就踩到了：mock 抛异常但 failed 指标为 0。
        //   record(Supplier) 会让异常正常传播。
        // ⚠️⚠️ **不能用 Timer.record(...) 包住整个 try/catch**：
        //   Micrometer 的 record(Runnable) 与 record(Supplier) **都会 catch 掉异常**
        //   （转交给 MeterRegistry 的异常处理器），
        //   于是内层的 catch **永远不会执行** ——
        //   flushed{failed} 恒为 0、DLQ 也不写。
        //   首轮与次轮实测都踩到了这个坑。
        //   正确做法：**手动计时**（System.nanoTime + finally 里 record），
        //   让业务代码的 try/catch 自己掌控异常。
        long t0 = System.nanoTime();
        try {
            try {
                pvByDay.forEach((day, byCode) -> {
                    String date = formatDay(day);
                    byCode.forEach((code, n) -> {
                        String key = PV_KEY_PREFIX + code + ":" + date;
                        // ⚠️ F4：用 Lua 把 INCRBY + EXPIRE 合成原子操作。
                        //    两条命令之间进程崩溃会留下**永不过期**的 key。
                        redis.execute(pvScript,
                                List.of(key),
                                String.valueOf(n),
                                String.valueOf(TimeUnit.DAYS.toSeconds(KEY_TTL_DAYS)));
                    });
                });
                // ⚠️ F1：按**条**计数，不是按批。
                //   阶段 2 按批计数时 drained(条) == flushed(批) 的对账等式不成立。
                flushedPvOk.increment(pvCount);
            } catch (Exception e) {
                flushedPvFailed.increment(pvCount);
                pushToDlq(batch, "pv", e, pvCount);
                log.warn("PV 刷写失败，本批 {} 条已进死信", pvCount, e);
            }
        } finally {
            flushPvTimer.record(System.nanoTime() - t0, java.util.concurrent.TimeUnit.NANOSECONDS);
        }
    }

    private void flushUv(Map<LocalDate, Map<String, List<String>>> uvByDay, int uvCount,
                         List<StatsEvent> batch) {
        long t0 = System.nanoTime();
        try {
            try {
                uvByDay.forEach((day, byCode) -> {
                    String date = formatDay(day);
                    byCode.forEach((code, visitors) -> {
                        String key = UV_KEY_PREFIX + code + ":" + date;
                        // ⚠️ F4 + DR-054 回归：Lua 脚本里 ARGV 形态由脚本定义，
                        //    不存在「传 List 变单个元素」的歧义。
                        //    ARGV[1]=TTL，ARGV[2..]=visitors。
                        Object[] args = new Object[visitors.size() + 1];
                        args[0] = String.valueOf(TimeUnit.DAYS.toSeconds(KEY_TTL_DAYS));
                        for (int i = 0; i < visitors.size(); i++) {
                            args[i + 1] = visitors.get(i);
                        }
                        // ⚠️⚠️ **必须写 (Object[]) 强转**：execute 的签名是
                        //   execute(RedisScript, List<K>, Object... args)。
                        //   直接传 String[] 时，Java 会把整个数组当成
                        //   **单个 varargs 元素**再包一层——ARGV 于是变成
                        //   [整个数组] 一个元素。⚠️ 这正是 DR-054 的坑：
                        //   「List 被当成单个元素序列化」的同构错误，
                        //   只是这次发生在 Java->Lua 的参数传递上。
                        //   强转成 Object[] 后 varargs 才会正确展开。
                        redis.execute(uvScript, List.of(key), (Object[]) args);
                    });
                });
                flushedUvOk.increment(uvCount);
            } catch (Exception e) {
                flushedUvFailed.increment(uvCount);
                pushToDlq(batch, "uv", e, uvCount);
                log.warn("UV 刷写失败，本批 {} 条已进死信", uvCount, e);
            }
        } finally {
            flushUvTimer.record(System.nanoTime() - t0, java.util.concurrent.TimeUnit.NANOSECONDS);
        }
    }

    /**
     * 从事件时间戳推导统计日期（F2）。
     *
     * <p>⚠️ 用 {@link ZoneId#systemDefault()}：统计口径与服务器本地时区一致。
     * 若将来要多时区统计，得在 key 里带上时区标识，否则跨时区会串。
     */
    private LocalDate dayOf(long ts) {
        return LocalDate.ofInstant(Instant.ofEpochMilli(ts), ZoneId.systemDefault());
    }

    private String formatDay(LocalDate day) {
        return day.format(DATE_FMT);
    }

    /**
     * 死信：整批 LPUSH 到 {@code shortlink:stats:dl:{date}}。
     *
     * <p>⚠️ <b>死信写入本身也可能失败</b>（Redis 挂了）。
     * 此时只记日志——不能再往 Redis 写，那会无限递归。
     */
    private void pushToDlq(List<StatsEvent> batch, String target, Exception cause,
                           int eventCount) {
        try {
            String dlqKey = DLQ_KEY_PREFIX + today();
            List<String> payload = new ArrayList<>(batch.size());
            for (StatsEvent e : batch) {
                payload.add(target + "|" + e.code() + "|" + e.visitor() + "|" + e.ts());
            }
            redis.opsForList().rightPushAll(dlqKey, payload);
            redis.expire(dlqKey, KEY_TTL_DAYS, TimeUnit.DAYS);
        } catch (Exception dlqFailure) {
            // ⚠️ 到这里数据是真的没了。**光记日志不够**——
            //    运维不翻日志就看不到数据在丢，所以必须计指标。
            if ("pv".equals(target)) {
                dlqLostRedisPv.increment(eventCount);
            } else {
                dlqLostRedisUv.increment(eventCount);
            }
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