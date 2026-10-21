package com.linkforge.stats.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.linkforge.core.repository.LinkRepository;
import com.linkforge.core.repository.StatsRepository;
import com.linkforge.core.repository.StatsRepository.PvRow;
import com.linkforge.core.repository.StatsRepository.UvRow;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * ⚠️⚠️ <b>层 3：真实 Redis 验证「写侧登记 dirty set → 读侧 SPOP → GETDEL」全链路</b>（阶段 3 核心验收）
 *
 * <p><b>为什么必须有这一层</b>：
 * Mockito 只能验证「Java 调了什么方法」，验证不了：
 * <ul>
 *   <li>Lua 里的 {@code SADD} 到底有没有真的登记到 dirty set</li>
 *   <li>{@code SPOP key count} 是否<b>真的把成员移除了</b>（这是「取走即删」的全部要害）</li>
 *   <li>{@code GETDEL} 是否真的同时取值与删除</li>
 *   <li>dirty set 的 TTL 是否真的被设上（否则「映射不到 link_id 的短码」会永久驻留）</li>
 * </ul>
 * DR-054 的教训正是「mock 形态由测试者构造，与真实序列化器可能不一致」。
 *
 * <p>⚠️ 用独立 db 索引（{@code REDIS_DB=15}）避免污染开发库的统计 key。
 */
@EnabledIfEnvironmentVariable(named = "REDIS_HOST", matches = ".+")
@DisplayName("真实 Redis 验证阶段 3 全链路（dirty set 登记 + SPOP + GETDEL）")
class StatsFlushRedisIntegrationTest {

    private StringRedisTemplate redis;
    private SimpleMeterRegistry registry;
    private AsyncStatsQueue queue;

    /** 记录落库调用，用于断言「PV 与 UV 分开」与 payload 内容。 */
    private final List<PvRow> pvCalls = new CopyOnWriteArrayList<>();
    private final List<UvRow> uvCalls = new CopyOnWriteArrayList<>();

    private LinkRepository links;
    private StatsRepository stats;
    private RedisDistributedLock realLock;

    @BeforeEach
    void setUp() {
        RedisStandaloneConfiguration cfg = new RedisStandaloneConfiguration(
                System.getenv("REDIS_HOST"),
                Integer.parseInt(System.getenv().getOrDefault("REDIS_PORT", "6379")));
        cfg.setDatabase(Integer.parseInt(System.getenv().getOrDefault("REDIS_DB", "15")));
        var factory = new org.springframework.data.redis.connection.lettuce
                .LettuceConnectionFactory(cfg);
        // ⚠️ LettuceConnectionFactory 必须显式 start()
        factory.afterPropertiesSet();
        factory.start();
        redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();

        registry = new SimpleMeterRegistry();
        queue = new AsyncStatsQueue(registry, redis, 1000);
        // ⚠️ 必须在这里就 start：StatsFlushService 的守卫要求 queue.isRunning() == true，
        //    否则整轮 flush 会被跳过，所有断言都会「莫名其妙地全绿」。
        //    （这正是该守卫存在的意义——它拦的不只是停机，也包括测试里的误用。）
        queue.start();
        realLock = new RedisDistributedLock(redis);

        // 仓储替身：只记录，不真的连 MySQL（本类只验 Redis 侧语义）
        links = new StubLinkRepository();
        stats = new StubStatsRepository();
    }

    @AfterEach
    void tearDown() {
        if (queue != null) {
            queue.stop();
        }
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();
    }

    private StatsFlushService newService() {
        return new StatsFlushService(registry, redis, realLock, links, stats, queue,
                500, 20, 2, 60, "0,0,0");
    }

    private static String fmt(LocalDate d) {
        return StatsKeys.date(d);
    }

