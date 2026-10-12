package com.linkforge.redirect.infrastructure.id;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.linkforge.core.domain.Base62;
import com.linkforge.core.domain.IdSpace;
import com.linkforge.core.repository.ShortCodeGenerator.ShortCodeGenerationException;
import java.lang.reflect.Proxy;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * {@link RedisShortCodeGenerator} 的启动自检与发号测试。
 *
 * <p>覆盖三种初始化情况，重点是**情况 2：key 已存在但值异常偏小**。
 * 原实现只用 {@code SETNX}，而 {@code SETNX} 不覆盖已存在的 key——
 * Redis 被清空或 RDB 回退后，计数器会从一个小值继续，发出的短码与历史短码重复。
 *
 * <p>用内存假实现而不是 mocking 库：假实现把「何时 GET / 何时 SETNX / 何时 SET」
 * 显式记下来，正好是这些用例要断言的交互序列。
 */
class RedisShortCodeGeneratorTest {

    /**
     * 内存版 Redis，只实现本项目用到的四个操作。
     *
     * <p>{@code handle()} 把「未实现的方法」统一转成 {@link UnsupportedOperationException}：
     * 这样假实现**天然覆盖整个 Spring 接口**，不需要手写新版接口新增的每个方法，
     * 也不会因为漏写某个 abstract 方法而编译失败。
     */
    private static final class FakeRedis {

        private final ConcurrentHashMap<String, String> store = new ConcurrentHashMap<>();
        private final AtomicInteger setCalls = new AtomicInteger();
        private final AtomicInteger setIfAbsentCalls = new AtomicInteger();

        StringRedisTemplate template() {
            return new StringRedisTemplate() {
                @Override
                @SuppressWarnings("unchecked")
                public ValueOperations<String, String> opsForValue() {
                    return (ValueOperations<String, String>) Proxy.newProxyInstance(
                            ValueOperations.class.getClassLoader(),
                            new Class<?>[] {ValueOperations.class},
                            (proxy, method, args) -> dispatch(method.getName(), args));
                }
            };
        }

        private Object dispatch(String method, Object[] args) {
            switch (method) {
                case "get":
                    return store.get((String) args[0]);
                case "set":
                    setCalls.incrementAndGet();
                    store.put((String) args[0], (String) args[1]);
                    return null;
                case "setIfAbsent":
                    setIfAbsentCalls.incrementAndGet();
                    return store.putIfAbsent((String) args[0], (String) args[1]) == null;
                case "increment":
                    return Long.parseLong(store.compute((String) args[0],
                            (k, v) -> Long.toString((v == null ? 0L : Long.parseLong(v.trim())) + 1L)));
                case "toString":
                    return "FakeRedisValueOperations";
                case "hashCode":
                    return System.identityHashCode(this);
                case "equals":
                    return false;
                default:
                    throw new UnsupportedOperationException("FakeRedis 未实现: " + method);
            }
        }

        String raw(String key) {
            return store.get(key);
        }

        void put(String key, String value) {
            store.put(key, value);
        }

        int setCalls() {
            return setCalls.get();
        }

        int setIfAbsentCalls() {
            return setIfAbsentCalls.get();
        }
    }

    private FakeRedis redis;
    private RedisShortCodeGenerator generator;

    @BeforeEach
    void setUp() {
        redis = new FakeRedis();
        generator = new RedisShortCodeGenerator(redis.template());
    }

    @Nested
    @DisplayName("情况 1：key 不存在")
    class KeyAbsent {

        @Test
        @DisplayName("初始化为 REDIS_KEY_INIT_VALUE（99999），首个发出的号是 100000")
        void initializesToRangeStart() {
            generator.ensureInitialized();

            assertThat(redis.raw(RedisShortCodeGenerator.SEQUENCE_KEY))
                    .isEqualTo("99999")
                    .isEqualTo(Long.toString(IdSpace.REDIS_KEY_INIT_VALUE));
            assertThat(redis.setIfAbsentCalls()).isEqualTo(1);
            assertThat(redis.setCalls()).isZero();

            String first = generator.nextShortCode();
            assertThat(first).isEqualTo("0000Q0u");
            assertThat(Base62.decode(first)).isEqualTo(IdSpace.FIRST_SEQUENCE);
        }
    }

    @Nested
    @DisplayName("情况 2：key 存在但值异常偏小（Redis 被清空/回退）")
    class ValueTooSmall {

        @Test
        @DisplayName("⭐ 值为 1 时修正为 99999，首个号是 100000（原实现只 SETNX，此用例会失败）")
        void correctsAbnormallySmallValue() {
            redis.put(RedisShortCodeGenerator.SEQUENCE_KEY, "1");

            generator.ensureInitialized();

            assertThat(redis.raw(RedisShortCodeGenerator.SEQUENCE_KEY)).isEqualTo("99999");
            // 走 SET 修正分支，而不是 SETNX（SETNX 不覆盖已存在的 key）
            assertThat(redis.setCalls()).isEqualTo(1);
            assertThat(redis.setIfAbsentCalls()).isZero();

            String first = generator.nextShortCode();
            assertThat(first).isEqualTo("0000Q0u");
            assertThat(Base62.decode(first)).isEqualTo(IdSpace.FIRST_SEQUENCE);
        }

