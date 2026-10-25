#!/usr/bin/env bash
# =============================================================================
# LinkForge 长稳测试采集脚本（第 5 周阶段 3）
#
# 目的：验证 DR-057 记录的「19 小时 Metaspace 涨到 74MB」**是不是泄漏**。
#
# ⚠️ **采集三条曲线，缺一不可**：
#   ① Metaspace         —— 怀疑对象
#   ② 已加载类数        —— ⭐ **最直接的证据**。
#      Metaspace 涨 = 类加载多（或类卸载不掉）。
#      LoadedClassCount 平稳而 Metaspace 涨 → 类卸载不掉（如动态代理持有）
#      LoadedClassCount 线性增长 → 找到了元凶，能用 jcmd 抓出类名
#   ③ meter 数量        —— 对照组：动态 tag 涨的是**堆**不是 Metaspace
#
# 为什么从 Prometheus 拉而不是直接 curl /actuator/prometheus：
#   Prometheus 已经在采集了，走它的 API 等于「验证监控栈本身也在正常工作」。
#
# 用法：
#   ./scripts/longrun-collect.sh <时长秒> [间隔秒]
#   例：./scripts/longrun-collect.sh 28800 1800   # 8 小时，每 30 分钟一次
#
# 输出：
#   benchmark/longrun/metrics-<开始时间>.log   人类可读的采样记录
#   benchmark/longrun/metrics-<开始时间>.csv    便于后续画图/计算斜率
# =============================================================================
set -uo pipefail

DURATION="${1:-28800}"      # 总时长（秒），默认 8 小时
INTERVAL="${2:-1800}"       # 采样间隔（秒），默认 30 分钟
OUT_DIR="${OUT_DIR:-./benchmark/longrun}"
PROM_URL="${PROM_URL:-http://localhost:9090}"
PROM_USER="${PROM_USER:-linkforge}"
PROM_PASS_FILE="${PROM_PASS_FILE:-./docker/secrets/prometheus_basic_auth.txt}"
APP_URL="${APP_URL:-http://localhost:8080}"

mkdir -p "$OUT_DIR"

START_ISO="$(date -Iseconds)"
STAMP="$(date +%Y%m%d-%H%M%S)"
LOG="$OUT_DIR/metrics-$STAMP.log"
CSV="$OUT_DIR/metrics-$STAMP.csv"

# ⚠️ Prometheus 开了 Basic Auth（第 5 周），裸 curl 会 401。
PROM_PASS="$(cat "$PROM_PASS_FILE" 2>/dev/null || echo '')"

echo "长稳采集开始：$START_ISO  时长=${DURATION}s 间隔=${INTERVAL}s" | tee -a "$LOG"
echo "输出：$LOG / $CSV" | tee -a "$LOG"

# CSV 表头
echo "timestamp,elapsed_sec,metaspace_bytes,heap_bytes,classes_loaded,meter_count,gc_count,gc_full_count,pv_total,scrape_total" > "$CSV"

# 取一个 Prometheus 指标的当前值。
# ⚠️ 用 instant query（/api/v1/query）而不是 range query —— 采集要的是「此刻的值」。
# ⚠️ 认证失败时返回空串而不是让脚本崩：一个采样点失败不该中断整个 8 小时采集。
prom_query() {
    local q="$1"
    # ⚠️⚠️ **必须把查询里的双引号换成单引号**。
    #   `--data-urlencode "query=$q"` 是双引号包裹的，
    #   而 $q 里若含双引号（比如 label 匹配器 uri="/actuator/prometheus"），
    #   **shell 会把它当作结束引号** -> 参数被拆散 -> curl 收到残缺的查询 ->
    #   Prometheus 返回空或报错，而脚本**不报错**（只取到空串）。
    #   PromQL 的 string literal 单双引号等价（标准 SQL 风格），换成单引号即可。
    q="${q//\"/\'}"
    if [ -n "$PROM_PASS" ]; then
        curl -s --max-time 15 -u "$PROM_USER:$PROM_PASS" \
            --data-urlencode "query=$q" "$PROM_URL/api/v1/query" 2>/dev/null
    else
        curl -s --max-time 15 --data-urlencode "query=$q" "$PROM_URL/api/v1/query" 2>/dev/null
    fi
}

# 从 Prometheus 的 JSON 里取第一条 series 的值。取不到返回空串。
extract_value() {
    python3 -c "
import json,sys
try:
    d=json.load(sys.stdin)
    r=d.get('data',{}).get('result',[])
    print(r[0]['value'][1] if r else '')
except Exception:
    print('')
" 2>/dev/null
}

