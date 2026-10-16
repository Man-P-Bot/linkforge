package com.linkforge.redirect.infrastructure.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.linkforge.core.infrastructure.cache.BloomHash;
import com.linkforge.core.infrastructure.cache.CacheKeys;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * 布隆过滤器测试（docs/ARCHITECTURE.md §7.1，DR-007）。
 *
 * <p>方案场景 1~5：未初始化放行、判不存在拦截、误判放行、双缓冲顺序、不可用降级。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RedisBloomFilterTest {

    private static final String CODE = "0Q1A2b3";

    @Mock
    private StringRedisTemplate redis;

    @Mock
    private ValueOperations<String, String> valueOps;

    private SimpleMeterRegistry registry;
    private RedisBloomFilter bloom;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        given(redis.opsForValue()).willReturn(valueOps);
        bloom = new RedisBloomFilter(redis, registry);
    }

    @Test
    @DisplayName("⭐ 场景 1：布隆 key **不存在** → 放行（否则首次部署后所有请求 404）")
    void notReadyPassesThrough() {
        given(redis.hasKey(BloomHash.PREFIX)).willReturn(false);

        assertThat(bloom.mightContain(CODE)).isTrue();
        // ⚠️ 关键：绝不能查位。布隆不存在时任何判断都是无意义的
        verify(valueOps, never()).getBit(anyString(), anyLong());
    }

    @Test
    @DisplayName("⭐ 场景 1：isReady 与 key 存在性一致")
    void isReadyReflectsKeyExistence() {
        given(redis.hasKey(BloomHash.PREFIX)).willReturn(true);
        assertThat(bloom.isReady()).isTrue();

        given(redis.hasKey(BloomHash.PREFIX)).willReturn(false);
        assertThat(bloom.isReady()).isFalse();
    }

    @Test
    @DisplayName("⭐ 场景 2：某一位为 0 → 判「一定不存在」，这是唯一可放心 404 的情况")
    void anyZeroBitMeansAbsent() {
        given(redis.hasKey(BloomHash.PREFIX)).willReturn(true);
        int[] bits = indexes();
        // 第 3 位为 0
        given(valueOps.getBit(BloomHash.PREFIX, bits[2])).willReturn(false);

        assertThat(bloom.mightContain(CODE)).isFalse();
        assertThat(registry.get("shortlink.bloom.blocked").counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("⭐ 场景 2：7 位全为 1 → 「可能存在」，必须继续查缓存/DB")
    void allBitsSetMeansMaybePresent() {
        given(redis.hasKey(BloomHash.PREFIX)).willReturn(true);
        for (int bit : indexes()) {
            given(valueOps.getBit(BloomHash.PREFIX, bit)).willReturn(true);
        }

        assertThat(bloom.mightContain(CODE)).isTrue();
    }

    @Test
    @DisplayName("⭐ 场景 4：Redis 不可用 → 放行（降级查库，服务可用）")
    void redisFailureDegradesToPassThrough() {
        given(redis.hasKey(BloomHash.PREFIX))
                .willThrow(new RedisConnectionFailureException("down"));

        assertThat(bloom.mightContain(CODE)).isTrue();
    }

    @Test
    @DisplayName("⭐ 写穿失败只记指标，**不抛出**（不阻塞创建，ARCHITECTURE §259）")
    void writeThroughFailureDoesNotThrow() {
        doThrow(new QueryTimeoutException("timeout"))
                .when(valueOps).setBit(anyString(), anyLong(), anyBoolean());

        // 不抛即为通过：布隆是优化不是正确性来源，
        // 让它成为单点依赖反而会因写失败而拖垮创建接口
        bloom.put(CODE);

        assertThat(registry.get("shortlink.bloom.put.failed").counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("⭐ 写穿成功 → 7 个位全部置 1")
    void writeThroughSetsAllBits() {
        bloom.put(CODE);

        for (int bit : indexes()) {
            verify(valueOps).setBit(BloomHash.PREFIX, bit, true);
        }
        assertThat(registry.get("shortlink.bloom.put.failed").counter().count()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("⭐⭐ 场景 4 / DR-007：重建**从不删除现行 key**，只 RENAME 切换")
    void rebuildNeverDeletesLiveKey() {
        bloom.rebuild(List.of("0Q1A2b3", "0Q1A2b4"));

        // ⚠️ 核心断言：DEL 的对象只能是 :building，绝不能是 shortlink:bloom。
        // 若误删了现行 key，从 DEL 到 RENAME 之间所有真实短码都会 404。
        verify(redis).delete(BloomHash.PREFIX + BloomHash.BUILDING_SUFFIX);
        verify(redis, never()).delete(BloomHash.PREFIX);
        verify(redis).rename(BloomHash.PREFIX + BloomHash.BUILDING_SUFFIX, BloomHash.PREFIX);
    }

    @Test
    @DisplayName("⭐⭐ 场景 4：重建时先写 :building，读路径仍指向现行 key")
    void rebuildWritesOnlyBuildingKey() {
        bloom.rebuild(List.of("0Q1A2b3"));

        verify(valueOps, never()).setBit(eq(BloomHash.PREFIX), anyLong(), anyBoolean());
    }

    @Test
    @DisplayName("⭐ 规格透传：n / m / k 与 ARCHITECTURE 一致")
    void exposesSpec() {
        assertThat(bloom.expectedInsertions()).isEqualTo(10_000_000L);
        assertThat(bloom.bitSize()).isEqualTo(95_850_584L);
        assertThat(bloom.hashFunctions()).isEqualTo(7);
    }

    @Test
    @DisplayName("⭐ 场景 3：合并查询——布隆判不存在时返回 absent，不查缓存")
    @SuppressWarnings("unchecked")
    void mergedQueryReturnsAbsent() {
        given(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .willReturn(List.of(0L, Boolean.FALSE));

        RedisBloomFilter.BloomCheckResult result = bloom.checkAndGet(CODE);

        assertThat(result.definitelyAbsent()).isTrue();
        assertThat(registry.get("shortlink.bloom.blocked").counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("⭐ 场景 3：合并查询——布隆放过且缓存有值")
    @SuppressWarnings("unchecked")
    void mergedQueryReturnsCachedValue() {
        given(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .willReturn(List.of(1L, "v1|ACTIVE||4|abcd"));

        RedisBloomFilter.BloomCheckResult result = bloom.checkAndGet(CODE);

        assertThat(result.definitelyAbsent()).isFalse();
        assertThat(result.cachedValue()).isEqualTo("v1|ACTIVE||4|abcd");
    }

    @Test
    @DisplayName("⭐ 场景 3：合并查询——布隆放过但缓存无值 → 需回源")
    @SuppressWarnings("unchecked")
    void mergedQueryRequestsSource() {
        given(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .willReturn(List.of(1L, Boolean.FALSE));

        RedisBloomFilter.BloomCheckResult result = bloom.checkAndGet(CODE);

        assertThat(result.definitelyAbsent()).isFalse();
        assertThat(result.requiresSource()).isTrue();
    }

    @Test
    @DisplayName("⭐ 合并查询失败 → 降级为「需回源」，不让跳转失效")
    @SuppressWarnings("unchecked")
    void mergedQueryFailureDegradesToSource() {
        given(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .willThrow(new RedisConnectionFailureException("down"));

        RedisBloomFilter.BloomCheckResult result = bloom.checkAndGet(CODE);

        assertThat(result.definitelyAbsent()).isFalse();
        assertThat(result.requiresSource()).isTrue();
    }

    @Test
    @DisplayName("⭐ 合并查询传的缓存 key 是文档定义的 shortlink:code:{shortCode}")
    @SuppressWarnings("unchecked")
    void mergedQueryUsesDocumentedCacheKey() {
        given(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .willReturn(List.of(1L, Boolean.FALSE));

        bloom.checkAndGet(CODE);

        ArgumentCaptor<List> keys = ArgumentCaptor.forClass(List.class);
        verify(redis).execute(any(RedisScript.class), keys.capture(), any(Object[].class));
        assertThat(keys.getValue()).containsExactly(BloomHash.PREFIX, CacheKeys.code(CODE));
    }

    private static int[] indexes() {
        int[] bits = new int[BloomHash.HASH_COUNT];
        BloomHash.bitIndexes(CODE, bits);
        return bits;
    }

}
