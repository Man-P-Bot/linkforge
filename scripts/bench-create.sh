#!/usr/bin/env bash
# =============================================================================
# LinkForge 并发造数 + 唯一性验证（第 1 周性能基线的语料来源）
#
# 目标（docs/ROADMAP.md §5 第 1 周验收）：并发创建 10000 个短码，数据库无重复、无失败。
# 这是「发号器在并发下是否唯一」的唯一性证明，也是跳转压测的语料来源。
#
# 用法：
#   ./scripts/bench-create.sh                 # 容器内并发（默认，快）
#   ./scripts/bench-create.sh 10000 50 host   # 宿主 fork 并发（慢，仅供对照）
#
# ⚠️ 为什么默认走容器：
#   宿主版每个请求都要 fork 一个 bash + curl 进程。实测 50 并发下
#   **每分钟仅约 13 个请求**（进程创建开销远大于请求本身），
#   造 1 万个需要约 12 小时。容器内单进程并发无此开销，实测快 3 个数量级。
#   ⚠️ 这只影响造数速度，**不影响被测应用**——两者打的是同一个 app:8080。
#   但报告里必须注明造数是容器内发起的，否则会被误读成「应用吞吐只有 13/s」。
#
# ⚠️ 为什么统计 503 而不是 409：
#   409 (LINKFORGE-409-01) 是**自定义短码**已占用时的返回；本脚本全部走自动短码
#   （不传 customCode），自动短码的冲突最终表现为发号器重试 3 次仍失败 → **503**
#   （LINKFORGE-503-01，API.md §5.4）。因此这里统计 503 才是对的计数口径。
#
# ⚠️ 最终唯一性检查才是权威判据：
#   无论 201/503 各多少，只要数据库里出现重复 short_code 就说明发号器坏了。
#   脚本最后直接查库做 GROUP BY 判定，不依赖任何计数器的推断。
# =============================================================================
set -uo pipefail

TOTAL="${1:-10000}"
CONCURRENCY="${2:-50}"
MODE="${3:-container}"
BASE_URL_HOST="${BASE_URL:-http://localhost:8080}"
BASE_URL_CONTAINER="http://app:8080"
NETWORK="${DOCKER_NETWORK:-linkforge_default}"
WORK_DIR="${WORK_DIR:-./benchmark/results}"
CODE_LIST="$WORK_DIR/codes.txt"
SUMMARY="$WORK_DIR/create-summary.txt"

mkdir -p "$WORK_DIR"

if [ "$MODE" = "host" ]; then
  exec "$0" "$TOTAL" "$CONCURRENCY" internal-host
fi

# ---------------------------------------------------------------- 容器内并发
if [ "$MODE" = "container" ]; then
  echo "=== LinkForge 并发造数（容器内并发）==="
  echo "目标数量 : $TOTAL"
  echo "并发数   : $CONCURRENCY"
  echo "目标地址 : $BASE_URL_CONTAINER（compose 网络内服务名，非 localhost）"
  echo

  START_TS=$(date +%s)
  # 单进程内用 xargs -P 起并发 curl；输出形如 "201 <shortCode>"
  # ⚠️ URL 用服务名 app：容器内 localhost 指向本压测容器自身（DR-003）。
  docker run --rm --network "$NETWORK" \
    -v "$WORK_DIR:/out" alpine:3 sh -c '
      set -e
      apk add --no-cache curl >/dev/null 2>&1
      seq 1 '"$TOTAL"' | xargs -P '"$CONCURRENCY"' -I{} sh -c '\''
        code=$(curl -s -o /tmp/r.$$ -w "%{http_code}" \
                 -X POST http://app:8080/api/v1/links \
                 -H "Content-Type: application/json" \
                 -d "{\"targetUrl\":\"https://example.com/bench/{}\"}")
        sc=$(grep -o "\"shortCode\":\"[^\"]*\"" /tmp/r.$$ 2>/dev/null | head -1 | sed "s/.*:\"//; s/\"$//")
        rm -f /tmp/r.$$
        echo "$code ${sc:-}"
      '\'' _
    ' > "$WORK_DIR/create-raw.log" 2>"$WORK_DIR/create-err.log"
  RC=$?
  END_TS=$(date +%s)

  if [ $RC -ne 0 ]; then
    echo "❌ 容器内造数失败（exit=$RC）："
    cat "$WORK_DIR/create-err.log"
    exit $RC
  fi

  ELAPSED=$((END_TS - START_TS))
  echo "耗时 ${ELAPSED}s"
  echo

  # 从原始日志切出状态码分布与短码清单
  awk '{print $1}' "$WORK_DIR/create-raw.log" | sort | uniq -c | \
    while read -r n s; do echo "  $s : $n"; done
  awk '$1=="201" && NF>=2 {print $2}' "$WORK_DIR/create-raw.log" > "$CODE_LIST"

  C201=$(awk '$1=="201"' "$WORK_DIR/create-raw.log" | wc -l | tr -d ' ')
  C409=$(awk '$1=="409"' "$WORK_DIR/create-raw.log" | wc -l | tr -d ' ')
  C429=$(awk '$1=="429"' "$WORK_DIR/create-raw.log" | wc -l | tr -d ' ')
  C500=$(awk '$1=="500"' "$WORK_DIR/create-raw.log" | wc -l | tr -d ' ')
  C503=$(awk '$1=="503"' "$WORK_DIR/create-raw.log" | wc -l | tr -d ' ')
  OTHER=$(awk '$1!="201" && $1!="409" && $1!="429" && $1!="500" && $1!="503"' \
            "$WORK_DIR/create-raw.log" | wc -l | tr -d ' ')