    /** 等消费线程把事件写进 Redis（判据是 flushed 指标，不是队列空）。 */
    private void awaitFlushed() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 8000;
        while (System.currentTimeMillis() < deadline) {
            double ok = registry.find("shortlink.stats.flushed")
                    .tag("target", "pv").tag("result", "ok").counter().count();
            double failed = registry.find("shortlink.stats.flushed")
                    .tag("target", "pv").tag("result", "failed").counter().count();
            if (ok + failed > 0) {
                Thread.sleep(150);
                return;
            }
            Thread.sleep(20);
        }
        Thread.sleep(200);
    }

    // ============================================ 写侧：dirty set 登记

    @Test
    @DisplayName("W1️⃣⭐⭐⭐ PV 写入时**自动登记 dirty set**（写侧是 dirty set 的唯一来源）")
    void pvWriteRegistersDirtySet() throws Exception {
        queue.offer(new StatsEvent("0Q1A2b3", null, System.currentTimeMillis()));
        queue.start();
        awaitFlushed();

        String dirtyKey = StatsKeys.dirty(LocalDate.now());
        Set<String> members = redis.opsForSet().members(dirtyKey);

        assertThat(members)
                .as("⚠️ 阶段 2/3 之间的关键一环：没有 SADD 登记，"
                        + "flush 每分钟 SPOP 一个空集合，指标全 0，数据烂在 Redis 里")
                .containsExactly("0Q1A2b3");

        assertThat(redis.getExpire(dirtyKey))
                .as("⚠️ dirty set 也必须有 TTL，否则映射不到的短码会永久驻留")
                .isGreaterThan(0L);
    }

    @Test
    @DisplayName("W2️⃣⭐⭐ 同一短码多条 PV 只登记一次（SADD 天然去重，不放大 set 体积）")
    void sameCodeRegisteredOnce() throws Exception {
        for (int i = 0; i < 20; i++) {
            queue.offer(new StatsEvent("0Q1A2b3", null, System.currentTimeMillis()));
        }
        queue.start();
        awaitFlushed();

        assertThat(redis.opsForValue().get("shortlink:pv:0Q1A2b3:" + fmt(LocalDate.now())))
                .as("20 条 PV 累加为 20").isEqualTo("20");
        assertThat(redis.opsForSet().size(StatsKeys.dirty(LocalDate.now())))
                .as("dirty set 只有 1 个成员").isEqualTo(1L);
    }

    @Test
    @DisplayName("W3️⃣⭐⭐ 跨零点的批次登记到**两个** dirty set（昨天的数据不会丢）")
    void midnightBatchRegistersTwoDirtySets() throws Exception {
        LocalDate day1 = LocalDate.now().minusDays(1);
        LocalDate day2 = LocalDate.now();

        queue.offer(evAt("0Q1A2b3", null, day1));
        queue.offer(evAt("0Q1A2b3", null, day2));
        queue.start();
        awaitFlushed();

        assertThat(redis.opsForSet().members(StatsKeys.dirty(day1))).containsExactly("0Q1A2b3");
        assertThat(redis.opsForSet().members(StatsKeys.dirty(day2))).containsExactly("0Q1A2b3");
    }

    // ============================================ 读侧：SPOP + GETDEL

    @Test
    @DisplayName("R1️⃣⭐⭐⭐ SPOP 把短码从 dirty set **真的移除**（取走即删）")
    void spopRemovesFromDirtySet() throws Exception {
        queue.offer(new StatsEvent("0Q1A2b3", null, System.currentTimeMillis()));
        queue.offer(new StatsEvent("0Q1A2b4", null, System.currentTimeMillis()));
        queue.start();
        awaitFlushed();

        String dirtyKey = StatsKeys.dirty(LocalDate.now());
        assertThat(redis.opsForSet().size(dirtyKey)).isEqualTo(2L);

        newService().flush();

        assertThat(redis.opsForSet().size(dirtyKey))
                .as("⚠️ SPOP 必须真的移除成员。若用 SMEMBERS + SREM，"
                        + "期间新登记的短码会被误删——既不被本轮处理也不被下轮处理")
                .isZero();
        assertThat(pvCalls)
                .as("两个短码都应落库").hasSize(2);
    }

    @Test
    @DisplayName("R2️⃣⭐⭐⭐ GETDEL 取走即删：PV key **被删除**，第二次 flush 不重复落库")
    void getdelDeletesPvKeySoNoDoubleCount() throws Exception {
        queue.offer(new StatsEvent("0Q1A2b3", null, System.currentTimeMillis()));
        queue.offer(new StatsEvent("0Q1A2b3", null, System.currentTimeMillis()));
        queue.start();
        awaitFlushed();

        String pvKey = "shortlink:pv:0Q1A2b3:" + fmt(LocalDate.now());
        assertThat(redis.opsForValue().get(pvKey)).isEqualTo("2");

        newService().flush();

        assertThat(redis.opsForValue().get(pvKey))
                .as("⚠️ GETDEL 必须真的删除。若只是 GET，"
                        + "下一轮 SPOP 到同名短码会再落一次同一份增量 -> PV 翻倍")
                .isNull();
        assertThat(pvCalls).hasSize(1);
        assertThat(pvCalls.get(0).delta()).isEqualTo(2L);

        // 第二轮：dirty set 已空，不应再有任何落库
        newService().flush();
        assertThat(pvCalls)
                .as("⚠️ 同一份增量绝不能落两次").hasSize(1);
    }

    @Test
    @DisplayName("R3️⃣⭐⭐⭐ 新流量在 flush 之后到达：登记回 dirty set，下一轮正常落库")
    void newTrafficAfterFlushIsPickedUpNextRound() throws Exception {
        queue.offer(new StatsEvent("0Q1A2b3", null, System.currentTimeMillis()));
        queue.start();
        awaitFlushed();
        newService().flush();
        assertThat(pvCalls).hasSize(1);

        // 第二批流量
        queue.offer(new StatsEvent("0Q1A2b3", null, System.currentTimeMillis()));
        awaitFlushed();

        assertThat(redis.opsForSet().members(StatsKeys.dirty(LocalDate.now())))
                .as("⚠️ 新流量必须重新登记，否则会永久丢失")
                .containsExactly("0Q1A2b3");

        newService().flush();

        assertThat(pvCalls)
                .as("第二轮落库的是**新增的那 1 条**，不是累计值")
                .hasSize(2);
        assertThat(pvCalls.get(1).delta())
                .as("⚠️ 必须是增量 1，不是 3（累计值）")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("R4️⃣⭐⭐ UV 走 PFCOUNT 且**不删 HLL key**（删掉会丢历史访客基数）")
    void uvIsReadButNotDeleted() throws Exception {
        queue.offer(new StatsEvent("0Q1A2b3", "1.1.1.1", System.currentTimeMillis()));
        queue.offer(new StatsEvent("0Q1A2b3", "2.2.2.2", System.currentTimeMillis()));
        queue.start();
        awaitFlushed();

        String uvKey = "shortlink:uv:0Q1A2b3:" + fmt(LocalDate.now());
        newService().flush();

        assertThat(uvCalls).hasSize(1);
        assertThat(uvCalls.get(0).estimate())
                .as("PFCOUNT 应为 2").isEqualTo(2L);
        assertThat(redis.opsForHyperLogLog().size(uvKey))
                .as("⚠️ HLL key 不能删：PFCOUNT 是当日累计值，删掉会让下次读到 0")
                .isEqualTo(2L);
    }

    @Test
    @DisplayName("R5️⃣⭐⭐ UV 的累计语义：第二次 flush 读到的是**更大的累计值**（交由 GREATEST 去重）")
    void uvAccumulatesAcrossFlushes() throws Exception {
        queue.offer(new StatsEvent("0Q1A2b3", "1.1.1.1", System.currentTimeMillis()));
        queue.start();
        awaitFlushed();
        newService().flush();
        assertThat(uvCalls.get(0).estimate()).isEqualTo(1L);

        // 新访客 + 重新登记
        queue.offer(new StatsEvent("0Q1A2b3", "2.2.2.2", System.currentTimeMillis()));
        awaitFlushed();
        newService().flush();

        assertThat(uvCalls).hasSize(2);
        assertThat(uvCalls.get(1).estimate())
                .as("⚠️ 必须是当日累计值 2。若代码把 UV 也当增量处理，"
                        + "这里会是 1 —— 而 MySQL 侧用 GREATEST 掩盖了它，"
                        + "表现为「UV 一直停在 1」")
                .isEqualTo(2L);
    }

    // ============================================ 分布式锁（真实 Redis）

    @Test
    @DisplayName("L1️⃣⭐⭐⭐ 两个实例抢同一把锁：只有一个能进（PV 不会被重复累加）")
    void onlyOneInstanceAcquiresLock() {
        RedisDistributedLock a = new RedisDistributedLock(redis);
        RedisDistributedLock b = new RedisDistributedLock(redis);
        String key = StatsKeys.FLUSH_LOCK;

        assertThat(a.tryLock(key, Duration.ofSeconds(60))).isTrue();
        assertThat(b.tryLock(key, Duration.ofSeconds(60)))
                .as("⚠️ 多实例下若无锁，PV 会被重复累加且不可回滚").isFalse();

        // A 释放后 B 才能拿到
        assertThat(a.unlock(key)).isTrue();
        assertThat(b.tryLock(key, Duration.ofSeconds(60))).isTrue();
    }

    @Test
    @DisplayName("L2️⃣⭐⭐⭐ A 超时未释放时，B 不能删掉 A 的锁（Lua 校验 value）")
    void unlockDoesNotStealOthersLock() {
        RedisDistributedLock a = new RedisDistributedLock(redis);
        RedisDistributedLock b = new RedisDistributedLock(redis);
        String key = StatsKeys.FLUSH_LOCK;

        a.tryLock(key, Duration.ofSeconds(60));

        // ⚠️ B 试图释放 A 的锁：Lua 里 value 不匹配，必须拒绝
        assertThat(b.unlock(key))
                .as("⚠️ 不校验 value 会让 B 删掉 A 的锁，第三个实例就能同时进入")
                .isFalse();
        assertThat(a.holdsLock(key))
                .as("A 的锁必须还在").isTrue();
    }

    @Test
    @DisplayName("L3️⃣⭐⭐ 锁的 TTL 真的被设上（否则崩溃后永久锁死）")
    void lockHasTtl() {
        RedisDistributedLock a = new RedisDistributedLock(redis);
        a.tryLock(StatsKeys.FLUSH_LOCK, Duration.ofSeconds(60));

        Long ttl = redis.getExpire(StatsKeys.FLUSH_LOCK);
        assertThat(ttl)
                .as("⚠️ 拆成 SETNX + EXPIRE 时，这个窗口里崩溃会留下永不过期的锁")
                .isGreaterThan(0L).isLessThanOrEqualTo(60L);
    }

    // ============================================ 死信（真实 Redis List）

    @Test
    @DisplayName("D1️⃣⭐⭐⭐ DLQ 用 LPUSH/RPUSH + LREM 重放，失败条目**留在队列里**")
    void dlqEntryIsRemovedOnlyAfterSuccessfulReplay() {
        String payload = "v1|mysql|pv|7|" + LocalDate.now() + "|3";
        redis.opsForList().rightPush(StatsKeys.dlq(LocalDate.now()), payload);

        // 让 PV 落库失败 -> 重放也失败 -> 条目必须保留
        stats = new FailingStatsRepository();

        newService().flush();

        assertThat(redis.opsForList().range(StatsKeys.dlq(LocalDate.now()), 0, -1))
                .as("⚠️ 重放失败必须保留条目。若用 LPOP 先取出再处理，"
                        + "这一失败就等于永久丢弃")
                .containsExactly(payload);
    }

    @Test
    @DisplayName("D2️⃣⭐⭐ DLQ 重放成功才删条目")
    void dlqEntryRemovedAfterSuccessfulReplay() {
        String payload = "v1|mysql|pv|7|" + LocalDate.now() + "|3";
        redis.opsForList().rightPush(StatsKeys.dlq(LocalDate.now()), payload);

        newService().flush();

        assertThat(redis.opsForList().range(StatsKeys.dlq(LocalDate.now()), 0, -1))
                .as("成功后必须删掉，否则每分钟都会重复落库 -> PV 无限增长")
                .isEmpty();
        assertThat(pvCalls).hasSize(1);
    }

    // ============================================ 替身

    private static StatsEvent evAt(String code, String visitor, LocalDate day) {
        return new StatsEvent(code, visitor,
                day.atStartOfDay(ZoneIdHolder.ZONE).toInstant().toEpochMilli());
    }

    private static final class ZoneIdHolder {
        static final java.time.ZoneId ZONE = java.time.ZoneId.systemDefault();
    }

    /** 短码 -> 主键映射：恒返回 id = 短码哈希，稳定可断言。 */
    private final class StubLinkRepository implements LinkRepository {
        @Override
        public java.util.Optional<com.linkforge.core.domain.Link> findByShortCode(String shortCode) {
            return java.util.Optional.empty();
        }

        @Override
        public java.util.Optional<com.linkforge.core.domain.Link> findById(long id) {
            return java.util.Optional.empty();
        }

        @Override
        public void insert(com.linkforge.core.domain.Link link) {
        }

        @Override
        public int markDeleted(long id) {
            return 0;
        }

        @Override
        public Map<String, Long> resolveIdsByShortCodes(Collection<String> shortCodes) {
            Map<String, Long> m = new java.util.HashMap<>();
            shortCodes.forEach(c -> m.put(c, (long) c.hashCode() & 0xFFFFL));
            return m;
        }

        @Override
        public List<String> scanActiveShortCodes(long afterId, int limit) {
            return List.of();
        }

        @Override
        public List<IdShortCode> scanActiveWithId(long afterId, int limit) {
            return List.of();
        }
    }

    /** 记录调用即成功。 */
    private class StubStatsRepository implements StatsRepository {
        @Override
        public int accumulatePv(Collection<PvRow> rows) {
            pvCalls.addAll(rows);
            return rows.size();
        }

        @Override
        public int mergeUv(Collection<UvRow> rows) {
            uvCalls.addAll(rows);
            return rows.size();
        }
    }

    /** 永远失败的仓储：用于验证「失败进 DLQ 且条目保留」。 */
    private final class FailingStatsRepository implements StatsRepository {
        @Override
        public int accumulatePv(Collection<PvRow> rows) {
            throw new RuntimeException("模拟 MySQL 不可用");
        }

        @Override
        public int mergeUv(Collection<UvRow> rows) {
            throw new RuntimeException("模拟 MySQL 不可用");
        }
    }
}
