package com.linkforge.stats.infrastructure;

import com.linkforge.core.repository.LinkRepository;
import com.linkforge.core.repository.StatsRepository;
import com.linkforge.core.repository.StatsRepository.PvRow;
import com.linkforge.core.repository.StatsRepository.UvRow;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.HyperLogLogOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 统计定时落库：Redis 增量 → {@code link_stats_daily}（阶段 3）。
 *
 * <h2>一、数据流</h2>
 * <pre>
 *   跳转请求
 *      │ offer（非阻塞，队列满即丢）
 *      ▼
 *   AsyncStatsQueue 消费线程
 *      │ pv_incr_by.lua: INCRBY + EXPIRE + SADD dirty   ← dirty set 在这里登记
 *      │ uv_pfadd.lua:   PFADD + EXPIRE
 *      ▼
 *   Redis: shortlink:pv:{code}:{date}         （增量，可安全取走）
 *          shortlink:uv:{code}:{date}         （累计估算，只能读）
 *          shortlink:stats:dirty:{date}       （当日待落库的短码清单）
 *
 *   ↓ 每分钟（本类，@Scheduled + 全局锁）
 *
 *   SPOP dirty  →  短码清单（取走即删）
 *      │ resolveIdsByShortCodes（一次 IN 查询，走 uk_short_code，不回表）
 *      │ GETDEL pv（原子取走即删）    PFCOUNT uv（只读）
 *      ▼
 *   link_stats_daily: INSERT ... ON DUPLICATE KEY UPDATE
 *      pv           = pv + VALUES(pv)                      ← 累加
 *      uv_estimated = GREATEST(uv_estimated, VALUES(uv_estimated))  ← 覆盖
 * </pre>
 *
 * <h2>二、⚠️ 为什么 PV 与 UV 必须分成两条 SQL</h2>
 * 两者累加语义相反：
 * <ul>
 *   <li>PV 是<b>增量</b>：本次携带「自上次落库以来新增的量」，多次落库要叠加。</li>
 *   <li>UV 是<b>当日累计估算</b>：{@code PFCOUNT} 返回的是「当日至今」的基数，
 *       <b>不是增量</b>。若也用累加，一天 flush 1440 次会把同一个访客计 1440 遍。</li>
 * </ul>
 * 分成两条还带来第二个好处：<b>PV 成功、UV 失败时可以只重试 UV</b>。
 * 若合并成一条，重试会把已成功的 PV 再累加一遍，而 PV 一旦多加<b>无法回滚</b>——
 * 这是比「数据丢了」严重得多的错误（丢了是少，多了是错）。
 *
 * <h2>三、⚠️ 「取走即删」的两个原子性保证</h2>
 * <ol>
 *   <li><b>从 dirty set 取短码</b>：用 {@code SPOP key count}（单条命令，原子）。
 *       绝不能用 {@code SMEMBERS} + {@code SREM}——两者之间若有新流量登记进来，
 *       {@code SREM} 会把它一起删掉，那批数据<b>本轮不会被处理、下一轮也不会</b>，
 *       静静烂在 Redis 里直到 35 天 TTL 过期。这是典型的「看起来有清理逻辑」的静默数据丢失。</li>
 *   <li><b>从 PV key 取增量</b>：用 {@code GETDEL}（Redis 6.2+，单条命令原子完成
 *       GET + DEL）。先 GET 再 DEL 同样有窗口：DEL 之前进程崩溃，下次 flush
 *       会把<b>同一份增量再落一次库</b>，PV 翻倍。</li>
 * </ol>
 * UV 的 HLL key <b>不删</b>：{@code PFCOUNT} 返回的是累计值，删掉会把历史访客基数清零。
 * 残留由 35 天 TTL 回收；下次同一短码再有流量时会重新登记 dirty，
 * 读到的仍是<b>更新更大的累计值</b>，由 {@code GREATEST} 天然去重。
 *
 * <h2>四、⚠️ 全量 try-catch：异常逃逸会永久取消后续调度</h2>
 * {@code @Scheduled} 的行为有一个<b>不看文档就会踩</b>的细节：
 * 任务方法<b>抛出的异常会让整个 Trigger 失效</b>，且 Spring 不会重建它。
 * 表现是「跑了一次之后统计再也没落过库」，而日志里只有第一次的堆栈。
 * <p>
 * 所以 {@link #flush()} 必须把<b>一切</b>都包起来，一个异常都不许逃出去。
 *
 * <h2>五、⚠️ 运行状态守卫：先问 {@code queue.isRunning()}</h2>
 * {@link AsyncStatsQueue} 的线程<b>不可重启</b>（{@code Thread} 只能 start 一次）。
 * 应用上下文重启后消费线程已死，此时继续 flush 只会持续抛异常并触发上面的「调度失效」。
 * 守卫的语义就是：<b>消费端不在工作，就不要做它下游的事</b>。
 *
 * <h2>六、⚠️ 分布式锁：为什么必须有</h2>
 * 见 {@link RedisDistributedLock} 的类注释。一句话：{@code @Scheduled} 是每 JVM 各自触发的，
 * 多实例下 PV 会被重复累加，而 PV 累加错误不可逆。
 *
 * <h2>七、⚠️ 重试预算与锁 TTL 的算术</h2>
 * 单轮最坏耗时 = PV 重试（1+4+16=21s）+ UV 重试（21s）+ IO ≈ 45s，
 * <b>必须小于锁 TTL（60s）</b>，否则会出现「A 还在重试、B 已接管锁」的双跑。
 * 双跑本身不致命（取走即删让重复执行天然幂等），但会白白多打一轮数据库。
 * 若将来调大重试次数，<b>必须同步调大 {@code linkforge.stats.flush.lock-ttl-seconds}</b>。
 */
@Component
public class StatsFlushService {

    private static final Logger log = LoggerFactory.getLogger(StatsFlushService.class);

    /**
     * 死信 payload 的版本标记。
     *
     * <p>⚠️ 为什么需要它：阶段 2 写出的死信格式是 {@code pv|code|visitor|ts}（4 段，无版本号）。
     * 本阶段新增的格式带 stage 维度，若不显式标记版本，解析器要靠「段数」去猜格式，
     * 那是最容易在演进中出错的方式。<b>新写入一律带 {@code v1} 前缀</b>，
     * 旧格式按 legacy 分支兼容读取（滚动发布期间两种格式会共存）。
     */
    private static final String PAYLOAD_VERSION = "v1";

    /** 死信里的 stage：Redis 写入阶段失败（阶段 2 遗留）。 */
    private static final String STAGE_REDIS = "redis";

    /** 死信里的 stage：MySQL 落库阶段失败（本阶段）。 */
    private static final String STAGE_MYSQL = "mysql";

    /**
     * ⚠️ DLQ 头部落满「无法识别的条目」时的保护阈值。
     *
     * <p>场景：DLQ 头部堆了 N 条未知格式的条目（格式演进 / 人工塞值）。
     * 若只做「解析失败就跳过」，每分钟都会重新 LRANGE 到同一批、每批都跳过，
     * 形成<b>空转</b>：不推进、不报错、CPU 与网络照常消耗。
     * 超过阈值就整轮放弃 DLQ 处理并告警，把问题暴露出来而不是静默空转。
     */
    private static final int DLQ_UNPARSED_GIVE_UP = 10;

    private final MeterRegistry meterRegistry;
    private final StringRedisTemplate redis;
    private final RedisDistributedLock lock;
    private final LinkRepository links;
    private final StatsRepository stats;
    private final AsyncStatsQueue queue;
    private final Clock clock;

    private final int batchSize;
    private final int maxBatchesPerRun;
    private final int lookbackDays;
    private final Duration lockTtl;
    private final long[] retryBackoffMillis;

    // ---- 指标 ----
    private final Counter runOk;
    private final Counter runSkippedLock;
    private final Counter runSkippedStopped;
    private final Counter runFailed;
    private final Counter codesProcessed;
    private final Counter codesUnmapped;
    private final Counter rowsOk;
    private final Counter rowsFailed;
    private final Counter dlqRetryOk;
    private final Counter dlqRetryFailed;
    private final Counter dlqUnparsed;
    private final Counter dateFailed;
    private final Timer flushTimer;
    private final Timer persistTimer;

    /** stage=mysql 的死信丢失计数（阶段 2 里刻意留空、由本类注册）。 */
    private final Counter dlqLostMysqlPv;
    private final Counter dlqLostMysqlUv;

    /**
     * ⚠️ <b>只能有一个 public 构造器</b>：Spring 在存在多个构造器且都无
     * {@code @Autowired} 时会退化成找无参构造器，找不到就报
     * {@code No default constructor found}（AsyncStatsQueue 实测踩过）。
     * 测试需要非 1 分钟的 cron 时用子类覆写 {@link #cron()}，不要加构造器。
     */
    public StatsFlushService(MeterRegistry meterRegistry,
                             StringRedisTemplate redis,
                             RedisDistributedLock lock,
                             LinkRepository links,
                             StatsRepository stats,
                             AsyncStatsQueue queue,
                             Clock clock,
                             @Value("${linkforge.stats.flush.batch-size:500}") int batchSize,
                             @Value("${linkforge.stats.flush.max-batches-per-run:20}") int maxBatchesPerRun,
                             @Value("${linkforge.stats.flush.lookback-days:2}") int lookbackDays,
                             @Value("${linkforge.stats.flush.lock-ttl-seconds:60}") long lockTtlSeconds,
                             @Value("${linkforge.stats.flush.retry-backoff-ms:1000,4000,16000}") String backoffSpec) {
        this.meterRegistry = meterRegistry;
        this.redis = redis;
        this.lock = lock;
        this.links = links;
        this.stats = stats;
        this.queue = queue;
        this.clock = clock;
        this.batchSize = batchSize;
        this.maxBatchesPerRun = maxBatchesPerRun;
        this.lookbackDays = lookbackDays;
        this.lockTtl = Duration.ofSeconds(lockTtlSeconds);
        this.retryBackoffMillis = parseBackoff(backoffSpec);

        this.runOk = Counter.builder("shortlink.stats.flush.runs")
                .tag("result", "ok")
                .description("落库任务成功完成的轮次")
                .register(meterRegistry);
        this.runSkippedLock = Counter.builder("shortlink.stats.flush.runs")
                .tag("result", "skipped_lock")
                .description("未取得全局锁而跳过的轮次（多实例下正常）")
                .register(meterRegistry);
        this.runSkippedStopped = Counter.builder("shortlink.stats.flush.runs")
                .tag("result", "skipped_stopped")
                .description("因统计消费线程未运行而跳过的轮次")
                .register(meterRegistry);
        this.runFailed = Counter.builder("shortlink.stats.flush.runs")
                .tag("result", "failed")
                .description("落库过程中发生未预期异常而中断的轮次")
                .register(meterRegistry);
        this.codesProcessed = Counter.builder("shortlink.stats.flush.codes")
                .description("从 dirty set 取出并处理的短码数")
                .register(meterRegistry);
        this.codesUnmapped = Counter.builder("shortlink.stats.flush.unmapped")
                .description("dirty set 中查不到对应 link_id 的短码数（短链已被物理删除）")
                .register(meterRegistry);
        this.rowsOk = Counter.builder("shortlink.stats.flush.rows")
                .tag("result", "ok")
                .description("成功写入 MySQL 的统计行数")
                .register(meterRegistry);
        this.rowsFailed = Counter.builder("shortlink.stats.flush.rows")
                .tag("result", "failed")
                .description("重试耗尽后转入死信的统计行数")
                .register(meterRegistry);
        this.dlqRetryOk = Counter.builder("shortlink.stats.dlq.retry")
                .tag("result", "ok")
                .description("死信重放成功的条数")
                .register(meterRegistry);
        this.dlqRetryFailed = Counter.builder("shortlink.stats.dlq.retry")
                .tag("result", "failed")
                .description("死信重放仍失败、保留在队列等待下一轮的条数")
                .register(meterRegistry);
        this.dlqUnparsed = Counter.builder("shortlink.stats.dlq.unparsed")
                .description("死信中无法识别的条目数（格式不兼容或被人工污染）")
                .register(meterRegistry);
        this.dateFailed = Counter.builder("shortlink.stats.flush.date.failed")
                .description("单个自然日落库失败的次数。"
                        + "⚠️ 与 runs{result=failed} 区分：后者是整轮崩掉，"
                        + "前者是「被隔离到单个日期」的失败——若它持续增长，"
                        + "说明某个日期的数据在持续滞留")
                .register(meterRegistry);
        this.flushTimer = Timer.builder("shortlink.stats.flush.duration")
                .description("单轮落库任务耗时")
                .publishPercentileHistogram()
                .register(meterRegistry);
        this.persistTimer = Timer.builder("shortlink.stats.persist.duration")
                .description("单次 MySQL 批量落库（含重试）耗时")
                .publishPercentileHistogram()
                .register(meterRegistry);

        // ⚠️ 这两个在阶段 2 是**故意不注册**的（见 AsyncStatsQueue 的注释）：
        //   阶段 3 才真正落库，那时注册才有意义。提前注册恒为 0 的计数器
        //   会让人误以为「已经在监控落库丢失了」。
        this.dlqLostMysqlPv = Counter.builder("shortlink.stats.dlq.lost")
                .tag("target", "pv").tag("stage", "mysql")
                .description("PV 落库失败且死信也写入失败——数据永久丢失")
                .register(meterRegistry);
        this.dlqLostMysqlUv = Counter.builder("shortlink.stats.dlq.lost")
                .tag("target", "uv").tag("stage", "mysql")
                .description("UV 落库失败且死信也写入失败——数据永久丢失")
                .register(meterRegistry);
    }

    /**
     * 解析重试退避配置。
     *
     * <p>⚠️ <b>为什么是「解析后校验」而不是直接接受配置</b>：
     * 退避表写错（比如写成 {@code 1,4}）会让重试次数与退避数组长度脱钩，
     * 多出来的重试没有退避（等于忙等打数据库），或者少出来的重试被静默跳过。
     * 这里在<b>启动期</b>就把它对齐成 3 次重试，不等到运行期才发现。
     *
     * <p>⚠️ 另外注意：1s/4s/16s 是 <b>4 倍</b>增长而非教科书式的 2 倍。
     * 这是刻意选的：MySQL 抖动恢复通常是秒级，指数过大只会白白拉长一轮 flush、
     * 逼近锁 TTL（见类注释第七节的算术）。
     */
    private static long[] parseBackoff(String spec) {
        String[] parts = spec.split(",");
        long[] result = new long[parts.length];
        for (int i = 0; i < parts.length; i++) {
            result[i] = Long.parseLong(parts[i].trim());
            if (result[i] < 0) {
                throw new IllegalArgumentException("重试退避不能为负: " + spec);
            }
        }
        if (result.length != 3) {
            throw new IllegalArgumentException(
                    "linkforge.stats.flush.retry-backoff-ms 必须恰好 3 段（重试 3 次），当前: " + spec);
        }
        return result;
    }

    // ==================================================== 定时任务入口

    /**
     * 每分钟把 Redis 里的统计增量落库。
     *
     * <p>⚠️ <b>调度用 cron 而不是 fixedRate</b>：{@code fixedRate} 的语义是
     * 「上一次<b>开始</b>后固定间隔再触发」，若某轮跑了 70 秒，后续触发会<b>堆积</b>。
     * 堆积的任务各自还要抢锁，抢不到就白跑一轮，且堆积量随运行时长不可控。
     * cron 的语义是「到点触发，错过了就跳过」，天然无堆积。
     *
     * <p>⚠️ <b>为什么 cron 不能抽成方法</b>：{@code @Scheduled} 的 cron 只接受
     * <b>编译期常量</b>或占位符字符串，写成 {@code cron()} 方法会在启动期报
     * 「占位符无法解析」。要改频率就改配置项，不要改代码结构。
     *
     * <p>⚠️ <b>本方法绝不允许抛出任何异常</b>：抛出会让该 Trigger 被 Spring 取消且不再重建
     * （见类注释第四节）。所以最外层是 catch-all Throwable。
     *
     * <p>⚠️ 用 {@code Throwable} 而非 {@code Exception}：{@code NoClassDefFoundError} 这类
     * {@link Error} 同样会让调度永久失效。用 Exception 捕获它等于留下一个定时炸弹。
     */
    @Scheduled(cron = "${linkforge.stats.flush.cron:0 * * * * *}")
    public void flush() {
        long t0 = System.nanoTime();
        try {
            // ① 守卫：消费线程不在工作，就不做它下游的事。
            //    顺序很重要——放在加锁之前，避免每次停机都白占一次锁。
            if (!queue.isRunning()) {
                runSkippedStopped.increment();
                log.debug("统计消费线程未运行，跳过本轮落库");
                return;
            }

            // ② 全局锁。多实例下只有抢到的那一个真正执行。
            if (!lock.tryLock(StatsKeys.FLUSH_LOCK, lockTtl)) {
                runSkippedLock.increment();
                log.debug("未取得落库锁（其他实例正在执行或 Redis 不可用），跳过本轮");
                return;
            }

            try {
                // ③ 二次校验：加锁后到真正干活之间，锁可能已过期并被他人接管。
                //    此时若继续落库，就是两个实例并发写——PV 会被重复累加且不可回滚。
                if (!lock.holdsLock(StatsKeys.FLUSH_LOCK)) {
                    runSkippedLock.increment();
                    log.warn("落库锁在执行前已不属于本实例，放弃本轮（避免并发落库导致 PV 重复累加）");
                    return;
                }
                doFlush();
                runOk.increment();
            } finally {
                // ④ 释放带 value 校验，不会误删他人的锁。
                lock.unlock(StatsKeys.FLUSH_LOCK);
            }
        } catch (Throwable t) {
            // ⚠️ catch-all：见方法 javadoc。这里绝不能 rethrow。
            runFailed.increment();
            log.error("统计定时落库异常（调度已存活，继续下一轮）: {}", t.toString(), t);
        } finally {
            flushTimer.record(System.nanoTime() - t0, TimeUnit.NANOSECONDS);
        }
    }

    /** 持锁后的真正工作。抛出的异常由 {@link #flush()} 的 catch-all 兜住。 */
    private void doFlush() {
        // ⚠️ 必须用注入的 Clock（ARCHITECTURE §13：时间获取必须可注入）。
        //    直接 LocalDate.now() 会让「今天是哪天」无法在测试里固定，
        //    跨零点的批次归属日就测不了。
        LocalDate today = LocalDate.now(clock);
        // ⚠️ 必须回看前几天：昨天 23:59 的访问会写进「昨天」的 PV key 与 dirty set，
        //    若只处理今天，那批数据永远不会被落库（昨天不会再变成今天）。
        //    默认回看 2 天（今天 + 昨天）；应用停机超过 2 天则更早的数据会滞留，
        //    但那些 key 35 天后自然过期，且 dirty set 的价值本来就是「近期活跃」。
        for (int i = 0; i < lookbackDays; i++) {
            LocalDate date = today.minusDays(i);
            try {
                flushDate(date);
            } catch (Exception e) {
                // ⚠️ 单日失败不能中断其它日期：昨天的数据不该因为今天的
                // 一个 SQL 语法错误而一起滞留。
                // ⚠️ 必须计指标：只有日志的话，这个失败会持续几周才被发现，
                // 而那期间该日期的数据一直在 Redis 里累积。
                dateFailed.increment();
                log.warn("落库 {} 的统计失败，跳过该日期: {}", date, e.toString());
            }
        }

        // DLQ 重放放在最后：新数据优先落库，死信是兜底。
        // 顺序反过来会让一条陈旧死信把本轮 45 秒的重试预算耗光。
        for (int i = 0; i < lookbackDays; i++) {
            try {
                replayDlq(today.minusDays(i));
            } catch (Exception e) {
                log.warn("死信重放 {} 失败: {}", today.minusDays(i), e.toString());
            }
        }
    }

    // ==================================================== 主流程：SPOP → GETDEL → UPSERT

    /**
     * 处理某一自然日的 dirty set。
     *
     * <p>单轮内最多循环 {@code maxBatchesPerRun} 批：{@code SPOP} 到空为止在理论上没有次数上限，
     * 而一批落库的耗时（含失败重试）可达 45 秒，无上限会导致这一轮长期占用锁、
     * 后续轮次全部因抢不到锁而空转。剩下的数据下一分钟继续处理——<b>这不是丢失</b>，
     * 因为 SPOP 只移除了本轮已取走的部分，剩余的仍留在 dirty set 里。
     */
    private void flushDate(LocalDate date) {
        String dateText = StatsKeys.date(date);
        String dirtyKey = StatsKeys.dirty(date);

        for (int batch = 0; batch < maxBatchesPerRun; batch++) {
            // ⚠️ SPOP key count：单条命令原子完成「取 N 个并从集合移除」。
            //    绝不用 SMEMBERS + SREM（见类注释第三节）。
            //
            // ⚠️ 返回类型是 List 而不是 Set：Spring Data Redis 的
            //    SetOperations.pop(K, long) 声明为 List<V>（照抄 Redis 的回复形态）。
            //    这不影响正确性——SPOP 取自 Set，成员天然互不重复。
            List<String> codes = redis.opsForSet().pop(dirtyKey, (long) batchSize);
            if (codes == null || codes.isEmpty()) {
                return;
            }
            codesProcessed.increment(codes.size());
            persistCodes(codes, date, dateText);
        }
        log.debug("{} 的 dirty set 达到单轮批次上限 {}，剩余数据下一轮继续",
                dirtyKey, maxBatchesPerRun);
    }

    /** 把一批短码的 PV / UV 读出来并落库。 */
    private void persistCodes(List<String> codes, LocalDate date, String dateText) {
        Map<String, Long> idByCode;
        try {
            // 一次 IN 查询走 uk_short_code 唯一索引，不回表读 target_url。
            // ⚠️ 这一步失败时必须把短码**放回** dirty set：
            //    SPOP 已经把它们移出集合了，若就此返回，这批短码的 PV 数据
            //    会一直躺在 Redis 里直到 TTL 过期。
            //    放回是安全的——此时 PV key 尚未被 GETDEL，重放不会造成重复累加。
            idByCode = links.resolveIdsByShortCodes(codes);
        } catch (Exception e) {
            restoreDirty(date, codes);
            throw e;
        }

        List<PvRow> pvRows = new ArrayList<>(codes.size());
        List<UvRow> uvRows = new ArrayList<>(codes.size());

        for (String code : codes) {
            Long linkId = idByCode.get(code);
            if (linkId == null) {
                // ⚠️ 查不到主键：短链已被物理删除（正常路径只有逻辑删除，
                //    所以这几乎不会发生）。计数而不是静默丢弃——
                //    若它持续增长，说明存在意料之外的删除路径。
                codesUnmapped.increment();
                continue;
            }

            // ⚠️ GETDEL：原子「取值 + 删除」。返回 null 表示 key 不存在
            //    （TTL 过期、或上一轮已取走），此时无可落库的增量。
            String pvRaw = redis.opsForValue().getAndDelete(StatsKeys.pv(code, dateText));
            if (pvRaw != null) {
                long delta = parseLong(pvRaw);
                if (delta > 0) {
                    pvRows.add(new PvRow(linkId, date, delta));
                }
            }

            // ⚠️ PFCOUNT 只读不删：HLL 返回的是当日累计基数，删掉会丢历史访客。
            //    值为 0 说明当天还没有 UV 数据（visitor 全为 null），跳过。
            Long uvEstimate = hyperLogLog().size(StatsKeys.uv(code, dateText));
            if (uvEstimate != null && uvEstimate > 0) {
                uvRows.add(new UvRow(linkId, date, uvEstimate));
            }
        }

        // ⚠️ 两路分开落库、分开重试、分开进死信。见类注释第二节。
        persistPv(pvRows, date);
        persistUv(uvRows, date);
    }

    /**
     * 批量落 PV（累加语义）。
     *
     * <p>⚠️ <b>重试 3 次，退避 1s / 4s / 16s</b>。
     * MySQL 的瞬时故障（连接池耗尽、死锁、锁等待超时）通常在秒级恢复，
     * 立即重试基本必然再失败；退避让出资源给别的连接。
     */
    private void persistPv(List<PvRow> rows, LocalDate date) {
        if (rows.isEmpty()) {
            return;
        }
        int n = rows.size();
        long t0 = System.nanoTime();
        try {
            try {
                stats.accumulatePv(rows);
                rowsOk.increment(n);
            } catch (Exception e) {
                if (!retry(n, () -> stats.accumulatePv(rows))) {
                    // 重试耗尽：写死信，**不丢弃**。
                    // 下一轮 replayDlq 会重放；期间 key 的 35 天 TTL 就是数据的最长寿命。
                    writeToDlq(date, "pv", rows.stream()
                            .map(r -> PAYLOAD_VERSION + "|" + STAGE_MYSQL + "|pv|"
                                    + r.linkId() + "|" + r.statDate() + "|" + r.delta())
                            .toList());
                    rowsFailed.increment(n);
                    log.warn("PV 落库重试 {} 次仍失败，{} 行已进死信", retryBackoffMillis.length, n, e);
                } else {
                    rowsOk.increment(n);
                }
            }
        } finally {
            persistTimer.record(System.nanoTime() - t0, TimeUnit.NANOSECONDS);
        }
    }

    /**
     * 批量落 UV（覆盖 / GREATEST 语义）。
     *
     * <p>⚠️ 独立于 PV 的重试：两者<b>绝不共用一次调用</b>。
     * 合并会让「PV 已成功、UV 失败」的重试把 PV 再累加一遍。
     */
    private void persistUv(List<UvRow> rows, LocalDate date) {
        if (rows.isEmpty()) {
            return;
        }
        int n = rows.size();
        long t0 = System.nanoTime();
        try {
            try {
                stats.mergeUv(rows);
                rowsOk.increment(n);
            } catch (Exception e) {
                if (!retry(n, () -> stats.mergeUv(rows))) {
                    writeToDlq(date, "uv", rows.stream()
                            .map(r -> PAYLOAD_VERSION + "|" + STAGE_MYSQL + "|uv|"
                                    + r.linkId() + "|" + r.statDate() + "|" + r.estimate())
                            .toList());
                    rowsFailed.increment(n);
                    log.warn("UV 落库重试 {} 次仍失败，{} 行已进死信", retryBackoffMillis.length, n, e);
                } else {
                    rowsOk.increment(n);
                }
            }
        } finally {
            persistTimer.record(System.nanoTime() - t0, TimeUnit.NANOSECONDS);
        }
    }

    /**
     * 执行「首次 + 最多 3 次重试」。
     *
     * <p>⚠️ <b>不要用 {@code CompletableFuture} 或线程池做重试</b>：
     * 那会让多个重试<b>并发</b>执行同一个累加操作，PV 翻倍。
     * 重试必须是严格串行的。
     *
     * @param n     本次涉及的行数（仅用于日志）
     * @param write 落库动作
     * @return true = 成功（含重试后成功）；false = 重试耗尽
     */
    private boolean retry(int n, Runnable write) {
        for (int attempt = 0; attempt < retryBackoffMillis.length; attempt++) {
            long sleep = retryBackoffMillis[attempt];
            if (!sleepQuietly(sleep)) {
                // 被中断（通常是应用停机）。此时<b>不再重试</b>：
                // 调度线程正在被关停，继续睡下去毫无意义。
                // 直接返回 false 让调用方写死信，数据不会丢。
                log.warn("重试等待被中断（应用停机中），{} 行转入死信", n);
                return false;
            }
            try {
                write.run();
                return true;
            } catch (Exception e) {
                log.warn("落库第 {} 次重试失败（{} 行）: {}", attempt + 1, n, e.toString());
            }
        }
        return false;
    }

    /** 睡指定毫秒；返回 false 表示被中断。 */
    private boolean sleepQuietly(long millis) {
        if (millis <= 0) {
            return true;
        }
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            // ⚠️ 必须恢复中断标志：否则 stop() 流程会以为线程没被中断过。
            Thread.currentThread().interrupt();
            return false;
        }
    }

    // ==================================================== 死信

    /**
     * 写死信队列。
     *
     * <p>⚠️ payload 必须带 {@code stage=mysql}（Q5 硬性要求）：
     * 它区分「Redis 写入阶段失败」与「MySQL 落库阶段失败」——
     * 后者的重放动作是<b>写数据库</b>，前者的重放动作是<b>写 Redis</b>，
     * 用错会导致 PV 凭空翻倍或 UV 被清零。
     *
     * <p>⚠️ DLQ 的 key 用<b>处理当天</b>而非 stat_date：DLQ 是「处理队列」，
     * 按哪一天发现失败来分桶；真正的归属日写在 payload 里。
     * 若按 stat_date 分桶，一条 3 天前失败的数据会被塞进 3 天前的队列，
     * 而 replay 只回看 lookbackDays 天——它就永远不会被处理了。
     */
    private void writeToDlq(LocalDate date, String channel, List<String> payloads) {
        if (payloads.isEmpty()) {
            return;
        }
        try {
            String key = StatsKeys.dlq(date);
            redis.opsForList().rightPushAll(key, payloads);
            redis.expire(key, StatsKeys.KEY_TTL);
        } catch (Exception dlqFailure) {
            // ⚠️ 到这里数据是真的没了：**光记日志不够**，运维不翻日志就看不到。
            if ("pv".equals(channel)) {
                dlqLostMysqlPv.increment(payloads.size());
            } else {
                dlqLostMysqlUv.increment(payloads.size());
            }
            log.error("死信写入也失败，{} 条**永久丢失**: channel={} 原因={}",
                    payloads.size(), channel, dlqFailure.toString());
        }
    }

    /**
     * 重放死信。
     *
     * <p>⚠️ <b>为什么用 LRANGE + LREM 而不是 LPOP</b>：{@code LPOP} 一取出就出队，
     * 若取出后崩溃/失败，那条数据就没了——死信队列会变成「一次性丢弃队列」。
     * 用 {@code LRANGE} <b>只读窥探</b>，成功的条目才用 {@code LREM key 1 value}
     * 精确删掉自己那一份。失败的条目原地保留，下一轮再来。
     *
     * <p>⚠️ <b>靶向重试的核心约束</b>：解析 payload 的 {@code channel}，
     * {@code pv} 只走 {@code accumulatePv}、{@code uv} 只走 {@code mergeUv}。
     * 绝不在一次重试里同时更新两者——PV 已成功的那部分会被重复累加，
     * 而 PV 一旦多加<b>无法回滚</b>（UV 用 GREATEST，重复执行反而无害）。
     */
    private void replayDlq(LocalDate date) {
        String key = StatsKeys.dlq(date);
        List<String> entries = redis.opsForList().range(key, 0, batchSize - 1);
        if (entries == null || entries.isEmpty()) {
            return;
        }

        // 按 (stage, channel) 分桶。LinkedHashMap 保证处理顺序稳定，测试可断言。
        Map<String, List<MysqlEntry>> mysqlPv = new LinkedHashMap<>();
        Map<String, List<MysqlEntry>> mysqlUv = new LinkedHashMap<>();
        List<RedisEntry> redisPv = new ArrayList<>();
        List<RedisEntry> redisUv = new ArrayList<>();
        int unparsed = 0;

        for (String entry : entries) {
            MysqlEntry m = parseMysqlPayload(entry);
            if (m != null) {
                ("pv".equals(m.channel()) ? mysqlPv : mysqlUv)
                        .computeIfAbsent(m.statDate().toString(), d -> new ArrayList<>()).add(m);
                continue;
            }
            RedisEntry r = parseRedisPayload(entry);
            if (r != null) {
                ("pv".equals(r.channel()) ? redisPv : redisUv).add(r);
                continue;
            }
            unparsed++;
        }

        if (unparsed > 0) {
            dlqUnparsed.increment(unparsed);
            if (unparsed >= DLQ_UNPARSED_GIVE_UP) {
                log.error("死信 {} 头部有 {} 条无法识别的条目，本轮不再处理（否则会每分钟空转）。"
                                + "请人工检查该 key 的内容",
                        key, unparsed);
                return;
            }
            log.warn("死信 {} 中有 {} 条无法识别的条目，已保留待查", key, unparsed);
        }

        // ---- stage=mysql：重放数据库 ----
        for (List<MysqlEntry> group : mysqlPv.values()) {
            replayPv(key, group);
        }
        for (List<MysqlEntry> group : mysqlUv.values()) {
            replayUv(key, group);
        }

        // ---- stage=redis：重放 Redis（阶段 2 遗留死信的闭环） ----
        replayRedisPv(key, redisPv);
        replayRedisUv(key, redisUv);
    }

    /**
     * 重放 PV 死信（<b>只动 PV</b>）。
     *
     * <p>⚠️ 按 statDate 分组批量执行：不同日期的数据必须落在不同的行上。
     * 分组后重试粒度与语义都更清晰——一个日期的 PV 失败不该让另一个日期也重试。
     */
    private void replayPv(String dlqKey, List<MysqlEntry> entries) {
        List<PvRow> rows = new ArrayList<>(entries.size());
        for (MysqlEntry e : entries) {
            rows.add(new PvRow(e.linkId(), e.statDate(), e.value()));
        }
        if (!retry(rows.size(), () -> stats.accumulatePv(rows))) {
            dlqRetryFailed.increment(entries.size());
            log.warn("死信 PV 重试 {} 次仍失败（{} 行），保留在队列等待下一轮",
                    retryBackoffMillis.length, rows.size());
            return;
        }
        dlqRetryOk.increment(entries.size());
        entries.forEach(e -> removeFromDlq(dlqKey, e.raw()));
    }

    /** 重放 UV 死信（<b>只动 UV</b>）。见 {@link #replayPv} 的靶向约束说明。 */
    private void replayUv(String dlqKey, List<MysqlEntry> entries) {
        List<UvRow> rows = new ArrayList<>(entries.size());
        for (MysqlEntry e : entries) {
            rows.add(new UvRow(e.linkId(), e.statDate(), e.value()));
        }
        if (!retry(rows.size(), () -> stats.mergeUv(rows))) {
            dlqRetryFailed.increment(entries.size());
            log.warn("死信 UV 重试 {} 次仍失败（{} 行），保留在队列等待下一轮",
                    retryBackoffMillis.length, rows.size());
            return;
        }
        dlqRetryOk.increment(entries.size());
        entries.forEach(e -> removeFromDlq(dlqKey, e.raw()));
    }

    /**
     * 重放 {@code stage=redis} 的 PV 死信（回写 Redis 的增量计数）。
     *
     * <p>⚠️ <b>这是阶段 3 之外的一小段「闭环」代码</b>，需要说明为什么加：
     * 阶段 2 的死信（Redis 写入失败）原本没有消费方。若 flush 只处理自己的
     * {@code stage=mysql} 死信，那些条目会一直躺在 DLQ 里直到 35 天 TTL 到期
     * ——<b>既没报错也没指标</b>，属于最坏的一类缺陷：无声的数据丢失。
     * 所以这里补上重放，让「写失败 → 死信 → 下一轮恢复」形成闭环。
     * 实现仍然遵守靶向约束：PV 死信只碰 PV key。
     */
    private void replayRedisPv(String dlqKey, List<RedisEntry> entries) {
        if (entries.isEmpty()) {
            return;
        }
        Map<String, long[]> byKey = new LinkedHashMap<>();
        for (RedisEntry e : entries) {
            byKey.computeIfAbsent(e.redisKey(), k -> new long[1])[0] += 1;
        }
        try {
            for (Map.Entry<String, long[]> e : byKey.entrySet()) {
                redis.opsForValue().increment(e.getKey(), e.getValue()[0]);
                redis.expire(e.getKey(), StatsKeys.KEY_TTL);
            }
            dlqRetryOk.increment(entries.size());
            entries.forEach(e -> removeFromDlq(dlqKey, e.raw()));
            log.info("死信 PV 重放成功：{} 条", entries.size());
        } catch (Exception ex) {
            dlqRetryFailed.increment(entries.size());
            log.warn("死信 PV 重放失败，{} 条保留在队列: {}", entries.size(), ex.toString());
        }
    }

    /** 重放 {@code stage=redis} 的 UV 死信（回写 HLL）。靶向：只碰 UV key。 */
    private void replayRedisUv(String dlqKey, List<RedisEntry> entries) {
        if (entries.isEmpty()) {
            return;
        }
        Map<String, List<String>> visitorsByKey = new LinkedHashMap<>();
        for (RedisEntry e : entries) {
            visitorsByKey.computeIfAbsent(e.redisKey(), k -> new ArrayList<>()).add(e.visitor());
        }
        try {
            for (Map.Entry<String, List<String>> e : visitorsByKey.entrySet()) {
                // ⚠️ visitor 为 null 的条目不能进 PFADD（会 NPE）。这类条目
                //    只能丢弃——它本来就是「PV 已记、UV 无法去重」的状态。
                List<String> vs = e.getValue().stream().filter(java.util.Objects::nonNull).toList();
                if (vs.isEmpty()) {
                    continue;
                }
                hyperLogLog().add(e.getKey(), vs.toArray(new String[0]));
                redis.expire(e.getKey(), StatsKeys.KEY_TTL);
            }
            dlqRetryOk.increment(entries.size());
            entries.forEach(e -> removeFromDlq(dlqKey, e.raw()));
            log.info("死信 UV 重放成功：{} 条", entries.size());
        } catch (Exception ex) {
            dlqRetryFailed.increment(entries.size());
            log.warn("死信 UV 重放失败，{} 条保留在队列: {}", entries.size(), ex.toString());
        }
    }

    /**
     * 从死信队列里精确移除一条。
     *
     * <p>⚠️ 用 {@code LREM key 1 value}（count=1）而不是 {@code LPOP}：
     * 我们是「窥探 → 处理成功 → 删除自己那一条」。count=1 只删第一个匹配项，
     * 重复 payload（同一 linkId 同一天反复失败）会堆成多个相同元素，
     * 每次成功只删一个，<b>计数自然对齐</b>。
     */
    private void removeFromDlq(String dlqKey, String raw) {
        try {
            redis.opsForList().remove(dlqKey, 1, raw);
        } catch (Exception e) {
            // ⚠️ 删除失败会导致下一轮重放同一条。
            //    UV 重放是幂等的（GREATEST / PFADD），但 **PV 重放不是**——会重复累加。
            //    因此这里必须让错误可见，而不是只打一条 debug。
            log.error("死信条目删除失败，下一轮会重放该条（PV 可能重复累加）: {}", raw, e);
        }
    }

    // ==================================================== payload 解析

    /**
     * 解析 {@code v1|mysql|pv|{linkId}|{statDate}|{value}}。
     *
     * @return 解析失败返回 {@code null}（交给下一个解析器 / 计入 unparsed）
     */
    private static MysqlEntry parseMysqlPayload(String payload) {
        if (payload == null) {
            return null;
        }
        String[] parts = payload.split("\\|");
        // ⚠️ 段数必须恰好 6：少段是格式损坏，多段说明有未知的扩展字段。
        //    宁可判为「无法识别」也不要猜——猜错会把数据写进错误的行。
        if (parts.length != 6
                || !PAYLOAD_VERSION.equals(parts[0])
                || !STAGE_MYSQL.equals(parts[1])
                || !("pv".equals(parts[2]) || "uv".equals(parts[2]))) {
            return null;
        }
        try {
            return new MysqlEntry(payload, parts[2],
                    Long.parseLong(parts[3]), LocalDate.parse(parts[4]),
                    Long.parseLong(parts[5]));
        } catch (NumberFormatException | java.time.format.DateTimeParseException e) {
            return null;
        }
    }

    /**
     * 解析阶段 2 的 legacy 格式 {@code pv|code|visitor|ts}。
     *
     * <p>⚠️ <b>兼容它是因为滚动发布期间两种格式会共存</b>：旧实例写的死信
     * 只能由新代码消费。若直接判为 unparsed，那批数据会等到 TTL 过期。
     *
     * @return 解析失败返回 {@code null}
     */
    private static RedisEntry parseRedisPayload(String payload) {
        if (payload == null) {
            return null;
        }
        String[] parts = payload.split("\\|", -1);
        if (parts.length != 4 || !("pv".equals(parts[0]) || "uv".equals(parts[0]))) {
            return null;
        }
        long ts;
        try {
            ts = Long.parseLong(parts[3]);
        } catch (NumberFormatException e) {
            return null;
        }
        // visitor 允许为 null（阶段 2 的 PV 死信就是这样：PV 不需要 visitor）
        String visitor = "null".equals(parts[2]) ? null : parts[2];
        LocalDate date = java.time.Instant.ofEpochMilli(ts)
                .atZone(java.time.ZoneId.systemDefault())
                .toLocalDate();
        String dateText = StatsKeys.date(date);
        String key = "pv".equals(parts[0])
                ? StatsKeys.pv(parts[1], dateText)
                : StatsKeys.uv(parts[1], dateText);
        return new RedisEntry(payload, parts[0], key, visitor);
    }

    private record MysqlEntry(String raw, String channel, long linkId,
                              LocalDate statDate, long value) {
    }

    private record RedisEntry(String raw, String channel, String redisKey, String visitor) {
    }

    // ==================================================== 小工具

    /**
     * 把短码放回 dirty set。
     *
     * <p>⚠️ <b>只在「尚未读取任何 PV 数据」时调用</b>。
     * 一旦对某些 code 执行过 GETDEL，再放回集合就会导致下一轮<b>重复落库同一份增量</b>。
     */
    private void restoreDirty(LocalDate date, List<String> codes) {
        String dirtyKey = StatsKeys.dirty(date);
        try {
            redis.opsForSet().add(dirtyKey, codes.toArray(new String[0]));
            redis.expire(dirtyKey, StatsKeys.KEY_TTL);
            log.warn("短码已放回 {}（{} 个）等待下一轮", dirtyKey, codes.size());
        } catch (Exception e) {
            // ⚠️ 放回也失败 → 这些短码的 PV 会滞留到 TTL 过期。
            //    必须计数：这属于「已知的数据丢失」，不记就等于没有。
            codesUnmapped.increment(codes.size());
            log.error("短码放回 {} 失败，{} 个短码的统计将滞留到 TTL 过期",
                    dirtyKey, codes.size(), e);
        }
    }

    private HyperLogLogOperations<String, String> hyperLogLog() {
        return redis.opsForHyperLogLog();
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            // ⚠️ 不抛异常：PV key 的值由本项目的 Lua 写入，理论上一定是数字。
            //    但若有人手工 SET 了一个脏值，让整批落库失败比丢这一条更糟。
            log.warn("PV 值不是合法数字，按 0 处理: {}", s);
            return 0L;
        }
    }

    /** 暴露给测试：当前重试退避表。 */
    long[] retryBackoffMillis() {
        return retryBackoffMillis.clone();
    }
}