else
  # ------------------------------------------------------------ 宿主 fork 并发
  echo "=== LinkForge 并发造数（宿主 fork 并发，仅供对照）==="
  echo "⚠️  该模式每请求 fork 一次进程，50 并发下约 13 请求/分钟，造 1 万需约 12 小时"
  echo

  for code in 201 409 429 500 503; do : > "$WORK_DIR/status-$code.txt"; done

  create_one() {
    local idx="$1" status
    status=$(curl -s -o "$WORK_DIR/resp-$idx.json" -w '%{http_code}' \
              -X POST "$BASE_URL_HOST/api/v1/links" \
              -H 'Content-Type: application/json' \
              -d '{"targetUrl":"https://example.com/bench/'"$idx"'"}' 2>/dev/null)
    status="${status:-000}"
    case "$status" in 201|409|429|500|503) ;; *) status=500 ;; esac
    echo "$status" >> "$WORK_DIR/status-$status.txt"
    if [ "$status" = "201" ]; then
      grep -o '"shortCode":"[^"]*"' "$WORK_DIR/resp-$idx.json" 2>/dev/null \
        | head -1 | sed 's/.*:"//; s/"$//' >> "$CODE_LIST"
    fi
    rm -f "$WORK_DIR/resp-$idx.json"
  }
  export -f create_one
  export BASE_URL_HOST WORK_DIR CODE_LIST

  START_TS=$(date +%s)
  seq 1 "$TOTAL" | xargs -P "$CONCURRENCY" -I{} bash -c 'create_one "$@"' _ {}
  END_TS=$(date +%s)
  ELAPSED=$((END_TS - START_TS))

  echo "耗时 ${ELAPSED}s"
  for code in 201 409 429 500 503; do
    n=$(wc -l < "$WORK_DIR/status-$code.txt" | tr -d ' ')
    echo "  $code : $n"
  done
  C201=$(wc -l < "$WORK_DIR/status-201.txt" | tr -d ' ')
  C409=$(wc -l < "$WORK_DIR/status-409.txt" | tr -d ' ')
  C429=$(wc -l < "$WORK_DIR/status-429.txt" | tr -d ' ')
  C500=$(wc -l < "$WORK_DIR/status-500.txt" | tr -d ' ')
  C503=$(wc -l < "$WORK_DIR/status-503.txt" | tr -d ' ')
  OTHER=0
fi

UNIQ_CODES=$(sort -u "$CODE_LIST" | wc -l | tr -d ' ')
DUP_CODES=$((C201 - UNIQ_CODES))

{
  echo "模式=$MODE"
  echo "总数=$TOTAL"
  echo "并发=$CONCURRENCY"
  echo "耗时秒=$ELAPSED"
  echo "201=$C201"
  echo "503=$C503"
  echo "409=$C409"
  echo "429=$C429"
  echo "500=$C500"
  echo "其他=$OTHER"
  echo "抽取短码数=$C201"
  echo "去重后短码数=$UNIQ_CODES"
  echo "重复短码数=$DUP_CODES"
  [ "$C201" -gt 0 ] && echo "请求速率_每秒=$(awk "BEGIN{printf \"%.1f\", $C201/($ELAPSED>0?$ELAPSED:1)}")"
} > "$SUMMARY"

echo
echo "=== 结果摘要（已写入 $SUMMARY）==="
cat "$SUMMARY"
echo

# ---------------------------------------------------------------- 唯一性验证
# ⚠️ 权威判据是数据库唯一性，不是这里的计数：
# 即使 201 数与去重数一致，若数据库里本就有同码的历史记录，仍会冲突。
echo "=== 唯一性检查（数据库口径）==="
if [ -n "${MYSQL_HOST:-}" ]; then
  DUP_IN_DB=$(docker exec linkforge-mysql mysql -ulinkforge \
      -p"${MYSQL_PASSWORD:-}" "${MYSQL_DATABASE:-linkforge}" -N -B \
      -e "SELECT COUNT(*) FROM (SELECT BINARY short_code FROM short_link
          GROUP BY short_code HAVING COUNT(*) > 1) t;" 2>/dev/null | tr -d ' ')
  # ⚠️ 必须用 BINARY（V3 之后 short_code 是 ascii_bin），
  #    用 LOWER() 会把 0Q1A 与 0Q1a 误判为重复——那是合法数据。
  echo "库内重复 short_code 组数 : ${DUP_IN_DB:-查询失败}"
  if [ "${DUP_IN_DB:-1}" != "0" ]; then
    echo "❌ 存在重复短码，发号器有问题"
    exit 1
  fi
  echo "✅ 库内无重复 short_code"
else
  echo "（未设 MYSQL_HOST，跳过库内检查——请手动执行 SELECT 检查唯一性）"
fi

echo
[ "$OTHER" != "0" ] && echo "⚠️  有 $OTHER 个请求返回了意外状态码（见 create-raw.log）"
if [ "$DUP_CODES" != "0" ]; then
  echo "❌ 应用层出现 $DUP_CODES 个重复短码，发号器有问题"
  exit 1
fi
if [ "$C500" != "0" ]; then
  echo "❌ 有 $C500 个 500：说明有编程错误漏到了兜底 handler，必须先修再谈基线"
  exit 1
fi
if [ "$C201" != "$TOTAL" ]; then
  echo "⚠️  有 $((TOTAL - C201)) 个请求未成功，跳转压测语料不足 $((TOTAL - C201)) 个"
fi

echo "✅ 造数完成，短码清单：$CODE_LIST（$UNIQ_CODES 个）"