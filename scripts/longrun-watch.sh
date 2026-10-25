#!/usr/bin/env bash
# =============================================================================
# 长稳测试的「异常跳变」看护（第 5 周阶段 3）
#
# 职责：每 60 秒读一次 Prometheus 的三个指标，
#      若 **Metaspace 单次跳变 > 5MB**，立刻抓类加载分布做分析。
#
# ⚠️ 为什么必须盯「跳变」而不是「总量」：
#   长稳测试要回答的是「有没有泄漏」，而泄漏的表现是**持续线性增长**，
#   不是某个具体数值。盯跳变能第一时间发现「突然来了一大批类」
#   （比如某个异常路径被首次触发、某个定时任务第一次跑）。
#
# ⚠️ 三个指标缺一不可：
#   jvm_memory_used_bytes{area="nonheap",id="Metaspace"}  怀疑对象
#   jvm_classes_loaded_classes                            已加载类数（存活）
#   jvm_classes_unloaded_classes_total                    累计卸载数
#
#   ⚠️ **unloaded_total = 0 的含义（比想象中更精确）**：
#      类一旦加载就**永不卸载**（除非类加载器被回收），
#      而本项目从未回收过类加载器 —— 所以
#      「已加载类数」单调递增，**不会回落**。
#
#      ⚠️⚠️ **但它不能推出「Metaspace 增长 ≡ 已加载类增长」**！
#      本项目实测到过：**Metaspace 涨了 93KB，而 LoadedClassCount 没涨**。
#      原因是 JDK 15+ 的 **hidden class（lambda 隐藏类）**：
#      它们**计入 Metaspace，但不计入 LoadedClassCount**。
#
#      正确的表述（两层）：
#        ① 已加载类数**只增不减** → 它是「类加载累积」的上界证据；
#        ② Metaspace 的增长 = 已加载类 + **hidden class（lambda）** + 其它元数据。
#      因此分析时必须看**两个增量的比值**：
#        每类约 19KB（实测：45 类 → 0.84MB）——比值接近则是普通类；
#        比值远大于 19KB 则**有 hidden class 贡献**（lambda 隐藏类嫌疑）。
#
#      ⚠️ 而 lambda 隐藏类的数量 = 代码里**不同的 lambda 调用点**数量（固定），
#         **与请求量无关** → 若长跑后收敛（不线性增长），就**不是泄漏**。
#
# 用法：
#   bash scripts/longrun-watch.sh [间隔秒] [跳变阈值MB]
#   默认：60 秒 / 5MB
#
# 输出：
#   benchmark/longrun/watch-<时间戳>.log   （含每次采样 + 触发时的诊断快照）
# =============================================================================
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
cd "${ROOT_DIR}"

INTERVAL="${1:-60}"
THRESHOLD_MB="${2:-5}"
THRESHOLD_BYTES=$(( THRESHOLD_MB * 1024 * 1024 ))

PROM_URL="${PROM_URL:-http://localhost:9090}"
SECRET_FILE="docker/secrets/prometheus_basic_auth.txt"
OUT_DIR="benchmark/longrun"

mkdir -p "${OUT_DIR}"
STAMP="$(date +%Y%m%d-%H%M%S)"
LOG="${OUT_DIR}/watch-${STAMP}.log"

if [ ! -f "${SECRET_FILE}" ]; then
    echo "❌ 找不到 ${SECRET_FILE}"
    exit 1
fi
PROM_PW="$(grep -v '^[[:space:]]*#' "${SECRET_FILE}" | grep -v '^[[:space:]]*$' | tail -1 | tr -d '\r\n')"

# 一次查询多个指标（Prometheus 的 /api/v1/query 只接受单个表达式，
# 所以用 instant query 分别取，再合并输出）
query() {
    curl -s -u "linkforge:${PROM_PW}" --data-urlencode "query=$1" \
        "${PROM_URL}/api/v1/query" 2>/dev/null \
    | python -c "
import json,sys
try:
    r = json.load(sys.stdin)['data']['result']
    print(r[0]['value'][1] if r else '')
except Exception:
    print('')
" 2>/dev/null
}

