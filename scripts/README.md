# LinkForge 压测脚本

**测量口径以 `docs/PERFORMANCE.md` 为准**（DR-003 / DR-019）。

## 文件

| 文件 | 用途 |
|---|---|
| `bench-create.sh` | 并发造数 + 唯一性验证。产出短码清单供跳转压测使用 |
| `wrk-redirect.lua` | wrk 跳转压测脚本。从短码清单轮询取码，统计非 302 响应 |

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
→ 4. **等布隆重建完成**（见下，**不可省略**） → 5. **预热 10s（结果丢弃）**
→ 6. **3 次正式压测，每次 30s，取中位数** → 7. **清理**

预热必须在造数之后：预热请求会命中 MySQL 的 buffer pool，
先用真实语料把它捂热，正式压测测的才是稳态。

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
