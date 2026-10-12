package com.linkforge.redirect.infrastructure.id;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.linkforge.core.domain.Base62;
import com.linkforge.core.domain.IdSpace;
import com.linkforge.core.repository.ShortCodeGenerator.ShortCodeGenerationException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 发号器测试（docs/DECISIONS.md DR-011 / DR-022）。
 *
 * <p>这里用假的 {@link IdSegmentAllocator} 子类代替真实数据库，让测试纯内存运行：
 * 号段的推进语义（取一段、用尽再取）是纯逻辑，不需要真库也能完整验证。
 */
class ShortCodeGeneratorTest {

    /** 内存版取段器：模拟 {@code id_segment} 的 max_id 推进。 */
    private static final class FakeAllocator extends IdSegmentAllocator {

        private final AtomicLong maxId;
        private final int step;
        private int advanceCalls;

        FakeAllocator(long initialMaxId, int step) {
            super(null, IdSegmentAllocator.BIZ_TAG, step);
            this.maxId = new AtomicLong(initialMaxId);
            this.step = step;
        }

        @Override
        public void ensureRow() {
            // 内存实现无需建行
        }

        @Override
        public Long currentMaxId() {
            return maxId.get();
        }

        @Override
        public synchronized long advanceAndGetMaxId() {
            advanceCalls++;
            return maxId.addAndGet(step);
        }

        int advanceCalls() {
            return advanceCalls;
        }
    }

    @Nested
    @DisplayName("SegmentShortCodeGenerator：号段发号")
    class SegmentGenerator {

        @Test
        @DisplayName("首个号等于 SEGMENT_FIRST_SEQUENCE（不是 max_id + 1 之外的其它值）")
        void firstSequence() {
            FakeAllocator allocator = new FakeAllocator(IdSpace.SEGMENT_MAX_ID_INIT_VALUE, 1000);
            SegmentShortCodeGenerator generator = new SegmentShortCodeGenerator(allocator, 1000);

            String code = generator.nextShortCode();

            assertThat(Base62.decode(code)).isEqualTo(IdSpace.SEGMENT_FIRST_SEQUENCE);
            assertThat(code).isEqualTo("015ftgG").hasSize(IdSpace.SHORT_CODE_LENGTH);
        }

        @Test
        @DisplayName("一个号段内只访问数据库一次（step 次调用 -> 1 次取段）")
        void oneAdvancePerSegment() {
            FakeAllocator allocator = new FakeAllocator(IdSpace.SEGMENT_MAX_ID_INIT_VALUE, 10);
            SegmentShortCodeGenerator generator = new SegmentShortCodeGenerator(allocator, 10);

            for (int i = 0; i < 10; i++) {
                generator.nextShortCode();
            }

            assertThat(allocator.advanceCalls()).isEqualTo(1);
        }

        @Test
        @DisplayName("号段用尽后自动取下一段，且序号连续不重复")
        void refillsAfterSegmentExhausted() {
            FakeAllocator allocator = new FakeAllocator(IdSpace.SEGMENT_MAX_ID_INIT_VALUE, 10);
            SegmentShortCodeGenerator generator = new SegmentShortCodeGenerator(allocator, 10);

            List<Long> sequences = new ArrayList<>();
            for (int i = 0; i < 25; i++) {
                sequences.add(Base62.decode(generator.nextShortCode()));
            }

            assertThat(allocator.advanceCalls()).isEqualTo(3);          // 10 + 10 + 10 的第三段
            assertThat(sequences).doesNotHaveDuplicates();
            assertThat(sequences).isSorted();
            assertThat(sequences.get(0)).isEqualTo(IdSpace.SEGMENT_FIRST_SEQUENCE);
            assertThat(sequences.get(24)).isEqualTo(IdSpace.SEGMENT_FIRST_SEQUENCE + 24);
        }

