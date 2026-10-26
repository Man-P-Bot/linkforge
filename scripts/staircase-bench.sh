#!/usr/bin/env bash
# =============================================================================
# 阶梯加压（组 C）—— 找**拐点**，不刷 QPS
#
# 目的：回答「在多大并发下开始降级」，而不是「最高能到多少 QPS」。
# ⚠️ **数据不与前三周直接比**（口径不同：并发逐档变化，不是固定 -t4 -c100）。
#    单独成章汇报。
#
# ⚠️⚠️ **两种模式测的是不同的东西，绝不能混用**：
#
#   MODE=threads（固定 -c，递增 -t）：
#     **连接总数不变** → 后端看到的**并发深度不变**
#     → 测出的是**压测端自己的饱和点**
#     实测（2026-10-26）：-t4 之后 QPS 不再增长，-t12 反而比 -t4 低 18%。
#        那个 21,660 QPS 是**压测端的天花板**，不是后端的。
#
#   MODE=conn（固定 -t，递增 -c）：
#     **后端的并发深度递增** → 这才是**后端容量水位**的正确测法，
#     能找到后端在多大并发下开始降级。
#
#   判据：
#     问「后端在多大并发下开始降级」  -> MODE=conn
#     问「压测端最多能发多快」        -> MODE=threads
#
# ⚠️ **为什么不用 wrk 一直压**：
#   长时间高强度压测会**烤干机器**——不仅影响本机，
#   还会让后续的「同配置基线」数据被污染（宿主 CPU 被抢、内存被压）。
#   所以每一档**只跑 30 秒**，且**档间留冷却**让系统回落。
#
# 用法：
#   bash scripts/staircase-bench.sh                                  # 默认 MODE=threads
#   MODE=conn CONN_STEPS="50 100 200 400 800" bash scripts/staircase-bench.sh
#   bash scripts/staircase-bench.sh --quick                          # 每档 15s
# =============================================================================
set -uo pipefail

# ---------------------------------------------------------------- 参数
CONN="${CONN:-50}"
DUR="${DUR:-30s}"
COOLDOWN="${COOLDOWN:-15}"
THREAD_STEPS="${THREAD_STEPS:-1 2 3 4 6 8 12}"
CONN_STEPS="${CONN_STEPS:-}"
THREAD_FIX="${THREAD_FIX:-4}"
MODE="${MODE:-threads}"
NETWORK="${DOCKER_NETWORK:-linkforge_default}"
WORK_DIR="${WORK_DIR:-./benchmark/results}"
OUT_DIR="${OUT_DIR:-./benchmark/staircase}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

# 共享提取库（awk 按字段取，见该文件的注释）
# shellcheck source=scripts/bench-extract.sh
. "$SCRIPT_DIR/bench-extract.sh"

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
log "   模式       : ${MODE}"
if [ "$MODE" = "conn" ]; then
    log "   线程数固定 : -t${THREAD_FIX}（递增**连接数** → 后端并发深度递增）"
    log "   连接数档位 : ${CONN_STEPS}"
else
    log "   连接数固定 : -c${CONN}（递增**线程数** → 测的是**压测端**饱和点）"
    log "   线程档位   : ${THREAD_STEPS}"
fi
log "   每档时长   : ${DUR}"
log "   档间冷却   : ${COOLDOWN}s"
log "   语料       : ${CODE_COUNT} 条"
log "════════════════════════════════════════════════════"

# ⚠️ 与 final-bench.sh 同样的保护：长稳压测在跑时不要并行压测
if docker ps --format '{{.Names}}' 2>/dev/null | grep -q "linkforge-longrun-wrk"; then
    fail "检测到长稳压测容器正在运行 —— 两组压测并行会互相污染，请先结束长稳测试"
fi

# ---------------------------------------------------------------- 停监控
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

# ---------------------------------------------------------------- 重启 app
log "② 重启 app（清 Metaspace 与 GC 状态）"
docker compose restart app >/dev/null 2>&1
STATUS=""
for i in $(seq 1 40); do
    STATUS="$(docker inspect -f '{{.State.Health.Status}}' linkforge-app 2>/dev/null || echo '')"
    [ "$STATUS" = "healthy" ] && break
    sleep 3
