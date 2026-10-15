# LinkForge 性能基线报告

> 测量口径以本文档为准（DR-003 / DR-019）。
> **第 5 周的优化结果必须与本文档的数字并列对比**，因此本文档同时记录了环境、口径与已知风险，
> 而不只是一组数字。没有口径的数字无法对比，等于没测。

---

## 1. 第 1 周基线（2026-10-15 实测）

### 1.1 摘要

| 指标 | 数值 | 说明 |
|---|---|---|
| 跳转 QPS | **13426 req/s** | 3 次 30s 取中位数 |
| 跳转 P50 | **6.59ms** | |
| 跳转 P99 | **36.32ms** | 3 次最大值；三次分别为 36.32 / 35.94 / 38.90ms |
| 跳转 P99 最大值 | **38.90ms** | 三次里最差的一次 |
| Socket 错误 | **2 ~ 20 次/ 30s** | 全部为 `timeout`，占比约 0.006% |
| 非 302 响应 | **0** | 压测流量全部命中有效短码 |
| 并发创建 QPS | **303 req/s** | 造数实测，50 并发 |
| 缓存命中率 | **0%** | 第 1 周无缓存，这是 PRD A-3 要求的分母，不是缺陷 |
| 短码唯一性 | **10000/10000 无重复** | 库内 `BINARY` 口径校验 |

### 1.2 三次正式压测原始数据

命令（每次相同）：

```bash
MSYS_NO_PATHCONV=1 docker run --rm --network linkforge_default \
  -v "D:/homework/projects/linkforge/scripts:/scripts:ro" \
  -v "D:/homework/projects/linkforge/benchmark/results:/codes:ro" \
  -e CODE_LIST=/codes/codes.txt \
  williamyeh/wrk -t4 -c100 -d30s --latency -s /scripts/wrk-redirect.lua http://app:8080
```

| 次数 | 总请求数 | QPS | P50 | P90 | P99 | timeout |
|---|---|---|---|---|---|---|
| 1 | 409624 | 13638.41 | 6.49ms | 18.33ms | 36.32ms | 15 |
| 2 | 403546 | 13426.86 | 6.59ms | 18.22ms | 35.94ms | 20 |
| 3 | 371537 | 12345.86 | 7.12ms | 19.38ms | 38.90ms | 2 |
| **中位数** | 403546 | **13426.86** | 6.59ms | 18.33ms | 36.32ms | 15 |

> **为什么 QPS 取中位数而不是最大值**：第 5 周要对比的是「同一脚本、同一环境」下的改进比例。
> 取最大值等于挑最好的那次，会把环境噪声当成优化效果。

### 1.3 并发造数（唯一性验证）

| 项 | 结果 |
|---|---|
| 请求数 | 10000 |
| 201 | **10000** |
| 503 / 409 / 429 / 500 / 其他 | **0 / 0 / 0 / 0 / 0** |
| 库内重复短码组数（`BINARY` 口径） | **0** |
| 耗时 | 33s |
| 速率 | 303 req/s（50 并发） |

### 1.4 压测窗口内应用侧错误数

**0**。压测窗口（22:15~22:19）的应用日志中 `ERROR` 计数为 0。

---

## 2. 环境快照

⚠️ 第 5 周复现时必须与本节一致，否则数字不可比。

### 2.1 硬件与宿主

| 项 | 值 |
|---|---|
| 操作系统 | Windows（Git Bash 环境） |
| 逻辑核数 | **18** |
| 内存 | 23.5 GB |
| **Docker 可用内存** | **11.42 GiB** ← 本环境最紧的约束 |

> ⚠️ Docker 只分到 11.42 GiB，要同时承载 MySQL、Redis、应用 JVM，
> 以及第 5 周的 Prometheus 与 Grafana。后续若出现容器被OOM Kill，**先怀疑这里**。

### 2.2 容器版本

| 组件 | 版本 | 镜像 |
|---|---|---|
| Docker Engine | **29.6.2** | — |
| MySQL | **8.4.11** | `mysql:8.4` |
| Redis | **7.4.11** | `redis:7.4` |
| 应用运行时 | JRE **17** | `eclipse-temurin:17-jre-jammy` |
| 构建 | Maven **3.9.12** | `maven:3.9.12-eclipse-temurin-17` |
| 压测工具 | wrk | `williamyeh/wrk` |

### 2.3 应用配置（DR-038口径）

```
JAVA_OPTS=-Xms1g -Xmx1g -XX:+UseG1GC -XX:+PrintGCDetails -Xlog:gc*:file=/dev/stdout:time,uptime,level,tags
```

- 固定堆（1g）：基线必须可复现，不固定堆则第 2 周调优时「堆大小」会混进「代码优化」的效果里。
- `-XX:+PrintGCDetails` 在 JDK 17 已废弃，**保留仅作历史记录**，GC 日志一律读 `-Xlog:gc*`。
- 数据源：`jdbc:mysql://mysql:3306/linkforge?...&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true`

---

## 3. 测量口径声明（必读）

### 3.1 压测拓扑

```
wrk 容器（--network linkforge_default）
        │
        │  HTTP，compose 内部网络
        ▼
app 容器（:8080，堆固定 1g）
        │
        ▼
MySQL 8.4（mysql:3306）      Redis 7.4（发号，仅创建路径）
```

⚠️ **URL 必须用服务名 `app`**，不能用 `localhost`——
容器内的 `localhost` 指向压测容器自身，这是最容易犯的错（DR-003）。

