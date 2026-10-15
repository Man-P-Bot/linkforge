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

1. **造数** → 2. **唯一性验证** → 3. **记录 `before_id`** → 4. **预热 10s（结果丢弃）**
→ 5. **3 次正式压测，每次 30s，取中位数** → 6. **清理**

预热必须在造数之后：预热请求会命中 MySQL 的 buffer pool，
先用真实语料把它捂热，正式压测测的才是稳态。

## 三、清理

```bash
# 压测前记录
docker exec linkforge-mysql mysql -ulinkforge -p"$MYSQL_PASSWORD" linkforge \
  -N -B -e "SELECT MAX(id) FROM short_link;"
# 压测后清理（保留 before_id 之前的记录，含手工创建的短码）
docker exec linkforge-mysql mysql -ulinkforge -p"$MYSQL_PASSWORD" linkforge \
  -e "DELETE FROM short_link WHERE id > $BEFORE_ID;"
```