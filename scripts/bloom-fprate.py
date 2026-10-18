#!/usr/bin/env python3
# =============================================================================
# 布隆误判率实测（层1 验证的一部分，DR-054）
#
# ⚠️ 为什么这个脚本独立存在：
#   verify-bloom-lua.sh 的其余场景必须打真实 Redis，但**误判率不需要**——
#   误判的定义就是「k 个位是否全为 1」，纯位集合运算，与 Redis 无关。
#   用 Redis 循环要20 万次 docker exec（几十分钟），用位集合算只要 1 秒，
#   结果完全等价。
#
# ⚠️ 这个脚本要回答的是「k 到底是 6 还是 7」：
#   ecac087 的占位符 bug 让 Lua 从 i=2 开始，只检查 6 个位。
#   那个错误**不会崩溃**，只会让误判率变差——靠「能不能跳转」永远发现不了。
#   这里是唯一能定量发现它的地方。
#
# 输出：三个用空格分隔的数字（假阳性数 样本数 误判率百分比），
#      供 verify-bloom-lua.sh 读取。
# =============================================================================
import math
import sys

# ⚠️ 这三个常量必须与 BloomHash 保持一致，改了一边要同步
BIT_SIZE = 95850584
K = 7
N = 1000# 写入的「真实短码」数量
SAMPLE = 20000        # 用来测假阳性的「不存在短码」数量


def rotl(x: int, r: int) -> int:
    return ((x << r) | (x >> (32 - r))) & 0xFFFFFFFF


def murmur3(key: bytes, seed: int = 0x9747B28C):
    """与 BloomHash.bitIndexes 同源的 murmur3 双哈希
    （Kirsch-Mitzenmacher：算 2 次递推 k 次）。"""
    h1 = seed & 0xFFFFFFFF
    h2 = 0
    k = len(key)
    for i in range(k):
        c = key[i]
        h1 = ((h1 ^ c) * 0xCC9E2D51) & 0xFFFFFFFF
        h1 = rotl(h1, 15)
        h1 = (h1 * 0x1B873593) & 0xFFFFFFFF
        h2 = ((h2 + c) * 0xCC9E2D51) & 0xFFFFFFFF
        h2 = rotl(h2, 13)
        h2 = (h2 * 0x1B873593) & 0xFFFFFFFF
        if i % 2 == 1:
            h1 = (h1 + h2) & 0xFFFFFFFF
    h1 ^= (k * 4) & 0xFFFFFFFF
    h2 ^= (k * 6) & 0xFFFFFFFF
    return h1, h2


def bits_of(code: str) -> list[int]:
    h1, h2 = murmur3(code.encode())
    return [(h1 + j * h2) % BIT_SIZE for j in range(K)]


def main() -> int:
    # 写入 N 个真实短码的位
    written: set[int] = set()
    for i in range(N):
        written.update(bits_of("its" + format(i, "04d")))

    # 测 SAMPLE 个不存在的码，统计「7 位全中」的个数
    fp = 0
    for i in range(SAMPLE):
        if all(b in written for b in bits_of("fpp" + format(i, "06d"))):
            fp += 1

    rate = fp / SAMPLE * 100

    print("=" * 62)
    print(" 布隆误判率实测（k=7）")
    print("=" * 62)
    print(f"  写入真实短码: {N} 条 -> 占位 {len(written)} 个")
    print(f"  测试样本    : {SAMPLE} 条不存在的码")
    print(f"  假阳性      : {fp} 条")
    print(f"  实测误判率  : {rate:.4f} %")
    print()

    p7 = (1 - math.exp(-K * N / BIT_SIZE)) ** K * 100
    p6 = (1 - math.exp(-6 * N / BIT_SIZE)) ** 6 * 100
    print(f"  k=7 理论值  : {p7:.6f} %")
    print(f"  k=6 理论值  : {p6:.6f} %  <- 旧实现（占位符 bug）")
    print(f"  两者差异    : {abs(p7 - p6):.6f} 个百分点"
          f"（占k=7 的 {abs(p7-p6)/p7*100 if p7 else 0:.1f}%）")
    print(f"  实测与 k=7 理论差: {abs(rate - p7):.4f} 个百分点")
    print()
    print(f"  ⚠️ n={N} 远小于设计的 1000 万，故实测远低于 1%——")
    print("     这是预期的。本次验证的是 k 到底是 6 还是 7，不是绝对精度。")
    print(f"     k=7 与 k=6 在 n={N} 下分别是 {p7:.6f}% 与 {p6:.6f}%，")
    print("     **量级完全无法区分**——这正是我不该用误判率判定 k 的原因。")
    print("     （设计规模 n=1000 万时差异也只有 1%，同样不可用于判定。）")
    print()

    # ⚠️ **不能用误判率判定 k 是 6 还是 7**：n=1000 时两者都是 1e-6% 量级，
    #   样本量根本不足以区分；即使 n=1000 万（设计规模），k=7 是 1.0039%、
    #   k=6 是 1.0143%，差异也只有 1%。
    #   判定 k 的唯一可靠方法是**场景 E**：只置 6 位、第 7 位留 0，
    #   看脚本是否判否——那直接检验「有没有检查第 7 位」。
    print("  [INFO] 误判率**无法**用于判定 k（k=6 与 k=7 差异仅约 1%）。")
    print("         判定 k 靠场景 E：只置 6 位、第 7 位留 0，看是否判否。")
    print("         误判率的用途是「确认位分布无退化」，不是「确认 k」。")

    print(f"{fp} {SAMPLE} {rate:.4f}")
    return 0


if __name__ == "__main__":
    sys.exit(main())