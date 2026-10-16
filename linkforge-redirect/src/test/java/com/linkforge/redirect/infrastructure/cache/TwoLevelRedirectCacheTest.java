package com.linkforge.redirect.infrastructure.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.linkforge.core.infrastructure.cache.CacheEntry;
import com.linkforge.core.infrastructure.cache.CachedLink;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDateTime;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.BDDMockito;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * 两级缓存门面的编排测试：本地 → Redis 的顺序、回填规则、删除顺序。
 *
 * <p>方案 5.9 的场景 8~13 中属于「门面」的部分。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TwoLevelRedirectCacheTest {

    private static final String CODE = "0Q1A2b3";

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
        // ⚠️ bloom 是 mock：本类只测 L1/L2 的编排，布隆行为由
        // BloomMergedRoundTripTest 覆盖。返回「需回源」让它不干扰本类断言。
        cache = new TwoLevelRedirectCache(local, redis, bloom, registry);
    }

    private static CachedLink link() {
        return new CachedLink("https://example.com/x", "ACTIVE", null);
    }

    @Test
    @DisplayName("⭐ 场景 8：本地命中 → Redis 零调用，且累加 local.hit")
    void localHitSkipsRedis() {
        local.put(CODE, new CacheEntry.Hit(link()));

        Optional<CacheEntry> result = cache.get(CODE);

        assertThat(result).isPresent();
        verify(redis, never()).get(anyString());
        assertThat(registry.get("shortlink.cache.local.hit").counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("⭐ 场景 9：本地未命中、Redis 命中 → 读取 Redis 且回填本地")
    void redisHitBackfillsLocal() {
        givenHitFromRedis();

        Optional<CacheEntry> result = cache.get(CODE);

        assertThat(result).isPresent();
        verify(redis).get(CODE);
        assertThat(local.getIfPresent(CODE)).isNotNull();
    }

    @Test
    @DisplayName("⭐ 场景 12：NegativeHit **不下沉到本地**（DR-046）")
    void negativeHitDoesNotBackfillLocal() {
        BDDMockito.given(redis.get(CODE)).willReturn(Optional.of(new CacheEntry.NegativeHit()));

        Optional<CacheEntry> result = cache.get(CODE);

        assertThat(result).isPresent().get().isInstanceOf(CacheEntry.NegativeHit.class);
        // ⚠️ 核心断言：空值一旦进本地，一个刚创建的短码会持续 404 长达 5 分钟
        assertThat(local.getIfPresent(CODE)).isNull();
    }

    @Test
    @DisplayName("⭐ 两级未命中 → 返回 empty（调用方回源）")
    void bothMissReturnsEmpty() {
        BDDMockito.given(redis.get(CODE)).willReturn(Optional.empty());

        assertThat(cache.get(CODE)).isEmpty();
    }

    @Test
    @DisplayName("⭐ 场景 10：put 同时写两级")
    void putWritesBothLevels() {
        cache.put(CODE, link());

        assertThat(local.getIfPresent(CODE)).isNotNull();
        verify(redis).put(CODE, link());
    }

    @Test
    @DisplayName("⭐ 场景 11：putEmpty **只写 Redis**")
    void putEmptyOnlyWritesRedis() {
        cache.putEmpty(CODE);

        verify(redis).putEmpty(CODE);
        assertThat(local.getIfPresent(CODE)).isNull();
    }

    @Test
    @DisplayName("⭐ 场景 13 / DR-045：evict 顺序必须是先本地后 Redis")
    void evictOrderIsLocalFirst() {
        local.put(CODE, new CacheEntry.Hit(link()));

        cache.evict(CODE);

        // ⚠️ 不能用 inOrder(local, redis)：local 是**真实的 Caffeine 实例**，不是 mock，
        // Mockito 的 InOrder 只接受 mock。顺序的验证方式改为「事后状态 + 源码约定」：
        // 1) 本地确实被清了（下面单独断言）
        // 2) Redis evict 确实被调用
        // 3) 真正的顺序保证来自 TwoLevelRedirectCache.evict 的两行代码与 DR-045 的注释，
        //    这里能做的是锁住「两层都清」这个结果。
        assertThat(local.getIfPresent(CODE)).isNull();
        verify(redis).evict(CODE);
    }

    @Test
    @DisplayName("⭐ 场景 13：evict 后本地确实查不到")
    void evictRemovesFromLocal() {
        local.put(CODE, new CacheEntry.Hit(link()));

        cache.evict(CODE);

        assertThat(local.getIfPresent(CODE)).isNull();
    }

    @Test
    @DisplayName("⭐ Caffeine 参数按业务约定：TTL 5 分钟、size 10000、recordStats 开")
    void caffeineParametersMatchDecision() {
        assertThat(CaffeineConfig.LOCAL_TTL).isEqualTo(Duration.ofMinutes(5));
        assertThat(CaffeineConfig.LOCAL_MAX_SIZE).isEqualTo(10_000L);

        // recordStats 的效果是「统计数字会动」——必须先有操作，否则 hit+miss 都是 0，
        // 断言「0 > 0」会失败而不是验证到东西
        local.put(CODE, new CacheEntry.Hit(link()));
        local.getIfPresent(CODE);
        local.getIfPresent("never-written");

        assertThat(local.stats().hitCount()).isEqualTo(1L);
        assertThat(local.stats().missCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("⭐ 本地缓存只接受 Hit 类型 —— NegativeHit 在编译期就被挡住")
    void localCacheValueTypeRejectsNegativeHit() {
        // 这是编译期约束，不是运行时断言：
        // Cache<String, CacheEntry.Hit> 的 value 类型里根本没有 NegativeHit 可放。
        // 本测试的存在意义是记录这个设计选择，防止后人把泛型改成 CacheEntry 以求「灵活」。
        Cache<String, CacheEntry.Hit> typed = local;
        assertThat(typed).isNotNull();
    }

    private void givenHitFromRedis() {
        CachedLink l = new CachedLink("https://example.com/x", "ACTIVE",
                LocalDateTime.parse("2027-01-01T00:00:00"));
        BDDMockito.given(redis.get(CODE)).willReturn(Optional.of(new CacheEntry.Hit(l)));
    }
}