#!/usr/bin/env bash
# =============================================================================
# 终极压测的**自动判定**（第 5 周阶段 4）
#
# ⚠️ **为什么独立成脚本**：
#   1. **判定必须能对历史数据重跑** —— 提取逻辑出 bug 时，
#      不需要重跑 4 分钟的压测就能重新判定；
#   2. **判定逻辑不能内嵌在压测脚本里** —— 内嵌时它跟着压测一起跑，
#      提取出错就整轮数据「看起来正常但全是空」；
#   3. 判据来自 **docs/PERFORMANCE.md §11.4 的预注册**，
#      这里只是把它实现成代码 —— **判定这一步没有解释空间**。
#
# 判据（预注册，跑完不改）：
#   QPS >= 25,000 且 P99 <= 15ms 且零错误  -> ✅ 通过
#   QPS 20,000~25,000                       -> ⚠️ 基本通过（需归因）
#   QPS < 20,000                            -> ❌ 不通过
#   任何非 302 响应或 Socket 错误            -> ❌ 不通过（正确性问题）
#
# 用法：
#   bash scripts/judge-bench.sh [数据目录] [轮数]
#   默认：./benchmark/final 3
# =============================================================================
set -uo pipefail

OUT_DIR="${1:-./benchmark/final}"
ROUNDS="${2:-3}"

# ---------------------------------------------------------------- 提取
# ⚠️⚠️ **提取一律用 awk 按字段取，不用 `grep -oE '[0-9.]+'`**。
#   原因（实测踩了三次）：正则会把**标签里的数字**也提取出来 ——
#     "P50 / P90 / P99: 3.08 / 5.36 / 10.05 (ms)"
#     `grep -oE '[0-9.]+'` 得到的是 50 90 99 3.08 5.36 10.05
#     （"P50" 里的 50 被当成了 P50 的值！）
#   而 wrk 原生格式也变过：`Requests/sec:  29387.67`（**两个空格**）、
#   `99%   10.05ms`（不是 `99.00%`）。
#   **awk 按空白分割 + 取固定字段号，不受这些格式变化影响。**

extract_qps() {
    grep -E '^Requests/sec:' "$1" | head -1 | awk '{print $2}'
}
extract_lat() {
    # 行格式： P50 / P90 / P99: 3.08 / 5.36 / 10.05 (ms)
    # 字段：   $1=P50 $2=/ $3=P90 $4=/ $5=P99: $6=3.08 $7=/ $8=5.36 $9=/ $10=10.05 $11=(ms)
    grep -E 'P50 / P90 / P99:' "$1" | head -1 | awk -v n="$2" '{print $n}'
}
extract_non302() {
    grep -oE '非 302 响应数 : [0-9]+' "$1" | awk '{print $NF+0}'
}
extract_sockerr() {
    grep -oE 'Socket errors: connect [0-9]+, read [0-9]+, write [0-9]+, timeout [0-9]+' "$1" \
        | grep -oE '[0-9]+' | awk '{s+=$1} END{print s+0}'
}
extract_reqs() {
    grep -oE '[0-9]+ requests in' "$1" | head -1 | awk '{print $1}'
}

median() {
    printf '%s\n' "$@" | grep -vE '^$|^\?$' | sort -n \
        | awk '{a[NR]=$1} END{if(NR==0){print ""}else{print a[int((NR+1)/2)]}}'
}

# ---------------------------------------------------------------- 判定
echo "════════ 终极压测自动判定（判据：PERFORMANCE.md §11.4 预注册）════════"
echo "  数据目录：${OUT_DIR}    轮数：${ROUNDS}"
echo ""

qps_vals=() p50_vals=() p90_vals=() p99_vals=()
non302_total=0 err_total=0 reqs_total=0

for i in $(seq 1 "$ROUNDS"); do
    F="${OUT_DIR}/run-main-${i}.txt"
    [ -f "$F" ] || continue
    q="$(extract_qps "$F")"
    qps_vals+=("${q:-}")
    p50_vals+=("$(extract_lat "$F" 6)")
    p90_vals+=("$(extract_lat "$F" 8)")
    p99_vals+=("$(extract_lat "$F" 10)")
    n3="$(extract_non302 "$F")"
    se="$(extract_sockerr "$F")"
    non302_total=$(( non302_total + n3 ))
    err_total=$(( err_total + se ))
    reqs_total=$(( reqs_total + $(extract_reqs "$F") ))
    printf "  第 %d 轮: QPS=%-10s P50=%-8s P90=%-8s P99=%-8s 非302=%-3s SocketErr=%-3s 请求数=%s\n" \
        "$i" "${q:-?}" "${p50_vals[-1]:-?}" "${p90_vals[-1]:-?}" "${p99_vals[-1]:-?}" \
        "$n3" "$se" "$(extract_reqs "$F")"
done

if [ "${#qps_vals[@]}" -eq 0 ]; then
    echo ""
    echo "  ❓ 没有可用的轮次数据 —— 请检查数据目录"
    exit 1
fi

mq="$(median "${qps_vals[@]}")"
m50="$(median "${p50_vals[@]}")"
m90="$(median "${p90_vals[@]}")"
m99="$(median "${p99_vals[@]}")"

echo ""
echo "  ── 中位数（${#qps_vals[@]} 轮）──"
echo "  QPS = ${mq}"
echo "  P50 = ${m50}ms    P90 = ${m90}ms    P99 = ${m99}ms"
echo "  非 302 响应总数 = ${non302_total}    Socket 错误总数 = ${err_total}    总请求数 = ${reqs_total}"
echo ""

if [ "${non302_total}" -gt 0 ] || [ "${err_total}" -gt 0 ]; then
    echo "  ❌ **不通过** —— 出现非 302 响应或 Socket 错误。"
    echo "     这是**正确性问题**，与性能无关；性能再好也不算数。"
    if [ "${err_total}" -gt 0 ] && [ "${non302_total}" -eq 0 ]; then
        awk -v e="${err_total}" -v r="${reqs_total}" \
            'BEGIN{printf "     （Socket 错误率 = %d / %d = %.6f%%）\n", e, r, (r>0 ? e/r*100 : 0)}'
        echo "     ⚠️ 注意：Socket 错误是**连接层**的（请求没完成），"
        echo "        而「非 302 = 0」说明**所有到达应用的请求都正确返回了 302**。"
        echo "        两者严重性不同 —— 判据把它们混在一起是本判据的一个缺陷，"
        echo "        已记录（见 PERFORMANCE.md §11.4 的判据反思）。"
    fi
    exit 0
fi

verdict="$(awk -v q="${mq}" -v p="${m99}" 'BEGIN{
    if (q >= 25000 && p <= 15) print "PASS"
    else if (q >= 20000)       print "WARN"
    else                       print "FAIL"
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
exit 0
