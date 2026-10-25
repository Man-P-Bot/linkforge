#!/usr/bin/env bash
# =============================================================================
# 生成 Prometheus Basic Auth 的 bcrypt 哈希，并同步「哈希」与「明文」两处
#
# 为什么需要这个脚本：
#   Prometheus 的 web.config.yml **只接受 bcrypt 哈希**（不能写明文），
#   而 curl / Grafana 用的是**明文**。两者放在两个地方：
#     - docker/prometheus/web.config.yml          -> bcrypt 哈希（进 git）
#     - docker/secrets/prometheus_basic_auth.txt  -> 明文（gitignore）
#
#   ⚠️ 手动改密码时**只改一处**是本项目最容易犯的错：
#     改了 secret 里的明文但没改哈希 -> Prometheus 永远返回 401，
#     而 healthcheck 也跟着失败 -> 容器显示 unhealthy，
#     但 Prometheus 进程本身完全正常（典型的「假故障」）。
#
# 用法：
#   bash scripts/gen-prometheus-hash.sh '我的新密码'
#   bash scripts/gen-prometheus-hash.sh              # 不传则随机生成
#
# 依赖：Docker（用 httpd:alpine 镜像里的标准 htpasswd 生成 bcrypt）
#       本机无 htpasswd 时会提示用 docker run 手工执行。
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
cd "${ROOT_DIR}"

WEB_CONFIG="docker/prometheus/web.config.yml"
SECRET_FILE="docker/secrets/prometheus_basic_auth.txt"
SECRET_EXAMPLE="docker/secrets/prometheus_basic_auth.txt.example"
USERNAME="linkforge"

# -----------------------------------------------------------------------------
# 取密码
# -----------------------------------------------------------------------------
if [ $# -ge 1 ]; then
    PLAIN="$1"
else
    PLAIN="lf-$(head -c 12 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 16)"
    echo "==> 未指定密码，随机生成：${PLAIN}"
fi

# -----------------------------------------------------------------------------
# 生成 bcrypt 哈希
# -----------------------------------------------------------------------------
echo "==> 生成 bcrypt 哈希（用 httpd:alpine 的 htpasswd）..."

if command -v htpasswd >/dev/null 2>&1; then
    # 本机有 htpasswd（Apache 工具，常见于 Linux/macOS）
    HASH="$(htpasswd -nbB "${USERNAME}" "${PLAIN}" | cut -d: -f2-)"
    echo "    用的是本机 htpasswd"
else
    # 用 Docker 里的 httpd:alpine（标准 Apache 工具，行为一致）
    MSYS_NO_PATHCONV=1 docker run --rm httpd:alpine \
        htpasswd -nbB "${USERNAME}" "${PLAIN}" > /tmp/lf_htpasswd.txt 2>/dev/null
    HASH="$(cut -d: -f2- /tmp/lf_htpasswd.txt | tr -d '\r\n')"
    rm -f /tmp/lf_htpasswd.txt
    echo "    用的是 docker run --rm httpd:alpine"
fi

if [ -z "${HASH}" ]; then
    echo "❌ 生成失败（htpasswd 无输出）"
    exit 1
fi
echo "    哈希: ${HASH}"

# -----------------------------------------------------------------------------
# 更新 web.config.yml（哈希，进 git）
# -----------------------------------------------------------------------------
if [ ! -f "${WEB_CONFIG}" ]; then
    echo "❌ 找不到 ${WEB_CONFIG}"
    exit 1
fi

# 用 awk 保留缩进 —— 直接 sed 容易把缩进弄丢，
# 而 basic_auth_users 下的缩进错了 YAML 能解析但 Prometheus 不认。
awk -v user="${USERNAME}" -v hash="${HASH}" '
  /^[[:space:]]+[A-Za-z0-9_-]+:[[:space:]]+\$2[aby]\$/ {
    match($0, /^[[:space:]]*/)
    indent = substr($0, 1, RLENGTH)
    print indent user ": " hash
    next
  }
  { print }
' "${WEB_CONFIG}" > "${WEB_CONFIG}.tmp" && mv "${WEB_CONFIG}.tmp" "${WEB_CONFIG}"

echo "==> ✅ 已更新 ${WEB_CONFIG}"

# -----------------------------------------------------------------------------
# 更新 secret 文件（明文，gitignore）
# -----------------------------------------------------------------------------
mkdir -p "docker/secrets"
printf '%s\n' "${PLAIN}" > "${SECRET_FILE}"
echo "==> ✅ 已更新 ${SECRET_FILE}"

# 模板也同步 —— 否则新 clone 的人拿到的模板与哈希不配套
if [ -f "${SECRET_EXAMPLE}" ]; then
    # 保留模板的注释头，只换最后一行明文
    awk -v plain="${PLAIN}" '
      { lines[NR] = $0 }
      END {
        # 找到最后一行非注释、非空的行，替换它
        for (i = NR; i >= 1; i--) {
          if (lines[i] !~ /^[[:space:]]*#/ && lines[i] !~ /^[[:space:]]*$/) {
            lines[i] = plain
            break
          }
        }
        for (i = 1; i <= NR; i++) print lines[i]
      }
    ' "${SECRET_EXAMPLE}" > "${SECRET_EXAMPLE}.tmp" && mv "${SECRET_EXAMPLE}.tmp" "${SECRET_EXAMPLE}"
    echo "==> ✅ 已同步 ${SECRET_EXAMPLE}（模板，保证新 clone 的人拿到的密码同样可用）"
fi

# -----------------------------------------------------------------------------
# 提示重建
# -----------------------------------------------------------------------------
echo ""
echo "==> ⚠️ 密码已改，需要两步才能完全生效："
echo ""
echo "    ① Prometheus 重建（让它加载新的哈希）"
echo "       docker compose up -d --force-recreate prometheus"
echo ""
echo "    ② ⚠️ **重置 Grafana 的数据源**（这一步不能省）"
echo "       bash scripts/reset-grafana-datasource.sh"
echo ""
echo "    ② 为什么不能省：Grafana 的 datasource provisioning"
echo "       **不会覆盖已存在数据源的 secureJsonData**"
echo "       （Grafana 刻意设计：避免每次启动都用明文覆盖已加密的密码）。"
echo "       于是 Prometheus 用了新密码，而 Grafana 数据源里存的还是"
echo "       **旧密码的密文** -> 数据源健康检查 401、所有面板 No data，"
echo "       而 provisioning 日志一切正常（最容易误判的地方）。"
echo ""
echo "==> 验证："
echo "      curl -u \"${USERNAME}:$(cat ${SECRET_FILE})\" http://localhost:9090/-/healthy"
echo "      curl -u \"admin://$(cat docker/secrets/grafana_admin_password.txt)\" \\"
echo "           http://localhost:3000/api/datasources/1/health"
