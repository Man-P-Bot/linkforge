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
     * 确保序号 key 存在且位于本区间的起点。
     *
     * <p>用 {@code SETNX}（{@code setIfAbsent}）而不是无条件 {@code SET}：
     * 后者会在应用重启时把计数器重置回起点，**直接造成大规模重复发号**。
     * 已存在时什么都不做，让计数器继续向前。
     */
    public void ensureInitialized() {
        Boolean created = redisTemplate.opsForValue()
                .setIfAbsent(SEQUENCE_KEY, Long.toString(IdSpace.REDIS_KEY_INIT_VALUE));
        if (Boolean.TRUE.equals(created)) {
            log.info("Redis 发号 key {} 不存在，已初始化为 {}（首个发出的号为 {}）",
                    SEQUENCE_KEY, IdSpace.REDIS_KEY_INIT_VALUE, IdSpace.FIRST_SEQUENCE);
        } else {
            String current = redisTemplate.opsForValue().get(SEQUENCE_KEY);
            log.info("Redis 发号 key {} 已存在，当前值 {}，继续递增", SEQUENCE_KEY, current);
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
