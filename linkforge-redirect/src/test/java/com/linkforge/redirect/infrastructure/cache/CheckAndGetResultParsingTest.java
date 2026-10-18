package com.linkforge.redirect.infrastructure.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;

import com.linkforge.core.infrastructure.cache.BloomHash;
import com.linkforge.core.infrastructure.cache.CacheKeys;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * 层 2 验证：{@code checkAndGet} 对 Lua 返回值的**解析**（DR-054）。
 *
 * <h2>⚠️ 这一层的边界要说清楚</h2>
 * 本类 {@code @Mock StringRedisTemplate}——**{@code redis.execute} 被完全打桩**，
 * 所以它验证的是「拿到某种返回形态后，Java 侧解析得对不对」，
 * <b>不验证</b>「参数能不能正确传出去」。
 *
 * <p>后者由层 1（redis-cli）与层 3（{@code @SpringBootTest} 全链路）覆盖。
 * <b>DR-054 的教训正是：三层缺一层，整条链路就是断的。</b>
 *
 * <h2>为什么这8 种形态都要覆盖</h2>
 * Lua 的返回值经Spring Data Redis 反序列化后，Java 侧实际拿到的类型
 * 取决于序列化器实现与 Redis 返回的字节形态，<b>不完全由脚本决定</b>。
 * 真实缺陷正是「实际拿到 {@code ArrayList} 而代码按 {@code String} 处理」，
 * 所以每一种可能的形态都必须有明确断言，不能靠「反正有 instanceof 兜着」。
 */
