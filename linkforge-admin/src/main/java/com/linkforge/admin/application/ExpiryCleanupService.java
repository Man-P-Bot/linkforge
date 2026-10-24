package com.linkforge.admin.application;

import com.linkforge.core.repository.LinkRepository;
import com.linkforge.stats.infrastructure.StatsKeys;
import com.linkforge.core.repository.LinkRepository.ExpiredLink;
import com.linkforge.stats.infrastructure.RedisDistributedLock;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 过期短链的定时清理（阶段 4）。
 *
 * <p><b>职责边界</b>：<b>只清缓存，不改数据库</b>。
 * 短链的 {@code status} 保持 {@code ACTIVE}，过期判定仍由
 * {@code LinkState.resolve} 在读路径上完成（返回 410）。理由见第二节。
 *
 * <h2>一、为什么需要「定时扫描」而不是只靠「读时判定」</h2>
 * 跳转路径上<b>本来就有</b>惰性判定（{@code RedirectService} 每次都检查
 * {@code expire_at}）——所以从「用户能不能跳过去」的角度，清理任务<b>不是必需的</b>：
 * 过期短链在库里放着也跳不过去。
 * <p>
 * 那为什么还要它？因为惰性判定<b>只管跳转，不管别的</b>：
 * <ol>
 *   <li><b>缓存不释放</b>：{@code shortlink:code:{code}} 的 TTL 是 1 小时 + 抖动。
 *       过期短链的缓存条目会一直占着 Redis 内存直到 TTL 到期。
 *       短链大量到期时，这是一笔可观的空间占用。</li>
 *   <li><b>布隆过滤器虚高</b>：布隆的位数组里仍然留着已过期的短码。
 *       它们会被判「可能存在」→ 多一次回源查库 → 查库发现已过期 → 410。
 *       这条路径每次都要走完整的「布隆 → 缓存 → 库」三级，比直接 410 慢。</li>
 *   <li><b>缓存一致</b>：过期短链的缓存条目在 TTL 到期前仍会命中，
 *       每次命中都要多走一遍状态判定。清理掉它们能省下这部分开销。</li>
 * </ol>
 *
 * <h2>二、⚠️ 本任务<b>不修改数据库</b>：只清缓存，保住 410 语义</h2>
 * 过期清理<b>不</b>把短链软删除成 {@code status = DELETED}。
 * 理由是那会造成一个<b>对外可见的行为回退</b>：
 * <pre>
 *   清理前：status=ACTIVE + expire_at 已过 → LinkState.resolve → EXPIRED → <b>410 Gone</b>
 *   软删除后：status=DELETED                    → LinkState.resolve → DELETED → <b>404 Not Found</b>
 * </pre>
 * 而 410 的全部价值就在于告诉调用方「<b>这个码曾经有效</b>」
 * （docs/API.md §4.4、PRD US-4）。
 * 软删除会把这个信息抹掉：一个纯粹的过期链接，
 * 因为被后台任务扫过一次，对外就从「曾经存在但已失效」变成了「从未存在」。
 * <p>
 * <b>本任务的职责因此收窄为</b>：清理已过期短链对应的 Redis 缓存（L2）
 * 与本地缓存（L1，靠 TTL 兜底）。数据库的 {@code status} 保持 {@code ACTIVE}，
 * 用户再次请求时 L2 miss 回源查库，{@code LinkState.resolve} 发现
 * {@code expire_at < now}，<b>正常返回 410</b>。
 * <p>
 * ⚠️ <b>代价：布隆过滤器不会淘汰过期短码</b>。
 * 布隆的定时全量重建走 {@code scanActiveWithId}——只取 {@code status = 0} 的短码。
 * 过期短链的 status 仍是 {@code ACTIVE}，所以它永远留在布隆里。
 * 后果：请求过期短码时，布隆判「可能存在」→ 缓存已清（miss）→ 回源查库 → 410。
 * 比「布隆直接判不存在」多一次查库，但<b>结果完全正确</b>。
 * 彻底解决需要给 {@code LinkStatus} 增加第三值（{@code EXPIRED}），
 * 那是更大的改动（表结构 + 枚举 + TypeHandler + 所有 status 过滤 + 布隆语义），本阶段不做。
 *
 * <h2>三、⚠️ 与统计 flush 的并发</h2>
 * 两个任务可能同时跑（它们用<b>不同的锁</b>，见下文）。
 * 由于本任务<b>只清缓存、不改数据库</b>，与 flush 之间<b>没有任何数据竞争</b>：
 * flush 操作 {@code link_stats_daily} 与 {@code short_link} 的读，
 * cleanup 只操作 {@code short_link} 的读与 Redis 缓存的删。
 * <p>
 * 统计<b>继续</b>落到已过期（甚至已被 admin 删除）短链的 link_id 上：
 * {@code resolveIdsByShortCodes} 不过滤 status，
 * 且统计落的是<b>已发生的访问</b>——那些访问真实发生过，不该被丢弃。
 *
 * <h2>四、⚠️ 布隆过滤器：<b>不删除</b></h2>
 * 标准布隆过滤器<b>不支持删除</b>（要删就得重算所有元素的位并重建整条链），
 * 而重建成本是 O(全部短码数)。
 * <p>
 * 本项目已经有每小时一次的全量重建（{@code BloomFilterMaintenance}），
 * 但它走 {@code scanActiveWithId}——只取 {@code status = 0} 的短码。
 * 由于本任务<b>不改 status</b>，过期短码<b>不会</b>被重建淘汰
 * （代价见第二节末尾）。
 * <p>
 * 布隆的误判率随删除累积缓慢上升，但每小时全量重建会把它重置
 * ——而 admin 手动删除的短链（status=DELETED）仍会被重建自然淘汰。
 *
 */
