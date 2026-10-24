package com.linkforge.stats.infrastructure;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 统计相关的 Redis Key 构造器（<b>唯一来源</b>）。
 *
 * <h2>⚠️ 为什么要抽这个类</h2>
 * 阶段 2（写）与阶段 3（读）必须对 key 的拼法<b>逐字符一致</b>，
 * 否则会出现一个极难发现的故障：flush 每分钟 {@code SPOP} 一个空集合，
 * 日志一切正常、指标全是 0，而统计数据静静地躺在 Redis 里直到 35 天后过期。
 * <p>
 * 两边各写一份 {@code "shortlink:pv:" + code + ":" + date} 是典型的
 * 「复制粘贴式约定」——现在一致，改一处忘了另一处就静默失效。
 * 所以这里抽成常量 + 方法，写侧与读侧都引用它。
 *
 * <h2>Key 结构总览</h2>
 * <pre>
 *   shortlink:pv:{code}:{yyyymmdd}      String   PV 计数，取走即删（GETDEL）
 *   shortlink:uv:{code}:{yyyymmdd}      HLL      UV 去重，只读估算（PFCOUNT）
 *   shortlink:stats:dirty:{yyyymmdd}    Set      当日「有待落库的短码」，SPOP 取走即删
 *   shortlink:stats:dl:{yyyymmdd}       List     死信队列（Redis 写失败 / MySQL 落库失败）
 *   shortlink:stats:flush:lock          String   落库任务的全局锁
 * </pre>
 *
 * <h2>⚠️ 为什么 dirty set 必须由写入侧维护</h2>
 * 常见做法是 flush 时用 {@code SCAN shortlink:pv:*} 扫 key，但那有两个致命问题：
 * <ol>
 *   <li><b>SCAN 是游标遍历，不是快照</b>：扫描期间新产生的 key 可能被漏掉
 *       （游标回绕机制决定了这一点），漏掉的 key 要等 35 天 TTL 到期才自然消失。</li>
 *   <li><b>无法区分「今天有量」与「35 天内有量」</b>：扫出来的 key 绝大多数属于历史日期，
 *       每分钟扫一遍全量 key 是纯粹的浪费。</li>
 * </ol>
 * dirty set 是「<b>逆写</b>」：谁产生了数据谁登记，flush 只处理登记过的短码。
 * 代价是每次 PV 写入多一次 {@code SADD}——但它被放进同一个 Lua 脚本里，
 * <b>不增加往返次数</b>（见 {@code lua/pv_incr_by.lua}）。
 *
 * <h2>⚠️ 为什么 dirty set 只由 PV 通道维护，UV 不维护</h2>
 * 因为 PV 是「每次访问必有」的通道，UV 只在 visitor 非 null 时才有。
 * 用 PV 登记就足以覆盖「这个短码今天有流量」这个语义——
 * 若 UV 也登记，会产生两倍的无意义 {@code SADD}（同一 code 同一天重复登记）。
 * 且 PV 已登记的 code 在 flush 时会<b>顺带读一次 UV</b>，UV 数据不会漏。
 */
public final class StatsKeys {

    private StatsKeys() {
        throw new AssertionError("工具类不应被实例化");
    }

    /** PV 计数 key 前缀。 */
    public static final String PV_PREFIX = "shortlink:pv:";

    /** UV 去重 key 前缀（HyperLogLog）。 */
    public static final String UV_PREFIX = "shortlink:uv:";

    /**
     * 脏数据集合前缀：登记「当日有流量、待落库」的短码。
     *
     * <p>⚠️ <b>必须设 TTL</b>：这个 set 一天一个 key，若不过期，
     * 「有流量但映射不到 link_id」的短码会永久驻留（例如短链被物理删除后）。
     */
    public static final String DIRTY_PREFIX = "shortlink:stats:dirty:";

    /** 死信队列前缀。 */
    public static final String DLQ_PREFIX = "shortlink:stats:dl:";

    /**
     * 过期清理的扫描游标前缀：{@code shortlink:stats:cleanup:cursor:{yyyymmdd}}。
     *
     * <p>⚠️ <b>游标是正确性必需，不是性能优化</b>：
     * 过期清理**不修改 status**（保住 410 语义，DR-060 决策 2），
     * 所以扫描条件 {@code status = 0 AND expire_at < ?} 对同一批行<b>永远成立</b>。
     * 没有游标时，每轮 {@code LIMIT} 出的都是<b>同一批</b>（id 最小的那些），
     * 后面几万条过期短码的缓存<b>永远清不到</b>。
     *
     * <p>游标存 Redis 而不是内存：清理任务在多实例下运行，
     * 且进程可能重启。存内存会让「重启后从头开始」——
     * 那意味着 id 最小的那批会被反复清理，而中间那段永远扫不到。
     */
    public static final String CLEANUP_CURSOR_PREFIX = "shortlink:stats:cleanup:cursor:";

    /** 落库任务的全局锁。 */
    public static final String FLUSH_LOCK = "shortlink:stats:flush:lock";

    /** 日期后缀格式：{@code 20260314}。定长，便于 key 排序与人工排查。 */
    public static final DateTimeFormatter DATE = DateTimeFormatter.BASIC_ISO_DATE;

    /**
     * 统计 key 的 TTL：35 天。
     *
     * <p>覆盖「当月 + 上月」（月初仍能查到上月报表），另留 5 天缓冲应对跨月边界的写入延迟。
     * <p>⚠️ <b>所有统计 key 都必须有 TTL</b>，否则 Redis 内存只增不减。
     */
    public static final Duration KEY_TTL = Duration.ofDays(35);

    public static String pv(String shortCode, String date) {
        return PV_PREFIX + shortCode + ":" + date;
    }

    public static String uv(String shortCode, String date) {
        return UV_PREFIX + shortCode + ":" + date;
    }

    public static String dirty(LocalDate date) {
        return DIRTY_PREFIX + date.format(DATE);
    }

    /**
     * 过期清理的扫描游标 key。
     *
     * @param date 扫描的日期（游标按天独立：跨天后从头扫新的一天）
     */
    public static String cleanupCursor(LocalDate date) {
        return CLEANUP_CURSOR_PREFIX + date.format(DATE);
    }

    public static String dlq(LocalDate date) {
        return DLQ_PREFIX + date.format(DATE);
    }

    public static String date(LocalDate date) {
        return date.format(DATE);
    }
}