        @Test
        @DisplayName("值为 0 时同样修正（否则 INCR 首次返回 1）")
        void correctsZeroValue() {
            redis.put(RedisShortCodeGenerator.SEQUENCE_KEY, "0");

            generator.ensureInitialized();

            assertThat(redis.raw(RedisShortCodeGenerator.SEQUENCE_KEY)).isEqualTo("99999");
            assertThat(Base62.decode(generator.nextShortCode())).isEqualTo(IdSpace.FIRST_SEQUENCE);
        }

        @Test
        @DisplayName("值为 99998（起点减一）时也修正，边界闭合")
        void correctsValueJustBelowStart() {
            redis.put(RedisShortCodeGenerator.SEQUENCE_KEY, "99998");

            generator.ensureInitialized();

            assertThat(redis.raw(RedisShortCodeGenerator.SEQUENCE_KEY)).isEqualTo("99999");
        }

        @Test
        @DisplayName("值不是整数（脏数据）时修正，避免 INCR 直接报错")
        void correctsNonNumericValue() {
            redis.put(RedisShortCodeGenerator.SEQUENCE_KEY, "not-a-number");

            generator.ensureInitialized();

            assertThat(redis.raw(RedisShortCodeGenerator.SEQUENCE_KEY)).isEqualTo("99999");
            assertThat(redis.setCalls()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("情况 3：key 存在且值正常")
    class ValueHealthy {

        @Test
        @DisplayName("⭐ 值为 200000 时不动它，首个号是 200001（绝不能重置）")
        void doesNotTouchHealthyValue() {
            redis.put(RedisShortCodeGenerator.SEQUENCE_KEY, "200000");

            generator.ensureInitialized();

            assertThat(redis.raw(RedisShortCodeGenerator.SEQUENCE_KEY)).isEqualTo("200000");
            // 既没 SETNX 也没 SET —— 重置会让已发出的号被重发
            assertThat(redis.setCalls()).isZero();
            assertThat(redis.setIfAbsentCalls()).isZero();

            assertThat(Base62.decode(generator.nextShortCode())).isEqualTo(200_001L);
        }

        @Test
        @DisplayName("值恰好等于起点（99999）时不修正")
        void valueExactlyAtStartIsHealthy() {
            redis.put(RedisShortCodeGenerator.SEQUENCE_KEY, "99999");

            generator.ensureInitialized();

            assertThat(redis.setCalls()).isZero();
            assertThat(Base62.decode(generator.nextShortCode())).isEqualTo(IdSpace.FIRST_SEQUENCE);
        }
    }

    @Nested
    @DisplayName("发号行为")
    class Issuing {

        @Test
        @DisplayName("连续发号递增，且长度恒为 7")
        void consecutiveCodesIncrease() {
            generator.ensureInitialized();

            long first = Base62.decode(generator.nextShortCode());
            long second = Base62.decode(generator.nextShortCode());
            long third = Base62.decode(generator.nextShortCode());

            assertThat(first).isEqualTo(IdSpace.FIRST_SEQUENCE);
            assertThat(second).isEqualTo(IdSpace.FIRST_SEQUENCE + 1);
            assertThat(third).isEqualTo(IdSpace.FIRST_SEQUENCE + 2);
            assertThat(generator.nextShortCode()).hasSize(IdSpace.SHORT_CODE_LENGTH);
        }

        @Test
        @DisplayName("超出 Redis 区间上界时抛异常且不回绕（回绕会让短码重复）")
        void throwsWhenRangeExhausted() {
            redis.put(RedisShortCodeGenerator.SEQUENCE_KEY, Long.toString(IdSpace.REDIS_RANGE_END - 1));

            // 下一次 INCR 得到 REDIS_RANGE_END，正好越界
            assertThatThrownBy(generator::nextShortCode)
                    .isInstanceOf(ShortCodeGenerationException.class)
                    .hasMessageContaining("区间用尽");
        }

        @Test
        @DisplayName("Redis 抛异常时包装为 ShortCodeGenerationException，供 Fallback 捕获降级")
        void wrapsRedisFailure() {
            StringRedisTemplate failing = new StringRedisTemplate() {
                @Override
                @SuppressWarnings("unchecked")
                public ValueOperations<String, String> opsForValue() {
                    return (ValueOperations<String, String>) Proxy.newProxyInstance(
                            ValueOperations.class.getClassLoader(),
                            new Class<?>[] {ValueOperations.class},
                            (proxy, method, args) -> {
                                if ("increment".equals(method.getName())) {
                                    throw new QueryTimeoutException("模拟 Redis 超时");
                                }
                                throw new UnsupportedOperationException(method.getName());
                            });
                }
            };
            RedisShortCodeGenerator failingGenerator = new RedisShortCodeGenerator(failing);

            assertThatThrownBy(failingGenerator::nextShortCode)
                    .isInstanceOf(ShortCodeGenerationException.class)
                    .hasMessageContaining("Redis 发号失败");
        }
    }
}
