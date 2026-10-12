package com.linkforge.core.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@link Base62} 的边界与双射性测试。
 *
 * <p>这些用例的期望值全部按 **0-9A-Za-z** 编码表手算得出。
 * 如果实现改用了 JDK 的 {@code Long.toString(n, 62)}（表为 0-9a-zA-Z），
 * 序号 ≥ 10 的用例会立刻失败——这正是本测试要锁住的行为。
 */
class Base62Test {

    @Nested
    @DisplayName("encode：编码表与进制进位")
    class Encode {

        @Test
        @DisplayName("序号 0 编码为 \"0\"（单字符，不补零）")
        void encodeZero() {
            assertThat(Base62.encode(0)).isEqualTo("0");
        }

        @Test
        @DisplayName("序号 61 是最后一个个位，编码为 \"z\"（小写 z，表中第 61 位）")
        void encodeLastSingleDigit() {
            assertThat(Base62.encode(61)).isEqualTo("z");
        }

        @Test
        @DisplayName("序号 62 进位为 \"10\"")
        void encodeCarry() {
            assertThat(Base62.encode(62)).isEqualTo("10");
        }

        @Test
        @DisplayName("序号 62*62-1 = 3843 编码为 \"zz\"（两位全满）")
        void encodeMaxTwoDigits() {
            assertThat(Base62.encode(3843)).isEqualTo("zz");
        }

        @Test
        @DisplayName("序号 62^7-1 编码为 \"zzzzzzz\"（7 位全满，上界）")
        void encodeMaxSequence() {
            assertThat(Base62.encode(IdSpace.MAX_SEQUENCE)).isEqualTo("zzzzzzz");
        }

        @Test
        @DisplayName("编码表顺序是 0-9A-Za-z：序号 10 -> \"A\"，35 -> \"Z\"，36 -> \"a\"")
        void alphabetOrderIsUpperBeforeLower() {
            assertThat(Base62.encode(10)).isEqualTo("A");
            assertThat(Base62.encode(35)).isEqualTo("Z");
            assertThat(Base62.encode(36)).isEqualTo("a");
        }

        @Test
        @DisplayName("JDK 根本不支持 base62：Long.toString(n, 62) 因基数超出 2~36 而降级为 10 进制")
        void jdkHasNoBase62Support() {
            // Character.MAX_RADIX == 36，所以 radix=62 会被 Long.toString 回退成 10 进制：
            //   Long.toString(10, 62) == "10"（不是 "A"，也不是 "a"）
            // 结论：**JDK 不能作为 base62 的对照实现**，本项目必须自己写编码表。
            assertThat(Character.MAX_RADIX).isEqualTo(36);
            assertThat(Long.toString(10, 62)).isEqualTo("10");
            assertThat(Long.toString(10, 36)).isEqualTo("a"); // 36 进制才是合法的，且表为 0-9a-z
            assertThat(Base62.encode(10)).isEqualTo("A");     // 本项目的表为 0-9A-Za-z
        }
    }

    @Nested
    @DisplayName("encodePadded：左补零到固定 7 位")
    class EncodePadded {

        /**
         * 用 {@code @MethodSource} 而不是 {@code @CsvSource}：
         * {@code @CsvSource} 会对目标参数类型做隐式转换，像 {@code "0000000"} 这样的
         * 全零/带前导零的期望值会被按数字解析成 {@code 0}，导致断言拿到错误期望值。
         * 这里显式构造 {@code Arguments}，两个参数的类型都不再经过隐式转换。
         */
        static Stream<Arguments> paddedCases() {
            return Stream.of(
                    Arguments.of(0L, "0000000"),
                    Arguments.of(1L, "0000001"),
                    Arguments.of(9L, "0000009"),
                    Arguments.of(10L, "000000A"),
                    Arguments.of(35L, "000000Z"),
                    Arguments.of(36L, "000000a"),
                    Arguments.of(61L, "000000z"),
                    Arguments.of(62L, "0000010"),
                    Arguments.of(63L, "0000011"),
                    Arguments.of(3843L, "00000zz"),
                    Arguments.of(3844L, "0000100"),
                    Arguments.of(99999L, "0000Q0t"),
                    Arguments.of(100000L, "0000Q0u"),
                    Arguments.of(100001L, "0000Q0v"),
                    Arguments.of(1000000L, "0004C92"),
                    Arguments.of(999999999L, "015ftgF"),
                    Arguments.of(1000000000L, "015ftgG"),
                    Arguments.of(IdSpace.MAX_SEQUENCE, "zzzzzzz"));
        }

