package com.linkforge.redirect.infrastructure.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.linkforge.core.infrastructure.cache.CacheEntry;
import com.linkforge.core.infrastructure.cache.CacheKeys;
import com.linkforge.core.infrastructure.cache.CachedLink;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * 布隆 + L2 合并查询的**往返次数契约**（DR-052）。
 *
 * <h2>⚠️ 这 5 条锁的不是「功能对不对」，而是「往返几次」</h2>
 * 初版 {@code mightContain} 走「EXISTS + 7 次 GETBIT」= 8 次独立往返，
 * 功能完全正确（单测全绿），但 8 万 QPS 下等于每秒 64 万次 Redis 往返，
 * Redis 直接成为瓶颈。**功能测试无法发现这类缺陷，只有「往返次数」断言能。**
 *
 * <p>这与 DR-051 的 {@code localHitSkipsRedisCompletely} 是同一类测试：
 * 断言被优化的资源本身没被访问，而不是断言指标。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BloomMergedRoundTripTest {

    private static final String CODE = "0Q1A2b3";
    /** L2 里存的完整序列化值（DR-045 的长度前缀格式）。 */
    private static final String CACHED_VALUE =
            "v1|ACTIVE||34|https://example.com/very/long/path";

    @Mock
    private RedisRedirectCache redis;

    @Mock
    private RedisBloomFilter bloom;

    private Cache<String, CacheEntry.Hit> local;
    private SimpleMeterRegistry registry;
    private TwoLevelRedirectCache cache;

    @BeforeEach
    void setUp() {
        local = Caffeine.newBuilder()
                .maximumSize(CaffeineConfig.LOCAL_MAX_SIZE)
                .expireAfterWrite(CaffeineConfig.LOCAL_TTL)
                .recordStats()
                .build();
        registry = new SimpleMeterRegistry();
        cache = new TwoLevelRedirectCache(local, redis, bloom, registry);
    }

    private static CachedLink link() {
        return new CachedLink("https://example.com/very/long/path", "ACTIVE",
                LocalDateTime.parse("2027-01-01T00:00:00"));
    }

    private void givenBloomAbsent() {
        given(bloom.checkAndGet(CODE))
                .willReturn(RedisBloomFilter.BloomCheckResult.absent());
    }

    private void givenBloomNeedsSource() {
        given(bloom.checkAndGet(CODE))
                .willReturn(RedisBloomFilter.BloomCheckResult.source());
    }

    private void givenBloomCached(String raw) {
        given(bloom.checkAndGet(CODE))
                .willReturn(RedisBloomFilter.BloomCheckResult.cached(raw));
    }

    @Test
    @DisplayName("⭐⭐⭐ 1. L1 命中 → **Redis 零调用**（布隆也不问）")
    void localHitCausesZeroRedisCalls() {
        local.put(CODE, new CacheEntry.Hit(link()));

        TwoLevelRedirectCache.BloomAwareResult result =
                cache.getOrLoadWithBloom(CODE, c -> new CacheEntry.Hit(link()));

        assertThat(result.hit()).isNotNull();
        assertThat(result.definitelyAbsent()).isFalse();
        // ⚠️ 核心契约：L1 命中时布隆和 L2 都不该被问
        verifyNoInteractions(bloom, redis);
    }

    @Test
    @DisplayName("⭐⭐⭐ 2. L1 miss → **恰好 1 次** checkAndGet，无额外 Redis 命令")
    void localMissCausesExactlyOneBloomCall() {
        givenBloomNeedsSource();

        cache.getOrLoadWithBloom(CODE, c -> new CacheEntry.Hit(link()));

        // ⚠️ 只允许 1 次：初版是 EXISTS + 7×GETBIT = 8 次独立往返
        verify(bloom, org.mockito.Mockito.times(1)).checkAndGet(CODE);
        // 不应该再有独立的 hasKey / opsForValue 调用
        verifyNoMoreInteractions(bloom);
    }

    @Test
    @DisplayName("⭐⭐⭐ 2b. 门面本身不产生额外 Redis 命令（无 hasKey / getBit 残留）")
    void facadeIssuesNoDirectRedisCommands() {
        // mock 的 RedisRedirectCache 上除了 putToRedisOnly 之外不应有任何调用
        givenBloomNeedsSource();

        cache.getOrLoadWithBloom(CODE, c -> new CacheEntry.Hit(link()));

        verify(redis, never()).evict(anyString());
        verify(redis, never()).putEmpty(anyString());
    }

    @Test
    @DisplayName("⭐⭐ 3. 布隆判不存在 → 1 次 Redis 后返回 absent，**不回源**")
    void bloomAbsentShortCircuitsWithoutLoading() {
        givenBloomAbsent();
        AtomicInteger loaderCalls = new AtomicInteger();

        TwoLevelRedirectCache.BloomAwareResult result = cache.getOrLoadWithBloom(CODE, c -> {
            loaderCalls.incrementAndGet();
            return new CacheEntry.Hit(link());
        });

        assertThat(result.definitelyAbsent()).isTrue();
        assertThat(result.hit()).isNull();
        // ⚠️ 布隆判否后绝不能再查库——那正是防穿透要避免的
        assertThat(loaderCalls.get()).isZero();
        verify(redis, never()).putEmpty(anyString());
    }

    @Test
    @DisplayName("⭐⭐ 4. L2 命中 → 写 L1，**后续请求 0 次 Redis**")
    void l2HitBackfillsLocalSoNextCallIsFree() {
        givenBloomCached(CACHED_VALUE);
        given(redis.parseCached(CODE, CACHED_VALUE)).willReturn(link());

        cache.getOrLoadWithBloom(CODE, c -> new CacheEntry.Hit(link()));
        assertThat(local.getIfPresent(CODE)).as("L2 命中应下沉 L1").isNotNull();

        // ⚠️ 核心契约：**第二次**请求 0 次 Redis。
        // 第一次有 redis.parseCached 调用是合法的（那是 Lua 返回值后的解析），
        // 所以用 clearInvocations 把它清掉，只观察第二次的交互。
        org.mockito.Mockito.clearInvocations(bloom, redis);

        cache.getOrLoadWithBloom(CODE, c -> new CacheEntry.Hit(link()));

        verifyNoInteractions(bloom, redis);
    }

    @Test
    @DisplayName("⭐⭐ 5. 需回源 → 单飞 loader 执行 1 次，写 L2 + 写 L1")
    void needsSourceLoadsOnceAndBackfillsBothLevels() {
        givenBloomNeedsSource();
        AtomicInteger loaderCalls = new AtomicInteger();

        TwoLevelRedirectCache.BloomAwareResult result = cache.getOrLoadWithBloom(CODE, c -> {
            loaderCalls.incrementAndGet();
            return new CacheEntry.Hit(link());
        });

        assertThat(result.hit()).isNotNull();
        assertThat(loaderCalls.get()).isEqualTo(1);
        // L1 由 local.get(key, loader) 自动写入
        assertThat(local.getIfPresent(CODE)).as("回源后应写 L1").isNotNull();
    }

    @Test
    @DisplayName("⭐ 空值哨兵 → 确认不存在，**不写 L1**（DR-047）")
    void emptySentinelDoesNotBackfillLocal() {
        givenBloomCached(CacheKeys.EMPTY_SENTINEL);

        TwoLevelRedirectCache.BloomAwareResult result =
                cache.getOrLoadWithBloom(CODE, c -> new CacheEntry.Hit(link()));

        assertThat(result.definitelyAbsent()).isFalse();
        assertThat(result.hit()).isNull();
        // ⚠️ 空值下沉 L1 会让刚创建的短码持续 404 达 5 分钟（DR-047）
        assertThat(local.getIfPresent(CODE)).isNull();
    }

    @Test
    @DisplayName("⭐ 缓存值损坏 → 当未命中回源，不把损坏值写进 L1")
    void corruptedCacheValueFallsBackToSource() {
        givenBloomCached("garbage-without-expected-shape");
        given(redis.parseCached(CODE, "garbage-without-expected-shape")).willReturn(null);
        AtomicInteger loaderCalls = new AtomicInteger();

        TwoLevelRedirectCache.BloomAwareResult result = cache.getOrLoadWithBloom(CODE, c -> {
            loaderCalls.incrementAndGet();
            return new CacheEntry.Hit(link());
        });

        assertThat(loaderCalls.get()).isEqualTo(1);
        assertThat(result.hit()).isNotNull();
    }

    @Test
    @DisplayName("⭐ 回源确认不存在 → 写 L2 空值，不写 L1")
    void sourceReturnsNullWritesOnlyRedisEmpty() {
        givenBloomNeedsSource();

        TwoLevelRedirectCache.BloomAwareResult result =
                cache.getOrLoadWithBloom(CODE, c -> null);

        assertThat(result.hit()).isNull();
        assertThat(result.definitelyAbsent()).isFalse();
        assertThat(local.getIfPresent(CODE)).isNull();
        verify(redis).putEmpty(CODE);
    }

    @Test
    @DisplayName("⭐ 布隆 L1 命中时不被调用——L1 数据已由查库确认过")
    void bloomIsSkippedOnLocalHitBecauseDataIsAlreadyVerified() {
        local.put(CODE, new CacheEntry.Hit(link()));

        cache.getOrLoadWithBloom(CODE, c -> new CacheEntry.Hit(link()));

        // ⚠️ 布隆对 L1 里的值判「不存在」的唯一可能是假阴性（重建漏扫），
        // 而此时我们已有一份**确定正确**的数据，不需要再问布隆。
        // 反过来若先问布隆，L1 命中也会多一次往返，DR-051 的修复就白做了。
        verifyNoInteractions(bloom);
        assertThat(registry.get("shortlink.cache.local.hit").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("⭐⭐ checkAndGet 的返回值结构：{0}/{1,null}/{1,value} 三种都要正确解析")
    @SuppressWarnings("unchecked")
    void checkAndGetReturnStructureIsFullyParsed() {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        RedisBloomFilter real = new RedisBloomFilter(template, registry);

        // {0, false} —— 布隆判否
        given(template.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .willReturn(List.of(0L, Boolean.FALSE));
        assertThat(real.checkAndGet(CODE).definitelyAbsent()).isTrue();

        // {1, false} —— 需回源
        given(template.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .willReturn(List.of(1L, Boolean.FALSE));
        assertThat(real.checkAndGet(CODE).requiresSource()).isTrue();

        // {1, value} —— L2 命中
        given(template.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .willReturn(List.of(1L, CACHED_VALUE));
        assertThat(real.checkAndGet(CODE).cachedValue()).isEqualTo(CACHED_VALUE);

        // 脚本每次只传 KEYS[1]=bloom、KEYS[2]=cachekey，ARGV[1] 是占位
        ArgumentCaptor<List> keys = ArgumentCaptor.forClass(List.class);
        verify(template, org.mockito.Mockito.atLeastOnce())
                .execute(any(RedisScript.class), keys.capture(), any(Object[].class));
        assertThat(keys.getValue()).containsExactly("shortlink:bloom", CacheKeys.code(CODE));
    }
}