done
[ "$STATUS" = "healthy" ] || fail "app 未在 120 秒内 healthy"

# ---------------------------------------------------------------- 等布隆
log "③ 等待布隆全量重建"
BLOOM_OK=0
# ⚠️ 必须以**容器启动时刻**为基准。`--since 5m` 会把上一次运行的重建日志
#   也匹配进来（重启后仅 20 秒 < initialDelay 30 秒时就误判）。
APP_START="$(docker inspect -f '{{.State.StartedAt}}' linkforge-app 2>/dev/null)"
for i in $(seq 1 60); do
    if docker logs --since "$APP_START" linkforge-app 2>/dev/null | grep -q "布隆全量重建完成"; then
        BLOOM_OK=1; break
    fi
    sleep 5
done
[ "$BLOOM_OK" -eq 1 ] && log "   ✅ 布隆重建日志已出现" || log "   ⚠️ 未看到重建日志，继续"

# ⚠️⚠️ **功能级验证**：空布隆会让所有请求 404 → 整轮阶梯数据作废。
#   日志说「重建完成」不等于布隆里真的有短码（PITFALLS P-1 的同一条原则）。
log "   功能级验证：真实短码应返回 302"
PROBE_CODE="$(curl -s --max-time 5 "http://localhost:8080/$(head -1 "$CODE_LIST" | tr -d '\r\n')" -o /dev/null -w '%{http_code}' 2>/dev/null || echo 000)"
if [ "$PROBE_CODE" != "302" ]; then
    fail "布隆不可用（真实短码返回 ${PROBE_CODE}，期望 302）。空布隆会让所有请求 404，数据作废。"
fi
log "   ✅ 布隆可用（302）"

# ---------------------------------------------------------------- 预热
log "④ 预热 15s（丢弃结果）"
MSYS_NO_PATHCONV=1 docker run --rm --network "$NETWORK" \
    -v "$(cd "$SCRIPT_DIR" && pwd -W):/scripts:ro" \
    -v "$(cd "$WORK_DIR" && pwd -W)/codes.txt:/codes/codes.txt:ro" \
    -e CODE_LIST=/codes/codes.txt \
    williamyeh/wrk -t2 -c"$CONN" -d15s \
    -s /scripts/wrk-redirect.lua http://app:8080 >/dev/null 2>&1

# ---------------------------------------------------------------- 逐档
log "⑤ 逐档加压"
RESULTS="$OUT_DIR/results.csv"
echo "mode,threads,connections,duration,qps,p50,p90,p99,non302,socket_errors,wrk_cpu" > "$RESULTS"

if [ "$MODE" = "conn" ]; then
    TOTAL_STEP=$(echo "$CONN_STEPS" | wc -w)
else
    TOTAL_STEP=$(echo "$THREAD_STEPS" | wc -w)
fi

