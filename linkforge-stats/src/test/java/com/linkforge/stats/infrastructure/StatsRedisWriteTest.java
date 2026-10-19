package com.linkforge.stats.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * 阶段 3 前置修补（F1/F2/F4）的验证。
 *
 * <p>⚠️ 本类只测「Java 侧调了什么」。<b>序列化与真实 Redis 的行为由层 3 覆盖</b>——
 * DR-054 的教训正是「mock 的形态由测试者构造，与真实序列化器可能不一致」。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StatsRedisWriteTest {

    private static final long TTL_SECONDS = TimeUnit.DAYS.toSeconds(35);

    private SimpleMeterRegistry registry;

    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ListOperations<String, String> listOps;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(1L);
        when(redis.opsForList()).thenReturn(listOps);
    }

    private AsyncStatsQueue newQueue(int capacity) {
        return new AsyncStatsQueue(registry, redis, capacity);
    }

    private static StatsEvent ev(String code, String visitor) {
        return new StatsEvent(code, visitor, System.currentTimeMillis());
    }

    private static StatsEvent evAt(String code, String visitor, LocalDateTime at) {
        long ts = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        return new StatsEvent(code, visitor, ts);
    }

    private static String fmt(LocalDate d) {
        return d.format(DateTimeFormatter.BASIC_ISO_DATE);
    }

    private void awaitDrained(AsyncStatsQueue q) {
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
            Thread.sleep(150);
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

    /** 捕获所有 Lua 调用，返回 [key, argv...] 的列表。 */
    private List<String[]> capturedLuaCalls() {
        ArgumentCaptor<List> keys = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<Object[]> argv = ArgumentCaptor.forClass(Object[].class);
        verify(redis, org.mockito.Mockito.atLeast(0))
                .execute(any(RedisScript.class), keys.capture(), argv.capture());
        List<String[]> out = new ArrayList<>();
        for (int i = 0; i < keys.getAllValues().size(); i++) {
            Object[] a = argv.getAllValues().get(i);
            String[] pair = new String[a.length + 1];
            pair[0] = String.valueOf(keys.getAllValues().get(i).get(0));
            for (int j = 0; j < a.length; j++) {
                pair[j + 1] = String.valueOf(a[j]);
            }
            out.add(pair);
        }
        return out;
    }

    // ======================================================== F1：按条计数

    @Test
    @DisplayName("F1️⃣⭐⭐⭐ drained == flushed{ok} + flushed{failed} + 队列剩余（真验算）")
    void drainedEqualsFlushedOkPlusFailedPlusQueueRemaining() throws Exception {
        AsyncStatsQueue q = newQueue(1000);
        try {
            for (int i = 0; i < 30; i++) {
                q.offer(i % 3 == 0
                        ? ev("c" + (i % 2), "1.1.1." + (i % 5))
                        : ev("c" + (i % 2), null));
            }
            q.start();
            awaitDrained(q);

            double drained = counter("shortlink.stats.drained");
            double pvOk = counter("shortlink.stats.flushed", "target", "pv", "result", "ok");
            double pvFailed = counter("shortlink.stats.flushed", "target", "pv", "result", "failed");
            double remaining = q.queueSize();

            assertThat(drained).as("drained 单位是条").isEqualTo(30.0);
            assertThat(pvOk + pvFailed + remaining)
                    .as("⚠️ F1 的核心不变式：drained(条) == flushed{ok}(条) + "
                            + "flushed{failed}(条) + 队列剩余(条)。"
                            + "阶段 2 按批计数时这条等式不成立")
                    .isEqualTo(drained);

            double uvOk = counter("shortlink.stats.flushed", "target", "uv", "result", "ok");
            double uvFailed = counter("shortlink.stats.flushed", "target", "uv", "result", "failed");
            assertThat(uvOk + uvFailed)
                    .as("不等式：flushed{uv} <= flushed{pv}（UV 只统计 visitor 非 null 的）")
                    .isLessThanOrEqualTo(pvOk + pvFailed);
        } finally {
            q.stop();
        }
    }

    @Test
    @DisplayName("F1️⃣b 100 条同一 code -> flushed{ok} +100（**不是 +1**）")
    void flushedOkIncrementsByItemCountNotBatchCount() throws Exception {
        AsyncStatsQueue q = newQueue(1000);
        try {
            for (int i = 0; i < 100; i++) {
                q.offer(ev("0Q1A2b3", null));
            }
            q.start();
            awaitDrained(q);

            assertThat(counter("shortlink.stats.flushed", "target", "pv", "result", "ok"))
                    .as("F1：必须 +100（条）。阶段 2 是 +1（批），导致对不上账")
                    .isEqualTo(100.0);
        } finally {
            q.stop();
        }
    }

    // ======================================================== F2：日界推导

    @Test
    @DisplayName("F2️⃣⭐⭐⭐ 跨零点批次写到两个不同的 PV key（F2 核心）")
    void batchSpanningMidnightGoesToDifferentDays() throws Exception {
        LocalDate day1 = LocalDate.of(2026, 3, 14);
        LocalDate day2 = day1.plusDays(1);

        AsyncStatsQueue q = newQueue(1000);
        try {
            q.offer(evAt("0Q1A2b3", null, LocalDateTime.of(day1, LocalTime.of(23, 59, 59, 999_000_000))));
            q.offer(evAt("0Q1A2b3", null, LocalDateTime.of(day1, LocalTime.of(23, 59, 59, 999_000_000))));
            q.offer(evAt("0Q1A2b3", null, LocalDateTime.of(day2, LocalTime.of(0, 0, 0, 1_000_000))));
            q.offer(evAt("0Q1A2b3", null, LocalDateTime.of(day2, LocalTime.of(0, 0, 0, 1_000_000))));
            q.start();
            awaitDrained(q);

            List<String> pvKeys = capturedLuaCalls().stream()
                    .map(a -> a[0])
                    .filter(k -> k.startsWith("shortlink:pv:"))
                    .toList();

            assertThat(pvKeys)
                    .as("⚠️ F2 核心：跨零点的 4 条必须分成两个 key，"
                            + "而不是全部算到「处理当天」")
                    .containsExactlyInAnyOrder(
                            "shortlink:pv:0Q1A2b3:" + fmt(day1),
                            "shortlink:pv:0Q1A2b3:" + fmt(day2));

            for (String[] call : capturedLuaCalls()) {
                if (call[0].startsWith("shortlink:pv:")) {
                    assertThat(call[1]).as("每天各 2 条").isEqualTo("2");
                }
            }
        } finally {
            q.stop();
        }
    }

    // ======================================================== F4：Lua 原子

    @Test
    @DisplayName("F4️⃣⭐⭐ PV 用 Lua 执行，**不再有独立的 expire 调用**")
    void pvUsesLuaInsteadOfSeparateExpire() throws Exception {
        AsyncStatsQueue q = newQueue(1000);
        try {
            q.offer(ev("0Q1A2b3", null));
            q.start();
            awaitDrained(q);

            verify(redis, times(1)).execute(any(RedisScript.class), anyList(), any(Object[].class));
            // ⚠️ 旧路径必须彻底消失：两条命令之间崩溃会留下永不过期的 key
            verify(redis, never()).opsForValue();
            verify(redis, never()).expire(anyString(),
                    org.mockito.ArgumentMatchers.anyLong(), any(TimeUnit.class));

            List<String[]> calls = capturedLuaCalls();
            assertThat(calls.get(0)[1]).as("ARGV[1] = 增量").isEqualTo("1");
            assertThat(calls.get(0)[2])
                    .as("ARGV[2] = TTL 秒数（35 天）")
                    .isEqualTo(String.valueOf(TTL_SECONDS));
        } finally {
            q.stop();
        }
    }

    @Test
    @DisplayName("F4️⃣b⭐⭐ UV 用 Lua：1 次调用、3 个 visitor（DR-054 形态验证见层 3）")
    void uvUsesLuaWithTtlFirstThenVisitors() throws Exception {
        AsyncStatsQueue q = newQueue(1000);
        try {
            q.offer(ev("0Q1A2b3", "1.1.1.1"));
            q.offer(ev("0Q1A2b3", "2.2.2.2"));
            q.offer(ev("0Q1A2b3", "3.3.3.3"));
            q.start();
            awaitDrained(q);

            List<String[]> uvCalls = capturedLuaCalls().stream()
                    .filter(a -> a[0].startsWith("shortlink:uv:"))
                    .toList();

            assertThat(uvCalls)
                    .as("3 个 visitor 合并成 1 次 UV Lua 调用")
                    .hasSize(1);
            String[] call = uvCalls.get(0);
            // ⚠️ 这里只断言「TTL 在最前」与「3 个 visitor 都在」，
            //    **不断言 argv 的确切长度**——Mockito 的 ArgumentCaptor
            //    记录的是它自己构造的调用形态，varargs 展开的细节它反映不准。
            //    真正的验证（ARGV 是否被正确展开成 4 个独立值、
            //    PFCOUNT 是否 == 3）在 StatsRedisIntegrationTest 用真实 Redis 做。
            assertThat(call[1])
                    .as("ARGV[1] 必须是 TTL，不能是 visitor（否则 Lua 会把 TTL 当 visitor 记进 HLL）")
                    .isEqualTo(String.valueOf(TTL_SECONDS));
            assertThat(Arrays.asList(call))
                    .as("3 个 visitor 都必须在参数里")
                    .contains("1.1.1.1", "2.2.2.2", "3.3.3.3");

            // ⚠️ 旧路径必须消失
            verify(redis, never()).opsForHyperLogLog();
        } finally {
            q.stop();
        }
    }

    @Test
    @DisplayName("F4️⃣c null visitor 不进 UV Lua，但 PV 照常写")
    void nullVisitorSkipsUvButPvStillWritten() throws Exception {
        AsyncStatsQueue q = newQueue(1000);
        try {
            q.offer(ev("0Q1A2b3", null));
            q.offer(ev("0Q1A2b3", null));
            q.offer(ev("0Q1A2b3", "1.1.1.1"));
            q.start();
            awaitDrained(q);

            List<String[]> calls = capturedLuaCalls();
            assertThat(calls.stream().filter(a -> a[0].startsWith("shortlink:pv:")).count())
                    .as("3 条 PV 合并成 1 次 Lua").isEqualTo(1L);
            assertThat(calls.stream().filter(a -> a[0].startsWith("shortlink:uv:")).count())
                    .as("只有 1 个非 null visitor -> 1 次 UV Lua").isEqualTo(1L);
        } finally {
            q.stop();
        }
    }

    @Test
    @DisplayName("F4️⃣d 全 null visitor 时**完全不调 UV Lua**")
    void allNullVisitorsSkipsUvScriptEntirely() throws Exception {
        AsyncStatsQueue q = newQueue(1000);
        try {
            q.offer(ev("0Q1A2b3", null));
            q.offer(ev("0Q1A2b3", null));
            q.start();
            awaitDrained(q);

            assertThat(capturedLuaCalls().stream()
                    .noneMatch(a -> a[0].startsWith("shortlink:uv:")))
                    .as("null visitor 会被 PFADD NPE，必须提前过滤")
                    .isTrue();
        } finally {
            q.stop();
        }
    }

    // ======================================================== 失败处理

    @Test
    @DisplayName("X3️⃣ flush.duration{target=pv|uv} 被记录且 totalTime > 0")
    void flushDurationRecorded() {
        AsyncStatsQueue q = newQueue(1000);
        q.start();
        try {
            q.offer(ev("0Q1A2b3", "1.1.1.1"));
            awaitDrained(q);

            var pvTimer = registry.find("shortlink.stats.flush.duration")
                    .tag("target", "pv").timer();
            var uvTimer = registry.find("shortlink.stats.flush.duration")
                    .tag("target", "uv").timer();

            assertThat(pvTimer).isNotNull();
            assertThat(pvTimer.count()).isEqualTo(1L);
            assertThat(pvTimer.totalTime(TimeUnit.NANOSECONDS)).isGreaterThan(0L);
            assertThat(uvTimer).isNotNull();
        } finally {
            q.stop();
        }
    }
}