#!/usr/bin/env bash
# =============================================================================
# LinkForge 长稳压测（第 5 周阶段 3）
#
# 目的：**不是**测 QPS（那是阶段 4 的终极压测），而是给应用**持续的低强度流量**，
#       让长稳测试有真实的负载 —— 没有流量的话，Metaspace 曲线是平的，
#       什么都证明不了。
#
# ⚠️ 为什么用 `-t2 -c10` 而不是压测基准的 `-t4 -c100`：
#   8 小时 × 100 并发会把机器烤干（而且挤占 Prometheus 的采集资源），
#   长稳测试要的是「**持续有流量但不极端**」。
#   10 个连接足以让 L1/L2/布隆/统计队列全部参与工作。
#
# ⚠️ 为什么必须轮换短码（用 wrk-redirect.lua）：
#   全部打同一个短码 → 100% L1 命中 → 数据库完全不参与 →
#   测的不是真实负载。而轮换短码能让 L1 miss → L2 → DB 全链路都跑。
#
# 用法：
#   ./scripts/longrun-load.sh <时长> [并发数] [线程数]
#   例：./scripts/longrun-load.sh 8h 10 2
# =============================================================================
set -uo pipefail

DURATION="${1:-8h}"
CONNECTIONS="${2:-10}"
THREADS="${3:-2}"
NETWORK="${DOCKER_NETWORK:-linkforge_default}"
CODE_LIST="${CODE_LIST:-./benchmark/results/codes.txt}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# ⚠️ 语料必须存在且非空，否则 wrk 脚本会 assert 失败退出。
if [ ! -s "$CODE_LIST" ]; then
    echo "❌ 语料不存在或为空：$CODE_LIST"
    echo "   先跑：bash scripts/bench-create.sh 10000 20 container"
    exit 1
fi

# ⚠️ MSYS_NO_PATHCONV=1：Git Bash 会把 /scripts/... 改写成 Windows 路径，
#    容器里当然找不到。必须关掉路径转换。
# ⚠️ 路径要用 Windows 格式（pwd -W）—— 相对路径会被 Docker 当成无名卷。
SCRIPT_W="$(cd "$SCRIPT_DIR" && pwd -W)"
CODE_W="$(cd "$(dirname "$CODE_LIST")" && pwd -W)/$(basename "$CODE_LIST")"

echo "════════════════════════════════════════════════════"
echo " LinkForge 长稳压测"
echo "   时长     : $DURATION"
echo "   并发/线程: $CONNECTIONS / $THREADS"
echo "   语料     : $CODE_LIST ($(wc -l < "$CODE_LIST") 条)"
echo "   目标     : http://app:8080（compose 网络内服务名）"
echo "════════════════════════════════════════════════════"
echo ""
echo "  手动停止（Ctrl+C 也会自动执行）："
echo "    docker rm -f linkforge-longrun-wrk"
echo "  查看进度："
echo "    docker logs -f linkforge-longrun-wrk"
echo ""

# ⚠️ 兜底清理：Ctrl+C / Ctrl+\ / 脚本被 kill 时，
#    容器**不会**因为 `--rm` 而自动删除（--rm 只在容器**正常退出**时生效）。
#    不清理的话，它会继续吃 CPU 直到 8 小时结束 —— 压测已经停了，流量还在打，
#    这会污染后续的所有测量。
cleanup() {
    echo ""
    echo "==> 清理长稳压测容器"
    docker rm -f linkforge-longrun-wrk >/dev/null 2>&1 || true
    echo "==> 已清理"
}
trap cleanup EXIT INT TERM

# ⚠️ `--rm` 让容器在**正常退出**时自动删除（8 小时跑完的场景）。
#    异常中断（Ctrl+C）由上面的 trap 兜底。
MSYS_NO_PATHCONV=1 docker run --rm --network "$NETWORK" \
    --name linkforge-longrun-wrk \
    -v "${SCRIPT_W}:/scripts:ro" \
    -v "${CODE_W}:/codes/codes.txt:ro" \
    -e CODE_LIST=/codes/codes.txt \
    williamyeh/wrk \
    -t"$THREADS" -c"$CONNECTIONS" -d"$DURATION" --latency \
    -s /scripts/wrk-redirect.lua \
    http://app:8080
