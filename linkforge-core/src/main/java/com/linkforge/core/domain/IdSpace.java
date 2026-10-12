package com.linkforge.core.domain;

/**
 * 短码发号的号空间定义（docs/DECISIONS.md DR-011 / DR-022）。
 *
 * <p>短码 = 序号经 {@link Base62} 编码后左补零到 {@link #SHORT_CODE_LENGTH} 位。
 * 7 位 Base62 空间为 {@code 62^7 = 3_521_614_606_208}（约 3.52 万亿）。
 *
 * <h2>为什么把两个发号器的区间写在同一处</h2>
 * Redis 发号（主路径）与数据库号段发号（降级路径）**共用同一个号空间**，一旦区间重叠，
 * 降级期间就会发出与 Redis 已发出号码重复的短码。原方案把「Redis 起点 100000」
 * 与「数据库号段起点」写在不同位置，且数据库号段发出的是 {@code max_id + 1}，
 * 会产生 1 的偏移——**区间边界漏一个号正是重复发号的经典成因**。
 *
 * <p>因此这里的做法是：把不变量写成常量，并让两个发号器的初始化值**由常量推导**，
 * 再用 {@code IdSpaceBoundaryTest} 断言「Redis 首个发出的号 + 1 == 数据库号段首个发出的号」。
 * 这样边界关系是被测试锁住的，而不是靠人记住。
 */
public final class IdSpace {

    /** 短码固定长度（PRD §7.1）。 */
    public static final int SHORT_CODE_LENGTH = 7;

    /** 每位 Base62 的基数。 */
    public static final int BASE = 62;

    /** 短码可表示的最大序号：{@code 62^7 - 1}，超出即无法用 7 位表示。 */
    public static final long MAX_SEQUENCE = 3_521_614_606_207L;

    /**
     * Redis 发号区间：{@code [FIRST_SEQUENCE, REDIS_RANGE_END]}。
     *
     * <p>{@link #FIRST_SEQUENCE} 是**第一个被发出的号**（不是 Redis key 的初始值）。
     * 因为 Redis 的 {@code INCR} 是「先加后返回」，要让首次 {@code INCR} 返回 {@code FIRST_SEQUENCE}，
     * key 必须预置为 {@code FIRST_SEQUENCE - 1}，即 {@link #REDIS_KEY_INIT_VALUE}。
     */
    public static final long FIRST_SEQUENCE = 100_000L;

    /** Redis key 的初始值 = 首个发出的号 − 1，由常量推导，避免手写导致差一。 */
    public static final long REDIS_KEY_INIT_VALUE = FIRST_SEQUENCE - 1;

    /** Redis 区间上界（不含）：数据库号段从 {@link #SEGMENT_FIRST_SEQUENCE} 起跳，两区间不重叠。 */
    public static final long REDIS_RANGE_END = 1_000_000_000L;

    /** 数据库号段区间：{@code [SEGMENT_FIRST_SEQUENCE, MAX_SEQUENCE]}。 */
    public static final long SEGMENT_FIRST_SEQUENCE = REDIS_RANGE_END;

    /**
     * {@code id_segment.max_id} 的初始值。
     *
     * <p>号段发出的是 {@code max_id + 1}，所以要让首个发出的号等于 {@link #SEGMENT_FIRST_SEQUENCE}，
     * 初始 {@code max_id} 必须是 {@code SEGMENT_FIRST_SEQUENCE - 1}。
     * 这里刻意不写 {@code 100000}：那会让数据库号段从 100001 起，与 Redis 区间重叠。
     */
    public static final long SEGMENT_MAX_ID_INIT_VALUE = SEGMENT_FIRST_SEQUENCE - 1;

    /** 号段步长默认值；可用配置覆盖（最终取值由第 1 周 D7 压测决定，见 DATABASE.md D-4）。 */
    public static final int DEFAULT_SEGMENT_STEP = 1000;

    private IdSpace() {
    }

    /**
     * 校验序号是否落在可表示的范围内。
     *
     * @throws IllegalArgumentException 序号为负，或超出 7 位 Base62 可表示的上界。
     *          **绝不回绕、绝不截断**：回绕会让已发出的短码指向另一条链接。
     */
    public static void requireInRange(long sequence) {
        if (sequence < 0) {
            throw new IllegalArgumentException("序号不能为负: " + sequence);
        }
        if (sequence > MAX_SEQUENCE) {
            throw new IllegalArgumentException(
                    "序号 " + sequence + " 超出 " + SHORT_CODE_LENGTH + " 位 Base62 上界 " + MAX_SEQUENCE);
        }
    }
}
