#!/usr/bin/env bash
# =============================================================================
# LinkForge 终极压测（第 5 周阶段 4）
#
# 目标：跑出与第 1/2/3 周**同口径**的最终数据，并并列给出「同配置基线」与
#       「调优版」两组 —— 绝不混用（用户的硬要求）。
#
# ⚠️⚠️ **为什么必须分两组跑**
#   铁律：**调优后的数字不能直接与前三周比**。
#   若把「堆从 1g 调到 2g」的收益记成「代码优化的收益」，
#   结论就全错了（第 2 周就踩过这个坑）。
#   所以：
#     组 A = 与第 3 周**完全同配置**（JVM 参数一字不改）→ 可与前三周直接比
#     组 B = 调优版（改了 JVM 参数/代码）→ 只能与组 A 比
#
# ⚠️⚠️ **必须停掉 Prometheus 与 Grafana**
#   /actuator/prometheus 端点每次被访问都要**遍历并序列化全部 meter**。
#   8 万 QPS 下那是可观的 CPU 开销 —— Prometheus 会污染压测结果。
#   这是前三周**不存在**的污染源（那时还没有监控栈），必须显式排除。
#
# ⚠️ **压测前必须重启 app**：清 Metaspace 与 GC 状态（DR-057 的教训）。
#   不重启的话，Metaspace 的历史增长会让 GC 行为与基线周不同。
#
# ⚠️ **必须等布隆全量重建完成**：布隆没建好时前几千个请求会全部 miss，
#   拉低 P99 并抬高 QPS 的离散度。
#
# 用法：
#   ./scripts/final-bench.sh              # 完整流程（造数 → 压测 → 清理）
#   ./scripts/final-bench.sh --no-create  # 跳过造数（复用现有语料）
#   ./scripts/final-bench.sh --quick      # 只跑 30s 主口径，不跑 60s 补充
# =============================================================================
set -uo pipefail

# ---------------------------------------------------------------- 参数
DURATION_MAIN="${DURATION_MAIN:-30s}"      # 主口径时长（与前三周同）
DURATION_EXTRA="${DURATION_EXTRA:-60s}"     # 补充口径（稳态）
ROUNDS="${ROUNDS:-3}"                      # 主口径跑几次（取中位数）
THREADS="${THREADS:-4}"                    # 与前三周同：-t4
CONNECTIONS="${CONNECTIONS:-100}"           # 与前三周同：-c100
CODE_TOTAL="${CODE_TOTAL:-10000}"          # 与前三周同：1 万条语料
CREATE_CONCURRENCY="${CREATE_CONCURRENCY:-20}"
NETWORK="${DOCKER_NETWORK:-linkforge_default}"
WORK_DIR="${WORK_DIR:-./benchmark/results}"
OUT_DIR="${OUT_DIR:-./benchmark/final}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

DO_CREATE=1
DO_EXTRA=1
for arg in "$@"; do
    case "$arg" in
        --no-create) DO_CREATE=0 ;;
        --quick)     DO_EXTRA=0 ;;
        *) echo "未知参数：$arg"; exit 1 ;;
    esac
done

mkdir -p "$OUT_DIR"

# ---------------------------------------------------------------- 工具函数
log()  { echo "[$(date +%H:%M:%S)] $*"; }
fail() { echo "❌ $*" >&2; exit 1; }

# ⚠️⚠️ **监控容器必须在任何退出路径上都被恢复**。
#   脚本里有好几处 `fail ... exit 1`（语料不足、app 不 healthy、限流未放宽），
#   若不兜底，Prometheus 与 Grafana 会被**永久停掉** ——
#   而它们正是长稳测试的数据采集源，停掉就等于**毁掉正在跑的长稳数据**。
#   trap 的顺序：EXIT 覆盖正常结束与异常退出，INT/TERM 覆盖 Ctrl+C。
MONITOR_STOPPED=0
restore_monitor() {
    if [ "$MONITOR_STOPPED" -eq 1 ]; then
        log "⚠️ 恢复 Prometheus 与 Grafana（异常退出路径的兜底）"
        docker compose start prometheus grafana >/dev/null 2>&1 || true
        MONITOR_STOPPED=0
    fi
}
trap restore_monitor EXIT INT TERM

