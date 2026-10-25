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
for i in $(seq 1 60); do
    if docker logs --since 5m linkforge-app 2>/dev/null | grep -q "布隆全量重建完成"; then
        BLOOM_OK=1; break
    fi
    sleep 5
done
[ "$BLOOM_OK" -eq 1 ] && log "   ✅ 布隆已就绪" || log "   ⚠️ 未看到布隆重建完成日志，继续（可能有少量 miss）"

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
    QPS="$(grep -oE 'Requests/sec:[^ ]+' "$OUT" | head -1 | tr -d ',' | awk '{print $2}')"
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
    log "      QPS=$(grep -oE 'Requests/sec:[^ ]+' "$OUT" | head -1 | tr -d ',' | awk '{print $2}')"
fi

# ---------------------------------------------------------------- 7b. 自动判定
# ⚠️ **判据必须来自「预注册」，而不是跑完再定**。
#   判据写在 docs/PERFORMANCE.md §11.4（跑之前就定死了）。
#   这里把它实现成代码，是为了让「判定」这一步**没有解释空间**——
#   否则「到底算不算达标」会变成一个可以临场讨论的问题。
#
#   判据（PERFORMANCE.md §11.4）：
#     QPS >= 25,000 且 P99 <= 15ms 且零错误  -> ✅ 通过
#     QPS 20,000~25,000                       -> ⚠️ 基本通过（需归因）
#     QPS < 20,000                            -> ❌ 不通过
#     出现任何非 302 或 Socket 错误            -> ❌ 不通过（正确性问题）
#
# ⚠️ **为什么下限是 25,000 而不是 6 万**：
#   本机物理上限约 3.2 万 QPS（18 核 - 宿主固有 3 核 - 压测端 1.5 核，
#   每请求 CPU 417 µs，见 PERFORMANCE.md §12）。
#   把不切实际的目标写进判据，只会让「未达标」变成一句废话。

# wrk 的分位数单位不统一（us / ms / s），统一折算成毫秒
to_ms() {
    local v="$1" n u
    [ -z "$v" ] && { echo "0"; return; }
    n="$(echo "$v" | grep -oE '^[0-9.]+' || echo 0)"
    u="$(echo "$v" | grep -oE '(us|ms|s)$' || echo ms)"
    case "$u" in
        us) awk -v n="$n" 'BEGIN{printf "%.3f", n/1000}' ;;
        s)  awk -v n="$n" 'BEGIN{printf "%.3f", n*1000}' ;;
        *)  awk -v n="$n" 'BEGIN{printf "%.3f", n}' ;;
    esac
}

# 取 N 个数的中位数（sort -n 后取中间那个）
median() {
    printf '%s\n' "$@" | grep -vE '^$|^\?$' | sort -n         | awk '{a[NR]=$1} END{if(NR==0){print ""}else{print a[int((NR+1)/2)]}}'
}

judge() {
    log "⑧ 自动判定（判据来自 PERFORMANCE.md §11.4 的预注册）"

    local qps_vals=() p99_vals=() non302_total=0 err_total=0
    local i F q p
    for i in $(seq 1 "$ROUNDS"); do
        F="$OUT_DIR/run-main-$i.txt"
        [ -f "$F" ] || continue
        q="$(grep -oE 'Requests/sec:[^ ]+' "$F" | head -1 | tr -d ',' | awk '{print $2}')"
        p="$(grep -oE '99.00%[^ ]*' "$F" | head -1 | awk '{print $2}')"
        qps_vals+=("${q:-}")
        p99_vals+=("$(to_ms "${p:-}")")
        non302_total=$(( non302_total + $(grep -oE '非 302 响应数 : [0-9]+' "$F" | awk '{print $NF+0}') ))
        err_total=$(( err_total + $(grep -oE 'Socket errors: connect [0-9]+, read [0-9]+, write [0-9]+, timeout [0-9]+' "$F" | grep -oE '[0-9]+' | awk '{s+=$1} END{print s+0}') ))
    done

    local mq mp
    mq="$(median "${qps_vals[@]}")"
    mp="$(median "${p99_vals[@]}")"

    echo ""
    echo "  中位数 QPS = ${mq:-?}   中位数 P99 = ${mp:-?}ms"
    echo "  非 302 响应总数 = ${non302_total}    Socket 错误总数 = ${err_total}"
    echo ""

    if [ -z "${mq}" ]; then
        echo "  ❓ 无法判定（没有可用的 QPS 数据）"
        return
    fi

    # ⚠️ **错误优先于性能**：出现错误一律不通过。
    #    性能数字再好，链路有错误就是「不可用」。
    if [ "${non302_total}" -gt 0 ] || [ "${err_total}" -gt 0 ]; then
        echo "  ❌ **不通过** —— 出现非 302 响应或 Socket 错误。"
        echo "     这是**正确性问题**，与性能无关；性能再好也不算数。"
        return
    fi

    # 用 awk 做浮点比较（bash 不支持浮点）
    local verdict
    verdict="$(awk -v q="${mq}" -v p="${mp}" 'BEGIN{
        if (q >= 25000 && p <= 15)      print "PASS"
        else if (q >= 20000)            print "WARN"
        else                            print "FAIL"
    }')"

    case "${verdict}" in
        PASS)
            echo "  ✅ **通过** —— QPS >= 25,000 且 P99 <= 15ms 且零错误。"
            echo "     结论：加入监控栈后**性能无明显衰减**（本阶段的真正验收点）。"
            ;;
        WARN)
            echo "  ⚠️ **基本通过** —— QPS 在 20,000~25,000，需归因。"
            echo "     归因方向：宿主负载（见 §12.3）/ 监控端点开销 / 语料命中率。"
            ;;
        FAIL)
            echo "  ❌ **不通过** —— QPS < 20,000。"
            echo "     排查方向：监控栈是否成为新瓶颈（对照 §11.3 的污染源清单）。"
            ;;
    esac
    echo ""
}

judge

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
        echo "    P50   = $(grep -A1 'Latency' "$F" | grep -oE '50.00%[^ ]*' | head -1 | awk '{print $2}')"
        echo "    P99   = $(grep -oE '99.00%[^ ]*' "$F" | head -1 | awk '{print $2}')"
        echo "    非302 = $(grep -oE '非 302 响应数 : [0-9]+' "$F" | awk '{print $NF}')"
        echo "    错误  = $(grep -oE 'Socket 错误.*' "$F" | head -1)"
    done
    if [ "$DO_EXTRA" -eq 1 ] && [ -f "$OUT_DIR/run-extra-60s.txt" ]; then
        echo ""
        echo "── 补充口径（$DURATION_EXTRA 稳态）──"
        F="$OUT_DIR/run-extra-60s.txt"
        echo "    QPS   = $(grep -oE 'Requests/sec:[^ ]+' "$F" | head -1 | tr -d ',' | awk '{print $2}')"
        echo "    P99   = $(grep -oE '99.00%[^ ]*' "$F" | head -1 | awk '{print $2}')"
    fi
} | tee "$SUMMARY"

# ---------------------------------------------------------------- 9. 恢复监控
log "⑨ 恢复 Prometheus 与 Grafana"
docker compose start prometheus grafana >/dev/null 2>&1
MONITOR_STOPPED=0
log "   ✅ 已恢复"

log "完成。原始数据在 $OUT_DIR/"
