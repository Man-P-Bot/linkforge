#!/usr/bin/env bash
# =============================================================================
# wrk 输出提取（共享库，被 judge-bench.sh 与 staircase-bench.sh source）
#
# ⚠️⚠️ **为什么必须用 awk 按字段取，不能用 `grep -oE '[0-9.]+'`**：
#   实测踩了**三次**同一个坑：
#     ① "Requests/sec:  29387.67" 是**两个空格** -> `Requests/sec:[^ ]+` 匹配到空
#     ② "99%   10.05ms" 不是 "99.00%" -> 分位数正则匹配不到
#     ③ "P50 / P90 / P99: 3.08 / 5.36 / 10.05 (ms)" 里，
#        `grep -oE '[0-9.]+'` 会把**标签 "P50" 里的 50** 也当成 P50 的值
#        （得到 50/90/99/3.08/5.36/10.05）
#
#   **awk 按空白分割 + 取固定字段号，不受空格数量与标签内容影响。**
#   判据：**从带标签的输出里取数，不要用「会匹配到标签本身」的正则。**
#
# 行格式（wrk 原生 + 本项目 Lua 脚本附加的统计）：
#   Requests/sec:  29387.67
#   P50 / P90 / P99: 3.08 / 5.36 / 10.05 (ms)
#   非 302 响应数 : 0
#   Socket errors: connect 0, read 0, write 0, timeout 1
# =============================================================================

extract_qps() {
    # $1 = 文件路径
    grep -E '^Requests/sec:' "$1" | head -1 | awk '{print $2}'
}

extract_lat() {
    # $1 = 文件路径, $2 = 字段号（6=P50, 8=P90, 10=P99）
    # 字段： $1=P50 $2=/ $3=P90 $4=/ $5=P99: $6=3.08 $7=/ $8=5.36 $9=/ $10=10.05 $11=(ms)
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

extract_duration() {
    grep -oE 'requests in [0-9.]+[a-z]+' "$1" | head -1 | awk '{print $3}'
}

bench_median() {
    printf '%s\n' "$@" | grep -vE '^$|^\?$' | sort -n \
        | awk '{a[NR]=$1} END{if(NR==0){print ""}else{print a[int((NR+1)/2)]}}'
}