sample_once() {
    local elapsed="$1"
    local metaspace heap classes meter gc pv

    # ⚠️ Metaspace 的 area 是 **nonheap** 不是 metaspace（TROUBLESHOOTING T-020）：
    #    Micrometer 把 Metaspace 与 Compressed Class Space 都归在 nonheap 下。
    #    查 {area="metaspace"} 会返回空 —— 而空曲线看起来像「没有泄漏」。
    metaspace=$(prom_query 'jvm_memory_used_bytes{area="nonheap",id="Metaspace"}' | extract_value)
    heap=$(prom_query 'jvm_memory_used_bytes{area="heap"}' | extract_value)
    classes=$(prom_query 'jvm_classes_loaded_classes' | extract_value)
    meter=$(prom_query 'shortlink_meter_count' | extract_value)
    # ⚠️⚠️ **GC 必须对所有 cause 求和，不能取 r[0]**。
    #   `jvm_gc_pause_seconds_count` 有**多条 series**（每个 GC cause 一条）：
    #     cause="G1 Evacuation Pause"     -> 真正的 young GC（本项目 8 小时 14,387 次）
    #     cause="Metadata GC Threshold"    -> 元空间专用 GC（本项目 8 小时 1 次）
    #   而 `extract_value` 取 r[0]（label 排序第一条）拿到的是
    #   **Metadata GC Threshold**（count=1）——
    #   于是「8 小时只 GC 了 1 次」这个结论**完全错误**（差了 4 个数量级）。
    #   修复：用 sum() 聚合，并**额外单列** Full GC 的计数。
    gc=$(prom_query 'sum(jvm_gc_pause_seconds_count)' | extract_value)
    gc_full=$(prom_query 'sum(jvm_gc_pause_seconds_count{action=~".*major.*"})' | extract_value)
    # ⚠️ **PV 必须是跳转端点的总数**，不能用 r[0]：
    #   `http_server_requests_seconds_count{uri=~"/.*"}` 的 r[0] 是
    #   `/actuator/prometheus`（Prometheus 每 15s 抓一次），
    #   它跟业务 PV 毫无关系。必须显式限定 uri=~"/{code}"。
    pv=$(prom_query "sum(http_server_requests_seconds_count{uri=~'/{code}'})" | extract_value)
    # 抓取端点的请求数（用于验证采集链路本身的健康度）
    pv_scrape=$(prom_query "sum(http_server_requests_seconds_count{uri='/actuator/prometheus'})" | extract_value)

    local line="$(date -Iseconds),$elapsed,$metaspace,$heap,$classes,$meter,$gc,$gc_full,$pv,$pv_scrape"
    echo "$line" >> "$CSV"

    printf "[%6ss] Metaspace=%-12s Heap=%-12s Classes=%-9s Meters=%-6s GC=%-8s FullGC=%-6s PV=%-12s Scrape=%s\n" \
        "$elapsed" "${metaspace:-N/A}" "${heap:-N/A}" "${classes:-N/A}" "${meter:-N/A}" \
        "${gc:-N/A}" "${gc_full:-N/A}" "${pv:-N/A}" "${pv_scrape:-N/A}" | tee -a "$LOG"

    # ⚠️ 同时记一份 jcmd 的类加载统计（每 2 小时一次，太频繁会拖慢 JVM）。
    if [ $((elapsed % 7200)) -eq 0 ] && [ "$elapsed" -gt 0 ]; then
        echo "  --- jcmd VM.classloader_stats（每 2 小时）---" >> "$LOG"
        docker exec linkforge-app jcmd 1 VM.classloader_stats >> "$LOG" 2>&1 || true
        echo "  --- jcmd GC.class_histogram top20 ---" >> "$LOG"
        docker exec linkforge-app jcmd 1 GC.class_histogram 2>/dev/null | head -20 >> "$LOG" || true
    fi
}

START_EPOCH="$(date +%s)"
sample_once 0

# 主循环：用 date +%s 算 elapsed，而不是依赖 sleep 的累加 ——
# ⚠️ 理由：机器负载高时 sleep 会漂移，用绝对时间算才不会「8 小时变 9 小时」。
while true; do
    NOW="$(date +%s)"
    ELAPSED=$((NOW - START_EPOCH))
    if [ "$ELAPSED" -ge "$DURATION" ]; then
        break
    fi
    # sleep 到下一个采样点（不足一个间隔就按剩余时间睡）
    REMAIN=$((INTERVAL - (ELAPSED % INTERVAL)))
    if [ "$REMAIN" -le 0 ]; then REMAIN=1; fi
    sleep "$REMAIN"
    NOW="$(date +%s)"
    ELAPSED=$((NOW - START_EPOCH))
    if [ "$ELAPSED" -ge "$DURATION" ]; then
        break
    fi
    sample_once "$ELAPSED"
done

END_ISO="$(date -Iseconds)"
echo "长稳采集结束：$END_ISO  实际时长=$(( $(date +%s) - START_EPOCH ))s" | tee -a "$LOG"
echo "CSV：$CSV" | tee -a "$LOG"
