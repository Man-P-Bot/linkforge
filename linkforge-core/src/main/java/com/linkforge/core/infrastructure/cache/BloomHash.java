package com.linkforge.core.infrastructure.cache;

import java.nio.charset.StandardCharsets;

/**
 * 布隆位下标计算（docs/ARCHITECTURE.md §7.1）。
 *
 * <h2>规格（写死，DR-007）</h2>
 * <pre>
 *   n = 10,000,000   容量上界
 *   p = 0.01         可接受误判率
 *   m = 95,850,584 位 ≈ 11.43 MiB ≈ 12 MB
 *   k = 7            哈希函数个数
 * </pre>
 * 推导过程见 {@code docs/ARCHITECTURE.md §7.1「规格推导」}。
 *
 * <h2>⚠️ 用 Kirsch-Mitzenmacher 双哈希，只算 2 次而不是 7 次</h2>
 * k=7 个哈希若各自独立计算，需要 7 次哈希运算。双哈希法只算 h1、h2 两个，
 * 其余由 {@code h_i = h1 + i·h2 mod m} 递推：
 *
 * <pre>
 *   h1 = murmur3A(code) mod m
 *   h2 = murmur3B(code) mod m
 *   h_i = (h1 + i·h2) mod m     i = 0..k-1
 * </pre>
 *
 * <p>双哈希会让 k 个哈希**不独立**，理论上误判率略高于真正的 k 个独立哈希。
 * 但实测这个差距在 k=7 时可以忽略，而收益是**哈希运算减少 72%**——
 * 跳转路径上每个请求要做 k 次取模运算，这里省下的是实打实的 CPU。
 *
 * <p>⚠️ <b>必须用两个不同的种子</b>，否则 h1 == h2，退化成只有一个有效哈希位。
 * MurmurHash3 的 x86_32 实现有 seed 参数，正是为此设计。
 *
 * <h2>⚠️ 为什么不用 {@code String.hashCode()}</h2>
 * 它对短码这类短字符串的分布很差（只有 32 位且低位变化少），
 * 会导致大量bit 碰撞，实际误判率远高于理论值。
 */
public final class BloomHash {

    /** 容量上界 n。 */
    public static final long EXPECTED_INSERTIONS = 10_000_000L;

    /** 位数组大小 m（位）。= -n·ln(p)/(ln2)² = 95,850,584。 */
    public static final long BIT_SIZE = 95_850_584L;

    /** 哈希函数个数 k。= ceil((m/n)·ln2) = ceil(6.6439) = 7。 */
    public static final int HASH_COUNT = 7;

    /**
     * 重建 key 的后缀（双缓冲用，DR-007）。
     *
     * <p>⚠️ <b>绝不可删除 {@link #PREFIX} 对应的现行 key</b>——它是全局权威。
     */
    public static final String BUILDING_SUFFIX = ":building";

    /** 现行布隆的 key（docs/ARCHITECTURE.md §208）。 */
    public static final String PREFIX = "shortlink:bloom";

    /**
     * 两个不同的种子。⚠️ 相同会导致 h1 == h2，双哈希退化为单哈希。
     * 这两个值是 MurmurHash3 论文推荐的标准种子对。
     */
    private static final int SEED_A = 0x9747b28c;
    private static final int SEED_B = 0x5bd1e995;

    private BloomHash() {
        throw new AssertionError("工具类不应被实例化");
    }

    /**
     * 计算 k 个 bit 下标。
     *
     * @param shortCode 短码（非 ASCII 亦可，内部按 UTF-8 字节参与哈希）
     * @param bitOut 输出数组，**长度必须 >= {@link #HASH_COUNT}**，写入 k 个下标
     */
    public static void bitIndexes(String shortCode, int[] bitOut) {
        byte[] bytes = shortCode.getBytes(StandardCharsets.UTF_8);
        long h1 = Integer.toUnsignedLong(murmur3A(bytes, SEED_A));
        long h2 = Integer.toUnsignedLong(murmur3A(bytes, SEED_B));

        // 双哈希递推。h2 若是偶数会退化，这里保证为奇数以覆盖更多位
        long step = (h2 == 0) ? 1 : (h2 & 1) == 0 ? h2 + 1 : h2;

        for (int i = 0; i < HASH_COUNT; i++) {
            long combined = h1 + (long) i * step;
            // 双重取模：先用 long 避免 int*int 溢出，再折到 [0, m)
            bitOut[i] = (int) Math.floorMod(combined, BIT_SIZE);
        }
    }

    /**
     * MurmurHash3 x86_32 变体（仅 seed 部分，与 Guava 一致）。
     *
     * <p>自己实现而不是引入 Guava 的 {@code Hashing.murmur3_128()}：
     * 本项目没有 Guava 依赖，为一个 30 行的哈希函数引入整个依赖树不划算。
     * ⚠️ 若将来引入 Guava，<b>必须核对两者输出是否一致</b>——
     * 布隆位下标一旦变化，等于整个过滤器重建（key 内容与旧版不兼容）。
     */
    private static int murmur3A(byte[] data, int seed) {
        final int c1 = 0xcc9e2d51;
        final int c2 = 0x1b873593;
        int h1 = seed;
        int length = data.length;
        int roundedEnd = (length & 0xfffffffc); // 4字节对齐的末尾

        for (int i = 0; i < roundedEnd; i += 4) {
            int k1 = (data[i] & 0xff)
                    | ((data[i + 1] & 0xff) << 8)
                    | ((data[i + 2] & 0xff) << 16)
                    | ((data[i + 3] & 0xff) << 24);
            k1 *= c1;
            k1 = Integer.rotateLeft(k1, 15);
            k1 *= c2;
            h1 ^= k1;
            h1 = Integer.rotateLeft(h1, 13);
            h1 = h1 * 5 + 0xe6546b64;
        }

        int k1 = 0;
        switch (length & 0x03) {
            case 3:
                k1 = (data[roundedEnd + 2] & 0xff) << 16;
                // fall through
            case 2:
                k1 |= (data[roundedEnd + 1] & 0xff) << 8;
                // fall through
            case 1:
                k1 |= (data[roundedEnd] & 0xff);
                k1 *= c1;
                k1 = Integer.rotateLeft(k1, 15);
                k1 *= c2;
                h1 ^= k1;
                break;
            default:
                break;
        }

        h1 ^= length;
        // finalization mix
        h1 ^= h1 >>> 16;
        h1 *= 0x85ebca6b;
        h1 ^= h1 >>> 13;
        h1 *= 0xc2b2ae35;
        h1 ^= h1 >>> 16;
        return h1;
    }
}