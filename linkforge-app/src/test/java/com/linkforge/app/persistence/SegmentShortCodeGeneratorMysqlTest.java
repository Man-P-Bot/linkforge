package com.linkforge.app.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.linkforge.core.domain.IdSpace;
import com.linkforge.core.infrastructure.persistence.IdSegmentMapper;
import com.linkforge.core.repository.ShortCodeGenerator.ShortCodeGenerationException;
import com.linkforge.redirect.infrastructure.id.IdSegmentAllocator;
import com.linkforge.redirect.infrastructure.id.SegmentShortCodeGenerator;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 号段发号器**真实降级路径**的集成测试（DR-042 / DR-043）。
 *
 * <h2>为什么必须用真实 MyBatis + 真实事务，不能 mock</h2>
 * DR-042 的缺陷是：{@code IdSegmentMapper.advanceAndGetMaxId} 被声明成返回
 * {@code long}，但 MyBatis 的 {@code <update>} 一律返回**受影响行数**（恒为 1）。
 * 于是号段右端点被算成 1，段首变成 {@code 1 - step + 1 = -998}（负序号）。
 *
 * <p>这个缺陷能长期潜伏，正是因为两重遮蔽：
 * <ol>
 *   <li>{@code SegmentShortCodeGeneratorTest} 把 {@code IdSegmentAllocator} mock 掉了，
 *       mock 返回的是真正的 {@code max_id}，于是 8 个单元测试全绿；</li>
 *   <li>{@code RUN_MYBATIS_IT=false} 时集成测试整体跳过，
 *       真实 SQL 路径从未被执行过一次。</li>
 * </ol>
 *
 * <p>因此本类**注入容器里的真实 {@link SegmentShortCodeGenerator} bean**，
 * 不自己 {@code new}。这一点是刻意的：
 * {@code IdSegmentAllocator} 的 {@code REQUIRES_NEW} 依赖 Spring 代理，
 * 手工 new 出来的实例上注解**完全失效**——那样测的就不是生产路径，
 * 而是「事务边界不存在」的另一个世界。
 *
 * <p>⚠️ <b>刻意不加类级 {@code @Transactional}</b>：那会让本类的事务在整个用例期间
 * 持有 {@code id_segment} 行的 InnoDB 行锁，而发号器内部的
 * {@code REQUIRES_NEW} 事务要更新**同一行**——自己等自己，必然
 * {@code Lock wait timeout exceeded}（实测每个用例卡 50s 超时）。
 * 这不是生产缺陷：生产里取号段是独立事务，不被任何外层事务包裹。
 * 因此本类改为每个用例后手工把 {@code id_segment} 归位。
 *
 * <p>测试类由 {@code RUN_MYBATIS_IT=true} 开关控制。
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("号段发号器真实降级路径（DR-042 / DR-043）")
class SegmentShortCodeGeneratorMysqlTest extends MySqlContainerSupport {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * 容器里的 {@link IdSegmentAllocator} bean —— 必须是**代理**而不是原对象。
     *
     * <p>{@code REQUIRES_NEW} 依赖 Spring 代理：手工 {@code new IdSegmentAllocator(...)}
     * 得到的实例上注解完全失效，测出来的就不是生产路径。
     * 而 {@code SegmentShortCodeGenerator} 只是持有一个 allocator 引用，
     * 换成代理不影响它的行为，因此可以放心复用容器里的这个。
     */
    @Autowired
    private IdSegmentAllocator allocator;

    /**
     * 每个用例一个全新的发号器。
     *
     * <p>⚠️ 不能直接用容器里的单例 {@code SegmentShortCodeGenerator}：
     * 它的 {@code current}/{@code limit}/{@code initialized} 是**进程内状态**，
     * 会跨用例残留——上一个用例推进到某一段之后，下一个用例虽然把数据库
     * {@code max_id} 归位了，发号器内存里的段却还是旧的，于是两个用例发出���号重叠
     * （实测跨段用例拿到 1723/2500 个唯一值）。
     */
    private SegmentShortCodeGenerator segmentGenerator;

    /** 容器里的真实 Mapper bean：与生产完全同一条装配路径，不手工 new。 */
    @Autowired
    private IdSegmentMapper idSegmentMapper;