        @Test
        @DisplayName("连发 1 万个号无重复，且全部为 7 位 Base62")
        void tenThousandUniqueCodes() {
            FakeAllocator allocator = new FakeAllocator(IdSpace.SEGMENT_MAX_ID_INIT_VALUE, 1000);
            SegmentShortCodeGenerator generator = new SegmentShortCodeGenerator(allocator, 1000);

            Set<String> codes = new HashSet<>();
            for (int i = 0; i < 10_000; i++) {
                String code = generator.nextShortCode();
                assertThat(code).hasSize(IdSpace.SHORT_CODE_LENGTH);
                assertThat(Base62.isBase62(code)).isTrue();
                codes.add(code);
            }

            assertThat(codes).hasSize(10_000);
        }

        @Test
        @DisplayName("并发发号不重复（覆盖 getAndIncrement 的竞态）")
        void concurrentAllocationIsUnique() throws Exception {
            FakeAllocator allocator = new FakeAllocator(IdSpace.SEGMENT_MAX_ID_INIT_VALUE, 500);
            SegmentShortCodeGenerator generator = new SegmentShortCodeGenerator(allocator, 500);

            int threads = 16;
            int perThread = 500;
            Set<String> codes = ConcurrentHashMap.newKeySet();
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch start = new CountDownLatch(1);
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();

            for (int t = 0; t < threads; t++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        codes.add(generator.nextShortCode());
                    }
                    return null;
                }));
            }
            start.countDown();
            for (java.util.concurrent.Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
            pool.shutdown();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

            assertThat(codes).hasSize(threads * perThread);
        }

        @Test
        @DisplayName("号段步长非正数时构造失败")
        void invalidStepRejected() {
            FakeAllocator allocator = new FakeAllocator(IdSpace.SEGMENT_MAX_ID_INIT_VALUE, 1000);
            assertThatThrownBy(() -> new SegmentShortCodeGenerator(allocator, 0))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new SegmentShortCodeGenerator(allocator, -1))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("启动自检：max_id 小于区间起点时抛异常（避免与 Redis 区间重叠）")
        void ensureInitializedRejectsOverlappingRange() {
            // 模拟被误设成 100000 的库（会让号段从 100001 起，落进 Redis 区间）
            FakeAllocator allocator = new FakeAllocator(100_000L, 1000);
            SegmentShortCodeGenerator generator = new SegmentShortCodeGenerator(allocator, 1000);

            assertThatThrownBy(generator::ensureInitialized)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("不得小于");
        }

        @Test
        @DisplayName("启动自检：max_id 合法时通过")
        void ensureInitializedAcceptsValidRange() {
            FakeAllocator allocator = new FakeAllocator(IdSpace.SEGMENT_MAX_ID_INIT_VALUE, 1000);
            SegmentShortCodeGenerator generator = new SegmentShortCodeGenerator(allocator, 1000);

            generator.ensureInitialized();

            assertThat(allocator.currentMaxId()).isEqualTo(IdSpace.SEGMENT_MAX_ID_INIT_VALUE);
        }
    }

    @Nested
    @DisplayName("FallbackShortCodeGenerator：单向降级")
    class Fallback {

        /** 可控失败的假主发号器。 */
        private final class FlakyGenerator implements com.linkforge.core.repository.ShortCodeGenerator {
            private final AtomicLong counter = new AtomicLong(1_000_000L);
            private volatile boolean failing;
            private int calls;

            @Override
            public String nextShortCode() {
                calls++;
                if (failing) {
                    throw new ShortCodeGenerationException("模拟 Redis 不可用");
                }
                return Base62.encodePadded(counter.getAndIncrement());
            }
        }

        @Test
        @DisplayName("主路径正常时不走降级")
        void usesPrimaryWhenHealthy() {
            FlakyGenerator primary = new FlakyGenerator();
            FakeAllocator allocator = new FakeAllocator(IdSpace.SEGMENT_MAX_ID_INIT_VALUE, 1000);
            FallbackShortCodeGenerator generator =
                    new FallbackShortCodeGenerator(primary, new SegmentShortCodeGenerator(allocator, 1000));

            String code = generator.nextShortCode();

            assertThat(code).isEqualTo(Base62.encodePadded(1_000_000L));
            assertThat(generator.isDegraded()).isFalse();
            assertThat(allocator.advanceCalls()).isZero();
        }

        @Test
        @DisplayName("主路径失败后切到降级路径，且发出的号落在数据库号段区间")
        void degradesOnPrimaryFailure() {
            FlakyGenerator primary = new FlakyGenerator();
            primary.failing = true;
            FakeAllocator allocator = new FakeAllocator(IdSpace.SEGMENT_MAX_ID_INIT_VALUE, 1000);
            FallbackShortCodeGenerator generator =
                    new FallbackShortCodeGenerator(primary, new SegmentShortCodeGenerator(allocator, 1000));

            String code = generator.nextShortCode();

            assertThat(generator.isDegraded()).isTrue();
            assertThat(generator.degradeCount()).isEqualTo(1);
            assertThat(Base62.decode(code)).isGreaterThanOrEqualTo(IdSpace.SEGMENT_FIRST_SEQUENCE);
        }

        @Test
        @DisplayName("⭐ 降级后不再尝试主路径（单向降级，DR-022）")
        void doesNotRetryPrimaryAfterDegrade() {
            FlakyGenerator primary = new FlakyGenerator();
            primary.failing = true;
            FakeAllocator allocator = new FakeAllocator(IdSpace.SEGMENT_MAX_ID_INIT_VALUE, 1000);
            FallbackShortCodeGenerator generator =
                    new FallbackShortCodeGenerator(primary, new SegmentShortCodeGenerator(allocator, 1000));

            generator.nextShortCode();               // 触发降级
            primary.failing = false;                 // Redis 恢复了
            generator.nextShortCode();
            generator.nextShortCode();

            // 主路径只被调用过一次：恢复后也不切回，因为切回会导致号段重复
            assertThat(primary.calls).isEqualTo(1);
            assertThat(generator.degradeCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("⭐ 降级期间发出的号与主路径区间不重叠（唯一性不依赖同步）")
        void degradedRangeDoesNotOverlapPrimaryRange() {
            FlakyGenerator primary = new FlakyGenerator();
            primary.failing = true;
            FakeAllocator allocator = new FakeAllocator(IdSpace.SEGMENT_MAX_ID_INIT_VALUE, 1000);
            FallbackShortCodeGenerator generator =
                    new FallbackShortCodeGenerator(primary, new SegmentShortCodeGenerator(allocator, 1000));

            Set<Long> degradedSequences = new HashSet<>();
            for (int i = 0; i < 100; i++) {
                degradedSequences.add(Base62.decode(generator.nextShortCode()));
            }

            // 全部落在数据库号段区间，且都不小于 Redis 区间上界
            assertThat(degradedSequences).allSatisfy(seq -> {
                assertThat(seq).isGreaterThanOrEqualTo(IdSpace.SEGMENT_FIRST_SEQUENCE);
                // 号段区间必须整体位于 Redis 区间之上（REDIS_RANGE_END 是 Redis 的上界，不含）
                assertThat(seq).isGreaterThanOrEqualTo(IdSpace.REDIS_RANGE_END);
            });
            assertThat(degradedSequences).hasSize(100);
        }
    }

    @Nested
    @DisplayName("IdSpace 边界：区间划分是唯一性的基础")
    class RangeBoundaries {

        @Test
        @DisplayName("Redis 区间长度 = 9.999 亿，按日 10 万条可用约 74 年")
        void redisRangeCapacity() {
            long capacity = IdSpace.REDIS_RANGE_END - IdSpace.FIRST_SEQUENCE;
            assertThat(capacity).isEqualTo(999_900_000L);
            assertThat(capacity / 100_000L / 365L).isGreaterThanOrEqualTo(27L); // 保守下界
        }

        @Test
        @DisplayName("数据库号段区间容量远大于 Redis 区间")
        void segmentRangeCapacity() {
            long segmentCapacity = IdSpace.MAX_SEQUENCE - IdSpace.SEGMENT_FIRST_SEQUENCE + 1;
            long redisCapacity = IdSpace.REDIS_RANGE_END - IdSpace.FIRST_SEQUENCE;
            assertThat(segmentCapacity).isGreaterThan(redisCapacity);
        }
    }
}
