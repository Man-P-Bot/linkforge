#!/usr/bin/env bash
# =============================================================================
# 一键初始化本地环境（第 5 周）
#
# 目的：让「没有任何密钥、刚 clone 仓库的人」敲一条命令就能起起全套。
#
# 它做四件事：
#   1. .env.example  -> .env（已存在则跳过，不覆盖用户改动）
#   2. docker/secrets/*.example -> 真实 secret 文件（已存在则跳过）
#   3. 校验 Prometheus 的「明文密码」与「bcrypt 哈希」是否匹配
#   4. 打印访问方式（含初始密码的获取命令）
#
# ⚠️ 为什么必须有第 3 步：
#   Docker Compose v2 在 secret 文件**缺失**时**不报错** —— 它会挂一个空目录进去，
#   容器照常启动。于是：
#     - Grafana：GF_SECURITY_ADMIN_PASSWORD__FILE 指向空文件 -> 容器正常起，
#                但 admin 密码为空 -> 用户登不进去，且**没有任何错误提示**；
#     - Prometheus：web.config.yml 里的 bcrypt 哈希是**进 git 的**（固定值），
#                而 secret 里的明文是空的 -> 认证永远失败。
#   这是一个「静默故障」：一切看起来正常，但用不了。
#   本脚本把它变成「启动前的一次显式校验」。
#
# 用法：
#   bash scripts/setup-local.sh
#
# 跨平台：Git Bash / Linux / macOS 均可（纯 POSIX sh 语法）。
# =============================================================================
set -euo pipefail

# ⚠️ 必须定位到仓库根目录（脚本可能在任意 cwd 下被调用）
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
cd "${ROOT_DIR}"

echo "==> LinkForge 本地环境初始化"
echo "    仓库根目录: ${ROOT_DIR}"
echo ""

# -----------------------------------------------------------------------------
# 1. .env
# -----------------------------------------------------------------------------
if [ -f ".env" ]; then
    echo "[1/4] ✅ .env 已存在 —— 跳过（不覆盖你的改动）"
else
    if [ ! -f ".env.example" ]; then
        echo "[1/4] ❌ 找不到 .env.example —— 仓库不完整？"
        exit 1
    fi
    cp ".env.example" ".env"
    echo "[1/4] ✅ 已从 .env.example 生成 .env"
fi