    private static final int STEP = 1000;

    @BeforeEach
    void resetSegmentRow() {
        reset();
        this.segmentGenerator = new SegmentShortCodeGenerator(allocator, STEP);
    }

    /**
     * 把 {@code id_segment} 恢复到初始状态。
     *
     * <p>用 {@code ON DUPLICATE KEY UPDATE} 而非 {@code INSERT IGNORE}——
     * 这里就是要**重置**（生产侧则绝不能覆盖，见 {@code IdSegmentMapper} 的 Javadoc）。
     * 没有 {@code @Transactional} 回滚兜底，因此每个用例后都要调用。
     */
    private void reset() {
        jdbcTemplate.update(
                "INSERT INTO id_segment (biz_tag, max_id, step) VALUES (?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE max_id = VALUES(max_id), step = VALUES(step)",
                IdSegmentAllocator.BIZ_TAG, IdSpace.SEGMENT_MAX_ID_INIT_VALUE, STEP);
    }

    @AfterEach
    void restoreSegmentRow() {
        // 行可能已被某个用例删掉，ON DUPLICATE KEY UPDATE 两种情况都能恢复
        reset();
    }

    @Test
    @DisplayName("⭐ 连续取 100 个号：全部 7 位、无重复、字符合法（DR-042 核心回归）")
    void shouldIssue100DistinctCodes() {
        Set<String> codes = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            String code = segmentGenerator.nextShortCode();

            assertThat(code).as("第 %d 个短码不应为空", i).isNotBlank();
            assertThat(code).as("短码应恒为 7 位").hasSize(7).matches("^[0-9A-Za-z]{7}$");
            assertThat(codes.add(code))
                    .as("第 %d 个短码重复：%s（发号器出现重号）", i, code)
                    .isTrue();
        }
        assertThat(codes).hasSize(100);
    }

    @Test
    @DisplayName("⭐ 发出的号落在 [10亿, +∞) 的号段区间，不与 Redis 区间重叠（DR-011）")
    void shouldNotOverlapWithRedisRange() {
        for (int i = 0; i < 20; i++) {
            String code = segmentGenerator.nextShortCode();
            long sequence = decodeBase62(code);
            assertThat(sequence)
                    .as("第 %d 个号 %s 逆算得 %d，必须落在数据库号段区间", i, code, sequence)
                    .isGreaterThanOrEqualTo(IdSpace.SEGMENT_FIRST_SEQUENCE);
            assertThat(sequence)
                    .as("第 %d 个号 %s 逆算得 %d，不得超过 7 位 Base62 上界", i, code, sequence)
                    .isLessThanOrEqualTo(IdSpace.MAX_SEQUENCE);
        }
    }

    @Test
    @DisplayName("⭐ 100 个号只取 1 段（max_id 恰好推进一个 step）")
    void shouldRefillSegmentOnlyOnceFor100Codes() {
        long before = currentMaxId();
        for (int i = 0; i < 100; i++) {
            segmentGenerator.nextShortCode();
        }
        long after = currentMaxId();

        assertThat(after - before)
                .as("100 个号（step=%d）只应推进 1 段", STEP)
                .isEqualTo(STEP);
    }

    @Test
    @DisplayName("⭐ 跨段边界无重号：取 2500 个号（跨 3 段）")
    void shouldNotDuplicateAcrossSegmentBoundaries() {
        Set<String> codes = new HashSet<>();
        for (int i = 0; i < STEP * 2 + 500; i++) {
            codes.add(segmentGenerator.nextShortCode());
        }

        assertThat(codes)
                .as("跨段时若出现重号，集合大小会小于取号总数")
                .hasSize(STEP * 2 + 500);
    }

    @Test
    @DisplayName("⭐ 号段越过上界时抛 ShortCodeGenerationException 而非 IllegalArgumentException（DR-043）")
    void exhaustedSegment_shouldThrowShortCodeGenerationException() {
        jdbcTemplate.update("UPDATE id_segment SET max_id = ? WHERE biz_tag = ?",
                IdSpace.MAX_SEQUENCE, IdSegmentAllocator.BIZ_TAG);

        assertThatThrownBy(() -> segmentGenerator.nextShortCode())
                .as("区间用尽是「依赖给不出可用区间」，必须映射 503 而不是 500")
                .isInstanceOf(ShortCodeGenerationException.class);
    }

    @Test
    @DisplayName("⭐ max_id 异常导致段首为负时抛 ShortCodeGenerationException（DR-042/043 的原症状）")
    void negativeSegmentStart_shouldThrowShortCodeGenerationException() {
        // 精确复现 DR-042：把 max_id 顶成 1，段首算成 1 - 1000 + 1 = -998。
        // 修复前这里会漏出 IllegalArgumentException 并变成 500。
        jdbcTemplate.update("UPDATE id_segment SET max_id = ? WHERE biz_tag = ?",
                1L, IdSegmentAllocator.BIZ_TAG);

        assertThatThrownBy(() -> segmentGenerator.nextShortCode())
                .as("段首为负必须在发号器内部被拦住，且异常类型要让上层映射成 503")
                .isInstanceOf(ShortCodeGenerationException.class);
    }

    @Test
    @DisplayName("⭐ 号段行不存在时抛 ShortCodeGenerationException（推进受影响行数 != 1）")
    void missingSegmentRow_shouldThrowShortCodeGenerationException() {
        jdbcTemplate.update("DELETE FROM id_segment WHERE biz_tag = ?", IdSegmentAllocator.BIZ_TAG);

        assertThatThrownBy(() -> segmentGenerator.nextShortCode())
                .as("号段行不存在是依赖侧数据缺失，不是编程错误")
                .isInstanceOf(ShortCodeGenerationException.class);
    }

    @Test
    @DisplayName("⭐ mapper 的 advanceMaxId 返回受影响行数而非 max_id（DR-042 根因锁定）")
    void mapperAdvance_shouldReturnAffectedRows() {
        Long before = currentMaxIdOrNull();

        // 走容器里的真实 Mapper bean：与生产完全同一条装配路径
        int affected = idSegmentMapper.advanceMaxId(IdSegmentAllocator.BIZ_TAG);
        Long after = idSegmentMapper.selectMaxId(IdSegmentAllocator.BIZ_TAG);

        assertThat(affected)
                .as("⚠️ 这正是 DR-042 的根因：UPDATE 返回的是受影响行数 1。"
                        + "把它当 max_id 用就会算出 segmentStart = 1 - step + 1 = -998")
                .isEqualTo(1);
        assertThat(affected)
                .as("若这里返回的是 max_id（一个十亿量级的数），"
                        + "说明 Mapper 签名又被改回了返回 max_id 的错误形态")
                .isNotEqualTo(after);
        assertThat(after - before)
                .as("UPDATE 应把 max_id 推进一个 step")
                .isEqualTo(STEP);
    }

    @Test
    @DisplayName("⭐ mapper 的 advanceMaxId 在行不存在时返回 0（上层据此拒绝推进）")
    void mapperAdvance_shouldReturnZeroWhenRowMissing() {
        jdbcTemplate.update("DELETE FROM id_segment WHERE biz_tag = ?", IdSegmentAllocator.BIZ_TAG);

        assertThat(idSegmentMapper.advanceMaxId(IdSegmentAllocator.BIZ_TAG))
                .as("受影响行数为 0 是「行不存在」的唯一信号，Allocator 据此抛异常")
                .isZero();
        assertThat(idSegmentMapper.selectMaxId(IdSegmentAllocator.BIZ_TAG))
                .as("行不存在时读回 null")
                .isNull();
    }

    private Long currentMaxIdOrNull() {
        return jdbcTemplate.queryForObject(
                "SELECT max_id FROM id_segment WHERE biz_tag = ?", Long.class,
                IdSegmentAllocator.BIZ_TAG);
    }

    private long currentMaxId() {
        Long v = currentMaxIdOrNull();
        return v == null ? -1 : v;
    }

    /** Base62 逆运算：把 7 位短码还原成序号（与 Base62.encodePadded 严格互逆）。 */
    private static long decodeBase62(String code) {
        final String alphabet = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
        long value = 0;
        for (char c : code.toCharArray()) {
            value = value * 62 + alphabet.indexOf(c);
        }
        return value;
    }
}