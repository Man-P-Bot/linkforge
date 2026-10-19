package com.linkforge.stats.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * ⚠️⚠️ <b>层 3：真实 Redis 的全链路验证（F4 的核心验收）</b>
 *
 * <p><b>为什么必须有这一层，而不能只靠 Mockito</b>：
 * DR-054 的教训——「Lua 用 redis-cli 验过、解析用 mock 验过，
 * 但 Java 把参数交给 Spring Data Redis 这条链路从未跑通过」，
 * 上线后全500。
 *
 * <p>Mock 的 {@code ArgumentCaptor} 记录的是<b>Mockito 自己构造的调用形态</b>，
 * 它无法反映 varargs 展开、Lua 脚本解析、{@code PFADD} 语义这些真实行为。
 * 本类全部断言<b>Redis 里的实际值</b>。
 *
 * <p>覆盖：
 * <ul>
 *   <li>F4：INCRBY 与 EXPIRE 在同一脚本内执行 -> TTL 必须真的存在</li>
 *   <li>DR-054：PFADD 收到的是多个独立 visitor，PFCOUNT 结果正确</li>
 *   <li>F2：跨零点写入两个不同的日期 key</li>
 * </ul>
 */
/**
 * ⚠️ 用**本地 Redis 容器**（{@code REDIS_HOST=localhost:6379}）而非 Testcontainers：
 * 项目没有引入 testcontainers 依赖，且 E2E 测试本来就连本地容器
 * （application-test.yml 已指向 6379）。引入 Testcontainers 会给 stats 模块
 * 加 3 个 test 依赖，而它验证的「Lua 原子性」用本地 Redis 完全能验证。
 *
 * <p>⚠️ 用独立 db 索引（{@code REDIS_DB=15}）避免污染开发库的统计 key。
 */
@EnabledIfEnvironmentVariable(named = "REDIS_HOST", matches = ".+")
@DisplayName("真实 Redis 验证（F4 Lua 原子性 + DR-054 + F2 日界）")
class StatsRedisIntegrationTest {

    private StringRedisTemplate redis;
    private SimpleMeterRegistry registry;
    private AsyncStatsQueue queue;

    @BeforeEach
    void setUp() {
        RedisStandaloneConfiguration cfg = new RedisStandaloneConfiguration(
                System.getenv("REDIS_HOST"),
                Integer.parseInt(System.getenv().getOrDefault("REDIS_PORT", "6379")));
        cfg.setDatabase(Integer.parseInt(System.getenv().getOrDefault("REDIS_DB", "15")));
        // ⚠️ StringRedisTemplate 的构造器要的是 ConnectionFactory，
        //   不是 StandaloneConfiguration——必须包一层 LettuceConnectionFactory
        var factory = new org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory(cfg);
        // ⚠️ LettuceConnectionFactory 必须显式 start()，
        //   否则用的时候抛 "has been CREATED. Use start() to initialize it"
        factory.afterPropertiesSet();
        factory.start();
        redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
        registry = new SimpleMeterRegistry();
        queue = new AsyncStatsQueue(registry, redis, 1000);
    }

    @AfterEach
    void tearDown() {
        if (queue != null) {
            queue.stop();
        }
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();
    }

    private static StatsEvent evAt(String code, String visitor, LocalDateTime at) {
        return new StatsEvent(code, visitor,
                at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
    }

    private static String fmt(LocalDate d) {
        return d.format(DateTimeFormatter.BASIC_ISO_DATE);
    }

    /**
     * 等待消费完成。
     *
     * <p>⚠️⚠️ **不能只等 {@code queueSize() == 0}**：
     * 消费线程把元素从队列**取走**的那一刻，队列就空了，
     * 但它还在执行 flush（写 Redis）。此时断言会看到
     * 「队列空但 Redis 里没数据」——看起来像 flush 没跑。
     *
     * <p>正确判据：<b>等 flushed 指标出现</b>（它是 flush 真正完成的信号）。
     */
    private void awaitDrained() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 8000;
        while (System.currentTimeMillis() < deadline) {
            double ok = registry.find("shortlink.stats.flushed")
                    .tag("target", "pv").tag("result", "ok").counter().count();
            double failed = registry.find("shortlink.stats.flushed")
                    .tag("target", "pv").tag("result", "failed").counter().count();
            if (ok + failed > 0) {
                Thread.sleep(100);
                return;
            }
            Thread.sleep(20);
        }
        Thread.sleep(200);
    }