// ⚠️ Spring Boot 3.5 的 OutputCaptureExtension 用法（本项目踩了三个坑）：
//   1. **没有 start() 静态工厂**（旧版 API 已移除）
//   2. **构造器是包私有**，外部不能 new
//   3. **不能用 @RegisterExtension 实例字段**：JUnit 5 要求该字段必须已初始化，
//      不赋值会在注册阶段报 "field value must not be null"
//   正确用法：类级 @ExtendWith(OutputCaptureExtension.class)，
//   CapturedOutput 通过**测试方法参数**注入
@ExtendWith(MockitoExtension.class)
@ExtendWith(OutputCaptureExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CheckAndGetResultParsingTest {

    private static final String CODE = "0Q1A2b3";
    private static final String CACHED = "v1|ACTIVE||34|https://example.com/very/long/path";

    @Mock
    private StringRedisTemplate redis;

    private SimpleMeterRegistry registry;
    private RedisBloomFilter bloom;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        bloom = new RedisBloomFilter(redis, registry);
    }

    private void givenRaw(Object raw) {
        given(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .willReturn(raw);
    }

    private double blockedCount() {
        var counter = registry.find("shortlink.bloom.blocked").counter();
        return counter == null ? 0.0 : counter.count();
    }

    @Test
    @DisplayName("1️⃣ {1, \"v1|...\"} → HIT，值逐字符完整")
    void stringValueBecomesHit() {
        givenRaw(List.of(1L, CACHED));

        RedisBloomFilter.BloomCheckResult result = bloom.checkAndGet(CODE);

        assertThat(result.definitelyAbsent()).isFalse();
        assertThat(result.cachedValue())
                .as("含分隔符与长度前缀的值必须逐字符完整，不能被截断")
                .isEqualTo(CACHED);
        assertThat(result.requiresSource()).isFalse();
    }

    @Test
    @DisplayName("2️⃣ {1, false} → NEED_SOURCE（Lua 正常返回的无值形态）")
    void falseValueBecomesNeedsSource() {
        givenRaw(List.of(1L, Boolean.FALSE));

        RedisBloomFilter.BloomCheckResult result = bloom.checkAndGet(CODE);

        assertThat(result.definitelyAbsent()).isFalse();
        assertThat(result.requiresSource()).isTrue();
        assertThat(result.cachedValue()).isNull();
    }

    @Test
    @DisplayName("3️⃣ {1, null} → NEED_SOURCE")
    void nullValueBecomesNeedsSource() {
        givenRaw(java.util.Arrays.asList(1L, null));

        RedisBloomFilter.BloomCheckResult result = bloom.checkAndGet(CODE);

        assertThat(result.definitelyAbsent()).isFalse();
        assertThat(result.requiresSource()).isTrue();
        assertThat(result.cachedValue()).isNull();
    }

    @Test
    @DisplayName("4️⃣ {1}（缺第二项）→ NEED_SOURCE，**不能抛 IndexOutOfBounds**")
    void missingSecondElementBecomesNeedsSource() {
        // ⚠️ 原代码直接 r.get(1)，此形态会抛 IndexOutOfBoundsException
        givenRaw(List.of(1L));

        RedisBloomFilter.BloomCheckResult result = bloom.checkAndGet(CODE);

        assertThat(result.requiresSource()).isTrue();
        assertThat(result.cachedValue()).isNull();
        assertThat(blockedCount()).as("布隆放过，不该累加 blocked").isZero();
    }

    @Test
    @DisplayName("5️⃣⭐ {1, ArrayList} → NEED_SOURCE ——**这是本次实测的真实形态**")
    void arrayListValueBecomesNeedsSource() {
        // ⚠️ 真实故障形态：Java 侧拿到 ArrayList，按 String 强转会抛
        //    ClassCastException: class java.util.ArrayList cannot be cast to String
        //    旧代码在这里就已经炸了（连降级兜底都没进）。
        givenRaw(List.of(1L, new java.util.ArrayList<>(List.of("unexpected"))));

        RedisBloomFilter.BloomCheckResult result = bloom.checkAndGet(CODE);

        assertThat(result.definitelyAbsent()).isFalse();
        assertThat(result.requiresSource()).isTrue();
        assertThat(result.cachedValue()).isNull();
    }

    @Test
    @DisplayName("5️⃣b {1, byte[]} → HIT（RedisTemplate 可能返回字节数组）")
    void byteArrayValueBecomesHit() {
        givenRaw(List.of(1L, CACHED.getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        RedisBloomFilter.BloomCheckResult result = bloom.checkAndGet(CODE);

        assertThat(result.cachedValue()).isEqualTo(CACHED);
        assertThat(result.requiresSource()).isFalse();
    }

    @Test
    @DisplayName("6️⃣ {0, false} → DEFINITELY_ABSENT，且 **blocked 计数器 +1**")
    void zeroVerdictBecomesAbsentAndCountsBlocked() {
        givenRaw(List.of(0L, Boolean.FALSE));

        RedisBloomFilter.BloomCheckResult result = bloom.checkAndGet(CODE);

        assertThat(result.definitelyAbsent()).isTrue();
        assertThat(blockedCount()).as("判否必须累加 blocked，否则压测看不到拦截数").isEqualTo(1.0);
    }

    @Test
    @DisplayName("6️⃣b 其它形态**不**累加 blocked（只有 {0,...} 才对）")
    void nonZeroVerdictDoesNotCountBlocked() {
        givenRaw(List.of(1L, CACHED));
        bloom.checkAndGet(CODE);
        givenRaw(List.of(1L, Boolean.FALSE));
        bloom.checkAndGet(CODE);
        givenRaw(java.util.Arrays.asList(1L, null));
        bloom.checkAndGet(CODE);

        assertThat(blockedCount()).as("只有 {0,...} 是真拦截").isZero();
    }

    @Test
    @DisplayName("7️⃣ raw = null → NEED_SOURCE，**不能抛 NPE**")
    void nullRawBecomesNeedsSource() {
        givenRaw(null);

        RedisBloomFilter.BloomCheckResult result = bloom.checkAndGet(CODE);

        assertThat(result.requiresSource()).isTrue();
        assertThat(blockedCount()).isZero();
    }

    @Test
    @DisplayName("8️⃣ raw = 空 List → NEED_SOURCE，**不能抛 IndexOutOfBounds**")
    void emptyRawBecomesNeedsSource() {
        givenRaw(Collections.emptyList());

        RedisBloomFilter.BloomCheckResult result = bloom.checkAndGet(CODE);

        assertThat(result.requiresSource()).isTrue();
        assertThat(blockedCount()).isZero();
    }

    @Test
    @DisplayName("9️⃣ raw = {非数字} → NEED_SOURCE（flag 类型异常也降级）")
    void nonNumericFlagBecomesNeedsSource() {
        givenRaw(List.of("not-a-number", Boolean.FALSE));

        RedisBloomFilter.BloomCheckResult result = bloom.checkAndGet(CODE);

        assertThat(result.requiresSource()).isTrue();
        assertThat(blockedCount()).isZero();
    }

    @Test
    @DisplayName("10️⃣ execute 抛 CCE → **降级为需回源，不抛**（DR-054 缺陷 1+3）")
    void classCastExceptionDegradesInsteadOfPropagating() {
        // ⚠️ 真实故障：参数序列化的 CCE。
        // catch (DataAccessException) 抓不住它 -> 异常穿透到 Controller -> 跳转全 500。
        given(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .willThrow(new ClassCastException(
                        "class java.util.ArrayList cannot be cast to class java.lang.String"));

        RedisBloomFilter.BloomCheckResult result = bloom.checkAndGet(CODE);

        // 布隆是**保护措施**，它自己出错不该让跳转挂掉
        assertThat(result.requiresSource()).isTrue();
        assertThat(blockedCount()).isZero();
    }

    @Test
    @DisplayName("11️⃣⭐ 参数必须是**7 个 String**（不是 List，不是 8 个含占位符）")
    void parametersAreSevenStringsWithoutPlaceholder() {
        givenRaw(List.of(1L, Boolean.FALSE));

        bloom.checkAndGet(CODE);

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        ArgumentCaptor<List> keys = ArgumentCaptor.forClass(List.class);
        org.mockito.Mockito.verify(redis)
                .execute(any(RedisScript.class), keys.capture(), args.capture());

        // ⚠️ 核心契约：
        // 1) 数量必须是 k=7（占位符 bug 会变成 8，或 i=2 时只用 6）
        assertThat(args.getValue()).hasSize(BloomHash.HASH_COUNT);
        // 2) 每个元素必须是 String —— StringRedisSerializer 会对非 String 强转抛 CCE
        for (Object a : args.getValue()) {
            assertThat(a)
                    .as("execute 的每个 arg 必须是 String，否则 StringRedisSerializer 抛 CCE")
                    .isInstanceOf(String.class);
        }
        // 3) KEYS 是 2 个（bloom key + cache key）
        assertThat(keys.getValue())
                .containsExactly(BloomHash.PREFIX, CacheKeys.code(CODE));
    }

    /**
     * 日志捕获（Spring Boot 自带，零新依赖）。
     *
     * <p>⚠️ 只断言**关键字**，不匹配完整堆栈——堆栈格式跨 JDK 版本会变，
     * 匹配堆栈会让测试在升级 JDK 时莫名失败，而那时要改的是测试不是代码。
     */
    // ------------------------------------------------------ 日志断言（降级必须有日志）

    @Test
    @DisplayName("L1️⃣ {1, ArrayList} → WARN 含「形态异常」+ 短码")
    void abnormalShapeLogsWarning(CapturedOutput out) {
        givenRaw(List.of(1L, new java.util.ArrayList<>(List.of("unexpected"))));

        bloom.checkAndGet(CODE);

        assertThat(out.getOut())
                .as("未知形态必须留日志——静默降级会让人误以为布在工作")
                .contains("形态异常")
                .contains(CODE);
    }

    @Test
    @DisplayName("L2️⃣ raw=null → WARN 含「形态异常」")
    void nullRawLogsWarning(CapturedOutput out) {
        givenRaw(null);

        bloom.checkAndGet(CODE);

        assertThat(out.getOut()).contains("形态异常").contains(CODE);
    }

    @Test
    @DisplayName("L3️⃣ raw=空 List → WARN 含「形态异常」")
    void emptyRawLogsWarning(CapturedOutput out) {
        givenRaw(Collections.emptyList());

        bloom.checkAndGet(CODE);

        assertThat(out.getOut()).contains("形态异常").contains(CODE);
    }

    @Test
    @DisplayName("L4️⃣ execute 抛 CCE → WARN 含「降级为回源」")
    void cceLogsDegradation(CapturedOutput out) {
        given(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .willThrow(new ClassCastException(
                        "class java.util.ArrayList cannot be cast to class java.lang.String"));

        bloom.checkAndGet(CODE);

        assertThat(out.getOut())
                .as("Redis 侧异常必须留日志，否则限流/缓存降级全是静默的")
                .contains("降级为回源")
                .contains(CODE);
    }
}
