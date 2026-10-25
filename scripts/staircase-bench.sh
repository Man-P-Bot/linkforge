#!/usr/bin/env bash
# =============================================================================
# 阶梯加压（组 C）—— 找**拐点**，不刷 QPS
#
# 目的：回答「在哪个压力下开始降级」，而不是「最高能到多少 QPS」。
# ⚠️ **数据不与前三周直接比**（口径不同：并发逐档变化，不是固定 -t4 -c100）。
#    单独成章汇报。
#
# ⚠️⚠️ **为什么不用 wrk 一直压**：
#   长时间高强度压测会**烤干机器**——不仅影响本机，
#   还会让后续的「同配置基线」数据被污染（宿主 CPU 被抢、内存被压）。
#   所以每一档**只跑 30 秒**，且**档间留冷却**让系统回落。
#
# ⚠️ **为什么固定 -c50、只加线程数**：
#   - `-c`（连接数）决定**并发深度**，直接决定后端要处理多少个同时在途的请求；
#   - `-t`（线程数）决定**压测端自己**能并发发起多少请求。
#   如果两个一起加，就分不清「降级是因为并发深度」还是「因为压测端先饱和了」。
#   固定 -c50、只加 -t，是**先榨干压测端**、再让**后端**成为瓶颈的顺序 ——
#   这样测出的拐点才是**后端的真实拐点**。
#
#   ⚠️ 预判：本机 18 核、压测端本身要占 1~2 核，
#      所以 `-t` 加到 8 之后压测端就会先饱和（QPS 不再涨）。
#      届时**拐点由后端给出**（QPS 涨不动且 P99 恶化）。
#
# 用法：
#   bash scripts/staircase-bench.sh                # 默认档位
#   bash scripts/staircase-bench.sh --quick        # 每档 15s（快速探点）
#   CONN=50 DUR=30s bash scripts/staircase-bench.sh
# =============================================================================
set -uo pipefail

# ---------------------------------------------------------------- 参数
CONN="${CONN:-50}"                                   # 固定连接数
DUR="${DUR:-30s}"                                    # 每档时长
COOLDOWN="${COOLDOWN:-15}"                           # 档间冷却秒数
THREAD_STEPS="${THREAD_STEPS:-1 2 3 4 6 8 12}"      # 线程数档位
NETWORK="${DOCKER_NETWORK:-linkforge_default}"
WORK_DIR="${WORK_DIR:-./benchmark/results}"
OUT_DIR="${OUT_DIR:-./benchmark/staircase}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

for arg in "$@"; do
    case "$arg" in
        --quick) DUR="15s"; COOLDOWN="10" ;;
        *) echo "未知参数：$arg"; exit 1 ;;
    esac
done

mkdir -p "$OUT_DIR"

log()  { echo "[$(date +%H:%M:%S)] $*"; }
fail() { echo "❌ $*" >&2; exit 1; }

CODE_LIST="$WORK_DIR/codes.txt"
[ -s "$CODE_LIST" ] || fail "语料不存在或为空：$CODE_LIST（先跑 scripts/bench-create.sh）"
CODE_COUNT="$(wc -l < "$CODE_LIST")"

log "════════════════════════════════════════════════════"
log " 阶梯加压（组 C）—— 找拐点"
log "   连接数   : -c${CONN}（固定）"
log "   每档时长 : ${DUR}"
log "   档间冷却 : ${COOLDOWN}s"
log "   线程档位 : ${THREAD_STEPS}"
log "   语料     : ${CODE_COUNT} 条"
log "════════════════════════════════════════════════════"

# ⚠️ 与 final-bench.sh 同样的保护：长稳压测在跑时不要并行压测
if docker ps --format '{{.Names}}' 2>/dev/null | grep -q "linkforge-longrun-wrk"; then
    fail "检测到长稳压测容器正在运行 —— 两组压测并行会互相污染，请先结束长稳测试"
fi

# 监控容器必须停掉（与 final-bench.sh 同口径）
log "① 停掉 Prometheus 与 Grafana"
docker compose stop prometheus grafana >/dev/null 2>&1
MONITOR_STOPPED=1
restore_monitor() {
    if [ "${MONITOR_STOPPED:-0}" -eq 1 ]; then
        log "⚠️ 恢复 Prometheus 与 Grafana"
        docker compose start prometheus grafana >/dev/null 2>&1 || true
        MONITOR_STOPPED=0
    fi
}
trap restore_monitor EXIT INT TERM