### 3.2 绝对延迟的解读

wrk 与被测服务同处一个 Docker 网络、同一台宿主机，
因此**测到的绝对延迟包含网络栈开销，不代表真实用户视角的延迟**。
（相比之下，把压测容器与被测服务放在跨主机场景，绝对值会更接近真实。）

**本基线的用途是第 5 周的同口径对比**：
同一脚本、同一拓扑、同一硬件，优化前后的**比例**才是结论，绝对值次要。

### 3.3 语料说明

压测请求命中 10000 个真实短码（`benchmark/results/codes.txt`，由 `scripts/bench-create.sh` 造数产生）。

- 脚本按连接轮询取码，不随机生成——随机会大量命中 404/400，
  测到的就成了「错误处理路径」而不是「跳转主路径」。
- **非 302 响应数为 0**，证明流量全部打在有效短码上。
  若该值不为 0，本基线直接作废。

### 3.4 顺序不可颠倒

```
造数 → 唯一性验证 → 记录 before_id → 预热 10s → 3×30s 正式压测 → 清理
```

**预热必须在造数之后**：预热请求会捂热 MySQL 的 buffer pool，
先用真实语料捂热，正式压测测的才是稳态。

---

## 4. 造数与清理的可复现步骤

```bash
# 1. 造数（容器内并发，50 并发）
export MYSQL_HOST=1
export MYSQL_PASSWORD=$(grep MYSQL_PASSWORD .env | cut -d= -f2)
./scripts/bench-create.sh 10000 50

# 2. 记录 before_id（压测后据此清理，保留此前的记录）
docker exec linkforge-mysql mysql -ulinkforge -p"$MYSQL_PASSWORD" linkforge \
  -N -B -e "SELECT MAX(id) FROM short_link;"

# 3. 预热（结果丢弃）
MSYS_NO_PATHCONV=1 docker run --rm --network linkforge_default \
  -v "$PWD/scripts:/scripts:ro" -v "$PWD/benchmark/results:/codes:ro" \
  -e CODE_LIST=/codes/codes.txt \
  williamyeh/wrk -t4 -c100 -d10s --latency -s /scripts/wrk-redirect.lua http://app:8080

# 4. 3 次正式压测（每次 30s）

# 5. 清理
docker exec linkforge-mysql mysql -ulinkforge -p"$MYSQL_PASSWORD" linkforge \
  -e "DELETE FROM short_link WHERE id > $BEFORE_ID;"
```

⚠️ **Windows / Git Bash 用户必须设 `MSYS_NO_PATHCONV=1`**，
否则 `/scripts/...` 会被改写成 Windows 路径，容器内找不到文件。

---

## 5. 已知风险与限制

| # | 问题 | 影响 | 处置 |
|---|---|---|---|
| 1 | **宿主机 fork 版造数极慢**：每请求 fork 一个 `bash`+`curl`，50 并发下约 **13 请求/分钟**，造 1 万需约 12 小时 | 若误用宿主版，基线采集会超时 | 默认已改为容器内并发（实测 303 req/s，快 3 个数量级）。宿主版保留为 `bench-create.sh <n> <c> host`，**仅供对照，不得用于正式采集** |
| 2 | **wrk 压测与应用共享 CPU**：宿主机 18 核上同时跑 wrk（4 线程）+ 应用 JVM + MySQL + Redis | QPS 可能受压测端自身瓶颈限制，而非应用上限 | 第 5 周对比时**沿用同一拓扑**，比例仍成立；若要测绝对上限，需把 wrk 单独部署 |
| 3 | **Socket timeout 2~20 次/30s** | 占比约 0.006%，对 P99 影响可忽略 | 记录但不处理；第 5 周若显著上升需排查连接池 |
| 4 | **`GET /`（根路径）返回 500 而非 404** | 契约缺陷。`NoResourceFoundException` 落到兜底 handler | **已知缺陷，第 1 周不修**（不影响跳转基线）。第 2 周前应补 `NoResourceFoundException` → 404 的映射 |
| 5 | **Docker 可用内存仅 11.42 GiB** | 第 5 周加 Prometheus + Grafana 后可能 OOM | 届时先削减 MySQL 缓冲池与 Grafana 采样频率 |
| 6 | **Flyway 曾观测到一条来源未定位的 WARN**（`Detected 2 non-empty schema history table(s)`） | 无法复现，字节码中无此消息；启动日志不含 ERROR | 不做修复，记录在案（见 `docs/PROGRESS.md`） |
| 7 | **`-XX:+PrintGCDetails` 已废弃** | 基线与最终结果的 JVM 参数不完全一致的风险 | 第 5 周调优对比时，两边 GC 日志均取自 `-Xlog:gc*`；报告中显式声明参数差异（DR-038） |

---

## 6. 第 5 周对比模板

复现时按此表填写，与 §1.1 并列：

| 指标 | 第 1 周基线 | 第 5 周结果 | 变化 |
|---|---|---|---|
| 跳转 QPS | 13426 req/s | | |
| 跳转 P50 | 6.59ms | | |
| 跳转 P99 | 36.32ms | | |
| 缓存命中率 | 0% | | |
| 并发创建 QPS | 303 req/s | | |
| 短码唯一性 | 10000/10000 | | |
| 环境是否一致 | 见 §2 | ☐ 逐项核对 | |

⚠️ 环境快照（§2）任一项不一致时，**必须在报告中说明差异**，
否则「优化比例」会把环境变化误算成代码收益。