package com.linkforge.redirect.infrastructure.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.linkforge.core.infrastructure.cache.CacheEntry;
import com.linkforge.core.infrastructure.cache.CacheKeys;
import com.linkforge.core.infrastructure.cache.CachedLink;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
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
import org.springframework.data.redis.core.ValueOperations;

/**
 * Redis 跳转缓存测试（docs/ARCHITECTURE.md §206-207）。
 *
 * <p>方案 5.9 的场景 1~7。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RedisRedirectCacheTest {

    private static final String CODE = "0Q1A2b3";
    private static final String URL = "https://example.com/some/path?a=1&b=2#frag";

    @Mock
    private StringRedisTemplate redis;

    @Mock
    private ValueOperations<String, String> valueOps;

    private MeterRegistry registry;
    private RedisRedirectCache cache;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        given(redis.opsForValue()).willReturn(valueOps);
        cache = new RedisRedirectCache(redis, registry);
    }

    private CachedLink link(LocalDateTime expireAt) {
        return new CachedLink(URL, "ACTIVE", expireAt);
    }

    private void givenStored(String raw) {
        given(valueOps.get(CacheKeys.code(CODE))).willReturn(raw);
    }

    private double counter(String name) {
        return registry.get(name).counter().count();
    }

    @Nested
    @DisplayName("场景 1：未命中返回 Optional.empty()，**不是** NegativeHit")
    class Miss {

        @Test
        @DisplayName("⭐ Redis 无此 key → empty")
        void missingKeyIsEmpty() {
            givenStored(null);

            Optional<CacheEntry> result = cache.get(CODE);

            // ⚠️ 这条断言是整个防穿透机制的基石：混淆 empty 与 NegativeHit
            // 会让「未命中」被当成「已确认不存在」，正确性彻底反了
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("⭐ 空值命中 → NegativeHit（不是 Hit）")
        void sentinelIsNegativeHit() {
            givenStored(CacheKeys.EMPTY_SENTINEL);

            Optional<CacheEntry> result = cache.get(CODE);

            assertThat(result).isPresent();
            assertThat(result.get()).isInstanceOf(CacheEntry.NegativeHit.class);
        }
    }

    @Nested
    @DisplayName("场景 2：命中正常值，字段完整还原")
    class Hit {

        @Test
        @DisplayName("⭐ 含 expireAt 的往返：字段逐一相等")
        void roundTripsWithExpireAt() {
            LocalDateTime expire = LocalDateTime.parse("2026-12-31T23:59:59");
            givenStored(writeByTest(link(expire)));

            Optional<CacheEntry> result = cache.get(CODE);

            assertThat(result).isPresent();
            CacheEntry.Hit hit = (CacheEntry.Hit) result.get();
            assertThat(hit.link().targetUrl()).isEqualTo(URL);
            assertThat(hit.link().status()).isEqualTo("ACTIVE");
            assertThat(hit.link().expireAt()).isEqualTo(expire);
        }

        @Test
        @DisplayName("⭐ expireAt 为 null 的往返（永久有效）")
        void roundTripsWithNullExpireAt() {
            givenStored(writeByTest(link(null)));

            CacheEntry.Hit hit = (CacheEntry.Hit) cache.get(CODE).orElseThrow();

            assertThat(hit.link().expireAt()).isNull();
        }

        @Test
        @DisplayName("⭐ targetUrl 含 URL 保留字符（? & #）不破坏解析")
        void urlWithQueryAndFragmentSurvives() {
            givenStored(writeByTest(link(null)));

            CacheEntry.Hit hit = (CacheEntry.Hit) cache.get(CODE).orElseThrow();

            assertThat(hit.link().targetUrl()).isEqualTo("https://example.com/some/path?a=1&b=2#frag");
        }

        @Test
        @DisplayName("⭐ targetUrl 含换行也不破坏解析（长度前缀的用途）")
        void urlWithNewlineSurvives() {
            String tricky = "https://example.com/a\nb|c|d";
            givenStored(writeByTest(new CachedLink(tricky, "ACTIVE", null)));

            CacheEntry.Hit hit = (CacheEntry.Hit) cache.get(CODE).orElseThrow();

            // 分隔符方案在这里会丢字符或错位，长度前缀方案才正确
            assertThat(hit.link().targetUrl()).isEqualTo(tricky);
        }

        @Test
        @DisplayName("损坏数据（段数不对）→ 当未命中并删除，不返回错误数据")
        void corruptedValueTreatedAsMiss() {
            givenStored("v1|ACTIVE");

            assertThat(cache.get(CODE)).isEmpty();
            verify(redis).delete(CacheKeys.code(CODE));
        }

        @Test
        @DisplayName("版本号不匹配 → 当未命中（发布期间格式变化时不猜着解析）")
        void versionMismatchTreatedAsMiss() {
            givenStored("v99|ACTIVE||" + URL.length() + "|" + URL);

            assertThat(cache.get(CODE)).isEmpty();
        }

        @Test
        @DisplayName("长度前缀与实际不符（截断）→ 当未命中")
        void lengthMismatchTreatedAsMiss() {
            givenStored("v1|ACTIVE||999|" + URL);

            assertThat(cache.get(CODE)).isEmpty();
        }
    }

    @Nested
    @DisplayName("场景 3：TTL 抖动")
    class Ttl {

        @Test
        @DisplayName("⭐ put 的 TTL 落在 [1h, 1h+5min) —— 抖动上界必须存在")
        void putTtlHasJitterUpperBound() {
            Duration observedMax = Duration.ZERO;
            for (int i = 0; i < 200; i++) {
                ArgumentCaptor<Duration> captor = ArgumentCaptor.forClass(Duration.class);
                cache.put(CODE, link(null));
                verify(valueOps, org.mockito.Mockito.atLeastOnce())
                        .set(eq(CacheKeys.code(CODE)), anyString(), captor.capture());
                observedMax = observedMax.compareTo(captor.getValue()) > 0
                        ? observedMax : captor.getValue();
            }

            // 上界必须严格大于 TTL（否则说明抖动被去掉了）
            assertThat(observedMax).isGreaterThan(RedisRedirectCache.TTL);
            // 且不超过 TTL + 5 分钟
            assertThat(observedMax).isLessThanOrEqualTo(
                    RedisRedirectCache.TTL.plus(RedisRedirectCache.JITTER_MAX));
        }

        @Test
        @DisplayName("⭐ putEmpty 的 TTL 恰好 30 秒，不加抖动")
        void emptyTtlIsFixed() {
            cache.putEmpty(CODE);

            ArgumentCaptor<Duration> captor = ArgumentCaptor.forClass(Duration.class);
            verify(valueOps).set(eq(CacheKeys.code(CODE)), eq(CacheKeys.EMPTY_SENTINEL), captor.capture());
            assertThat(captor.getValue()).isEqualTo(Duration.ofSeconds(30));
        }

        @Test
        @DisplayName("⭐ put 写的是 shortlink:code:{shortCode}（文档定义的 key）")
        void usesDocumentedKey() {
            cache.put(CODE, link(null));

            verify(valueOps).set(eq("shortlink:code:" + CODE), anyString(), any(Duration.class));
        }
    }

    @Nested
    @DisplayName("场景 4：指标")
    class Metrics {

        @Test
        @DisplayName("⭐ 正常值命中累加 redis.hit，不累加 miss")
        void hitCounterIncrements() {
            givenStored(writeByTest(link(null)));

            cache.get(CODE);

            assertThat(counter("shortlink.cache.redis.hit")).isEqualTo(1.0);
            assertThat(counter("shortlink.cache.miss")).isEqualTo(0.0);
        }

        @Test
        @DisplayName("⭐ 空值命中也算 hit（它确实避免了回源）")
        void negativeHitCountsAsHit() {
            givenStored(CacheKeys.EMPTY_SENTINEL);

            cache.get(CODE);

            assertThat(counter("shortlink.cache.redis.hit")).isEqualTo(1.0);
            assertThat(counter("shortlink.cache.miss")).isEqualTo(0.0);
        }

        @Test
        @DisplayName("⭐ 未命中累加 miss")
        void missCounterIncrements() {
            givenStored(null);

            cache.get(CODE);

            assertThat(counter("shortlink.cache.miss")).isEqualTo(1.0);
        }
    }

    @Nested
    @DisplayName("场景 5：Redis 故障降级为未命中，绝不抛出")
    class RedisDown {

        @Test
        @DisplayName("⭐ 读失败 → 返回 empty（调用方回源），不是抛异常")
        void readFailureDegradesToMiss() {
            given(valueOps.get(anyString()))
                    .willThrow(new RedisConnectionFailureException("down"));

            assertThat(cache.get(CODE)).isEmpty();
        }

        @Test
        @DisplayName("⭐ 读超时 → 返回 empty")
        void readTimeoutDegradesToMiss() {
            given(valueOps.get(anyString())).willThrow(new QueryTimeoutException("timeout"));

            assertThat(cache.get(CODE)).isEmpty();
        }

        @Test
        @DisplayName("写失败 → 静默忽略，不影响本次请求")
        void writeFailureIgnored() {
            // ValueOperations.set 返回 void，不能用 given(...).willThrow，
            // 必须用 doThrow（void 方法没有返回值可打桩）
            org.mockito.Mockito.doThrow(new RedisConnectionFailureException("down"))
                    .when(valueOps).set(anyString(), anyString(), any(Duration.class));

            // 不抛即为通过：回源已成功，缓存写失败不应把请求变成 500
            cache.put(CODE, link(null));
        }

        @Test
        @DisplayName("空值写入失败 → 静默忽略")
        void emptyWriteFailureIgnored() {
            org.mockito.Mockito.doThrow(new RedisConnectionFailureException("down"))
                    .when(valueOps).set(anyString(), anyString(), any(Duration.class));

            cache.putEmpty(CODE);
        }

        @Test
        @DisplayName("删除失败 → 静默忽略")
        void deleteFailureIgnored() {
            org.mockito.Mockito.doThrow(new RedisConnectionFailureException("down"))
                    .when(redis).delete(anyString());

            cache.evict(CODE);
        }
    }

    @Nested
    @DisplayName("场景 6：空值哨兵的不可达性")
    class Sentinel {

        @Test
        @DisplayName("⭐ EMPTY_SENTINEL 不可能等于任何合法短码的缓存值")
        void sentinelNotAValidValue() {
            // 哨兵含下划线，而短码是 7 位 Base62（0-9A-Za-z）→ 永不冲突
            assertThat(CacheKeys.EMPTY_SENTINEL).contains("_");
        }

        @Test
        @DisplayName("⭐ evict 删除的是文档定义的 key")
        void evictUsesDocumentedKey() {
            cache.evict(CODE);

            verify(redis).delete("shortlink:code:" + CODE);
            verify(valueOps, never()).set(anyString(), anyString(), any(Duration.class));
        }
    }

    /** 用被测代码之外的独立实现生成合法值，避免「用被测逻辑验证被测逻辑」。 */
    private String writeByTest(CachedLink l) {
        return "v1|" + l.status() + "|" + (l.expireAt() == null ? "" : l.expireAt())
                + "|" + l.targetUrl().length() + "|" + l.targetUrl();
    }
}