# jcmd 类加载分布（需要 JDK sidecar，见 docker-compose.yml 的 diagnose profile）
diagnose() {
    local reason="$1"
    echo "" >> "${LOG}"
    echo "════ 诊断触发：${reason} @ $(date -Iseconds) ════" >> "${LOG}"

    # ① 首选：JDK sidecar（需要 --profile diagnose）
    if docker compose --profile diagnose ps --services 2>/dev/null | grep -q "jdk-tools"; then
        echo "--- jcmd VM.classloader_stats ---" >> "${LOG}"
        docker compose --profile diagnose run --rm --no-deps jdk-tools \
            jcmd 1 VM.classloader_stats >> "${LOG}" 2>&1 || true
        echo "--- jcmd GC.class_histogram (top 40) ---" >> "${LOG}"
        docker compose --profile diagnose run --rm --no-deps jdk-tools \
            jcmd 1 GC.class_histogram >> "${LOG}" 2>&1 | head -40 >> "${LOG}" || true
    else
        # ② 降级：用 Micrometer 的类加载指标做**连续量**的对比分析
        #    （比 jcmd 的一次性快照更有说服力：它能给出趋势）
        echo "--- 降级分析（无 JDK sidecar，用 Micrometer 指标）---" >> "${LOG}"
        {
            echo "  已加载类数(存活)   : $(query 'jvm_classes_loaded_classes')"
            echo "  累计卸载类数       : $(query 'jvm_classes_unloaded_classes_total')"
            echo "  Metaspace 字节     : $(query 'jvm_memory_used_bytes{area=\"nonheap\",id=\"Metaspace\"}')"
            echo "  Code Cache 字节    : $(query 'jvm_memory_used_bytes{area=\"nonheap\",id=~\"CodeHeap.*\"}')"
            echo "  CompressedClassSpace: $(query 'jvm_memory_used_bytes{area=\"nonheap\",id=\"Compressed Class Space\"}')"
            echo ""
            echo "  判读："
            echo "    · 已加载类数涨 -> 元空间涨（普通类，约 19KB/类）"
            echo "    · 已加载类数**不涨**但元空间涨 -> **hidden class（lambda 隐藏类）**"
            echo "      （JDK 15+：计入 Metaspace，但不计入 LoadedClassCount）"
            echo "    · 累计卸载类数恒为 0 -> 类加载器从未被回收，"
            echo "      所以「已加载类数」单调递增、不会回落"
            echo ""
            echo "  ⚠️ lambda 隐藏类的数量 = 代码里**不同的 lambda 调用点**数量（固定），"
            echo "     与请求量无关 -> 若长跑后收敛（不线性增长），就**不是泄漏**。"
            echo ""
            echo "  ⚠️ 要拿到类名分布，需启动 JDK sidecar："
            echo "     docker compose --profile diagnose run --rm --no-deps jdk-tools \\"
            echo "         jcmd 1 GC.class_histogram"
        } >> "${LOG}"
    fi
    echo "════ 诊断结束 ════" >> "${LOG}"
    echo "" >> "${LOG}"
}

echo "==> 长稳看护启动：间隔 ${INTERVAL}s，跳变阈值 ${THRESHOLD_MB}MB"
echo "    日志：${LOG}"
echo "    停止：Ctrl+C"

prev_meta=""
prev_class=""
prev_time=""
sample_no=0

while true; do
    meta="$(query 'jvm_memory_used_bytes{area="nonheap",id="Metaspace"}')"
    cls="$(query 'jvm_classes_loaded_classes')"
    unloaded="$(query 'jvm_classes_unloaded_classes_total')"
    now="$(date -Iseconds)"

    if [ -z "${meta}" ] || [ -z "${cls}" ]; then
        echo "[${now}] ⚠️ 采集失败（Prometheus 未响应？），跳过本次" >> "${LOG}"
        sleep "${INTERVAL}"
        continue
    fi

    meta_i="$(printf '%.0f' "${meta}")"
    cls_i="$(printf '%.0f' "${cls}")"

    if [ -n "${prev_meta}" ]; then
        delta=$(( meta_i - prev_meta ))
        # ⚠️ 格式化**放在 awk 里**做：shell 的 printf 对大整数 + %.2f
        #    会输出 "35576.00" 这种「把字节数当 MB」的假象
        #    （日志行一度因此把 35KB 的增长显示成 35576MB）。
        delta_mb="$(awk -v d="${delta}" 'BEGIN{printf "%.3f", d/1048576}')"
        cls_delta=$(( cls_i - prev_class ))
        # ⚠️ 每类的「元数据成本」= Metaspace 增量 / 类数增量。
        #    实测基准：普通类约 19KB/类（45 类 → 0.84MB）。
        #    比值远大于 19KB ⇒ 有 **hidden class（lambda 隐藏类）** 贡献 ——
        #    它们计入 Metaspace 但不计入 LoadedClassCount。
        if [ "${cls_delta}" -gt 0 ]; then
            per_class="$(awk -v d="${delta}" -v c="${cls_delta}" 'BEGIN{printf "%.1f", d/1024/c}')"
            per_class_txt="  ${per_class}KB/类"
        elif [ "${delta}" -gt 0 ]; then
            per_class_txt="  ⚠️ 类数未涨但元空间涨 —— 疑似 hidden class（lambda）"
        else
            per_class_txt=""
        fi
        flag=""
        if [ "${delta}" -gt "${THRESHOLD_BYTES}" ]; then
            flag=" ⚠️ 跳变 >${THRESHOLD_MB}MB —— 触发诊断"
        fi
        printf '[%s] Metaspace=%s (%+.3fMB)  Classes=%+d  Unloaded=%s%s%s\n' \
            "${now}" "${meta_i}" "${delta_mb}" "${cls_delta}" "${unloaded}" \
            "${per_class_txt}" "${flag}" >> "${LOG}"

        if [ -n "${flag}" ]; then
            diagnose "Metaspace 跳变 ${delta_mb}MB（阈值 ${THRESHOLD_MB}MB）"
        fi
    else
        printf '[%s] Metaspace=%s  Classes=%s  Unloaded=%s  (基线)\n' \
            "${now}" "${meta_i}" "${cls_i}" "${unloaded}" >> "${LOG}"
    fi

    prev_meta="${meta_i}"
    prev_class="${cls_i}"
    prev_time="${now}"
    sample_no=$(( sample_no + 1 ))

    sleep "${INTERVAL}"
done