        @ParameterizedTest(name = "序号 {0} 补零后 -> \"{1}\"")
        @MethodSource("paddedCases")
        @DisplayName("补零后长度恒为 7，且右侧字符与最短编码一致")
        void paddedIsAlwaysSeven(long sequence, String expected) {
            String padded = Base62.encodePadded(sequence);
            assertThat(padded).isEqualTo(expected).hasSize(IdSpace.SHORT_CODE_LENGTH);
            assertThat(padded.replaceFirst("^0+", "")).isEqualTo(Base62.encode(sequence));
        }

        @Test
        @DisplayName("序号 0 补零为 \"0000000\"")
        void padZero() {
            assertThat(Base62.encodePadded(0)).isEqualTo("0000000");
        }

        @Test
        @DisplayName("序号 62 补零为 \"0000010\"")
        void padCarry() {
            assertThat(Base62.encodePadded(62)).isEqualTo("0000010");
        }

        @Test
        @DisplayName("上界序号补零后仍为 7 位，且不被截断")
        void padMaxSequenceNotTruncated() {
            String padded = Base62.encodePadded(IdSpace.MAX_SEQUENCE);
            assertThat(padded).isEqualTo("zzzzzzz").hasSize(IdSpace.SHORT_CODE_LENGTH);
        }

        @Test
        @DisplayName("首个发号序号 100000 的短码是 \"0000Q0u\"（大写 Q，不是小写 q）")
        void firstIssuedSequence() {
            // 这个期望值同时钉住了两件事：左补零到 7 位、以及编码表是 0-9A-Za-z。
            // 若改用 JDK 的 Long.toString(n, 62)（表为 0-9a-zA-Z），此处会得到 "0000q0u" 而失败。
            assertThat(Base62.encodePadded(IdSpace.FIRST_SEQUENCE)).isEqualTo("0000Q0u");
            assertThat(Base62.decode("0000Q0u")).isEqualTo(IdSpace.FIRST_SEQUENCE);
        }

        @Test
        @DisplayName("数据库中号段首个发出的号 1000000000 的短码是 \"015ftgG\"")
        void segmentFirstIssuedSequence() {
            assertThat(Base62.encodePadded(IdSpace.SEGMENT_FIRST_SEQUENCE)).isEqualTo("015ftgG");
        }
    }

    @Nested
    @DisplayName("decode：反解与双射性")
    class Decode {

        @ParameterizedTest
        @ValueSource(longs = {0L, 1L, 9L, 10L, 35L, 36L, 61L, 62L, 63L, 3843L, 3844L, 99999L, 100000L,
                1_000_000_000L, IdSpace.MAX_SEQUENCE})
        @DisplayName("decode(encode(n)) == n")
        void roundTrip(long sequence) {
            assertThat(Base62.decode(Base62.encode(sequence))).isEqualTo(sequence);
            assertThat(Base62.decode(Base62.encodePadded(sequence))).isEqualTo(sequence);
        }

        @Test
        @DisplayName("补零后解码仍得到原序号（前导零不影响数值）")
        void leadingZerosIgnored() {
            assertThat(Base62.decode("0000001")).isEqualTo(1);
            assertThat(Base62.decode("0000q0")).isEqualTo(Base62.decode("q0"));
        }

