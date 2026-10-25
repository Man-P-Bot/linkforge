# LinkForge 压测脚本

**测量口径以 `docs/PERFORMANCE.md` 为准**（DR-003 / DR-019）。

## 文件

| 文件 | 用途 |
|---|---|
| `bench-create.sh` | 并发造数 + 唯一性验证。产出短码清单供跳转压测使用 |
| `wrk-redirect.lua` | wrk 跳转压测脚本。从短码清单轮询取码，统计非 302 响应 |
| `final-bench.sh` | **第 5 周终极压测**（阶段 4）。完整流程：造数 → 停监控 → 重启 app → 等布隆 → 预热 → 3×30s 取中位数 + 60s 补充 → 恢复监控 |
| `longrun-load.sh` | **长稳压测**（阶段 3）。低强度持续流量 `wrk -t2 -c10 -d8h`，用于 Metaspace 泄漏排查 |
| `longrun-collect.sh` | **长稳采集**（阶段 3）。每 30 分钟从 Prometheus 采 Metaspace / 已加载类数 / meter 数三条曲线，每 2 小时附 jcmd 类加载统计 |
| `longrun-watch.sh` | **长稳看护**（阶段 3）。每 60 秒读 Prometheus，**Metaspace 单次跳变 > 5MB 立即抓类加载分布** |
| `setup-local.sh` | **一键初始化**（第 5 周）。生成 `.env` + Docker Secrets，并**实测校验**凭据一致性（详见 [docs/PITFALLS.md](../docs/PITFALLS.md)） |
| `gen-prometheus-hash.sh` | 改 Prometheus 密码时**同步** bcrypt 哈希（进 git）与明文（gitignore）两处 |
| `reset-grafana-datasource.sh` | 改密码后**重置 Grafana 数据源**（幂等）——Grafana 的 `secureJsonData` 不会被 upsert 覆盖 |
| `wrk-percentile.lua` | 分位数采集辅助脚本 |

### 第 5 周两个脚本的关键差异

| | `final-bench.sh`（阶段 4） | `longrun-load.sh`（阶段 3） |
|---|---|---|
| 目的 | 测**性能上限** | 测**长时间稳定性** |
| 并发 | `-t4 -c100`（与前三周同口径） | `-t2 -c10`（不烤机器） |
| 时长 | 3×30s + 60s | 8 小时 |
| 监控容器 | **必须停掉**（`/actuator/prometheus` 每次抓取都要序列化全部 meter，会污染压测） | **必须留着**（它就是数据源） |
| 输出 | `benchmark/final/` | `benchmark/longrun/` |

⚠️ **造数前必须用 bench overlay 放宽限流**：

```bash
docker compose -f docker-compose.yml -f docker-compose.bench.yml up -d --force-recreate app
```

否则创建接口 10/min 的默认值会让绝大多数请求返回 429
（实测踩过：9986/10000 被限流，语料只剩 14 条）。

## 前置条件

- `docker compose ps` 三个服务（`mysql` / `redis` / `app`）均 **healthy**
- `app` 跑在容器内（`http://app:8080` 在 compose 网络内可达）

## 一、造数

```bash
# 宿主机执行。默认 10000 个，并发 50
./scripts/bench-create.sh 10000 50
```

产出：

- `benchmark/results/codes.txt` — 短码清单（每行一个）
- `benchmark/results/create-summary.txt` — 计数摘要

**统计口径**：脚本统计 **503**（`LINKFORGE-503-01`）而非 409——
409 是**自定义短码**冲突才返回，本脚本全部走自动短码，
自动短码的冲突最终表现为发号器重试超限 → 503（API.md §5.4）。
最终判据是**唯一性检查**：库内出现重复 `short_code` 即判定发号器故障。

## 二、跳转压测

```bash
docker run --rm --network linkforge_default \
  -v "$(pwd)/scripts:/scripts:ro" \
  -v "$(pwd)/benchmark/results:/codes:ro" \
  -e CODE_LIST=/codes/codes.txt \
  williamyeh/wrk \
  -t4 -c100 -d30s --latency -s /scripts/wrk-redirect.lua http://app:8080
```

⚠️ **URL 必须用服务名 `app`**，不能用 `localhost`——
压测容器内的 `localhost` 指向压测容器自身，这是最容易犯的错（DR-003）。

### 预热与正式压测的顺序（不可颠倒）

1. **造数** → 2. **唯一性验证** → 3. **记录 `before_id`**
→ 4. **等布隆重建完成**（见下，**不可省略**） → 5. **预热 ≥15s（结果丢弃）**
→ 6. **正式压测取中位数** → 7. **清理**

> ⚠️ **窗口长度取决于代码路径**（DR-057）：
> 第 3 周实测带 Redis 同步调用时，30s 窗口仍在 JIT 爬坡
> （三轮 25862→27641→28817），60s 才是稳态（29362）。
> 纯缓存命中路径用 30s 足够；**含限流/布隆同步调用的路径建议 60s**。

