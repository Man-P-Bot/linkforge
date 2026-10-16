package com.linkforge.core.infrastructure.cache;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 布隆规格与哈希（docs/ARCHITECTURE.md §7.1）。
 *
 * <h2>⚠️ 这些测试的价值在于「锁住规格」</h2>
 * 布隆的四个参数互相绑定：改了 m 却不改 k，实际误判率会悄悄偏离目标 p，
 * 而功能测试（写入后能查到）<b>依然全绿</b>——因为误判率升高只会让更多请求
 * 多查一次库，不会报错。
 * 因此必须把规格本身写成断言。
 */
class BloomHashTest {

    @Test
    @DisplayName("⭐ 规格：n=1000万 / m=95,850,584 位 / k=7")
    void specMatchesArchitectureDoc() {
        assertThat(BloomHash.EXPECTED_INSERTIONS).isEqualTo(10_000_000L);
        // ⚠️ 这个数字是 -n·ln(0.01)/(ln2)² = 95,850,584。
        // 曾把它错写成 1,193,000（对应 n≈12.4万，差 80 倍），教训见 ARCHITECTURE §7.1
        assertThat(BloomHash.BIT_SIZE).isEqualTo(95_850_584L);
        assertThat(BloomHash.HASH_COUNT).isEqualTo(7);
    }

    @Test
    @DisplayName("⭐ 位数组大小落在 11~12 MB（95,850,584 / 8 字节）")
    void bitSizeTranslatesToAbout12Mb() {
        double bytes = BloomHash.BIT_SIZE / 8.0;
        double mib = bytes / 1024 / 1024;

        assertThat(mib).isGreaterThan(11.0).isLessThan(12.0);
    }

    @Test
    @DisplayName("⭐ k 是由 m/n·ln2 向上取整得来的，取 6 会让实际 p 超过目标")
    void kIsCeilingOfFormula() {
        double exact = (BloomHash.BIT_SIZE / (double) BloomHash.EXPECTED_INSERTIONS)
                * Math.log(2);

        assertThat(exact).isGreaterThan(6.0).isLessThan(7.0);
        // 实现取的是向上取整
        assertThat(BloomHash.HASH_COUNT).isEqualTo((int) Math.ceil(exact));
    }

    @Test
    @DisplayName("⭐ 每个下标都落在 [0, m) 内")
    void bitIndexesInRange() {
        int[] out = new int[BloomHash.HASH_COUNT];

        BloomHash.bitIndexes("0Q1A2b3", out);

        assertThat(out).hasSize(BloomHash.HASH_COUNT);
        for (int bit : out) {
            assertThat(bit).isGreaterThanOrEqualTo(0)
                    .isLessThan((int) Math.min(BloomHash.BIT_SIZE, Integer.MAX_VALUE));
        }
    }

    @Test
    @DisplayName("⭐ 同一短码的下标**稳定**（多次调用一致）")
    void indexesAreDeterministic() {
        int[] a = new int[BloomHash.HASH_COUNT];
        int[] b = new int[BloomHash.HASH_COUNT];

        BloomHash.bitIndexes("0Q1A2b3", a);
        BloomHash.bitIndexes("0Q1A2b3", b);

        assertThat(a).containsExactly(b);
    }

    @Test
    @DisplayName("⭐ 双哈希不退化：7 个下标里至少有 5 个互不相同")
    void doubleHashDoesNotDegenerate() {
        int[] out = new int[BloomHash.HASH_COUNT];

        BloomHash.bitIndexes("0Q1A2b3", out);

        long distinct = java.util.Arrays.stream(out).distinct().count();
        // 若 h1 == h2（种子写错成同一个），7 个下标会塌缩成 1~2 个
        assertThat(distinct).isGreaterThanOrEqualTo(5);
    }

    @Test
    @DisplayName("不同短码的下标不同（基本均匀性的粗略验证）")
    void differentCodesDifferentIndexes() {
        int[] a = new int[BloomHash.HASH_COUNT];
        int[] b = new int[BloomHash.HASH_COUNT];

        BloomHash.bitIndexes("0Q1A2b3", a);
        BloomHash.bitIndexes("0Q1A2b4", b);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    @DisplayName("大小写不同的短码得到不同下标（Base62 区分大小写，DR-040）")
    void caseMatters() {
        int[] upper = new int[BloomHash.HASH_COUNT];
        int[] lower = new int[BloomHash.HASH_COUNT];

        BloomHash.bitIndexes("0Q1A2B3", upper);
        BloomHash.bitIndexes("0q1a2b3", lower);

        // ⚠️ 与 DR-040 同源：数据库层 short_code 已是 ascii_bin（大小写敏感），
        // 若哈希层不区分大小写，0Q1A2B3 与 0q1a2b3 会共用同一批位——
        // 两者是库里**不同的两条记录**，却被当成同一条
        assertThat(upper).isNotEqualTo(lower);
    }

    @Test
    @DisplayName("7 位下标在 1 万条随机短码上的分布不集中（粗略均匀性）")
    void distributionIsNotDegenerate() {
        // ⚠️ 这不是严格的均匀性检验，只是防住「哈希实现有明显缺陷」这类问题。
        // 严格检验应跑 n=1000万次算实测误判率，第 5 周性能测试时做。
        int[] out = new int[BloomHash.HASH_COUNT];
        java.util.Set<Long> distinctBits = new java.util.HashSet<>();

        for (int i = 0; i < 10_000; i++) {
            BloomHash.bitIndexes("0Q1A" + String.format("%03d", i), out);
            for (int bit : out) {
                distinctBits.add((long) bit);
            }
        }

        // 1 万条 × 7 位 = 7 万次写入，若分布合理应有数万个不同位
        // （明显退化时会集中到极少数位上）
        assertThat(distinctBits.size()).isGreaterThan(5_000);
    }

    @Test
    @DisplayName("重建 key 带 :building 后缀，现行 key 无后缀")
    void buildingSuffixIsDistinct() {
        assertThat(BloomHash.PREFIX).isEqualTo("shortlink:bloom");
        assertThat(BloomHash.PREFIX + BloomHash.BUILDING_SUFFIX)
                .isEqualTo("shortlink:bloom:building")
                .isNotEqualTo(BloomHash.PREFIX);
    }
}