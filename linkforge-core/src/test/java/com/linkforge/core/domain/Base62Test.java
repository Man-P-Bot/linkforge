package com.linkforge.core.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
        @DisplayName("与 JDK Long.toString(n, 62) 的差异点被显式钉住：Base62.encode(10)==\"A\" 而 JDK 给 \"a\"")
        void differsFromJdkRadixConversion() {
            assertThat(Long.toString(10, 62)).isEqualTo("a");
            assertThat(Base62.encode(10)).isEqualTo("A").isNotEqualTo(Long.toString(10, 62));
        }
    }

    @Nested
    @DisplayName("encodePadded：左补零到固定 7 位")
    class EncodePadded {

        @ParameterizedTest(name = "序号 {0} 补零后 -> \"{1}\"")
        @CsvSource({
                "0,        0000000",
                "1,        0000001",
                "9,        0000009",
                "10,       000000A",
                "35,       000000Z",
                "36,       000000a",
                "61,       000000z",
                "62,       0000010",
                "63,       0000011",
                "3843,     00000zz",
                "3844,     0000100",
                "99999,    0000Q0t",
                "100000,   0000Q0u",
                "100001,   0000Q0v",
                "1000000,  0004C92",
                "999999999,015ftgF",
                "1000000000,015ftgG",
                "3521614606207,zzzzzzz",
        })
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
        @DisplayName("两个区间的容量都远大于练习场景需求")
        void capacityIsSufficient() {
            long redisCapacity = IdSpace.REDIS_RANGE_END - IdSpace.FIRST_SEQUENCE;
            // 按日创建 10 万条估算，可用年数
            long years = redisCapacity / 100_000L / 365L;
            assertThat(redisCapacity).isEqualTo(999_900_000L);
            assertThat(years).isGreaterThan(70L);
            assertThat(IdSpace.MAX_SEQUENCE - IdSpace.SEGMENT_FIRST_SEQUENCE).isGreaterThan(redisCapacity);
        }
    }
}