@Component
public class ExpiryCleanupService {

    private static final Logger log = LoggerFactory.getLogger(ExpiryCleanupService.class);

    /**
     * 清理任务的分布式锁 key。
     *
     * <p>⚠️ <b>与统计 flush 的锁是不同的 key</b>（{@code shortlink:stats:flush:lock}）：
     * 两个任务操作的是<b>不同的数据</b>（{@code short_link} vs {@code link_stats_daily}），
     * 用同一把锁会让其中一个白等一轮。
     * <p>
     * 而且它们<b>必须能并行</b>：清理要扫 1000 万行的表、flush 要写统计表，
     * 串行化会让两者的总时长相加。各自加锁、各自并行，才是对的。
     */
    public static final String CLEANUP_LOCK = "shortlink:stats:cleanup:lock";

    private final LinkRepository linkRepository;
    private final LinkExpiryService expiryService;
    private final RedisDistributedLock lock;
    private final StringRedisTemplate redis;
    private final Clock clock;

    private final int batchSize;
    private final int maxBatchesPerRun;
    private final Duration lockTtl;

    private final Counter scannedCounter;
    private final Counter clearedCounter;
    private final Counter failedCounter;
    private final Timer durationTimer;

    /**
     * @param batchSize     单批扫描条数
     * @param maxBatchesPerRun 单轮最多处理几批（必须有上限，理由见类注释）
     * @param lockTtlSeconds 锁 TTL，<b>必须大于单轮最坏耗时</b>
     */
    public ExpiryCleanupService(LinkRepository linkRepository,
                                LinkExpiryService expiryService,
                                RedisDistributedLock lock,
                                StringRedisTemplate redis,
                                Clock clock,
                                MeterRegistry meterRegistry,
                                @Value("${linkforge.cleanup.batch-size:1000}") int batchSize,
                                @Value("${linkforge.cleanup.max-batches-per-run:5}") int maxBatchesPerRun,
                                @Value("${linkforge.cleanup.lock-ttl-seconds:60}") long lockTtlSeconds) {
        this.linkRepository = linkRepository;
        this.expiryService = expiryService;
        this.lock = lock;
        this.redis = redis;
        this.clock = clock;
        this.batchSize = batchSize;
        this.maxBatchesPerRun = maxBatchesPerRun;
        this.lockTtl = Duration.ofSeconds(lockTtlSeconds);

        this.scannedCounter = Counter.builder("shortlink.cleanup.scanned")
                .description("本轮扫描到的已过期短链数")
                .register(meterRegistry);
        this.clearedCounter = Counter.builder("shortlink.cleanup.cleared")
                .description("本轮成功清理缓存的过期短链数。"
                        + "⚠️ 注意**不是**删除数——过期清理不修改数据库状态")
                .register(meterRegistry);
        this.failedCounter = Counter.builder("shortlink.cleanup.failed")
                .description("本轮清理失败的短链数。持续增长说明缓存层有问题")
                .register(meterRegistry);
        this.durationTimer = Timer.builder("shortlink.cleanup.duration")
                .description("单轮过期清理耗时")
                .publishPercentileHistogram()
                .register(meterRegistry);
    }