    @Test
    @DisplayName("R1️⃣⭐⭐⭐ F4：Lua 脚本让 INCRBY 与 EXPIRE **真正原子**（TTL 存在）")
    void luaScriptSetsTtlAtomically() throws Exception {
        queue.offer(new StatsEvent("0Q1A2b3", null, System.currentTimeMillis()));
        queue.offer(new StatsEvent("0Q1A2b3", null, System.currentTimeMillis()));
        queue.offer(new StatsEvent("0Q1A2b3", null, System.currentTimeMillis()));
        queue.start();
        awaitDrained();

        String key = "shortlink:pv:0Q1A2b3:" + fmt(LocalDate.now());
        assertThat(redis.opsForValue().get(key))
                .as("3 条 PV 应累加为 3")
                .isEqualTo("3");

        Long ttl = redis.getExpire(key);
        assertThat(ttl)
                .as("⚠️ F4 核心断言：TTL 必须存在。"
                        + "若返回 -1（永不过期）说明 INCRBY 与 EXPIRE 没在同一脚本内执行——"
                        + "进程在两条命令间崩溃就会留下永久 key")
                .isGreaterThan(0L);
        assertThat(ttl).isLessThanOrEqualTo(35L * 24 * 3600);
    }

    @Test
    @DisplayName("R2️⃣⭐⭐⭐ DR-054：PFADD 收到多个独立 visitor（PFCOUNT == 3，不是 1）")
    void pfaddReceivesDistinctVisitors() throws Exception {
        queue.offer(new StatsEvent("0Q1A2b3", "1.1.1.1", System.currentTimeMillis()));
        queue.offer(new StatsEvent("0Q1A2b3", "2.2.2.2", System.currentTimeMillis()));
        queue.offer(new StatsEvent("0Q1A2b3", "3.3.3.3", System.currentTimeMillis()));
        queue.start();
        awaitDrained();

        String key = "shortlink:uv:0Q1A2b3:" + fmt(LocalDate.now());
        Long count = redis.opsForHyperLogLog().size(key);

        assertThat(count)
                .as("⚠️ DR-054 回归：若ARGV 被当成单个元素（整个数组或 List 的 toString），"
                        + "PFADD 只会记一个字符串，PFCOUNT 恒等于 1")
                .isEqualTo(3L);

        assertThat(redis.getExpire(key))
                .as("UV key 也必须有 TTL（内存控制）")
                .isGreaterThan(0L);
    }

    @Test
    @DisplayName("R3️⃣⭐⭐ F2：跨零点的批次写到两个不同日期的 key")
    void batchSpanningMidnightGoesToDifferentDays() throws Exception {
        // ⚠️⚠️ 必须用**今天与昨天**，不能用固定的历史日期：
        //   PV/UV 的 TTL 只有 35 天，写入 2026-03-14 这种 200 多天前的日期，
        //   key 会被 Redis **立即淘汰**（TTL 从写入时刻起算，不是从事件时刻起算）。
        //   踩过一次这个坑：断言拿到 null，还以为 F2 没实现。
        LocalDate today = LocalDate.now();
        LocalDate yesterday = today.minusDays(1);
        LocalDate day1 = yesterday;
        LocalDate day2 = today;
        LocalTime t2359 = LocalTime.of(23, 59, 59, 999_000_000);
        LocalTime t0000 = LocalTime.of(0, 0, 0, 1_000_000);

        queue.offer(evAt("0Q1A2b3", null, LocalDateTime.of(day1, t2359)));
        queue.offer(evAt("0Q1A2b3", null, LocalDateTime.of(day1, t2359)));
        queue.offer(evAt("0Q1A2b3", null, LocalDateTime.of(day2, t0000)));
        queue.offer(evAt("0Q1A2b3", null, LocalDateTime.of(day2, t0000)));
        queue.start();
        awaitDrained();

        assertThat(redis.opsForValue().get("shortlink:pv:0Q1A2b3:" + fmt(day1)))
                .as("23:59 的 2 条").isEqualTo("2");
        assertThat(redis.opsForValue().get("shortlink:pv:0Q1A2b3:" + fmt(day2)))
                .as("00:00 的 2 条").isEqualTo("2");

        // ⚠️ 防止回归：若哪天有人把 dayOf() 改回「服务器当前日期」，
        //    上面两个断言里 yesterday 那个就会失败——这是刻意的。
        assertThat(redis.keys("shortlink:pv:0Q1A2b3:*"))
                .as("必须恰好两个日期的 key")
                .containsExactlyInAnyOrder(
                        "shortlink:pv:0Q1A2b3:" + fmt(day1),
                        "shortlink:pv:0Q1A2b3:" + fmt(day2));
    }

