package com.linkforge.redirect.infrastructure.id;

import com.linkforge.core.domain.Base62;
import com.linkforge.core.domain.IdSpace;
import com.linkforge.core.repository.ShortCodeGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 主路径发号器：Redis {@code INCR}（docs/DECISIONS.md DR-011）。
 *
 * <h2>key 与初始值</h2>
 * <ul>
 *   <li>key：{@code shortlink:id:seq}</li>
 *   <li>初始值：{@link IdSpace#REDIS_KEY_INIT_VALUE}（= 首个发号 − 1，因为 {@code INCR} 先加后返回）</li>
 *   <li>区间：{@code [IdSpace.FIRST_SEQUENCE, IdSpace.REDIS_RANGE_END)}</li>
 * </ul>
 *
 * <h2>⚠️ 已知风险：Redis 被清空会导致号段回退</h2>
 * 若 Redis 丢失数据（RDB 恢复到旧快照、{@code FLUSHDB}、换实例），{@code INCR} 会从一个很小的值
 * 重新开始，发出的短码可能与历史短码**重复**。处置（DR-022）：
 * <ol>
 *   <li>启动时若 key 不存在，重新初始化为区间起点（本类 {@code ensureInitialized}）；</li>
 *   <li>真正的兜底是 {@code short_link} 上的唯一索引 {@code uk_short_code}——
 *       重复短码会被数据库拒绝，不会静默覆盖他人链接；</li>
 *   <li>冲突计数指标 {@code shortlink_id_conflict_total} 用于让运维**立即发现**这种回退。</li>
 * </ol>
 * v2 方案：在 {@code short_link} 增加 {@code seq} 列，启动时取 {@code MAX(seq)} 作为 Redis 初始值，
 * 从根本上让号段跟随数据库真相，而不是依赖一个可能被清空的计数器。
 */
public class RedisShortCodeGenerator implements ShortCodeGenerator {

    /** 见 PRD §「Redis Key 设计」：所有业务 key 统一 {@code shortlink:} 前缀。 */
    public static final String SEQUENCE_KEY = "shortlink:id:seq";

    private static final Logger log = LoggerFactory.getLogger(RedisShortCodeGenerator.class);

    private final StringRedisTemplate redisTemplate;
    private final int shortCodeLength;

    public RedisShortCodeGenerator(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.shortCodeLength = IdSpace.SHORT_CODE_LENGTH;
    }

    /**
     * 确保序号 key 存在，且其值**不低于本区间起点**。
     *
     * <p>三种情况分别处理（缺一不可）：
     * <ol>
     *   <li><b>key 不存在</b> → {@code SETNX} 初始化为 {@link IdSpace#REDIS_KEY_INIT_VALUE}。
     *       用 {@code SETNX} 而非无条件 {@code SET}：后者会在每次重启时把计数器重置回起点，
     *       导致大规模重复发号。</li>
     *   <li><b>key 存在但值小于起点</b>（Redis 被清空过、RDB 恢复到旧快照、有人手工 {@code SET}
     *       了异常值）→ <b>修正到起点并记 WARN</b>。这是原实现的漏洞：
     *       {@code SETNX} 不覆盖已存在的 key，于是 {@code INCR} 会从一个很小的值继续，
     *       发出的短码可能与历史短码重复。</li>
     *   <li><b>key 存在且值 ≥ 起点</b> → 正常，不动。**绝不能重置**，否则会重发已发出的号。</li>
     * </ol>
     *
     * <p>注意第 2 种修正只是**缩小**了回退窗口，并不能证明那些号没被发出去过——
     * 真正的唯一性兜底始终是 {@code short_link.uk_short_code} 唯一索引 +
     * {@code shortlink_id_conflict_total} 冲突指标（docs/DECISIONS.md DR-022）。
     */
    public void ensureInitialized() {
        String current = redisTemplate.opsForValue().get(SEQUENCE_KEY);

        if (current == null) {
            Boolean created = redisTemplate.opsForValue()
                    .setIfAbsent(SEQUENCE_KEY, Long.toString(IdSpace.REDIS_KEY_INIT_VALUE));
            if (Boolean.TRUE.equals(created)) {
                log.info("Redis 发号 key {} 不存在，已初始化为 {}（首个发出的号为 {}）",
                        SEQUENCE_KEY, IdSpace.REDIS_KEY_INIT_VALUE, IdSpace.FIRST_SEQUENCE);
            } else {
                // 极小概率：SETNX 期间被其它实例抢先创建。重新读一次即可，不影响正确性。
                log.info("Redis 发号 key {} 已被其它实例初始化，当前值 {}",
                        SEQUENCE_KEY, redisTemplate.opsForValue().get(SEQUENCE_KEY));
            }
            return;
        }

        Long value = parseSequence(current);
        if (value == null) {
            // 值不是数字（被误写成别的类型或脏数据）：直接修正，否则 INCR 会报错
            log.warn("Redis 发号 key {} 的值不是整数（当前 \"{}\"），已修正为 {}",
                    SEQUENCE_KEY, current, IdSpace.REDIS_KEY_INIT_VALUE);
            redisTemplate.opsForValue().set(SEQUENCE_KEY, Long.toString(IdSpace.REDIS_KEY_INIT_VALUE));
            return;
        }

        if (value < IdSpace.REDIS_KEY_INIT_VALUE) {
            // ⚠️ 已知风险场景：Redis 数据被清空/回退后，计数器从小值重新开始，
            // 会发出与历史短码重复的号。这里修正回起点并告警。
            log.warn("Redis 发号 key {} 当前值 {} 小于区间起点 {}，疑似 Redis 数据被清空或回退，"
                            + "已修正为 {}。注意：此修正无法证明这些号未被发出过，"
                            + "请关注 shortlink_id_conflict_total 指标（见 DR-022）",
                    SEQUENCE_KEY, value, IdSpace.REDIS_KEY_INIT_VALUE, IdSpace.REDIS_KEY_INIT_VALUE);
            redisTemplate.opsForValue().set(SEQUENCE_KEY, Long.toString(IdSpace.REDIS_KEY_INIT_VALUE));
            return;
        }

        log.info("Redis 发号 key {} 已存在，当前值 {}，继续递增", SEQUENCE_KEY, value);
    }

    /** 宽松解析：值不是合法 long 时返回 {@code null}，由调用方决定如何修正。 */
    private Long parseSequence(String raw) {
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    public String nextShortCode() {
        long sequence;
        try {
            Long value = redisTemplate.opsForValue().increment(SEQUENCE_KEY);
            if (value == null) {
                throw new ShortCodeGenerationException("Redis INCR 返回 null，key=" + SEQUENCE_KEY);
            }
            sequence = value;
        } catch (ShortCodeGenerationException e) {
            throw e;
        } catch (RuntimeException e) {
            // 连接异常、超时（连接超时配置为 50ms，见 ARCHITECTURE §11.3）都在这里被捕获，
            // 由 FallbackShortCodeGenerator 决定是否降级。此处不吞异常。
            throw new ShortCodeGenerationException("Redis 发号失败: " + e.getMessage(), e);
        }

        if (sequence >= IdSpace.REDIS_RANGE_END) {
            // 号空间用尽：绝不回绕（回绕会让短码重复）。按 DR-022 记 ERROR 并让调用失败——
            // 此时唯一正确的动作是人工介入把发号切到数据库号段区间。
            log.error("Redis 发号区间已用尽：seq={} 达到上界 {}，必须人工切换发号区间。key={}",
                    sequence, IdSpace.REDIS_RANGE_END, SEQUENCE_KEY);
            throw new ShortCodeGenerationException(
                    "Redis 发号区间用尽（seq=" + sequence + " >= " + IdSpace.REDIS_RANGE_END + "）");
        }

        String code = Base62.encodePadded(sequence);
        if (code.length() != shortCodeLength) {
            // encodePadded 已保证长度，这里只是防御性断言
            throw new ShortCodeGenerationException("短码长度异常: " + code);
        }
        return code;
    }
}
