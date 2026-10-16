package com.linkforge.redirect.infrastructure.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.linkforge.core.infrastructure.cache.CacheEntry;
import com.linkforge.core.infrastructure.cache.CachedLink;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * 单飞（single-flight）测试（DR-052）。
 *
 * <p>方案场景 6~8：并发只回源一次、异常不被缓存、404 不下沉本地。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SingleFlightTest {

    private static final String CODE = "0Q1A2b3";

    @Mock
    private RedisRedirectCache redis;

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
        cache = new TwoLevelRedirectCache(local, redis, registry);
    }

    private static CachedLink link() {
        return new CachedLink("https://example.com/x", "ACTIVE",
                LocalDateTime.parse("2027-01-01T00:00:00"));
    }

    @Test
    @DisplayName("⭐⭐ 场景 6：100 并发同 key → **loader 只执行 1 次**")
    void concurrentLoadsExecuteLoaderOnce() throws Exception {
        given(redis.get(CODE)).willReturn(Optional.empty());
        AtomicInteger loaderCalls = new AtomicInteger();

        int threads = 100;
        CountDownLatch startGun = new CountDownLatch(1);
        CountDownLatch allDone = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    startGun.await();
                    cache.getOrLoad(CODE, code -> {
                        // ⚠️ 加个延时扩大并发窗口，让所有线程都挤在 loader 执行期间
                        try {
                            Thread.sleep(50);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        loaderCalls.incrementAndGet();
                        return new CacheEntry.Hit(link());
                    });
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    allDone.countDown();
                }
            });
        }

        startGun.countDown();
        assertThat(allDone.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        // ⚠️ 核心断言：这就是「防击穿」的全部意义。
        // 没有单飞时这里是 100 次回源，热点 key 失效瞬间会打穿数据库
        assertThat(loaderCalls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("⭐⭐ 场景 7：loader 抛异常 → **异常不被缓存**，下次重新执行")
    void loaderExceptionIsNotCached() {
        given(redis.get(CODE)).willReturn(Optional.empty());
        AtomicInteger calls = new AtomicInteger();

        // 第一次：抛异常
        assertThatThrownBy(() -> cache.getOrLoad(CODE, code -> {
            calls.incrementAndGet();
            throw new IllegalStateException("db timeout");
        })).isInstanceOf(IllegalStateException.class);

        // ⚠️ Caffeine 不会缓存 loader 抛出的异常（这是它的既定行为），
        // 所以第二次会**重新执行 loader**。
        // 这正是我们要的：DR-048 要求查询失败不能被缓存成「不存在」
        assertThatThrownBy(() -> cache.getOrLoad(CODE, code -> {
            calls.incrementAndGet();
            throw new IllegalStateException("db timeout");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("⭐⭐ 场景 8：loader 返回 null（404）→ 写 Redis 空值，**不写本地**")
    void nullResultWritesOnlyRedisEmpty() {
        given(redis.get(CODE)).willReturn(Optional.empty());

        CacheEntry result = cache.getOrLoad(CODE, code -> null);

        assertThat(result).isInstanceOf(CacheEntry.NegativeHit.class);
        // ⚠️ 本地不能有——否则刚创建的短码会持续 404 达 5 分钟（DR-047）
        assertThat(local.getIfPresent(CODE)).isNull();
        verify(redis).putEmpty(CODE);
    }

    @Test
    @DisplayName("⭐⭐ getOrLoad 本地命中 → local.hit 计数器 +1（观测缺口修复）")
    void localHitIncrementsCounter() {
        // 先用一次回源把值写进 L1
        given(redis.get(CODE)).willReturn(Optional.empty());
        cache.getOrLoad(CODE, code -> new CacheEntry.Hit(link()));

        double afterFirst = registry.get("shortlink.cache.local.hit").counter().count();

        // 第二次：L1 有值，loader 不该再跑，但**计数器必须加 1**
        AtomicInteger calls = new AtomicInteger();
        cache.getOrLoad(CODE, code -> {
            calls.incrementAndGet();
            return new CacheEntry.Hit(link());
        });

        assertThat(calls.get()).isZero();
        assertThat(registry.get("shortlink.cache.local.hit").counter().count())
                .isEqualTo(afterFirst + 1.0);
    }

    @Test
    @DisplayName("⭐⭐ 首次回源（loader 执行）**不算**本地命中——否则指标虚高")
    void loaderExecutionIsNotCountedAsLocalHit() {
        // ⚠️ local.get(key, loader) 返回非 null 有两种情形：
        // L1 原有值 / 刚跑完 loader 写入。后者这次请求确实访问了数据库，
        // 计入 local.hit 会让「本地缓存省了多少回源」被高估，
        // 压测时就看不出真实的回源压力。
        given(redis.get(CODE)).willReturn(Optional.empty());

        cache.getOrLoad(CODE, code -> new CacheEntry.Hit(link()));

        assertThat(registry.get("shortlink.cache.local.hit").counter().count())
                .isEqualTo(0.0);
    }

    @Test
    @DisplayName("⭐ Redis 命中时 local.hit 不变（那次是 L2 供给的）")
    void redisHitDoesNotIncrementLocalHit() {
        given(redis.get(CODE)).willReturn(Optional.of(new CacheEntry.Hit(link())));

        cache.getOrLoad(CODE, code -> new CacheEntry.Hit(link()));

        // Redis 命中有自己的指标（redis.hit），不能同时记到 local.hit 上
        assertThat(registry.get("shortlink.cache.local.hit").counter().count())
                .isEqualTo(0.0);
    }

    @Test
    @DisplayName("⭐ Redis 命中时不执行 loader（省掉无谓的回源）")
    void redisHitSkipsLoader() {
        given(redis.get(CODE)).willReturn(Optional.of(new CacheEntry.Hit(link())));
        AtomicInteger calls = new AtomicInteger();

        CacheEntry result = cache.getOrLoad(CODE, code -> {
            calls.incrementAndGet();
            return new CacheEntry.Hit(link());
        });

        assertThat(calls.get()).isZero();
        assertThat(result).isInstanceOf(CacheEntry.Hit.class);
        // 正向结果会下沉到本地
        assertThat(local.getIfPresent(CODE)).isNotNull();
    }

    @Test
    @DisplayName("⭐⭐ Redis 命中 NegativeHit 时**不下沉本地**，也不执行 loader")
    void redisNegativeHitDoesNotBackfillLocal() {
        given(redis.get(CODE)).willReturn(Optional.of(new CacheEntry.NegativeHit()));
        AtomicInteger calls = new AtomicInteger();

        CacheEntry result = cache.getOrLoad(CODE, code -> {
            calls.incrementAndGet();
            return new CacheEntry.Hit(link());
        });

        assertThat(result).isInstanceOf(CacheEntry.NegativeHit.class);
        assertThat(calls.get()).isZero();
        // ⚠️ DR-047：空值绝不下沉，否则新短码会被「固化不存在」5 分钟
        assertThat(local.getIfPresent(CODE)).isNull();
    }

    @Test
    @DisplayName("⭐ 单飞回源成功后 Redis 被回填（putToRedisOnly）")
    void redisBackfilledAfterLoad() {
        given(redis.get(CODE)).willReturn(Optional.empty());

        cache.getOrLoad(CODE, code -> new CacheEntry.Hit(link()));
        cache.putToRedisOnly(CODE, link());

        verify(redis).put(CODE, link());
    }

    @Test
    @DisplayName("⭐ 并发不同 key 不互相阻塞（单飞是按 key 粒度的）")
    void differentKeysLoadIndependently() {
        given(redis.get(org.mockito.ArgumentMatchers.anyString()))
                .willReturn(Optional.empty());
        AtomicInteger calls = new AtomicInteger();

        cache.getOrLoad("key0001", code -> {
            calls.incrementAndGet();
            return new CacheEntry.Hit(link());
        });
        cache.getOrLoad("key0002", code -> {
            calls.incrementAndGet();
            return new CacheEntry.Hit(link());
        });

        assertThat(calls.get()).isEqualTo(2);
        verify(redis, never()).putEmpty(org.mockito.ArgumentMatchers.anyString());
    }

}