    @Test
    @DisplayName("R4️⃣⭐ null visitor 不进 HLL，但 PV 照常累加")
    void nullVisitorSkipsHllButPvStillCounted() throws Exception {
        queue.offer(new StatsEvent("0Q1A2b3", null, System.currentTimeMillis()));
        queue.offer(new StatsEvent("0Q1A2b3", "1.1.1.1", System.currentTimeMillis()));
        queue.start();
        awaitDrained();

        String day = fmt(LocalDate.now());
        assertThat(redis.opsForValue().get("shortlink:pv:0Q1A2b3:" + day))
                .as("2 条 PV 都要算").isEqualTo("2");
        assertThat(redis.opsForHyperLogLog().size("shortlink:uv:0Q1A2b3:" + day))
                .as("只有 1 个有效 visitor").isEqualTo(1L);
    }

    @Test
    @DisplayName("R5️⃣⭐ 对账：drained == flushed{ok} + flushed{failed}")
    void drainedEqualsFlushed() throws Exception {
        for (int i = 0; i < 50; i++) {
            queue.offer(new StatsEvent("c" + (i % 5),
                    i % 2 == 0 ? "10.0.0." + (i % 3) : null,
                    System.currentTimeMillis()));
        }
        queue.start();
        awaitDrained();

        double drained = registry.get("shortlink.stats.drained").counter().count();
        double pvOk = registry.find("shortlink.stats.flushed")
                .tag("target", "pv").tag("result", "ok").counter().count();
        double pvFailed = registry.find("shortlink.stats.flushed")
                .tag("target", "pv").tag("result", "failed").counter().count();

        assertThat(drained).isEqualTo(50.0);
        assertThat(pvOk + pvFailed)
                .as("⚠️ F1 核心：flushed 按条计数，drained 与之对账")
                .isEqualTo(drained);

        // 实际写入 Redis 的 PV 总数也应等于 drained
        long redisTotal = 0;
        for (int c = 0; c < 5; c++) {
            String v = redis.opsForValue().get("shortlink:pv:c" + c + ":" + fmt(LocalDate.now()));
            if (v != null) {
                redisTotal += Long.parseLong(v);
            }
        }
        assertThat((double) redisTotal)
                .as("Redis 里的 PV 总数必须等于 drained（数据真的落到位了）")
                .isEqualTo(drained);
    }

    // ============================================== 真实故障路径（DLQ）