        @Test
        @DisplayName("非法字符抛异常")
        void invalidCharacter() {
            // '-' 与 '_' 不在 Base62 表中（API §4.1 要求这类字符在接口层就返回 400）
            assertThatThrownBy(() -> Base62.decode("abc-123")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> Base62.decode("abc_123")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> Base62.decode("中文")).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("空串抛异常")
        void blankRejected() {
            assertThatThrownBy(() -> Base62.decode("")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> Base62.decode(null)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("边界与异常：绝不回绕、绝不截断")
    class Boundaries {

        @Test
        @DisplayName("序号 62^7 超出 7 位空间，抛异常（不回绕为 0）")
        void aboveMaxThrows() {
            long aboveMax = IdSpace.MAX_SEQUENCE + 1;
            assertThatThrownBy(() -> Base62.encode(aboveMax))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("超出");
        }

        @Test
        @DisplayName("负序号抛异常")
        void negativeThrows() {
            assertThatThrownBy(() -> Base62.encode(-1)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> Base62.encodePadded(-1)).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("isBase62 只做字符集判断，不做长度与范围判断")
        void isBase62ChecksCharsetOnly() {
            assertThat(Base62.isBase62("abc1234")).isTrue();
            assertThat(Base62.isBase62("ABCxyz09")).isTrue();
            assertThat(Base62.isBase62("abc-123")).isFalse();
            assertThat(Base62.isBase62("")).isFalse();
            assertThat(Base62.isBase62(null)).isFalse();
        }
    }

    @Nested
    @DisplayName("IdSpace 号空间不变量")
    class IdSpaceInvariants {

        @Test
        @DisplayName("7 位 Base62 上界为 62^7 - 1")
        void maxSequenceMatchesPower() {
            long expected = 1;
            for (int i = 0; i < IdSpace.SHORT_CODE_LENGTH; i++) {
                expected *= IdSpace.BASE;
            }
            assertThat(IdSpace.MAX_SEQUENCE).isEqualTo(expected - 1);
        }

        @Test
        @DisplayName("Redis key 初始值 = 首个发号 − 1（INCR 先加后返回）")
        void redisInitValueIsFirstMinusOne() {
            assertThat(IdSpace.REDIS_KEY_INIT_VALUE).isEqualTo(IdSpace.FIRST_SEQUENCE - 1);
            assertThat(IdSpace.REDIS_KEY_INIT_VALUE + 1).isEqualTo(IdSpace.FIRST_SEQUENCE);
        }

        @Test
        @DisplayName("⭐ 区间不重叠：数据库号段首个发出的号 = Redis 首个 + Redis 区间长度")
        void rangesDoNotOverlap() {
            // 号段发出的是 max_id + 1
            long segmentFirstIssued = IdSpace.SEGMENT_MAX_ID_INIT_VALUE + 1;
            assertThat(segmentFirstIssued).isEqualTo(IdSpace.SEGMENT_FIRST_SEQUENCE);
            assertThat(IdSpace.SEGMENT_FIRST_SEQUENCE).isEqualTo(IdSpace.REDIS_RANGE_END);
            // Redis 区间是 [FIRST_SEQUENCE, REDIS_RANGE_END)，最后一个号是 REDIS_RANGE_END - 1
            assertThat(IdSpace.FIRST_SEQUENCE).isLessThan(IdSpace.REDIS_RANGE_END);
            assertThat(IdSpace.SEGMENT_FIRST_SEQUENCE)
                    .isGreaterThan(IdSpace.REDIS_RANGE_END - 1);
        }

        @Test
        @DisplayName("数据库号段初始 max_id 不是 100000（那会让区间重叠，是重复发号的成因）")
        void segmentInitIsNotNaiveValue() {
            assertThat(IdSpace.SEGMENT_MAX_ID_INIT_VALUE).isNotEqualTo(100_000L);
            assertThat(IdSpace.SEGMENT_MAX_ID_INIT_VALUE).isEqualTo(999_999_999L);
        }

        @Test
        @DisplayName("Redis 区间容量 = 9.999 亿个号；按日创建 10 万条可用约 27.4 年")
        void capacityIsSufficient() {
            long capacity = IdSpace.REDIS_RANGE_END - IdSpace.FIRST_SEQUENCE;

            // 容量：1_000_000_000 − 100_000
            assertThat(capacity).isEqualTo(999_900_000L);

            // 年数推导（唯一一处口径，避免测试与文档各算各的）：
            //   容量 / 日创建量 / 每年天数 = 999_900_000 / 100_000 / 365 = 27.39 年
            // ⚠️ 曾误写为「约 74 年」——那是把容量当成 27 亿左右算出来的，
            //    或把日创建量当成约 3.7 万条。74 年对应的日创建量是 37,020 条，
            //    与「日创建 10 万条」的假设不符。以本断言为准。
            long days = capacity / 100_000L;
            assertThat(days).isEqualTo(9_999L);
            assertThat(days / 365L).isEqualTo(27L);
            assertThat(days / 365.0).isBetween(27.0, 28.0);

            // 口径留痕：27 年对练习项目已远超需求，不构成缩短码长度的理由
            assertThat(days / 365.0).isGreaterThan(25.0);
        }

        @Test
        @DisplayName("数据库号段区间约 3.52 万亿，约是 Redis 区间的 3521 倍")
        void segmentRangeIsMuchLarger() {
            long segmentCapacity = IdSpace.MAX_SEQUENCE - IdSpace.SEGMENT_FIRST_SEQUENCE + 1;
            long redisCapacity = IdSpace.REDIS_RANGE_END - IdSpace.FIRST_SEQUENCE;

            assertThat(segmentCapacity).isEqualTo(3_520_614_606_208L);
            assertThat(segmentCapacity / redisCapacity).isGreaterThan(3_000L);
        }
    }
}