    // ==================================================== 定时任务入口

    /**
     * 定时清理已过期的短链。
     *
     * <p>⚠️ <b>本方法绝不允许抛出任何异常</b>：{@code @Scheduled} 的任务抛异常后，
     * 该 Trigger 会被 Spring <b>取消且不再重建</b>（阶段 3 的同一个坑）。
     * 所以最外层是 catch-all {@code Throwable}。
     *
     * <p>⚠️ 用 cron 而非 fixedRate：fixedRate 是「上一次开始后固定间隔再触发」，
     * 某轮跑慢了后续触发会堆积。cron 是「到点触发，错过就跳过」，天然无堆积。
     */
    @Scheduled(cron = "${linkforge.cleanup.cron:0 */5 * * * *}")
    public void cleanup() {
        long t0 = System.nanoTime();
        try {
            // ① 守卫：消费线程不在工作就不做它下游的事？
            //    ⚠️ 过期清理**不依赖**统计队列——它清理的是短链本身，
            //    与统计链路无关。所以这里**没有** queue.isRunning() 守卫。
            //    （统计 flush 有，因为它的数据由队列生产。）

            // ② 全局锁：多实例下只有一个真正执行。
            if (!lock.tryLock(CLEANUP_LOCK, lockTtl)) {
                log.debug("未取得清理锁（其他实例正在执行或 Redis 不可用），跳过本轮");
                return;
            }
            try {
                // ③ 二次校验：加锁后到干活之间，锁可能已过期并被他人接管。
                if (!lock.holdsLock(CLEANUP_LOCK)) {
                    log.warn("清理锁在执行前已不属于本实例，放弃本轮（避免并发清理）");
                    return;
                }
                doCleanup();
            } finally {
                // ④ 释放带 value 校验的锁，不误删他人的锁。
                lock.unlock(CLEANUP_LOCK);
            }
        } catch (Throwable t) {
            // ⚠️ catch-all：见方法 javadoc。这里绝不能 rethrow。
            log.error("过期清理异常（调度已存活，继续下一轮）: {}", t.toString(), t);
        } finally {
            durationTimer.record(System.nanoTime() - t0, java.util.concurrent.TimeUnit.NANOSECONDS);
        }
    }

