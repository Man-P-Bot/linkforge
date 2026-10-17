#!/usr/bin/env bash
# =============================================================================
# LinkForge 并发造数 + 唯一性验证（第 1 周性能基线的语料来源）
#
# 目标（docs/ROADMAP.md §5 第 1 周验收）：并发创建 10000 个短码，数据库无重复、无失败。
# 这是「发号器在并发下是否唯一」的唯一性证明，也是跳转压测的语料来源。
#
# 用法：
#   ./scripts/bench-create.sh                 # 容器内并发（默认，快）
#   ./scripts/bench-create.sh 10000 20 host   # 宿主 fork 并发（慢，仅供对照）
#
# ⚠️ 为什么默认走容器：
#   宿主版每个请求都要 fork 一个 bash + curl 进程。实测 20 并发下
#   **每分钟仅约 5 个请求**（进程创建开销远大于请求本身），
#   造 1 万个需要约 14 小时。容器内单进程并发无此开销，实测快 3 个数量级。
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
#
# ⚠️ 并发数默认 20，不是 50——**并发必须小于连接池大小的 2 倍**：
#   创建接口每个请求都要开一个数据库连接（HikariCP maximum-pool-size=10）。
#   并发 50 时实测出现：
#     HikariPool-1 - Connection is not available, request timed out after 30000ms
#     (total=10, active=10, idle=0, waiting=136)
#   即 136 个请求在排队等 10 个连接，30 秒后集体超时，造数只完成 3432/10000。
#   并发 20 = 池子 2 倍，是「能压满但不排队」的上界。
#
#   ⚠️ **不要为了造数去调大连接池**——那是生产配置，
#   为了造数方便而改生产参数，等于让压测语料的生产环境与真实环境不一致。
#   造数慢一点没关系，它不参与性能数据。
# =============================================================================
set -uo pipefail

# --dry-run：只打印将要执行的命令，**不执行 docker/curl**。
# ⚠️ 为什么要有它：`bash -x` **不是 dry-run，它照常执行**。
#   曾用 `bash -x scripts/bench-create.sh 10000 2` 想「看看实际命令」，
#   结果它真的造了 1350 条数据进库。
#   「以为在观察、实际在执行」与「timeout 杀宿主但容器内进程存活」
#   是同一种认知偏差（见 DR-053）。
#   想看命令就用脚本自己的 --dry-run，不要用 -x。
# ⚠️ 必须先剥离 --dry-run 再取位置参数，否则 `bench-create.sh 10000 --dry-run`
#   会把 "--dry-run" 当成 $2（并发数）。
DRY_RUN=0
POSITIONAL=()
for arg in "$@"; do
  case "$arg" in
    --dry-run) DRY_RUN=1 ;;
    *) POSITIONAL+=("$arg") ;;
  esac
done
set -- "${POSITIONAL[@]+"${POSITIONAL[@]}"}"

TOTAL="${1:-10000}"
CONCURRENCY="${2:-20}"
MODE="${3:-container}"
BASE_URL_HOST="${BASE_URL:-http://localhost:8080}"
BASE_URL_CONTAINER="http://app:8080"
NETWORK="${DOCKER_NETWORK:-linkforge_default}"
WORK_DIR="${WORK_DIR:-./benchmark/results}"

# ⚠️ docker -v 的源路径**必须是 Windows 格式或 Docker 能自动转换的 MSYS 格式**。
#   相对路径（如 ./benchmark/results）在 Git Bash 下会被 Docker 当成**无名卷**，
#   容器内 /out 不存在 → 输出重定向失败 → 脚本 exit=1，
#   但 create-err.log 是空的，看起来像「没有原因」。
#   这个坑的实际后果：曾误判为「造数并发太高导致连接池超时」，
#   而真因是路径——两者是独立问题，不能混为一谈。
#   pwd -W 是 Git Bash 专有的，返回 D:/... 格式（正斜杠，Docker 友好）。
#   某些版本不可用时回退到 cygpath -w（返回 D:/... 反斜杠，Docker 同样接受）。
if [ -d "$WORK_DIR" ]; then
  WORK_DIR_ABS="$(cd "$WORK_DIR" && pwd -W 2>/dev/null || true)"
  if [ -z "$WORK_DIR_ABS" ]; then
    WORK_DIR_ABS="$(cygpath -w "$(cd "$WORK_DIR" && pwd)" 2>/dev/null || true)"
  fi
  [ -n "$WORK_DIR_ABS" ] && WORK_DIR="$WORK_DIR_ABS"
fi
CODE_LIST="$WORK_DIR/codes.txt"
SUMMARY="$WORK_DIR/create-summary.txt"

mkdir -p "$WORK_DIR"

if [ "$MODE" = "host" ]; then
  exec "$0" "$TOTAL" "$CONCURRENCY" internal-host
fi

if [ "$DRY_RUN" -eq 1 ]; then
  echo "=== [DRY-RUN] 不会执行任何 docker/curl，仅打印将执行的命令 ==="
  echo
  echo "目标数量 : $TOTAL"
  echo "并发数   : $CONCURRENCY"
  echo "模式     : $MODE"
  echo "网络     : $NETWORK"
  echo "WORK_DIR : $WORK_DIR"
  echo
  echo "--- 将执行 ---"
  echo "docker run --rm --network $NETWORK \\"
  echo "  -v $WORK_DIR:/out alpine:3 sh -c '... xargs -P $CONCURRENCY ... curl -X POST $BASE_URL_CONTAINER/api/v1/links ...'"
  echo
  echo "--- 将写入 ---"
  echo "$CODE_LIST（短码清单）"
  echo "$SUMMARY（统计摘要）"
  echo "$WORK_DIR/create-raw.log（原始响应）"
  echo "$WORK_DIR/create-err.log（stderr）"
  echo
  echo "=== DRY-RUN 结束，未做任何修改 ==="
  exit 0
fi

# ---------------------------------------------------------------- 容器内并发
if [ "$MODE" = "container" ]; then
  echo "=== LinkForge 并发造数（容器内并发）==="
  echo "目标数量 : $TOTAL"
  echo "并发数   : $CONCURRENCY"
  echo "目标地址 : $BASE_URL_CONTAINER（compose 网络内容器名，非 localhost）"
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