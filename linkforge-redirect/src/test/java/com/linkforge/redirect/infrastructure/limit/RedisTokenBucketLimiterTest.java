package com.linkforge.redirect.infrastructure.limit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * 令牌桶限流测试（docs/ARCHITECTURE.md §11，DR-002）。
 *
 * <p>方案场景 9~12：突发阈值、降级放行、时间源、scope 分 key。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RedisTokenBucketLimiterTest {

    private static final String IP = "203.0.113.7";

    /*
     * ⚠️ 踩坑记录（mock RedisTemplate.execute）：
     * 第三个参数是变参 Object...，匹配时必须用 Object 数组的 any()。
     * 用 String 数组的 any() 类型不匹配，桩**静默不生效**，
     * 表现为「Cannot invoke List.get(int) because r is null」——
     * 错误信息完全指不到真正的原因。
     * 传单个 List 参数时（布隆的 checkAndGet）Java 会包成 Object 数组，同样能匹配，
     * 所以两处统一用 Object 数组的 any()，不要按参数个数区分写法。
     */



    @Mock
    private StringRedisTemplate redis;

    private SimpleMeterRegistry registry;
    private RedisTokenBucketLimiter limiter;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        limiter = new RedisTokenBucketLimiter(redis, new RateLimitScope(20, 10, 60, 30), registry);
    }

    private void givenScriptResult(long allowed, long retryAfterMs) {
        given(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .willReturn(List.of(allowed, retryAfterMs));
    }

    @Nested
    @DisplayName("正常限流")
    class Normal {

        @Test
        @DisplayName("⭐ 场景 9：allow=1 → 放行，retryAfter 为零")
        void allowsWithinQuota() {
            givenScriptResult(1L, 0L);

            RedisTokenBucketLimiter.Decision decision =
                    limiter.tryAcquire(IP, RateLimitScope.REDIRECT);

            assertThat(decision.allowed()).isTrue();
            assertThat(decision.retryAfter()).isEqualTo(Duration.ZERO);
        }

        @Test
        @DisplayName("⭐ 场景 9：allow=0 → 拒绝，retryAfter 至少 1ms（防 0 死循环）")
        void rejectsOverQuota() {
            givenScriptResult(0L, 0L);

            RedisTokenBucketLimiter.Decision decision =
                    limiter.tryAcquire(IP, RateLimitScope.REDIRECT);

            assertThat(decision.allowed()).isFalse();
            // ⚠️ 脚本算出 0ms 时必须兜底成 1ms：客户端拿到 0 会立即重试形成热循环
            assertThat(decision.retryAfter()).isGreaterThanOrEqualTo(Duration.ofMillis(1));
        }

        @Test
        @DisplayName("⭐ 场景 9：拒绝时累加 rejected 指标")
        void rejectionIncrementsCounter() {
            givenScriptResult(0L, 50L);

            limiter.tryAcquire(IP, RateLimitScope.REDIRECT);

            assertThat(registry.get("shortlink.ratelimit.rejected").counter().count())
                    .isEqualTo(1.0);
        }
    }

    @Nested
    @DisplayName("降级：Redis 不可用时放行")
    class Degradation {

        @Test
        @DisplayName("⭐⭐ 场景 11：Redis 异常 → **放行**，不返回 429")
        void redisFailureAllowsThrough() {
            given(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                    .willThrow(new RedisConnectionFailureException("down"));

            RedisTokenBucketLimiter.Decision decision =
                    limiter.tryAcquire(IP, RateLimitScope.REDIRECT);

            // ⚠️ 限流是保护措施，它自己变成故障源是本末倒置
            assertThat(decision.allowed()).isTrue();
        }

        @Test
        @DisplayName("⭐ 降级时累加 degraded 指标（持续增长说明正暴露在无限流状态）")
        void degradationIncrementsCounter() {
            given(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                    .willThrow(new QueryTimeoutException("timeout"));

            limiter.tryAcquire(IP, RateLimitScope.REDIRECT);

            assertThat(registry.get("shortlink.ratelimit.degraded").counter().count())
                    .isEqualTo(1.0);
            // ⚠️ rejected 计数器在**构造时**就register 了（Counter.builder().register），
            // 所以降级分支里它依然存在，只是 count 为 0。
            // 断言「count 为 0」而不是「指标不存在」——后者会失败，
            // 而且它测的是注册行为，不是降级语义。
            assertThat(registry.get("shortlink.ratelimit.rejected").counter().count())
                    .isEqualTo(0.0);
        }
    }

    @Nested
    @DisplayName("脚本参数（场景 12：时间源来自 Redis TIME）")
    class ScriptArgs {

        @Test
        @DisplayName("⭐⭐ 场景 12：传给脚本的**没有客户端时间戳**")
        void noClientTimestampIsPassed() {
            givenScriptResult(1L, 0L);

            limiter.tryAcquire(IP, RateLimitScope.REDIRECT);

            ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
            verify(redis).execute(any(RedisScript.class), anyList(), args.capture());

            // ⚠️ 3 个参数：容量、每毫秒补充率、PEXPIRE 毫秒数。
            // **绝对不能出现客户端当前时间**——多实例时钟偏差会让限流失效。
            assertThat(args.getValue()).hasSize(3);
            assertThat(args.getValue()[0]).isEqualTo("20");        // capacity
            assertThat(args.getValue()[1]).isEqualTo("0.02");      // refill/ms = 20/1000
            assertThat(args.getValue()[2]).isEqualTo("2000");      // PEXPIRE = 窗口×2
        }

        @Test
        @DisplayName("⭐ 浮点补充率：整数除法会让令牌永不补充（场景 9 的陷阱）")
        void refillRateIsFloatingPoint() {
            // 20 / 1000 = 0.02。若用整数除法得到 0，桶空后永久拒绝
            RateLimitScope scopes = new RateLimitScope(20, 10, 60, 30);
            assertThat(scopes.refillPerMillisecond(RateLimitScope.REDIRECT)).isEqualTo(0.02);
            assertThat(scopes.refillPerMillisecond(RateLimitScope.CREATE))
                    .isGreaterThan(0.0)
                    .isLessThan(0.001);
        }
    }

    @Nested
    @DisplayName("Key 设计（场景：scope 分 key）")
    class KeyDesign {

        private final RateLimitScope scopes = new RateLimitScope(20, 10, 60, 30);

        @Test
        @DisplayName("⭐⭐ scope 决定 key：redirect 与 create 是**不同 key**")
        void scopesUseDifferentKeys() {
            String redirectKey = scopes.key(IP, RateLimitScope.REDIRECT);
            String createKey = scopes.key(IP, RateLimitScope.CREATE);

            // ⚠️ 共用 key 会让创建配额被跳转流量吃光（量级差 120 倍）
            assertThat(redirectKey).isNotEqualTo(createKey);
            assertThat(redirectKey).isEqualTo("shortlink:limit:" + IP + ":redirect");
            assertThat(createKey).isEqualTo("shortlink:limit:" + IP + ":create");
        }

        @Test
        @DisplayName("⭐ IPv6 的冒号被替换（否则与 key 分隔符冲突）")
        void ipv6ColonIsSanitized() {
            assertThat(scopes.key("::1", RateLimitScope.REDIRECT))
                    .isEqualTo("shortlink:limit:__1:redirect");
        }

        @Test
        @DisplayName("⭐ 完整 scope 集合已定义（第 4 周的 query/admin 现在就有位置）")
        void allFourScopesDefined() {
            assertThat(RateLimitScope.allScopes()).containsExactly(
                    "redirect", "create", "query", "admin");
        }

        @Test
        @DisplayName("⭐ 阈值与窗口与文档一致（20/s、10/min、60/s、30/min）")
        void thresholdsMatchDocs() {
            assertThat(scopes.rate(RateLimitScope.REDIRECT)).isEqualTo(20);
            assertThat(scopes.window(RateLimitScope.REDIRECT)).isEqualTo(Duration.ofSeconds(1));
            assertThat(scopes.rate(RateLimitScope.CREATE)).isEqualTo(10);
            assertThat(scopes.window(RateLimitScope.CREATE)).isEqualTo(Duration.ofMinutes(1));
            assertThat(scopes.rate(RateLimitScope.QUERY)).isEqualTo(60);
            assertThat(scopes.rate(RateLimitScope.ADMIN)).isEqualTo(30);
        }

        @Test
        @DisplayName("⭐⭐ 未知 scope 直接抛异常——**限流不能静默失效**")
        void unknownScopeThrowsInsteadOfSilentlyPassing() {
            // ⚠️ 若这里返回默认 20 而不是抛异常，拼错的 scope 会让限流
            // 以错误的阈值运行，且没有任何错误信号（静默故障）
            assertThatThrownBy(() -> scopes.rate("Redirect"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Redirect");
        }

        @Test
        @DisplayName("⭐ 阈值可配置：构造传入的值覆盖默认（压测临时放宽用）")
        void thresholdsAreConfigurable() {
            RateLimitScope relaxed = new RateLimitScope(100_000, 100_000, 60, 30);

            assertThat(relaxed.rate(RateLimitScope.REDIRECT)).isEqualTo(100_000);
            assertThat(relaxed.rate(RateLimitScope.CREATE)).isEqualTo(100_000);
            assertThat(relaxed.rate(RateLimitScope.QUERY)).isEqualTo(60);
        }

        @Test
        @DisplayName("⭐ 脚本收到的 key 就是 scopes.key() 的结果")
        void scriptReceivesScopedKey() {
            givenScriptResult(1L, 0L);

            limiter.tryAcquire(IP, RateLimitScope.CREATE);

            ArgumentCaptor<List> keys = ArgumentCaptor.forClass(List.class);
            verify(redis).execute(any(RedisScript.class), keys.capture(), any(Object[].class));
            assertThat(keys.getValue()).containsExactly(scopes.key(IP, RateLimitScope.CREATE));
        }
    }
}