# 从 wrk 的输出里抽中位数三次里的代表值。
# ⚠️ wrk 输出的是分位数文本，用 grep/sed 抽比解析整个输出稳。
extract_metric() {
    local file="$1" key="$2"
    grep -E "^\s*${key}\s*:" "$file" 2>/dev/null | head -1 \
        | awk '{print $2}' | tr -d ','
}

# ---------------------------------------------------------------- 前置检查
log "════════════════════════════════════════════════════"
log " LinkForge 终极压测（第 5 周）"
log "════════════════════════════════════════════════════"

docker compose ps >/dev/null 2>&1 || fail "docker compose 不可用"

# ⚠️⚠️ **保护：长稳压测在跑时拒绝执行本脚本**。
#   本脚本第 3 步会 `docker compose restart app` ——
#   那会**打断**正在跑的长稳压测（流量断流）并**清空 Metaspace 曲线**，
#   让 8 小时的曲线断成两段，结论作废。
#   这不是「顺手加的检查」，而是两阶段实验**不能并行**的硬约束。
if docker ps --format '{{.Names}}' 2>/dev/null | grep -q "linkforge-longrun-wrk"; then
    fail "检测到长稳压测容器正在运行（linkforge-longrun-wrk）。
       本脚本会重启 app，打断长稳测试并毁掉 Metaspace 曲线。
       请先等长稳测试结束（docker rm -f linkforge-longrun-wrk），再执行。"
fi

CODE_LIST="$WORK_DIR/codes.txt"

# ---------------------------------------------------------------- 1. 造数
if [ "$DO_CREATE" -eq 1 ]; then
    log "① 造数 ${CODE_TOTAL} 条（并发 ${CREATE_CONCURRENCY}）"
    log "   ⚠️ 必须先用 bench overlay 放宽限流，否则创建接口 10/min 的默认值"
    log "      会让绝大多数请求返回 429（实测踩过：9986/10000 被限流）"
    CURRENT_RL="$(docker exec linkforge-app env 2>/dev/null | grep RATE_LIMIT_CREATE_PER_MIN || echo '')"
    case "$CURRENT_RL" in
        *1000000*) log "   限流已放宽：$CURRENT_RL" ;;
        *) log "   ❌ 限流未放宽（当前：${CURRENT_RL:-默认}）"
           log "      请先执行：docker compose -f docker-compose.yml -f docker-compose.bench.yml up -d --force-recreate app"
           fail "限流阈值未放宽，造数会被限流" ;;
    esac

    ( cd "$REPO_ROOT" && bash scripts/bench-create.sh "$CODE_TOTAL" "$CREATE_CONCURRENCY" container ) \
        > "$OUT_DIR/create.log" 2>&1 || fail "造数失败，详见 $OUT_DIR/create.log"
    CREATED="$(grep -oE '去重后短码数=[0-9]+' "$OUT_DIR/create.log" | tail -1 | cut -d= -f2)"
    log "   造数完成：${CREATED:-?} 条"
fi

[ -s "$CODE_LIST" ] || fail "语料不存在或为空：$CODE_LIST"
CODE_COUNT="$(wc -l < "$CODE_LIST")"
log "   语料：$CODE_COUNT 条（目标 ${CODE_TOTAL}）"
[ "$CODE_COUNT" -ge $((CODE_TOTAL / 2)) ] || fail "语料过少（$CODE_COUNT），结果不可用"

# ---------------------------------------------------------------- 2. 停监控
log "② 停掉 Prometheus 与 Grafana（⚠️ /actuator/prometheus 会序列化全部 meter，污染压测）"
docker compose stop prometheus grafana >/dev/null 2>&1
MONITOR_STOPPED=1
# ⚠️ **验证真的停了**（而不是「以为停了」）：
#   `docker compose ps` 只列**在跑**的服务，所以要看的是
#   「prometheus 与 grafana 是否**不在**列表里」。
for svc in prometheus grafana; do
    if docker compose ps --format '{{.Service}}' 2>/dev/null | grep -qx "$svc"; then
        fail "服务 $svc 未能停止 —— 压测会被 Prometheus 的抓取开销污染"
    fi
done
log "   ✅ prometheus / grafana 均已停止"
log "   仍在跑的服务：$(docker compose ps --format '{{.Service}}' | tr '\n' ' ')"