# -----------------------------------------------------------------------------
# 2. Docker secrets
# -----------------------------------------------------------------------------
# ⚠️ **复制时必须剥离注释行**。
#    Grafana 的 GF_SECURITY_ADMIN_PASSWORD__FILE 会把 secret 文件的**整个内容**
#    当成密码（包括每一行注释和换行）。若模板里留了说明性注释，
#    用户 `cat` 出来看到的是「注释 + 明文」，而 Grafana 实际要的是「注释 + 明文」整段
#    —— 于是「照着 cat 出来的明文输入」登录失败，且毫无提示。
#    Prometheus 的 healthcheck 同样用 `$(cat ...)`，多行内容会让 Basic Auth 失败。
#
#    判据：**secret 真实文件 = 纯明文单行**，一行不多。
#    （Docker Secrets 的官方示例也是纯内容，无注释。）
mkdir -p "docker/secrets"
created=0
for example in docker/secrets/*.example; do
    [ -e "${example}" ] || continue
    target="${example%.example}"
    if [ -f "${target}" ]; then
        echo "[2/4] ✅ ${target} 已存在 —— 跳过"
    else
        # 取「最后一行非注释、非空」的内容作为明文（兼容带注释头的旧模板）
        plain_only="$(grep -v '^[[:space:]]*#' "${example}" | grep -v '^[[:space:]]*$' | tail -1 | tr -d '\r')"
        if [ -z "${plain_only}" ]; then
            echo "[2/4] ❌ ${example} 里找不到明文（全是注释？）"
            exit 1
        fi
        printf '%s\n' "${plain_only}" > "${target}"
        echo "[2/4] ✅ 已从模板生成 ${target}（已剥离注释，只保留明文单行）"
        created=$((created + 1))
    fi
done
if [ "${created}" -eq 0 ]; then
    echo "[2/4] ✅ 所有 secret 均已就绪"
fi

# -----------------------------------------------------------------------------
# 3. 校验 Prometheus 凭据的「明文 ↔ 哈希」一致性
# -----------------------------------------------------------------------------
echo ""
echo "[3/4] 校验 Prometheus 凭据..."

SECRET_FILE="docker/secrets/prometheus_basic_auth.txt"
GF_SECRET_FILE="docker/secrets/grafana_admin_password.txt"
WEB_CONFIG="docker/prometheus/web.config.yml"

# 明文：取 secret 文件里第一行非注释、非空的内容
plain=""
if [ -f "${SECRET_FILE}" ]; then
    plain="$(grep -v '^[[:space:]]*#' "${SECRET_FILE}" | grep -v '^[[:space:]]*$' | head -1 | tr -d '\r\n')"
fi

# 哈希：从 web.config.yml 里取
hash=""
if [ -f "${WEB_CONFIG}" ]; then
    hash="$(grep -E '^[[:space:]]+[A-Za-z0-9_-]+:[[:space:]]+\$2[aby]\$' "${WEB_CONFIG}" | head -1 | sed -E 's/.*:[[:space:]]*(\$2[aby]\$.*)/\1/' | tr -d '\r\n')"
fi

if [ -z "${plain}" ] || [ -z "${hash}" ]; then
    echo "      ⚠️  明文或哈希缺失，无法校验"
    echo "         明文文件: ${SECRET_FILE}"
    echo "         哈希文件: ${WEB_CONFIG}"
    echo "      → Prometheus 的 Basic Auth 可能无法登录。"
elif [ "${plain}" = "change_me_prometheus_basic_auth" ]; then
    # ⚠️ 模板默认值 —— 与进 git 的哈希是配套的，直接可用（已实测 HTTP 200）
    echo "      ✅ 使用模板默认密码，与 web.config.yml 里的哈希**配套**"
    echo "      （若你改了明文密码，必须同步重新生成哈希，见下方提示）"
else
    # 用户可能改过密码 —— 必须实测校验，不能假设
    echo "      ⚠️  检测到你修改过明文密码，正在实测与哈希是否匹配..."
    verify="$(mktemp)"
    printf 'linkforge:%s\n' "${hash}" > "${verify}" 2>/dev/null || true
    matched=0
    if command -v htpasswd >/dev/null 2>&1; then
        if htpasswd -vb "${verify}" linkforge "${plain}" >/dev/null 2>&1; then
            matched=1
        fi
    elif command -v docker >/dev/null 2>&1; then
        # ⚠️ 用与 gen-prometheus-hash.sh 完全相同的方式校验，
        #    否则「生成用 docker、校验用本机」会出现口径不一致。
        if MSYS_NO_PATHCONV=1 docker run --rm -v "${verify}:/tmp/htpasswd:ro" \
                httpd:alpine \
                htpasswd -vb /tmp/htpasswd linkforge "${plain}" >/dev/null 2>&1; then
            matched=1
        fi
    fi
    rm -f "${verify}"

    if [ "${matched}" = "1" ]; then
        echo "      ✅ 匹配"
        echo "      ⚠️  但 Grafana 的数据源里可能还存着**旧密码的密文**"
        echo "         （Grafana 的 provisioning 不会覆盖 secureJsonData）。"
        echo "         若面板显示 No data，跑：bash scripts/reset-grafana-datasource.sh"
    else
        echo "      ❌ **不匹配** —— Prometheus 登录会失败（401）"
        echo "         修复两步："
        echo "           bash scripts/gen-prometheus-hash.sh '${plain}'"
        echo "           docker compose up -d --force-recreate prometheus"
        echo "           bash scripts/reset-grafana-datasource.sh"
    fi
fi

# -----------------------------------------------------------------------------
# 3b. 若 Grafana 已在运行，顺带验证数据源健康（能发现「密码改了但没重置数据源」）
# -----------------------------------------------------------------------------
GRAFANA_URL="${GRAFANA_URL:-http://localhost:3000}"
if [ -f "${GF_SECRET_FILE}" ] && command -v curl >/dev/null 2>&1; then
    GF_PW="$(grep -v '^[[:space:]]*#' "${GF_SECRET_FILE}" | grep -v '^[[:space:]]*$' | head -1 | tr -d '\r\n')"
    ds_status="$(curl -s -u "admin:${GF_PW}" "${GRAFANA_URL}/api/datasources/1/health" 2>/dev/null \
        | python -c "
import json,sys
try:
    print(json.load(sys.stdin).get('status',''))
except Exception:
    print('')
" 2>/dev/null || true)"
    if [ -n "${ds_status}" ]; then
        if [ "${ds_status}" = "OK" ]; then
            echo "      ✅ Grafana 数据源健康（status=OK）"
        else
            echo "      ❌ Grafana 数据源异常（status=${ds_status}）"
            echo "         常见原因：改了 Prometheus 密码但没重置数据源"
            echo "         修复：bash scripts/reset-grafana-datasource.sh"
        fi
    else
        echo "      (Grafana 未在运行，跳过数据源健康检查)"
    fi
fi

# -----------------------------------------------------------------------------
# 4. 打印访问方式
# -----------------------------------------------------------------------------
echo ""
echo "[4/4] 访问方式"
cat << 'EOF'

  启动全套（5 个容器）：
      docker compose up -d
      docker compose ps          # 期望 5 个服务均 healthy

  ┌──────────────┬─────────────┬──────────────────────────────────────────┐
  │ 服务         │ 地址        │ 凭据                                     │
  ├──────────────┼─────────────┼──────────────────────────────────────────┤
  │ 应用         │ :8080       │ 无（公开接口）                           │
  │ Prometheus   │ :9090       │ 用户名 linkforge，密码见下方             │
  │ Grafana      │ :3000       │ 用户名 admin，密码见下方                 │
  │ MySQL        │ :3307       │ 见 .env                                 │
  │ Redis        │ :6379       │ 无密码（已知风险，见 docs/PROGRESS.md）  │
  └──────────────┴─────────────┴──────────────────────────────────────────┘

  获取 Prometheus 密码：
      cat docker/secrets/prometheus_basic_auth.txt
      curl -u "linkforge:$(cat docker/secrets/prometheus_basic_auth.txt)" \
           http://localhost:9090/api/v1/targets

  获取 Grafana 密码：
      cat docker/secrets/grafana_admin_password.txt
      # 浏览器打开 http://localhost:3000，用 admin + 上面的密码登录

  ⚠️ Prometheus 密码说明：
      Prometheus 实际用的是 docker/prometheus/web.config.yml 里的 **bcrypt 哈希**
      （进 git，泄露也拿不到明文），而 docker/secrets/prometheus_basic_auth.txt 里是
      **明文**（给 curl 用，gitignore）。
      两者的密码必须一致 —— **改密码时必须同时更新两处**：
          bash scripts/gen-prometheus-hash.sh '你的新密码'

EOF

echo "==> ✅ 初始化完成。下一步：docker compose up -d"