run_one() {
    local t="$1" c="$2"
    local OUT="$OUT_DIR/t${t}-c${c}.txt"
    # ⚠️ **不用 --rm**，且用固定容器名 —— 这样压测**期间**能采样它的 CPU。
    #   （原来用 --rm，容器在 run_one 返回时已被删除，
    #    `docker stats` 抓不到 -> wrk_cpu 永远是空的。）
    docker rm -f linkforge-staircase-wrk >/dev/null 2>&1 || true
    MSYS_NO_PATHCONV=1 docker run --name linkforge-staircase-wrk --network "$NETWORK" \
        -v "$(cd "$SCRIPT_DIR" && pwd -W):/scripts:ro" \
        -v "$(cd "$WORK_DIR" && pwd -W)/codes.txt:/codes/codes.txt:ro" \
        -e CODE_LIST=/codes/codes.txt \
        williamyeh/wrk -t"$t" -c"$c" -d"$DUR" --latency \
        -s /scripts/wrk-redirect.lua http://app:8080 > "$OUT" 2>&1 &

    # ⚠️ **压测期间**采样压测端 CPU（判读拐点时必须知道
    #    「是后端到顶了，还是压测端先饱和了」）
    # ⚠️ **取多次采样的最大值，且跳过 0**。
    #   `docker stats --no-stream` 的第一次采样会落在容器**刚启动**的瞬间
    #   （wrk 还在初始化）-> 读到 0.00%；
    #   而若取「最后一次有效值」，那次恰好是压测**刚结束**的瞬间 -> 也是 0.00%。
    #   压测期间的**峰值**才是有意义的值（判读时要看「压测端有没有先顶满」）。
    local WRKCPU="0"
    local j c1
    for j in 1 2 3 4 5; do
        sleep 6
        c1="$(docker stats --no-stream --format '{{.CPUPerc}}' linkforge-staircase-wrk 2>/dev/null | tr -d '%')"
        if [ -n "$c1" ] && awk -v a="$c1" -v b="$WRKCPU" 'BEGIN{exit !(a+0 > b+0)}'; then
            WRKCPU="$c1"
        fi
    done
    wait
    docker rm -f linkforge-staircase-wrk >/dev/null 2>&1 || true

    # ⚠️ 提取一律走**共享库**（awk 按字段取，不受空格数量 / 标签内容影响）
    local QPS P50 P90 P99 NON302 ERR
    QPS="$(extract_qps "$OUT")"
    P50="$(extract_lat "$OUT" 6)"
    P90="$(extract_lat "$OUT" 8)"
    P99="$(extract_lat "$OUT" 10)"
    NON302="$(extract_non302 "$OUT")"
    ERR="$(extract_sockerr "$OUT")"

    echo "${MODE},${t},${c},${DUR},${QPS:-?},${P50:-?},${P90:-?},${P99:-?},${NON302:-0},${ERR:-0},${WRKCPU:-?}" >> "$RESULTS"
    log "   -t${t} -c${c}: QPS=${QPS:-?}  P50=${P50:-?}  P90=${P90:-?}  P99=${P99:-?}  非302=${NON302:-0}  SocketErr=${ERR:-0}  wrkCPU=${WRKCPU:-?}"
}

step_idx=0
if [ "$MODE" = "conn" ]; then
    for c in $CONN_STEPS; do
        step_idx=$(( step_idx + 1 ))
        run_one "$THREAD_FIX" "$c"
        if [ "$step_idx" -lt "$TOTAL_STEP" ]; then
            log "   冷却 ${COOLDOWN}s..."
            sleep "$COOLDOWN"
        fi
    done
else
    for t in $THREAD_STEPS; do
        step_idx=$(( step_idx + 1 ))
        run_one "$t" "$CONN"
        if [ "$step_idx" -lt "$TOTAL_STEP" ]; then
            log "   冷却 ${COOLDOWN}s..."
            sleep "$COOLDOWN"
        fi
    done
fi

# ---------------------------------------------------------------- 恢复
log "⑥ 恢复 Prometheus 与 Grafana"
docker compose start prometheus grafana >/dev/null 2>&1
MONITOR_STOPPED=0

echo ""
echo "════════ 阶梯加压结果（${MODE} 模式）════════"
cat "$RESULTS"
echo ""
echo "⚠️ 判读："
if [ "$MODE" = "conn" ]; then
    echo "  · QPS 随**连接数**持续上涨 -> 后端还有余量"
    echo "  · QPS **涨不动**而 P99 明显恶化 -> **后端拐点**在此"
    echo "  · QPS **下降** -> 已过拐点（排队时延开始堆积）"
else
    echo "  · ⚠️ **本模式固定了连接数** → 后端并发深度不变 →"
    echo "    测出的是**压测端**的饱和点，**不是**后端拐点。"
    echo "    找后端拐点请用：MODE=conn CONN_STEPS=\"50 100 200 400 800\""
fi
echo "  · 判读时看 CSV 的 wrk_cpu 列：接近 100% 就是压测端的锅"
echo ""
echo "原始输出在 $OUT_DIR/"