# ---------------------------------------------------------------- 3. 重启应用
log "③ 重启 app（清 Metaspace 与 GC 状态，DR-057 的教训）"
docker compose restart app >/dev/null 2>&1
log "   等待就绪..."
for i in $(seq 1 40); do
    STATUS="$(docker inspect -f '{{.State.Health.Status}}' linkforge-app 2>/dev/null || echo '')"
    [ "$STATUS" = "healthy" ] && break
    sleep 3
done
[ "$STATUS" = "healthy" ] || fail "app 未在 120 秒内 healthy"

# ---------------------------------------------------------------- 4. 等布隆重建
log "④ 等待布隆全量重建完成"
BLOOM_OK=0
# ⚠️⚠️ **必须只看「重启之后新增的日志」**。
#   `docker compose logs app` 输出的是 app 的**全部历史日志**（含上一次运行的），
#   所以重启后**第一条 grep 就会命中上次的「布隆全量重建完成」** ->
#   立即误判为「布隆已就绪」-> 实际布隆还没建好 ->
#   前几千个请求全部 miss -> **P99 被拉高、QPS 离散度变大**。
#   而这正是本步骤本来要防的那件事 —— **防线的检测逻辑自己有 bug**。
#
#   修复：用 `docker logs --since` 只取最近 N 分钟的日志。
#   ⚠️ `--since` 用的是**相对时间**，要给足窗口（本项目布隆重建约 1~2 分钟）。
# ⚠️⚠️ **必须以「容器启动时刻」为基准，而不是 `--since 5m`**。
#   `--since 5m` 会把**上一次运行**的重建日志也匹配进来 ——
#   重启后仅 20 秒（而 initialDelay 是 30 秒）时，
#   第一条 grep 就命中了上次的记录 -> 误判「布隆已就绪」。
#   用容器的 StartedAt（RFC3339）作为起点，`--since` 精确接受它。
APP_START="$(docker inspect -f '{{.State.StartedAt}}' linkforge-app 2>/dev/null)"
log "   基准时刻（容器启动）：${APP_START}"
for i in $(seq 1 60); do
    if docker logs --since "$APP_START" linkforge-app 2>/dev/null | grep -q "布隆全量重建完成"; then
        BLOOM_OK=1; break
    fi
    sleep 5
done
[ "$BLOOM_OK" -eq 1 ] && log "   ✅ 布隆重建日志已出现" || log "   ⚠️ 未看到布隆重建完成日志，继续（可能有少量 miss）"

# ⚠️⚠️ **功能级验证：日志出现 ≠ 布隆可用**。
#   日志说「重建完成」只证明**重建动作跑完了**，
#   不证明**布隆里真的有短码** —— 重建中途失败、或重建时 Redis 尚未就绪，
#   都可能留下一个**空的**布隆。
#
#   空布隆的后果：所有请求都判「不存在」-> 全部 404 -> 压测数据完全作废
#   （而且「非 302 响应数」这一列会暴露它，但那时已经浪费了一轮压测）。
#
#   判据（PITFALLS.md P-1 的同一条原则）：
#   **静态检查全过 ≠ 功能可用** —— 要真的发一个请求，看它是不是 302。
log "   功能级验证：用一个真实短码请求，看是否返回 302"
PROBE_CODE="$(head -1 "$CODE_LIST" 2>/dev/null | tr -d '\r\n')"
PROBE_CODE="$(curl -s --max-time 5 "http://localhost:8080/$(head -1 "$CODE_LIST" | tr -d '\r\n')" -o /dev/null -w '%{http_code}' 2>/dev/null || echo 000)"
if [ "$PROBE_CODE" = "302" ]; then
    log "   ✅ 布隆可用（真实短码返回 302）"
else
    fail "布隆不可用（真实短码返回 ${PROBE_CODE}，期望 302）。
       空布隆会让所有请求 404，压测数据完全作废 —— 必须在压测前拦住。
       排查：docker compose logs app | grep -i 布隆"
fi

# ---------------------------------------------------------------- 5. 预热
log "⑤ 预热 15s（JIT 收敛，结果丢弃）"
MSYS_NO_PATHCONV=1 docker run --rm --network "$NETWORK" \
    -v "$(cd "$SCRIPT_DIR" && pwd -W):/scripts:ro" \
    -v "$(cd "$WORK_DIR" && pwd -W)/codes.txt:/codes/codes.txt:ro" \
    -e CODE_LIST=/codes/codes.txt \
    williamyeh/wrk -t"$THREADS" -c"$CONNECTIONS" -d15s \
    -s /scripts/wrk-redirect.lua http://app:8080 2>&1 | tail -3

