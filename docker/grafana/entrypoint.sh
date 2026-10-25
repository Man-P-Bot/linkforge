#!/bin/sh
# =============================================================================
# Grafana 启动包装脚本（第 5 周）
#
# ⚠️ **为什么需要它**：Grafana 的 provisioning **不支持环境变量插值** ——
#    `secureJsonData.basicAuthPassword` 只接受字面值，$__env{} / ${VAR} 都不生效
#    （实测：datasource 健康检查返回 401 "Authentication to data source failed"）。
#
#    而 Prometheus 的 Basic Auth 密码必须与
#    docker/secrets/prometheus_basic_auth.txt 一致 —— 那个文件是 **secret**，
#    不进 git。
#
# ⚠️ **本脚本做的事**：启动前从 secret 读出明文密码，
#    用它渲染一份**临时的** datasource provisioning 文件到 /tmp，
#    再让 Grafana 从那里加载。
#    渲染结果落在容器的可写层（不落盘到宿主机、不进 git），
#    且 Grafana 启动后会把它读进数据库（secureJsonData 是加密存储的）。
#
# ⚠️ **为什么不用 `envsubst` 之类**：镜像里不一定有。
#    这里用 POSIX sh + sed，零额外依赖。
#
# ⚠️ **为什么不用 Grafana 的环境变量覆盖（GF_<DS>_*）**：
#    那套机制只覆盖 Grafana 自身的配置（server / security / plugins 等），
#    **不覆盖 datasource provisioning**。datasource 只能走文件。
# =============================================================================

set -e

PROVISION_DIR="${GF_PATHS_PROVISIONING:-/etc/grafana/provisioning}"
SECRET_FILE="/run/secrets/prometheus_basic_auth"
TEMPLATE="${PROVISION_DIR}/datasources/datasource.yml"
RENDERED="/tmp/provisioning/datasources/datasource.yml"

# ⚠️ 密码不存在时不阻断启动：Grafana 仍可用（只是数据源无数据）。
#    硬失败会让「忘了配 secret」变成「整个监控栈起不来」，
#    而前者的症状（面板 No data）更容易定位。
if [ ! -f "$SECRET_FILE" ]; then
  echo "[entrypoint] ⚠️  未找到 $SECRET_FILE，跳过 Prometheus 密码渲染"
  echo "[entrypoint]     数据源将无认证，若 Prometheus 开了 Basic Auth 则面板会 No data"
  exec /run.sh "$@"
fi

PROM_PASSWORD="$(cat "$SECRET_FILE")"

if [ -z "$PROM_PASSWORD" ]; then
  echo "[entrypoint] ⚠️  $SECRET_FILE 为空，跳过渲染"
  exec /run.sh "$@"
fi

echo "[entrypoint] 渲染 Prometheus 数据源凭据到 $RENDERED"

# ⚠️ 用 awk 而不是 sed：密码里可能含有 sed 的分隔符（/、&），
#    awk 的字符串替换没有这个问题。
mkdir -p "$(dirname "$RENDERED")"

# ⚠️ **必须保留原行的缩进**：basicAuthPassword 是 secureJsonData 的**子键**，
#    缩进不对它就变成了兄弟键 -> YAML 能解析，但 Grafana 拿不到密码
#    （症状：secureJsonFields 为空、健康检查 401，而日志里毫无异常）。
awk -v pw="$PROM_PASSWORD" '
  /^[[:space:]]*basicAuthPassword:/ {
    # 取原行的前导空白，再拼上键名
    match($0, /^[[:space:]]*/)
    indent = substr($0, 1, RLENGTH)
    print indent "basicAuthPassword: \"" pw "\""
    next
  }
  { print }
' "$TEMPLATE" > "$RENDERED"

# ⚠️ 权限收紧：渲染结果含明文密码，容器内其他进程不该能读。
chmod 600 "$RENDERED"

# 让 Grafana 从渲染目录加载（保留 datasources / dashboards / plugins 三个子目录）
export GF_PATHS_PROVISIONING=/tmp/provisioning

# ⚠️ **必须建齐所有 provisioning 子目录**，包括 alerting / plugins / notifiers /
#    access-control —— 哪怕是空的。
#    缺失的目录会让 Grafana 报 "can't read ... provisioning files from directory"，
#    而 **datasource 的加载排在最后**，前序报错可能让它**根本没执行**。
#    症状极具迷惑性：日志里只有 dashboard 的加载记录，完全看不到 datasource，
#    但数据源却「创建出来了」（Grafana 用内置默认配置兜底建了一个）——
#    于是 secureJsonFields 是空的、健康检查 401，而一切看起来都「正常」。
mkdir -p /tmp/provisioning/datasources \
         /tmp/provisioning/dashboards \
         /tmp/provisioning/plugins \
         /tmp/provisioning/alerting \
         /tmp/provisioning/notifiers \
         /tmp/provisioning/access-control

cp -r "${PROVISION_DIR}"/dashboards/. /tmp/provisioning/dashboards/ 2>/dev/null || true
cp -r "${PROVISION_DIR}"/plugins/.    /tmp/provisioning/plugins/    2>/dev/null || true
cp -r "${PROVISION_DIR}"/alerting/.  /tmp/provisioning/alerting/  2>/dev/null || true

echo "[entrypoint] 启动 Grafana（provisioning 路径: /tmp/provisioning）"
exec /run.sh "$@"
