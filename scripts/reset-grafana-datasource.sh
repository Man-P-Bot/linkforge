#!/usr/bin/env bash
# =============================================================================
# 重置 Grafana 的 Prometheus 数据源（幂等）
#
# 为什么需要这个脚本：
#   Grafana 的 datasource provisioning **不会覆盖已存在数据源的 secureJsonData**。
#   源码逻辑是「仅当数据源不存在时才写入密码」——
#   理由是避免每次容器启动都用明文去覆盖已加密的密码。
#
#   于是改 Prometheus 密码后：
#     - Prometheus 用了新密码 ✅
#     - Grafana 数据源里存的还是**旧密码的密文** ❌
#     - 数据源健康检查 401，所有面板 No data
#     - 而 provisioning 日志一切正常（最容易误判的地方）
#
# 处置顺序（从轻到重）：
#   1. 删数据源（API）—— 但 provisioning 创建的数据源是 readOnly，会 403
#   2. 若 1 失败：删 Grafana 数据卷重建
#      ⚠️ 代价：手工改过的看板会丢。
#      本项目的看板全部是 provisioning 的（docker/grafana/dashboards/*.json），
#      重建后自动恢复，所以对本项目无损。
#
# 用法：
#   bash scripts/reset-grafana-datasource.sh
#
# 何时需要跑：
#   - 改了 Prometheus 密码之后（scripts/gen-prometheus-hash.sh 会提示）
#   - 数据源健康检查报 401 / "Authentication to data source failed"
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
cd "${ROOT_DIR}"

GRAFANA_URL="${GRAFANA_URL:-http://localhost:3000}"
PROM_URL="${PROM_URL:-http://localhost:9090}"
SECRET_FILE="docker/secrets/prometheus_basic_auth.txt"
GF_SECRET_FILE="docker/secrets/grafana_admin_password.txt"

if [ ! -f "${GF_SECRET_FILE}" ]; then
    echo "❌ 找不到 ${GF_SECRET_FILE} —— 先跑 scripts/setup-local.sh"
    exit 1
fi
GF_PW="$(cat "${GF_SECRET_FILE}" | tr -d '\r\n')"

echo "==> 1. 尝试通过 API 删除已存在的数据源"
deleted=0
ds_ids="$(curl -s -u "admin:${GF_PW}" "${GRAFANA_URL}/api/datasources" 2>/dev/null \
    | python -c "
import json,sys
try:
    ds = json.load(sys.stdin)
except Exception:
    ds = []
for d in ds:
    if d.get('type') == 'prometheus':
        print(d['id'])
" 2>/dev/null || true)"

for id in ${ds_ids}; do
    code="$(curl -s -u "admin:${GF_PW}" -X DELETE "${GRAFANA_URL}/api/datasources/${id}" \
        -o /dev/null -w '%{http_code}' 2>/dev/null || echo 000)"
    if [ "${code}" = "200" ]; then
        echo "    ✅ 已删除数据源 id=${id}"
        deleted=1
    else
        echo "    ⚠️  删除数据源 id=${id} 失败（HTTP ${code}）—— 它是 provisioning 创建的 readOnly 数据源"
    fi
done

if [ "${deleted}" = "1" ]; then
    echo "==> 2. 重启 Grafana，让 provisioning 用新密码重建数据源"
    docker compose restart grafana >/dev/null
else
    echo "==> 2. 降级：删 Grafana 数据卷并重建"
    echo "    ⚠️  手工改过的看板会丢失（本项目的看板全部是 provisioning 的，会自动恢复）"
    docker compose rm -sf grafana >/dev/null 2>&1 || true
    docker volume rm linkforge-grafana-data >/dev/null 2>&1 || true
    docker compose up -d grafana >/dev/null
fi

echo "==> 3. 等待 Grafana 就绪"
for i in $(seq 1 40); do
    if curl -s -u "admin:${GF_PW}" -o /dev/null "${GRAFANA_URL}/api/health" 2>/dev/null; then
        break
    fi
    sleep 3
done

echo "==> 4. 验证数据源认证"
ok=0
for i in $(seq 1 20); do
    status="$(curl -s -u "admin:${GF_PW}" "${GRAFANA_URL}/api/datasources/1/health" 2>/dev/null \
        | python -c "
import json,sys
try:
    print(json.load(sys.stdin).get('status',''))
except Exception:
    print('')
" 2>/dev/null || true)"
    if [ "${status}" = "OK" ]; then
        ok=1
        break
    fi
    sleep 3
done

if [ "${ok}" = "1" ]; then
    echo "    ✅ 数据源健康检查 OK"
else
    echo "    ❌ 数据源仍异常"
    echo "    排查："
    echo "      docker compose logs grafana | grep -i provisioning"
    echo "      curl -u \"admin:\$(cat ${GF_SECRET_FILE})\" ${GRAFANA_URL}/api/datasources/1"
    exit 1
fi

echo ""
echo "==> ✅ 完成。Prometheus 侧自检："
if [ -f "${SECRET_FILE}" ]; then
    echo "    curl -u \"linkforge:\$(cat ${SECRET_FILE})\" ${PROM_URL}/-/healthy"
fi