预热必须在造数之后：预热请求会命中 MySQL 的 buffer pool，
先用真实语料把它捂热，正式压测测的才是稳态。

### ⚠️ 压测前置检查（DR-057，**数据可信度先于一切**）

同样的代码，环境干扰下 QPS 能差 50%。压测前必须：

```bash
# 1. 宿主 CPU 占用 < 20%，loadavg < 2.0
cat /proc/loadavg            # Windows: Get-Counter "\Processor(_Total)\% Processor Time"
# ⚠️ 别只看 loadavg（Git Bash 里它只统计当前 shell 的进程）
#    要看**全系统 CPU 占用**和**进程列表**

# 2. 重启应用容器（清空 19 小时累积的 Metaspace / GC 状态）
docker compose -f docker-compose.yml -f docker-compose.bench.yml restart app

# 3. 等布隆重建完成（见下，**不可省略**）

# 4. 预热至少 15 秒
```

⚠️ **实测教训**：容器连续跑 19 小时后 Metaspace 到 74MB、G1 频繁
Evacuation，QPS 直接腰斩；宿主被无关应用（抖音）占掉 1.5 个核时，
QPS 同样腰斩。**两者都会让数据作废，且都与代码无关。**

### ⚠️ 必须等布隆重建完成（DR-055）

**不要用固定等待时间**（比如「等 30 秒」）。重建耗时随数据量线性增长
（实测 1 万条需要 **20 秒**，10 万条会更久），固定时间必然踩坑。

窗口期内**新创建的短码全部返回 404**——旧布隆还不包含它们。

**正确做法（两种任选）**：

```bash
# 方式 A：轮询日志（最直接，能顺便看到耗时与条数）
docker logs linkforge-app 2>&1 | grep --line-buffered "布隆全量重建完成"
# 输出形如：布隆全量重建完成：10009 条，耗时 19756 ms

# 方式 B：轮询 Redis（无日志权限时用）
until [ "$(docker exec linkforge-redis redis-cli EXISTS shortlink:bloom)" = "1" ]; do sleep 2; done
# ⚠️ 但 EXISTS=1 只说明「有 key」，不保证「包含新数据」——
#    若容器重启前就有旧 key，这个条件会立即满足却仍然是错的。
#    **优先用方式 A**。
```

⚠️ **实测踩过的坑**：第一次 curl 验证时全部 404，第二次就全 302，
差别就是第一次发生在重建完成之前（DR-055）。

## 三、清理

```bash
# 压测前记录
docker exec linkforge-mysql mysql -ulinkforge -p"$MYSQL_PASSWORD" linkforge \
  -N -B -e "SELECT MAX(id) FROM short_link;"
# 压测后清理（保留 before_id 之前的记录，含手工创建的短码）
docker exec linkforge-mysql mysql -ulinkforge -p"$MYSQL_PASSWORD" linkforge \
  -e "DELETE FROM short_link WHERE id > $BEFORE_ID;"
```
---

## 第2 周（缓存）压测口径补充

**预热在有缓存时是必需项，不是可选项。**

第 2 周没有加单飞（single-flight），冷启动时同一个短码的并发请求会各自回源。
实测：1 万条数据预热 10s 产生 **32180 次回源**（每条约 3 次）。
这些回源如果混进正式压测窗口，QPS 与 P99 都会被污染。

实测确认：预热后正式压测窗口的 `shortlink.cache.miss` 增量**为 0**，命中率 100%。

**指标从 actuator 读，不从日志数：**

```bash
# 三个指标（DR-045）
curl -s http://localhost:8080/actuator/metrics/shortlink.cache.local.hit
curl -s http://localhost:8080/actuator/metrics/shortlink.cache.redis.hit
curl -s http://localhost:8080/actuator/metrics/shortlink.cache.miss
```

⚠️ **正式窗口的命中率要用「指标差值法」算**——预热阶段的计数会被算进去。
每次压测前后各读一次指标，两次相减才是窗口内的真实命中数。

⚠️ **压测前必须清空缓存并重启**保证冷启动：

```bash
docker exec linkforge-redis redis-cli --scan --pattern "shortlink:code:*" \
  | xargs -r docker exec linkforge-redis redis-cli DEL
docker compose restart app
```

不重启的话 Caffeine 里的内容还在，Redis 里被删的 key 会在下次访问时被本地缓存挡回去，
测出来的就不是「两级缓存冷启动」而是「本地缓存热启动」。

**Redis key 核对（DR-045）：**

```bash
docker exec linkforge-redis redis-cli --scan --pattern "shortlink:*"
# shortlink:id:seq            发号器
# shortlink:code:0000Q0u      跳转缓存（与 ARCHITECTURE.md §206 一致）
```