# 重启 app（清 Metaspace 与 GC 状态，保证与组 A 同起点）
log "② 重启 app（清 Metaspace 与 GC 状态）"
docker compose restart app >/dev/null 2>&1
for i in $(seq 1 40); do
    STATUS="$(docker inspect -f '{{.State.Health.Status}}' linkforge-app 2>/dev/null || echo '')"
    [ "$STATUS" = "healthy" ] && break
    sleep 3
done
[ "$STATUS" = "healthy" ] || fail "app 未在 120 秒内 healthy"

# 等布隆重建（只看重启后新增的日志 —— 见 final-bench.sh 的注释）
log "③ 等待布隆全量重建"
BLOOM_OK=0
for i in $(seq 1 60); do
    if docker logs --since 5m linkforge-app 2>/dev/null | grep -q "布隆全量重建完成"; then
        BLOOM_OK=1; break
    fi
    sleep 5
done
[ "$BLOOM_OK" -eq 1 ] && log "   ✅ 布隆已就绪" || log "   ⚠️ 未看到重建日志，继续"

# 预热
log "④ 预热 15s（丢弃结果）"
MSYS_NO_PATHCONV=1 docker run --rm --network "$NETWORK" \
    -v "$(cd "$SCRIPT_DIR" && pwd -W):/scripts:ro" \
    -v "$(cd "$WORK_DIR" && pwd -W)/codes.txt:/codes/codes.txt:ro" \
    -e CODE_LIST=/codes/codes.txt \
    williamyeh/wrk -t2 -c"$CONN" -d15s \
    -s /scripts/wrk-redirect.lua http://app:8080 >/dev/null 2>&1

# 逐档压测
log "⑤ 逐档加压"
RESULTS="$OUT_DIR/results.csv"
echo "threads,connections,duration,qps,p50,p90,p99,non302,socket_errors" > "$RESULTS"

for t in $THREAD_STEPS; do
    OUT="$OUT_DIR/t${t}-c${CONN}.txt"
    MSYS_NO_PATHCONV=1 docker run --rm --network "$NETWORK" \
        -v "$(cd "$SCRIPT_DIR" && pwd -W):/scripts:ro" \
        -v "$(cd "$WORK_DIR" && pwd -W)/codes.txt:/codes/codes.txt:ro" \
        -e CODE_LIST=/codes/codes.txt \
        williamyeh/wrk -t"$t" -c"$CONN" -d"$DUR" --latency \
        -s /scripts/wrk-redirect.lua http://app:8080 > "$OUT" 2>&1

    QPS="$(grep -oE 'Requests/sec:[^ ]+' "$OUT" | head -1 | tr -d ',' | awk '{print $2}')"
    P50="$(grep -oE '50.00%[^ ]*' "$OUT" | head -1 | awk '{print $2}')"
    P90="$(grep -oE '90.00%[^ ]*' "$OUT" | head -1 | awk '{print $2}')"
    P99="$(grep -oE '99.00%[^ ]*' "$OUT" | head -1 | awk '{print $2}')"
    NON302="$(grep -oE '非 302 响应数 : [0-9]+' "$OUT" | awk '{print $NF}')"
    ERR="$(grep -oE 'Socket errors: connect [0-9]+, read [0-9]+, write [0-9]+, timeout [0-9]+' "$OUT" | grep -oE '[0-9]+' | awk '{s+=$1} END{print s+0}')"

    echo "${t},${CONN},${DUR},${QPS:-?},${P50:-?},${P90:-?},${P99:-?},${NON302:-0},${ERR:-0}" >> "$RESULTS"
    log "   -t${t} -c${CONN}: QPS=${QPS:-?}  P50=${P50:-?}  P90=${P90:-?}  P99=${P99:-?}  非302=${NON302:-0}"

    # 档间冷却：让 GC 与系统负载回落，避免上一档的余波影响下一档
    if [ "$t" != "$(echo "$THREAD_STEPS" | awk '{print $NF}')" ]; then
        log "   冷却 ${COOLDOWN}s..."
        sleep "$COOLDOWN"
    fi
done

log "⑥ 恢复 Prometheus 与 Grafana"
docker compose start prometheus grafana >/dev/null 2>&1
MONITOR_STOPPED=0

echo ""
echo "════════ 阶梯加压结果 ════════"
cat "$RESULTS"
echo ""
echo "⚠️ 判读："
echo "  · QPS 随线程数**持续上涨** -> 压测端还没饱和"
echo "  · QPS **涨不动**而 P99 明显恶化 -> 拐点在此（后端到达容量上限）"
echo "  · 若线程数很大时 QPS 仍不涨 -> 先确认是不是**压测端**先饱和了"
echo "    （用 docker stats 看 wrk 容器的 CPU：接近 100% 就是压测端的锅）"
echo ""
echo "原始输出在 $OUT_DIR/"
