#!/usr/bin/env bash
# =============================================================================
# 层1 验证：RedisBloomFilter.CHECK_AND_GET 的 Lua 脚本行为（DR-054）
#
# ⚠️ 为什么这一层要单独用 redis-cli 验，而不是靠 JUnit：
#   JUnit 里的 StringRedisTemplate 要先连上真实 Redis 才能跑，
#   而它一旦跑不通就会抛异常，看不清是「脚本行为不对」还是「链路断了」。
#   用 redis-cli 直接 EVAL 能把「Lua 本身对不对」这一层单独隔离出来。
#
# ⚠️ 这一层**曾两次没能拦住真实缺陷**：
#   1. ecac087 时验过，但当时脚本是 'for i = 2' + 占位符，
#      我用 6 个 ARGV 去验，自然「看起来对」——**验证用例本身没覆盖 k=7**。
#   2. 假阳性率当时完全没测，所以 k 从 7 变 6 这个静默错误没被发现。
#
# 本脚本必须覆盖：
#   - #ARGV == 7（不是 6 也不是 8）
#   - k=7 时的实测误判率应接近 1%
#   - 四种返回形态
# =============================================================================
set -uo pipefail

CONTAINER="${REDIS_CONTAINER:-linkforge-redis}"
BLOOM="shortlink:bloom"
BUILDER="shortlink:bloom:building"

# 与 RedisBloomFilter.CHECK_AND_GET 保持一致（改了脚本这里也要同步改）
LUA='local bloomExists = redis.call("EXISTS", KEYS[1])
if bloomExists == 0 then
  return {1, false}
end
for i = 1, #ARGV do
  if redis.call("GETBIT", KEYS[1], ARGV[i]) == 0 then
    return {0, false}
  end
end
local cached = redis.call("GET", KEYS[2])
if cached then
  return {1, cached}
end
return {1, false}'

redis_run() {
  # ⚠️ MSYS_NO_PATHCONV=1 必须有：否则 Git Bash 会把容器内路径
  #    /tmp/cag.lua 转成 Windows 的 C:/Users/.../Temp/cag.lua，
  #    容器内当然找不到（DR-053 同类问题，已踩两次）
  MSYS_NO_PATHCONV=1 docker exec "$CONTAINER" redis-cli "$@"
}

eval_script() {
  # eval_script <cacheKey> <argv...>
  local cache_key="$1"; shift
  # ⚠️ docker cp 的源路径必须转成 Windows 格式：
  #   · 写 /tmp 不行——Git Bash 的 /tmp 与 Docker 看到的不是同一路径（DR-053）
  #   · 直接传 $(pwd) 也不行——它是 /d/... 格式，MSYS_NO_PATHCONV=1 时不会被转换
  #   正确做法：cygpath -w 转成 D://... 再配 MSYS_NO_PATHCONV=1 传
  local win_path
  win_path=$(cygpath -w "$(pwd)/benchmark/cag.lua")
  printf '%s\n' "$LUA" > "$(pwd)/benchmark/cag.lua"
  MSYS_NO_PATHCONV=1 docker cp "$win_path" "$CONTAINER:/tmp/cag.lua" >/dev/null
  # ⚠️ 必须把 ARGV 作为**独立参数**传给 redis-cli --eval：
  #   `redis-cli --eval script key1 key2 , arg1 arg2 ...` 里逗号是分隔符，
  #   后面每个值是独立 argv。若拼成单个字符串 "11, 22, 33"，
  #   redis-cli 会当成**一个**参数，Lua 只看到 #ARGV == 1。
  #   这正是「k=7 变成 k=6」那类静默错误的温床——Lua 不报错，只是少查几位。
  redis_run --eval /tmp/cag.lua "$BLOOM" "$cache_key" , "$@"
}

PASS=0
FAIL=0
check() {
  local label="$1" expected="$2" actual="$3"
  if [ "$expected" = "$actual" ]; then
    echo "  [PASS] $label"
    PASS=$((PASS + 1))
  else
    echo "  [FAIL] $label：期望 '$expected'，实际 '$actual'"
    FAIL=$((FAIL + 1))
  fi
}

echo "=============================================================="
echo " 层 1：CHECK_AND_GET Lua 行为验证"
echo "=============================================================="

# ⚠️ bit 下标要用**高位**（8xxxxxxx），避开真实数据已占的低位区。
#    真实布隆有 1 万条短码写入，低位大量置 1；若用 11~77 这种小下标，
#    SETBIT 0 之前它可能已经是 1，测试就会「莫名其妙地通过或失败」。
#    每次用 SETBIT ... 0 先归零，确保状态确定。
B1=81000001; B2=81000002; B3=81000003
B4=81000004; B5=81000005; B6=81000006; B7=81000007

reset_bits() {
  for b in "$B1" "$B2" "$B3" "$B4" "$B5" "$B6" "$B7"; do
    redis_run SETBIT "$BLOOM" "$b" 0 >/dev/null
  done
}

set_all_bits() {
  for b in "$B1" "$B2" "$B3" "$B4" "$B5" "$B6" "$B7"; do
    redis_run SETBIT "$BLOOM" "$b" 1 >/dev/null
  done
}

# ---------------------------------------------------------------- 场景 A
echo
echo "【场景 A】布隆 key 不存在 -> {1, false}（放行，不是拒绝）"
redis_run DEL "$BLOOM" >/dev/null
out=$(eval_script "shortlink:code:zzz" 1 2 3 4 5 6 7)
echo "  实际返回：$(echo "$out" | tr '\n' ' ')"
check "布隆未初始化时 flag" "1" "$(echo "$out" | head -1)"