    /** 持锁后的真正工作。 */
    private void doCleanup() {
        // ⚠️ 必须用注入的 Clock（ARCHITECTURE §13：时间获取必须可注入）。
        //    直接取系统当前时间会让「今天是哪天」无法在测试里固定。
        LocalDate today = LocalDate.now(clock);
        Instant now = clock.instant();
        int totalScanned = 0;
        int totalCleared = 0;

        // ⚠️⚠️ 游标：从上次扫到的 id 之后继续。
        //
        //    过期清理**不修改 status**（保住 410 语义，DR-060 决策 2），
        //    所以扫描条件 `status = 0 AND expire_at < ?` 对同一批行**永远成立**。
        //    没有游标时，每轮 LIMIT 出的都是**同一批**（id 最小的那些），
        //    后面几万条过期短码的缓存**永远清不到**。
        //    游标存 Redis（不是内存）：任务在多实例下运行且进程可能重启，
        //    存内存会让「重启后从头开始」——那意味着 id 最小的那批被反复清理，
        //    而中间那段永远扫不到。
        long afterId = readCursor(today);

        for (int batch = 0; batch < maxBatchesPerRun; batch++) {
            List<ExpiredLink> expired = linkRepository.scanExpired(batchSize, now, afterId);
            if (expired.isEmpty()) {
                break;
            }
            totalScanned += expired.size();
            scannedCounter.increment(expired.size());

            int cleared = clearCachesSafely(expired);
            totalCleared += cleared;
            clearedCounter.increment(cleared);

            // ⚠️ 游标推进到本批最大 id。必须在清缓存**之后**推进 ——
            //    若先推进再清缓存，中途崩溃会跳过这一批（那批的缓存永远清不掉）。
            afterId = expired.get(expired.size() - 1).id();
            writeCursor(today, afterId);

            // ⚠️ 批次未清空说明库里还有更多过期的（batchSize 打满）。
            //    剩下的下一轮继续——游标保证下一轮从**这批之后**开始，
            //    不会重复处理这批。
            if (expired.size() < batchSize) {
                break;
            }
        }

        if (totalScanned > 0) {
            log.info("过期清理完成：扫描 {} 条，清缓存 {} 条（数据库状态未改动，游标推进到 id={}）",
                    totalScanned, totalCleared, afterId);
        }
    }

    /**
     * 读扫描游标。
     *
     * @return 上次扫到的最大 id；无游标时返回 0（从头开始）
     */
    private long readCursor(LocalDate date) {
        try {
            String raw = redis.opsForValue().get(StatsKeys.cleanupCursor(date));
            if (raw == null || raw.isBlank()) {
                return 0L;
            }
            return Long.parseLong(raw);
        } catch (RuntimeException e) {
            // ⚠️ 读游标失败**不能中断清理**：从头扫一遍虽然浪费，
            //    但总比不清理强。而且清缓存是幂等的，重复清无害。
            log.warn("读取过期清理游标失败，本轮从头开始扫: {}", e.getMessage());
            return 0L;
        }
    }

    /**
     * 写扫描游标。
     *
     * <p>⚠️ 用 {@code SET key value EX ttl} 一条命令 —— 拆成 SET + EXPIRE 时，
     * 两条之间崩溃会留下**永不过期**的游标（下次扫描会从那里继续，
     * 而中间新增的短码会被跳过）。
     */
    private void writeCursor(LocalDate date, long afterId) {
        try {
            redis.opsForValue().set(StatsKeys.cleanupCursor(date),
                    Long.toString(afterId), StatsKeys.KEY_TTL);
        } catch (RuntimeException e) {
            // ⚠️ 写游标失败：下一轮会从旧游标开始，重复清一批（幂等，无害）
            log.warn("写入过期清理游标失败（下一轮会重复清理一批，幂等无害）: {}",
                    e.getMessage());
        }
    }

    /**
     * 清理一批过期短链的缓存，**并捕获异常**。
     *
     * <p>⚠️ 失败时**这一批全部放弃**（不逐条重试）：
     * 下一轮会重新扫到它们。逐条重试的收益极低
     * （过期短链的缓存多留一分钟没有任何损失），
     * 代价却是在缓存层异常时把异常放大 N 倍。
     *
     * @return 成功清理的条数；异常时返回 0
     */
    private int clearCachesSafely(List<ExpiredLink> expired) {
        try {
            // ⚠️ 跨 bean 调用 → 走 Spring AOP 代理。
            //    早期版本把它写成同类私有方法 + @Transactional，
            //    结果同类自调用绕过了代理，事务根本没生效——
            //    缓存清理退化成「事务内立即执行」，删除静默失效。
            return expiryService.clearCaches(expired);
        } catch (RuntimeException e) {
            failedCounter.increment(expired.size());
            log.error("过期短链清缓存失败（{} 条），本批放弃，下轮重试: {}",
                    expired.size(), e.toString(), e);
            return 0;
        }
    }
}
