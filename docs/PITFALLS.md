# LinkForge 防呆清单（PITFALLS）

> **这份文档回答的是：「怎么让同类错误不再发生」。**
>
> 与 [TROUBLESHOOTING.md](TROUBLESHOOTING.md) 的分工：
> | 文档 | 面向 | 结构 |
> |---|---|---|
> | TROUBLESHOOTING.md | **已经出错了** | 现象 → 根因 → 解法 |
> | PITFALLS.md（本文） | **还没出错，但迟早会踩** | 共性 → 判据 → 自动化防线 |
>
> 下面 4 条坑全部来自第 5 周的监控栈落地，全部是**静默故障**——
> 「容器在跑、状态是 healthy、日志没有 ERROR」，
> 但服务**用不了**。
>
> **本项目提供一键启动脚本 `scripts/setup-local.sh`，已自动规避以下 4 个故障。**

---

## 目录

| # | 故障 | 一句话根因 | 谁在防 |
|---|---|---|---|
| [P-1](#p-1-docker-compose-在-secret-缺失时静默通过) | Compose 在 Secret 缺失时**不报错** | 缺失 ≠ 错误，被当成空值 | `setup-local.sh` |
| [P-2](#p-2-grafana-的-securejsondata-不会被-upsert-覆盖) | Grafana 数据源密码**不会更新** | 同一份凭据散在 3 个系统，同步不自动 | `gen-prometheus-hash.sh` + `reset-grafana-datasource.sh` |
| [P-3](#p-3-secret-文件必须是纯明文单行) | Secret 带注释 → **注释成了密码** | 「给人看的文件」被当「给程序用的文件」 | `setup-local.sh`（剥离注释） |
| [P-4](#p-4-crlf-会让密码多一个-r) | Windows CRLF → **密码多一个 `\r`** | 行尾不是内容，但对「整文件即值」的文件就是值 | `.gitattributes` + `setup-local.sh`（printf 重写） |

---

## P-1 Docker Compose 在 Secret 缺失时**静默通过**

### 现象

新 clone 的仓库跑 `docker compose up -d`，**一切正常**：

```
linkforge-app          Up (healthy)
linkforge-prometheus   Up (healthy)
linkforge-grafana      Up (healthy)
```

但 Grafana 登不进去（admin 密码为空），Prometheus 的 Basic Auth 永远 401。

### 为什么静默

Compose v2 的 `secrets: { file: ./path }` 在文件**不存在**时**不报错**——
它挂一个**空目录**到 `/run/secrets/<name>`，容器照常启动。

实测（最小复现）：

```yaml
services:
  dummy:
    image: busybox:1.36
    secrets: [my_secret]
secrets:
  my_secret:
    file: ./secrets/my_secret.txt   # ← 文件不存在
```

```
$ docker compose up -d
 Container clonetest2-dummy-1 Started     ← 成功！
```

⚠️ `docker compose config` 也不会报错（它只校验 YAML 结构，不检查文件存在性）。

### 判据（可迁移）

> **「静态检查全过」不等于「功能可用」。**
>
> 任何**只检查存在性**的验证（容器在跑？端口在听？healthcheck 通过？日志无 ERROR？）
> 都会在这种故障下**全部通过**。
>
> 唯一可靠的验证是：**真的拿凭据去请求一次。**

### 防线

`scripts/setup-local.sh` 在启动前**显式生成** secret 文件，并做**功能级**校验：

```bash
# 校验 1：Prometheus 的「明文」与「web.config.yml 里的 bcrypt 哈希」是否匹配
#         （用 htpasswd 实测，不用「看起来对」代替）
# 校验 2：curl 打 Grafana 的 /api/datasources/1/health，看 status 是否为 OK
# 校验 3：secret 文件是否为纯明文单行（见 P-3 / P-4）
```

---

## P-2 Grafana 的 `secureJsonData` **不会**被 upsert 覆盖

### 现象

改了 Prometheus 的密码并重建了 Prometheus：

```
$ curl -u "linkforge:新密码" http://localhost:9090/-/healthy
HTTP 200                                    ← Prometheus 侧正常
$ curl -u "admin:xxx" http://localhost:3000/api/datasources/1/health
{"status":"ERROR", ...}                     ← Grafana 数据源失败
```

Grafana 日志里 **provisioning 一切正常**，看板全是 No data。

### 为什么静默

Grafana 的 datasource provisioning 对 `secureJsonData` 的处理是：
**仅当数据源不存在时才写入密码**。

这是 Grafana 的**刻意设计** —— 避免每次容器启动都用明文去覆盖已加密的密码。

于是数据源已存在时（在 Grafana 的数据卷里）：
- `basicAuth: true` / `basicAuthUser` → **会**被 upsert 更新；
- `secureJsonData.basicAuthPassword` → **保持旧值不动**。

⚠️ 最迷惑的地方：`GET /api/datasources/1` 显示
`"secureJsonFields": {"basicAuthPassword": true}` —— 字段**确实存在**，
只是内容是**旧密码**。看字段是否存在**无法**判断密码对不对。

### 判据（可迁移）

> **同一份凭据散落在多个系统时，「改一处」不会自动传播。**
>
> 本项目的 Prometheus 密码有**三份**：

| 副本 | 位置 | 形态 | 是否进 git |
|---|---|---|---|
| 哈希 | `docker/prometheus/web.config.yml` | bcrypt | ✅ |
| 明文 | `docker/secrets/prometheus_basic_auth.txt` | 明文 | ❌（gitignore） |
| 密文 | Grafana 数据卷里的数据源 | Grafana 加密存储 | ❌ |

> 判据：**凭据类配置的「份数」就是风险面**。
> 份数 > 1 时，**改密码的流程必须脚本化**——
> 脚本的价值不只是省几行命令，而是**把同步顺序写死**
> （漏了第 ② 步的后果极隐蔽：面板全 No data，而日志一片正常）。

### 防线

```bash
bash scripts/gen-prometheus-hash.sh '新密码'       # ① 同步哈希 + 明文
docker compose up -d --force-recreate prometheus   # ② Prometheus 加载新哈希
bash scripts/reset-grafana-datasource.sh            # ③ 重置 Grafana 数据源（幂等）
```

`reset-grafana-datasource.sh` 的降级链：
API 删除数据源（provisioning 创建的是 `readOnly`，会 **403**）
→ 删 Grafana 数据卷重建。

⚠️ 降级会丢失**手工改过**的看板；本项目的看板全部是 provisioning 的
（`docker/grafana/dashboards/*.json`），重建后自动恢复，所以对本项目无损。

---

## P-3 Secret 文件必须是**纯明文单行**

### 现象

Secret 文件是从带注释的模板复制的：

```
# Grafana admin 密码
# 复制为 grafana_admin_password.txt 后修改。
# ⚠️ 真实文件被 .gitignore 排除，不会进 git。
change_me_grafana_admin_password
```

用户 `cat` 出这个文件，把**最后一行**当密码输入 → 登录失败。

### 为什么静默

`GF_SECURITY_ADMIN_PASSWORD__FILE` 会把 secret 文件的**整个内容**当成密码，
**包括每一行注释和中间的换行**。

于是「用户看到的明文」与「程序实际使用的密码」**根本不是同一个字符串**，
而且**没有任何报错**。

同样的问题会影响 Prometheus：healthcheck 用
`$(cat /run/secrets/prometheus_basic_auth)` 构造 Basic Auth，
多行内容会让认证头畸形 → healthcheck 永远失败。

### 判据（可迁移）

> **「给人看的文件」与「给程序用的文件」不是一回事。**
>
> 模板里加一行说明 seemed 很人性，但对「整文件即值」的消费者来说，
> 那行说明**就是密码的一部分**。
>
> 判据：**只要一个文件被整体当作配置值读取，它就只能有一个值。**
>
> 推广到别的场景：
> - `.env` → 每行一个 `KEY=VALUE`（这是另一种约定，一样要求「行内不能有多余内容」）
> - JSON 配置 → 不能有尾随逗号 / 注释
> - 证书 → 不能有 PEM 头尾之外的空白

### 防线

- `.example` 模板**只放纯明文**（说明文字移到 `setup-local.sh` 的输出与 README）；
- `setup-local.sh` 复制时**剥离注释行**（兼容手写的带注释模板）——
  用 `printf '%s\n'` 重写，而不是 `cp`。

---

## P-4 CRLF 会让密码多一个 `\r`

### 现象

在 Windows 上按 README 手工复制模板后，Grafana 登录失败。
用户 `cat` 出来的密码**肉眼完全正确**。

### 为什么静默

Windows 检出的 `.example` 是 **CRLF** 行尾（除非 `.gitattributes` 规定 LF）。
`cp` 是二进制复制，**保留源文件的行尾** → 真实 secret 也是 CRLF。

`GF_SECURITY_ADMIN_PASSWORD__FILE` 把内容原样当密码（去掉末尾换行，
中间的 `\r` 留着）→ 实际密码 = `change_me_grafana_admin_password\r`。

⚠️ **最具迷惑性的表现**：

| 方式 | 结果 |
|---|---|
| `curl -u "admin:$(cat file)"` | ✅ **能通**（`$(...)` 去掉尾部换行，但**保留 `\r`**） |
| 浏览器登录框粘贴 | ❌ **失败**（粘贴通常不带 `\r`） |

于是「curl 能通、浏览器不通」→ 让人误判成「密码是对的，是 Grafana 的问题」。

### 判据（可迁移）

> **行尾问题不只影响脚本。**
>
> - T-019：CRLF 让 `/bin/sh` 崩在第一行 → **语法错误，很显眼**
> - 本条：CRLF 让密码多一个字符 → **语义错误，不显眼**
>
> 同一个根因，两种完全不同的表现形式。
>
> 判据：**凡是「整文件即值」的文件**（secret、配置、密码、token），
> 行尾必须是 LF，而且要在**生成环节**保证，而不是指望用户复制时不出错。

### 防线

`.gitattributes`：

```
.env.example            text eol=lf
*.example                text eol=lf
.env                     text eol=lf
docker/secrets/*.txt     text eol=lf
```

叠加 `setup-local.sh` 的双重保险：复制时用 `printf '%s\n'` 重写（而非 `cp`），
生成的文件一定是 LF + 纯明文单行。

---

## 一分钟自检清单

新环境 / 改配置之后，逐条跑一遍。**每条都是「功能级」验证，不是存在性检查**。

```bash
# 0. 初始化（幂等，可反复跑）
bash scripts/setup-local.sh
#    期望输出：3 个 ✅（含「Grafana 数据源健康（status=OK）」）

# 1. 五个容器都真的在跑
docker compose ps
#    期望：5 个服务全部 healthy

# 2. Prometheus 的 Target 真的 UP（带凭据）
curl -u "linkforge:$(cat docker/secrets/prometheus_basic_auth.txt)" \
     "http://localhost:9090/api/v1/targets?state=active"
#    期望：health="up"  lastError=""

# 3. Grafana 的数据源真的能查到数据（不是「存在」，是「能查」）
curl -u "admin:$(cat docker/secrets/grafana_admin_password.txt)" \
     http://localhost:3000/api/datasources/1/health
#    期望：status="OK"

# 4. 应用真的暴露了指标（Prometheus 端点在工作）
curl -s http://localhost:8080/actuator/prometheus | grep -c '^shortlink_'
#    期望：> 0

# 5. Secret 文件是纯明文单行（没有注释、没有 CRLF）
for f in docker/secrets/*.txt; do
  printf "%-50s %s 行\n" "$f" "$(wc -l < "$f")"
done
#    期望：每个都是 1 行
```

> ⚠️ **第 5 条为什么单独列**：它是 P-3 / P-4 的直接自检。
> 一个「1 行但带 `\r`」的文件，行数是对的、密码是错的——
> 行数检查抓不到 `\r`，要靠「用这个密码真的请求一次」（第 2/3 条）才能确认。