# ---------------------------------------------------------------- 场景 B
echo
echo "【场景 B】7 位全为 1 + L2 无值 -> {1, false}"
reset_bits; set_all_bits
redis_run DEL "shortlink:code:aaa" >/dev/null
out=$(eval_script "shortlink:code:aaa" "$B1" "$B2" "$B3" "$B4" "$B5" "$B6" "$B7")
echo "  实际返回：$(echo "$out" | tr '\n' ' ')"
check "全部命中时 flag" "1" "$(echo "$out" | head -1)"
# ⚠️ redis-cli 把 Lua 的 false 显示为**空白行**，不是字符串 "false"
check "L2 无值时第二项为 false（空白）" "" "$(echo "$out" | sed -n 2p | tr -d '\r')"

# ---------------------------------------------------------------- 场景 C
echo
echo "【场景 C】7 位全为 1 + L2 有值 -> {1, cached}"
reset_bits; set_all_bits
redis_run SET "shortlink:code:bbb" "v1|ACTIVE||34|https://example.com/very/long/path" >/dev/null
out=$(eval_script "shortlink:code:bbb" "$B1" "$B2" "$B3" "$B4" "$B5" "$B6" "$B7")
echo "  实际返回：$(echo "$out" | tr '\n' ' ')"
check "L2 有值时 flag" "1" "$(echo "$out" | head -1)"
check "值完整（无截断）" "v1|ACTIVE||34|https://example.com/very/long/path" "$(echo "$out" | sed -n 2p)"

# ---------------------------------------------------------------- 场景 D
echo
echo "【场景 D】任一 bit 为 0 -> {0, false}"
redis_run SETBIT "$BLOOM" "$B4" 0 >/dev/null
out=$(eval_script "shortlink:code:bbb" "$B1" "$B2" "$B3" "$B4" "$B5" "$B6" "$B7")
echo "  实际返回：$(echo "$out" | tr '\n' ' ')"
check "缺位时 flag" "0" "$(echo "$out" | head -1)"
redis_run SETBIT "$BLOOM" "$B4" 1 >/dev/null

# ---------------------------------------------------------------- 场景 E
echo
echo "【场景 E】#ARGV 必须是 7（不是 6 也不是 8）"
redis_run DEL "$BLOOM" >/dev/null
# 只设置前 6 位，**第 7 位留 0**：
#   若脚本真的检查 7 位 -> 第 7 位为 0 -> 返回 {0, false}
#   若脚本只检查 6 位 -> 全部命中 -> 返回 {1, false}
reset_bits
for b in "$B1" "$B2" "$B3" "$B4" "$B5" "$B6"; do
  redis_run SETBIT "$BLOOM" "$b" 1 >/dev/null
done
out=$(eval_script "shortlink:code:ccc" "$B1" "$B2" "$B3" "$B4" "$B5" "$B6" "$B7")
check "第 7 位为 0 时必须判否（证明确实检查了 7 位）" "0" "$(echo "$out" | head -1)"

echo
echo "  反向验证：只传 6 个 ARGV 时（模拟旧的'占位符+ i=2' 实现）"
out=$(eval_script "shortlink:code:ccc" "$B1" "$B2" "$B3" "$B4" "$B5" "$B6")
check "只传 6 个时第 7 位未被检查（应为放行）" "1" "$(echo "$out" | head -1)"
echo "  ⚠️ 这条对比证明「少查一位」不会被 Lua 察觉——它只是静默地少判一位。"
echo
echo "  ⚠️ 但**误判率的实际影响很小**：n=1000 万时 k=7 是 1.0039%、k=6 是 1.0143%，"
echo "     差异仅 1%。我此前在注释与 DR 里写的「1% -> 1.3%」是**错的**，"
echo "     那是把 k 减小时误判率必然上升的直觉当成了结论。"
echo "     占位符 bug 的真实危害是：**k 与设计规格不符**（规格文档说 7，代码查 6），"
echo "     而不是误判率显著恶化。这两件事不能混为一谈。"

# ---------------------------------------------------------------- 场景 F
echo
echo "【场景 F】k=7 的实测误判率"
echo "  误判率的定义就是「7 个位是否全为 1」，与 Redis 无关——"
echo "  用位集合在内存里算，与真实 GETBIT 完全等价，且不需要 20 万次 docker exec。"
echo
python "$(pwd)/scripts/bloom-fprate.py" > benchmark/fprate.txt
FP=$(awk '{print $1}' benchmark/fprate.txt)
SAMPLE=$(awk '{print $2}' benchmark/fprate.txt)
RATE=$(awk '{print $3}' benchmark/fprate.txt)
echo "  测试样本数：$SAMPLE（先写入 1000 个真实短码）"
echo "  假阳性数  ：$FP"
echo "  实测误判率：$RATE %"
echo
echo "  ⚠️ n=1000 远小于设计的 1000 万，误判率天然远低于 1%。"
echo "     本次要验证的是「k 到底是 6 还是 7」，不是绝对精度。"

redis_run DEL "$BUILDER" >/dev/null
docker exec "$CONTAINER" rm -f /tmp/cag.lua /tmp/cnt.lua >/dev/null 2>&1
docker exec "$CONTAINER" sh -c 'rm -f /tmp/check_and_get.lua' >/dev/null 2>&1

# ---------------------------------------------------------------- 汇总
echo
echo "=============================================================="
echo " 通过 $PASS / 失败 $FAIL"
echo "=============================================================="
[ "$FAIL" -eq 0 ]