    @Test
    @DisplayName("D1️⃣⭐⭐⭐ 写 Redis 失败 -> flushed{failed}+条数 + DLQ + 消费线程存活")
    void redisWriteFailureGoesToDlqAndConsumerSurvives() throws Exception {
        // ⚠️ 用「一个必然抛异常的 Lua 脚本」模拟写失败：
        //   · mock StringRedisTemplate.execute 的泛型匹配极不可靠
        //     （RedisScript<T> 擦除后 stub 不生效，实测两次都返回 null）
        //   · 「真实连不上的 Redis」也不行：Lettuce 长时间重试连接，
        //     15 秒都到不了 flush 那一步
        //   换成坏脚本后，走的是**完全真实的** execute 路径
        //   （真实序列化、真实异常传播），只有脚本本身是坏的。
        org.springframework.data.redis.core.script.DefaultRedisScript<Long> badScript =
                new org.springframework.data.redis.core.script.DefaultRedisScript<>();
        badScript.setScriptText("return redis.call('THIS_COMMAND_DOES_NOT_EXIST')");
        badScript.setResultType(Long.class);

        AsyncStatsQueue q = AsyncStatsQueue.withScripts(registry, redis, 1000, badScript, badScript);
        try {
            q.offer(new StatsEvent("0Q1A2b3", null, System.currentTimeMillis()));
            q.offer(new StatsEvent("0Q1A2b3", null, System.currentTimeMillis()));
            // ⚠️ 必须显式 start()：SmartLifecycle 改造后线程不在构造器里启动
            q.start();

            long deadline = System.currentTimeMillis() + 8000;
            while (registry.find("shortlink.stats.flushed")
                    .tag("target", "pv").tag("result", "failed").counter().count() < 1
                    && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }

            assertThat(registry.find("shortlink.stats.flushed")
                    .tag("target", "pv").tag("result", "failed").counter().count())
                    .as("F1：Redis 写失败必须按条计入 flushed{failed}")
                    .isEqualTo(2.0);

            assertThat(q.isRunning())
                    .as("⚠️ 写失败也不能让消费线程退出——"
                            + "它退出后队列会满，offer 开始丢弃，统计彻底停摆")
                    .isTrue();

            // DLQ 里应该有数据（Redis 本身活着，只是脚本坏了）
            String dlqKey = "shortlink:stats:dl:"
                    + java.time.LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
            assertThat(redis.opsForList().size(dlqKey))
                    .as("2 条进死信")
                    .isEqualTo(2L);
        } finally {
            q.stop();
        }
    }

    @Test
    @DisplayName("D2️⃣⭐⭐ DLQ 也写失败 -> dlq.lost{stage=redis} 按条计数")
    void dlqWriteFailureIncrementsLostMetric() throws Exception {
        // ⚠️ 场景：Redis 整体不可用 -> 写失败 + DLQ 也失败 -> 数据真没了。
        //   用「PV 脚本坏 + DLQ 也指向同一个坏脚本」模拟：
        //   DLQ 用的是 opsForList，不走脚本，所以改用
        //   「让 redis template 本身对 List 操作也抛异常」——
        //   这里用 spy 包装，最小侵入。
        org.springframework.data.redis.core.script.DefaultRedisScript<Long> badScript =
                new org.springframework.data.redis.core.script.DefaultRedisScript<>();
        badScript.setScriptText("return redis.call('THIS_COMMAND_DOES_NOT_EXIST')");
        badScript.setResultType(Long.class);

        // DLQ 走 redis.opsForList().rightPushAll —— 用一个会抛的 ListOperations
        org.springframework.data.redis.core.ListOperations<String, String> throwingListOps =
                org.mockito.Mockito.mock(org.springframework.data.redis.core.ListOperations.class);
        org.mockito.Mockito.doThrow(new RuntimeException("DLQ write failed"))
                .when(throwingListOps).rightPushAll(anyString(), anyList());

        StringRedisTemplate spyRedis = org.mockito.Mockito.spy(redis);
        org.mockito.Mockito.doReturn(throwingListOps)
                .when(spyRedis).opsForList();

        AsyncStatsQueue q = AsyncStatsQueue.withScripts(registry, spyRedis, 1000, badScript, badScript);
        try {
            q.offer(new StatsEvent("0Q1A2b3", null, System.currentTimeMillis()));
            q.offer(new StatsEvent("0Q1A2b3", null, System.currentTimeMillis()));
            // ⚠️ 必须显式 start()：SmartLifecycle 改造后线程不在构造器里启动
            q.start();

            long deadline = System.currentTimeMillis() + 8000;
            while (registry.find("shortlink.stats.dlq.lost")
                    .tag("target", "pv").tag("stage", "redis").counter().count() < 1
                    && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }

            assertThat(registry.find("shortlink.stats.dlq.lost")
                    .tag("target", "pv").tag("stage", "redis").counter().count())
                    .as("⚠️ 只有日志不够——数据真的没了，必须可观测。按条计数 = 2")
                    .isEqualTo(2.0);
        } finally {
            q.stop();
        }
    }
}
