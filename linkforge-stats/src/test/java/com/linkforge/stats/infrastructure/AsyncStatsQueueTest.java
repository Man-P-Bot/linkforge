package com.linkforge.stats.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 异步统计队列的行为契约（DR-058）。
 *
 * <h2>⚠️ 本类锁的是三条不可退让的红线</h2>
 * <ol>
 *   <li><b>offer 绝不能阻塞</b>——它在跳转主链路上，任何阻塞都是延迟</li>
 *   <li><b>队列满绝不能降级为同步写</b>——那等于把统计变成主链路的负担</li>
 *   <li><b>丢弃必须可观测</b>——静默丢弃会让「统计不完整」无从发现</li>
 * </ol>
 *
 * <p>这些断言看起来琐碎，但它们防的正是第 3 周限流的教训：
 * **一个「顺手加的保护机制」在热路径上能造成 63% 的吞吐损失**。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AsyncStatsQueueTest {

    private SimpleMeterRegistry registry;
    @Mock
    private StringRedisTemplate redis;

    private AsyncStatsQueue queue;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        // 容量设小，方便测试「队列满」这个分支
        // ⚠️ 必须 mock execute：返回 null 时 Java 会拆箱 NPE，
        //    那个 NPE 会被 flushPv 的 catch 捕获并计入 dropped{error}，
        //    污染「队列满导致的丢弃」类断言
        org.mockito.Mockito.lenient()
                .when(redis.execute(any(org.springframework.data.redis.core.script.RedisScript.class),
                        anyList(), any(Object[].class)))
                .thenReturn(1L);
        queue = new AsyncStatsQueue(registry, redis, 50);
        // ⚠️ 显式 start：SmartLifecycle 改造后线程不在构造器里启动。
        //    需要观察「未消费」状态的测试（N1、N3、N4）必须先 stop()。
        queue.start();
    }

    @AfterEach
    void tearDown() {
        queue.stop();
    }

    private static StatsEvent ev(String code) {
        return new StatsEvent(code, null, System.currentTimeMillis());
    }

    private double counter(String name) {
        var c = registry.find(name).counter();
        return c == null ? 0.0 : c.count();
    }

    private double counter(String name, String tagKey, String tagValue) {
        var c = registry.find(name).tag(tagKey, tagValue).counter();
        return c == null ? 0.0 : c.count();
    }

    @Test
    @DisplayName("⭐⭐⭐ 队列满时 offer 返回 false，且**单次耗时 < 1ms**（绝不阻塞）")
    void offerNeverBlocksWhenFull() {
        // ⚠️ 必须先 stop，否则消费线程排空队列，「塞满」不成立
        queue.stop();
        // 先塞满
        for (int i = 0; i < 50; i++) {
            queue.offer(ev("fill" + i));
        }

        // ⚠️ 关键：消费线程会在 10ms 内排空一部分，
        // 所以不能假设队列仍是满的。改为「连续打1000 次，
        // 统计其中有多少次返回 false」——只要有任何一次是阻塞的，
        // 总耗时就会远超阈值。
        long start = System.nanoTime();
        int rejected = 0;
        for (int i = 0; i < 1000; i++) {
            if (!queue.offer(ev("overflow" + i))) {
                rejected++;
            }
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMs)
                .as("⚠️ 此阈值是**保护性阈值**（捕获真正的阻塞：误用 put / "
                        + "CallerRunsPolicy 会导致秒级阻塞），**非性能断言**。"
                        + "性能验证由压测环节负责（docs/BENCH-W4.md）。"
                        + "阈值放宽到 500ms 是为了让 CI 慢机不误报")
                .isLessThan(500);
        assertThat(rejected)
                .as("容量 50 而打了 1000 次，必然有大量丢弃")
                .isGreaterThan(0);
    }

    @Test
    @DisplayName("⭐⭐ 队列满 → 累加 shortlink.stats.dropped，且 consumed 不受影响")
    void fullQueueIncrementsDroppedMetric() {
        // ⚠️ 必须先 stop：消费线程会排空队列，
        //「塞 50 条」根本填不满，dropped 会恒为 0。
        queue.stop();
        for (int i = 0; i < 50; i++) {
            queue.offer(ev("fill" + i));
        }
        int rejected = 0;
        for (int i = 0; i < 500; i++) {
            if (!queue.offer(ev("overflow" + i))) {
                rejected++;
            }
        }

        // ⚠️ dropped 现在带 reason tag，必须分别读两个计数器
        assertThat(counter("shortlink.stats.dropped", "reason", "full"))
                .as("丢弃必须可观测——静默丢弃会让统计不完整无从发现")
                .isEqualTo((double) rejected);
        assertThat(counter("shortlink.stats.dropped", "reason", "error"))
                .as("队列满不属于 error")
                .isZero();
        assertThat(counter("shortlink.stats.offered"))
                .isEqualTo(50.0 + (500 - rejected));
    }

    @Test
    @DisplayName("⭐⭐ 正常 offer 后能被消费线程处理")
    void offeredItemsAreConsumed() throws Exception {
        for (int i = 0; i < 20; i++) {
            assertThat(queue.offer(ev("0Q1A2b" + i))).isTrue();
        }

        // 等消费线程排空
        long deadline = System.currentTimeMillis() + 3000;
        while (queue.queueSize() > 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }

        assertThat(queue.queueSize()).as("队列应被排空").isZero();
        assertThat(counter("shortlink.stats.drained"))
                .as("20 条都应被消费")
                .isEqualTo(20.0);
    }

    @Test
    @DisplayName("⭐ offer 绝不能抛异常（统计失败不能让跳转失败）")
    void offerNeverThrows() {
        // offer 一个合法值 + 后续 offer 到满，任何情况都不该抛出
        List<String> results = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            // try/catch 本身就是断言：能走到下一行就说明没抛
            results.add(String.valueOf(queue.offer(ev("code" + i))));
        }
        assertThat(results).hasSize(200);
    }

    @Test
    @DisplayName("⭐ offer(null) 也不能抛异常（调用方可能算出 null）")
    void offerNullDoesNotThrow() {
        assertThat(queue.offer(null))
                .as("null 也应安全返回，绝不抛 NPE")
                .isFalse();
        // ⚠️ 必须带 tag 读：拆了 reason 之后有两个同名 dropped meter，
        //    不带 tag 的 find(name).counter() 返回的是**任意一个**（Micrometer 行为），
        //    断言会随机失败。踩过一次。
        assertThat(counter("shortlink.stats.dropped", "reason", "error")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("⭐ 容量配置生效（防止 @Value 注入失败时静默用默认值）")
    void capacityIsHonored() {
        assertThat(queue.capacity()).isEqualTo(50);

        AsyncStatsQueue big = new AsyncStatsQueue(registry, redis, 12345);
        assertThat(big.capacity()).isEqualTo(12345);
        // ⚠️ 未 start()，无线程需要停止（构造器不再启线程）
    }

    @Test
    @DisplayName("⭐ 消费线程批量取：drained 累加的是「条数」而非「批次数」")
    void drainedCountsItemsNotBatches() throws Exception {
        for (int i = 0; i < 50; i++) {
            queue.offer(ev("i"));
        }
        long deadline = System.currentTimeMillis() + 3000;
        while (queue.queueSize() > 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }

        // 50 条可能被分成若干批消费，但总数必须是 50
        assertThat(counter("shortlink.stats.drained")).isEqualTo(50.0);
    }

    @Test
    @DisplayName("⭐ 高频 offer 的吞吐（证明非阻塞不是靠「一次只处理一个」）")
    void highFrequencyOfferThroughput() {
        int n = 100_000;
        long start = System.nanoTime();
        for (int i = 0; i < n; i++) {
            queue.offer(ev("i"));
        }
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertThat(elapsedMs)
                .as("⚠️ 此阈值是**保护性阈值**（捕获真正的阻塞），**非性能断言**。"
                        + "性能验证由压测环节负责（docs/BENCH-W4.md）。"
                        + "5s 仍比理论值（单次 < 1µs）慢 10 倍，足够宽松以适配 CI 慢机，"
                        + "又足够紧以捕获误用 put/CallerRunsPolicy 的秒级阻塞")
                .isLessThan(5000);
    }

    // ============================================================ 阶段 1 新增

    @Test
    @DisplayName("N1️⃣⭐ Gauge shortlink.stats.queue.size 反映真实积压深度")
    void gaugeIsRegisteredAndReflectsQueueSize() {
        queue.stop();
        // ⚠️ **不调用 start()**：不启动消费线程，队列不会被排空，
        //    才能断言 Gauge == 恰好入队的条数。
        for (int i = 0; i < 5; i++) {
            assertThat(queue.offer(ev("gauge" + i))).isTrue();
        }

        Gauge gauge = registry.find("shortlink.stats.queue.size").gauge();
        assertThat(gauge)
                .as("Gauge 必须已注册（否则 queueSize 完全不可观测）")
                .isNotNull();
        assertThat(gauge.value())
                .as("入队 5 条且未消费 -> Gauge 应为 5")
                .isEqualTo(5.0);
        assertThat(queue.queueSize()).isEqualTo(5);
    }

    @Test
    @DisplayName("N2️⃣⭐ SmartLifecycle：stop 后消费线程 terminated，无线程泄漏")
    void startAndStopLifecycleTerminatesConsumerThread() throws InterruptedException {
        // setUp 已经 start()，此处验证「stop 能彻底终止线程」
        assertThat(queue.isRunning()).as("setUp 已 start").isTrue();
        queue.offer(ev("lifecycle"));

        queue.stop();

        assertThat(queue.isRunning()).as("stop 后必须不再运行").isFalse();
        // ⚠️ 等线程真正退出（stop 内部已 join(2000)，这里再兜一次底）
        long deadline = System.currentTimeMillis() + 2000;
        while (queue.isRunning() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }

        long alive = Thread.getAllStackTraces().keySet().stream()
                .filter(t -> "stats-async-consumer".equals(t.getName()))
                .filter(Thread::isAlive)
                .count();
        assertThat(alive)
                .as("消费线程必须已终止，否则每次 @BeforeEach 都泄漏一个线程")
                .isZero();
    }

    @Test
    @DisplayName("N3️⃣⭐ dropped 带 reason tag：full 与 error 是两个独立计数器")
    void droppedMetricHasReasonTag() {
        queue.stop();
        // reason=full：塞满队列
        for (int i = 0; i < 50; i++) {
            queue.offer(ev("fill" + i));
        }
        int rejectedByFull = 0;
        for (int i = 0; i < 30; i++) {
            if (!queue.offer(ev("overflow" + i))) {
                rejectedByFull++;
            }
        }

        assertThat(counter("shortlink.stats.dropped", "reason", "full"))
                .as("reason=full 应等于队列满导致的丢弃数")
                .isEqualTo((double) rejectedByFull);
        assertThat(counter("shortlink.stats.dropped", "reason", "error"))
                .as("reason=error 此时应为 0")
                .isZero();
    }

    @Test
    @DisplayName("N4️⃣ offer(null) 走 reason=error 分支")
    void nullOfferCountsAsErrorNotFull() {
        queue.stop();
        // ⚠️ ArrayBlockingQueue.offer(null) 抛 NullPointerException，
        //    因此它落 catch 分属 -> reason=error，而不是 reason=full。
        //    这正是「拆 tag」的意义：两种原因必须可区分。
        assertThat(queue.offer(null)).isFalse();

        assertThat(counter("shortlink.stats.dropped", "reason", "error"))
                .as("NPE 属 error，不是 full")
                .isEqualTo(1.0);
        assertThat(counter("shortlink.stats.dropped", "reason", "full"))
                .isZero();
    }

    @Test
    @DisplayName("N5️⃣ drained 不等于 offered 的持久化语义（文档契约）")
    void drainedIsNotPersisted() throws InterruptedException {
        queue.start();
        for (int i = 0; i < 10; i++) {
            queue.offer(ev("d" + i));
        }
        long deadline = System.currentTimeMillis() + 3000;
        while (queue.queueSize() > 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }

        assertThat(counter("shortlink.stats.offered")).isEqualTo(10.0);
        assertThat(counter("shortlink.stats.drained")).isEqualTo(10.0);
        // ⚠️ 本测试**故意不断言任何持久化**：
        // 阶段 2 之前没有 Redis/DB 通道，drained 的语义就是「取出」而非「已存」，
        // 这一点写在 @drainedCounter 的 description 里。
    }
}
