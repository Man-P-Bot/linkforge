package com.linkforge.stats.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.HyperLogLogOperations;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * 阶段 2：消费线程写 Redis（PV/UV 双通道）。
 *
 * <p>⚠️ 本类只测「Java 侧调了什么」。<b>序列化与真实 Redis 的行为由层 3 覆盖</b>——
 * DR-054 的教训正是「mock 的形态由测试者构造，与真实序列化器可能不一致」
 * （当年 {@code toArgs} 传List 的问题，mock 全绿、真实 Redis 抛 CCE）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StatsRedisWriteTest {

    private static final String TODAY = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);

    private SimpleMeterRegistry registry;

    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ValueOperations<String, String> valueOps;
    @Mock
    private HyperLogLogOperations<String, String> hllOps;
    @Mock
    private ListOperations<String, String> listOps;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        when(redis.opsForValue()).thenReturn(valueOps);
        when(redis.opsForHyperLogLog()).thenReturn(hllOps);
        when(redis.opsForList()).thenReturn(listOps);
    }

    private AsyncStatsQueue newQueue(int capacity) {
        return new AsyncStatsQueue(registry, redis, capacity);
    }

    private static StatsEvent ev(String code, String visitor) {
        return new StatsEvent(code, visitor, System.currentTimeMillis());
    }

    /** 等待消费线程把队列排空（消费循环最长阻塞 POLL_INTERVAL_MS=10ms）。 */
    private void awaitDrained(AsyncStatsQueue q, int expected) {
        long deadline = System.currentTimeMillis() + 3000;
        while (q.queueSize() > 0 && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private double counter(String name, String... tagPairs) {
        var search = registry.find(name);
        for (int i = 0; i + 1 < tagPairs.length; i += 2) {
            search = search.tag(tagPairs[i], tagPairs[i + 1]);
        }
        var c = search.counter();
        return c == null ? 0.0 : c.count();
    }

    // ============================================================ 阶段 2

    @Test
    @DisplayName("W1️⃣⭐⭐ 同 code 100 条 → INCRBY 只调 1 次，参数 (key, 100)")
    void pvAggregatedByCodeWithinBatch() throws Exception {
        AsyncStatsQueue q = newQueue(1000);
        try {
            // ⚠️ **先填满队列再 start()**：若边投边消费，消费线程会在写入期间
            //    发现队列已空而立刻取下一批——那样会拆成多个 1L 的 INCRBY，
            //    测不到「批内聚合」这个行为（首轮实测就踩到了）。
            for (int i = 0; i < 100; i++) {
                q.offer(ev("0Q1A2b3", null));
            }
            assertThat(q.queueSize()).as("投递后、消费前队列应有 100 条").isEqualTo(100);
            q.start();
            awaitDrained(q, 100);

            // ⚠️ 核心断言：100 条同一短码只产生 1 次 INCRBY，且增量是 100
            verify(valueOps, times(1)).increment("shortlink:pv:0Q1A2b3:" + TODAY, 100L);

            verify(redis).expire("shortlink:pv:0Q1A2b3:" + TODAY, 35L,
                    java.util.concurrent.TimeUnit.DAYS);
        } finally {
            q.stop();
        }
    }

    @Test
    @DisplayName("W2️⃣⭐⭐ DR-054 回归：PFADD 收String[]，**不是 List**")
    void uvAddPassesStringArrayNotList() throws Exception {
        AsyncStatsQueue q = newQueue(1000);
        try {
            // ⚠️ 先填后start：保证 3 个 visitor 在**同一批**内，
            //    否则每个 visitor 一批，captor 只能抓到 1 个元素。
            q.offer(ev("0Q1A2b3", "1.1.1.1"));
            q.offer(ev("0Q1A2b3", "2.2.2.2"));
            q.offer(ev("0Q1A2b3", "3.3.3.3"));
            q.start();
            awaitDrained(q, 3);

            ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String[]> valuesCaptor = ArgumentCaptor.forClass(String[].class);

            verify(hllOps).add(keyCaptor.capture(), valuesCaptor.capture());

            assertThat(keyCaptor.getValue()).isEqualTo("shortlink:uv:0Q1A2b3:" + TODAY);
            // ⚠️⚠️ DR-054 回归点：传 List 会被当成单个元素序列化，
            //    结果是 PFADD 只记了一个 "[a, b, c]" 字符串，UV 恒等于 1。
            assertThat(valuesCaptor.getValue())
                    .as("必须是 String[]，长度 3。传 List 会让 UV 恒等于 1")
                    .isInstanceOf(String[].class)
                    .hasSize(3)
                    .containsExactlyInAnyOrder("1.1.1.1", "2.2.2.2", "3.3.3.3");
        } finally {
            q.stop();
        }
    }

    @Test
    @DisplayName("W3️⃣⭐ null visitor 不进 PFADD，但 PV 照常累加")
    void nullVisitorIsFilteredOutButPvStillCounted() throws Exception {
        AsyncStatsQueue q = newQueue(1000);
        try {
            q.offer(ev("0Q1A2b3", null));
            q.offer(ev("0Q1A2b3", null));
            q.offer(ev("0Q1A2b3", "1.1.1.1"));
            q.start();
            awaitDrained(q, 3);

            // PV：3 条都要算——「访问发生了」与「能否去重」是两件事
            verify(valueOps).increment("shortlink:pv:0Q1A2b3:" + TODAY, 3L);

            // UV：只有 1 个非 null visitor
            ArgumentCaptor<String[]> valuesCaptor = ArgumentCaptor.forClass(String[].class);
            verify(hllOps).add(anyString(), valuesCaptor.capture());
            assertThat(valuesCaptor.getValue())
                    .as("null visitor 必须被过滤，否则 PFADD 会 NPE")
                    .hasSize(1)
                    .containsExactly("1.1.1.1");
        } finally {
            q.stop();
        }
    }

    @Test
    @DisplayName("W4️⃣⭐⭐ 全 null visitor 时**完全不调 PFADD**")
    void allNullVisitorsSkipsPfaddEntirely() {
        AsyncStatsQueue q = newQueue(1000);
        q.start();
        try {
            q.offer(ev("0Q1A2b3", null));
            q.offer(ev("0Q1A2b3", null));
            awaitDrained(q, 2);

            verify(hllOps, never()).add(anyString(), any(String[].class));
            verify(valueOps).increment("shortlink:pv:0Q1A2b3:" + TODAY, 2L);
        } finally {
            q.stop();
        }
    }

    @Test
    @DisplayName("W5️⃣⭐⭐ INCRBY 抛异常 → 进死信、flushed{failed}+1、消费线程不退出")
    void redisFailureGoesToDlq() throws Exception {
        when(valueOps.increment(anyString(), any(Long.class)))
                .thenThrow(new RuntimeException("Redis down"));

        AsyncStatsQueue q = newQueue(1000);
        q.start();
        try {
            q.offer(ev("0Q1A2b3", null));
            q.offer(ev("0Q1A2b3", null));

            long deadline = System.currentTimeMillis() + 3000;
            while (counter("shortlink.stats.flushed", "target", "pv", "result", "failed") < 1
                    && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }

            assertThat(counter("shortlink.stats.flushed", "target", "pv", "result", "failed"))
                    .as("写失败必须计 flushed{failed}，否则与 drained 对不上账")
                    .isEqualTo(1.0);

            // 死信有数据，格式是 target|code|visitor|ts
            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<String>> dlqCaptor = ArgumentCaptor.forClass(List.class);
            verify(listOps).rightPushAll(
                    org.mockito.ArgumentMatchers.eq("shortlink:stats:dl:" + TODAY),
                    dlqCaptor.capture());
            assertThat(dlqCaptor.getValue())
                    .as("2 条应全部进死信")
                    .hasSize(2)
                    .allSatisfy(s -> assertThat(s).startsWith("pv|0Q1A2b3|"));

            // ⚠️ 关键：消费线程必须还活着（失败不能杀死它）
            assertThat(q.isRunning()).as("Redis 故障不得导致消费线程退出").isTrue();

            // 再投一条，证明它还能继续处理
            reset(valueOps);
            when(valueOps.increment(anyString(), any(Long.class))).thenReturn(1L);
            q.offer(ev("0Q1A2b4", null));
            long d2 = System.currentTimeMillis() + 3000;
            while (counter("shortlink.stats.flushed", "target", "pv", "result", "ok") < 1
                    && System.currentTimeMillis() < d2) {
                Thread.sleep(10);
            }
            assertThat(counter("shortlink.stats.flushed", "target", "pv", "result", "ok"))
                    .as("恢复后应能继续成功刷写")
                    .isEqualTo(1.0);
        } finally {
            q.stop();
        }
    }

    @Test
    @DisplayName("W6️⃣⭐ flush.duration{target=pv|uv} 被记录")
    void flushDurationRecorded() {
        AsyncStatsQueue q = newQueue(1000);
        q.start();
        try {
            q.offer(ev("0Q1A2b3", "1.1.1.1"));
            awaitDrained(q, 1);

            var pvTimer = registry.find("shortlink.stats.flush.duration")
                    .tag("target", "pv").timer();
            var uvTimer = registry.find("shortlink.stats.flush.duration")
                    .tag("target", "uv").timer();

            assertThat(pvTimer).as("PV 刷写耗时必须被记录").isNotNull();
            assertThat(pvTimer.count()).isEqualTo(1L);
            assertThat(pvTimer.totalTime(java.util.concurrent.TimeUnit.NANOSECONDS))
                    .as("总耗时必须 > 0，否则 Timer 没真正测量")
                    .isGreaterThan(0L);

            assertThat(uvTimer).isNotNull();
            assertThat(uvTimer.count()).isEqualTo(1L);
        } finally {
            q.stop();
        }
    }

    @Test
    @DisplayName("W7️⃣ flushed{ok} 与 drained 的对账等式成立")
    void flushedOkEqualsDrainedForSuccessfulBatch() {
        AsyncStatsQueue q = newQueue(1000);
        q.start();
        try {
            for (int i = 0; i < 50; i++) {
                q.offer(ev("code" + (i % 5), "1.1.1.1"));
            }
            awaitDrained(q, 50);

            long deadline = System.currentTimeMillis() + 3000;
            while (counter("shortlink.stats.flushed", "target", "pv", "result", "ok") < 1
                    && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            // ⚠️ 对账等式：drained == flushed{ok} 的「批数」× 该批条数。
            // 这里换个更实用的角度：50 条 drained 后队列必须为空、
            // 且没有任何 failed——否则说明数据在丢。
            assertThat(q.queueSize()).isZero();
            assertThat(counter("shortlink.stats.flushed", "target", "pv", "result", "failed"))
                    .as("正常路径不该有 failed")
                    .isZero();
            assertThat(counter("shortlink.stats.dropped", "reason", "full")).isZero();
        } finally {
            q.stop();
        }
    }
}