# ---------------------------------------------------------------- 6. 主口径
log "⑥ 主口径：${ROUNDS} × ${DURATION_MAIN}（取中位数）"
for i in $(seq 1 "$ROUNDS"); do
    OUT="$OUT_DIR/run-main-$i.txt"
    log "   第 $i/$ROUNDS 轮..."
    MSYS_NO_PATHCONV=1 docker run --rm --network "$NETWORK" \
        -v "$(cd "$SCRIPT_DIR" && pwd -W):/scripts:ro" \
        -v "$(cd "$WORK_DIR" && pwd -W)/codes.txt:/codes/codes.txt:ro" \
        -e CODE_LIST=/codes/codes.txt \
        williamyeh/wrk -t"$THREADS" -c"$CONNECTIONS" -d"$DURATION_MAIN" --latency \
        -s /scripts/wrk-redirect.lua http://app:8080 > "$OUT" 2>&1
    QPS="$(grep -E '^Requests/sec:' "$OUT" | head -1 | awk '{print $2}')"
    log "      QPS=${QPS:-?}"
done

# ---------------------------------------------------------------- 7. 补充口径
if [ "$DO_EXTRA" -eq 1 ]; then
    log "⑦ 补充口径：${DURATION_EXTRA}（稳态）"
    OUT="$OUT_DIR/run-extra-60s.txt"
    MSYS_NO_PATHCONV=1 docker run --rm --network "$NETWORK" \
        -v "$(cd "$SCRIPT_DIR" && pwd -W):/scripts:ro" \
        -v "$(cd "$WORK_DIR" && pwd -W)/codes.txt:/codes/codes.txt:ro" \
        -e CODE_LIST=/codes/codes.txt \
        williamyeh/wrk -t"$THREADS" -c"$CONNECTIONS" -d"$DURATION_EXTRA" --latency \
        -s /scripts/wrk-redirect.lua http://app:8080 > "$OUT" 2>&1
    log "      QPS=$(grep -E '^Requests/sec:' "$OUT" | head -1 | awk '{print $2}')"
fi

# ---------------------------------------------------------------- 8. 汇总
log "⑧ 汇总"
SUMMARY="$OUT_DIR/summary.txt"
{
    echo "LinkForge 终极压测汇总"
    echo "时间：$(date -Iseconds)"
    echo "参数：-t$THREADS -c$CONNECTIONS，语料 $CODE_COUNT 条"
    echo ""
    echo "── 主口径（$ROUNDS × $DURATION_MAIN，取中位数）──"
    for i in $(seq 1 "$ROUNDS"); do
        F="$OUT_DIR/run-main-$i.txt"
        [ -f "$F" ] || continue
        echo "  第 $i 轮:"
        echo "    QPS   = $(grep -oE 'Requests/sec:[^ ]+' "$F" | head -1 | tr -d ',' | awk '{print $2}')"
        echo "    P50   = $(grep -E 'P50 / P90 / P99:' "$F" | head -1 | awk '{print $6}')"
        echo "    P99   = $(grep -E 'P50 / P90 / P99:' "$F" | head -1 | awk '{print $10}')"
        echo "    非302 = $(grep -oE '非 302 响应数 : [0-9]+' "$F" | awk '{print $NF}')"
        echo "    错误  = $(grep -oE 'Socket 错误.*' "$F" | head -1)"
    done
    if [ "$DO_EXTRA" -eq 1 ] && [ -f "$OUT_DIR/run-extra-60s.txt" ]; then
        echo ""
        echo "── 补充口径（$DURATION_EXTRA 稳态）──"
        F="$OUT_DIR/run-extra-60s.txt"
        echo "    QPS   = $(grep -oE 'Requests/sec:[^ ]+' "$F" | head -1 | tr -d ',' | awk '{print $2}')"
        echo "    P99   = $(grep -E 'P50 / P90 / P99:' "$F" | head -1 | awk '{print $10}')"
    fi
} | tee "$SUMMARY"

# ---------------------------------------------------------------- 9. 恢复监控
log "⑨ 恢复 Prometheus 与 Grafana"
docker compose start prometheus grafana >/dev/null 2>&1
MONITOR_STOPPED=0
log "   ✅ 已恢复"

log "完成。原始数据在 $OUT_DIR/"
