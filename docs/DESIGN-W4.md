# 第 4 周设计：异步统计 + 过期清理 + 后台查询

> 状态：**已确认**（2026-10-19）。本文档是实现前的设计基线，实现过程中的偏离需回到此处说明。

---

## 1. 全局约束：DR-056 的教训必须贯穿整个设计

第 3 周实测：**每请求一次同步 Redis 调用，会在 Tomcat 线程池里累积成吞吐断崖**。
限流因这一条让 QPS 从 56638掉到 21085（折损 63%），而 Redis 的 CPU 只有 0.4%。

第 4 周的统计**每请求必然触发**。若不加隔离，会原样重演限流的覆辙。

因此本文档的第一原则：

> **跳转路径上不能有任何等待。** 统计可以丢，跳转不能慢。

---

## 2. 数据流

```
                          ┌─────────────────────────────────────┐
   GET /{code}             │        跳转主线程（同步）              │
     │                    │                                      │
     ├─► 限流（Redis EVAL）│  ← 唯一允许的同步 Redis 调用（DR-056）  │
     ├─► L1 缓存            │                                      │
     ├─► 布隆+L2（Redis）   │  ← L1 miss 时的唯一一次往返            │
     ├─► 回源（必要时）      │                                      │
     │                    │                                      │
     ├─► enqueue(PV, UV) ──┼────► 有界队列（容量 10000）              │
     │   ← 不等待 ←          │            │                        │
     │                    │            │ 队列满 → **直接丢弃**        │
     ▼                    │            │  + 计数器 shortlink.stats.dropped
   302 返回                │            ▼                        │
    （此时跳转已完成）        │      单消费线程（每 100ms 批量）        │
                          │            │                        │
                          │            ▼                        │
                          │   Redis 批量写：                        │
                          │     HINCRBY  shortlink:pv:{code}:{date} │
                          │     PFADD    shortlink:uv:{code}:{date} │
                          │            │                        │
                          │            ▼                        │
                          │  ┌──────────────────────────────────┐  │
                          │  │ @Scheduled 每 10 秒 + 分布式锁    │  │
                          │  │  1. Lua 原子：HGETALL + DEL      │  │
                          │  │  2. UPSERT 累加进 link_stats_daily │  │
                          │  │  3. 失败 → 死信队列                │  │
                          │  └──────────────────────────────────┘  │
                          └─────────────────────────────────────┘

   @Scheduled 每小时         ┌─────────────────────────────────────┐
     │                      │ 过期扫描：expire_at < NOW() 且 ACTIVE  │
     └─────────────────────►│ → status = DELETED（软删）           │
                            │ → 删 L2 缓存（L1 等 5 分钟 TTL）      │
                            └─────────────────────────────────────┘
```

---

## 3. Redis Key 结构

> ⚠️ **本节已按阶段 3 的实际实现更新**（2026-10-21）。

| Key | 类型 | TTL | 说明 |
|---|---|---|---|
| `shortlink:pv:{code}:{yyyymmdd}` | String（INCRBY 计数） | **35 天** | PV 计数，按天分片；flush 时用 GETDEL 取走即删 |
| `shortlink:uv:{code}:{yyyymmdd}` | HyperLogLog | **35 天** | UV 去重，标准误差 0.81%；flush 时只读（PFCOUNT）不删 |
| `shortlink:stats:dirty:{yyyymmdd}` | Set | **35 天** | 当日「有待落库的短码」，由 PV 的 Lua 写入时 SADD 登记，flush 用 SPOP 取走即删 |
| `shortlink:stats:flush:lock` | String（SET NX EX） | 60 秒 | 落库任务的全局锁；释放用 Lua 校验 value |
| `shortlink:stats:dl:{yyyymmdd}` | List | 35 天 | 死信队列：写 Redis 失败 / 落库 MySQL 失败 |

⚠️ **TTL 从设计稿的 3 天改为 35 天**（覆盖「当月 + 上月」，月初仍能查到上月报表，
另留 5 天缓冲应对跨月边界的写入延迟）。3 天太短：短链的访问是长尾的，
一个热门短链三天后还在被访问，PV 会被切在两天里。

⚠️ **所有统计 key 都必须有 TTL**，否则 Redis 内存只增不减。

### 3.1 内存预算（回答「108GB 从哪来」）

HyperLogLog 的内存与元素数**几乎无关**，取决于精度PP：

| PP | dense 编码大小 | 100 万短码 × 1 天 | 1 万短码 × 1 天 |
|---|---|---|---|
| 2^14（Redis 默认） | ~12 KB | **~12 GB** | **~120 MB** ✅ |
| 老实现 / 高PP | 16 KB~ | ~16 GB | ~160 MB |

⚠️ **108 GB 的典型成因**（三个都可能导致）：
1. **key 不清理** —— 没有 TTL，或 TTL 设成了 30 天
2. **PP 设得过高** —— 用 `PFADD` 时误配了自定义精度
3. **按天累积不删** —— 每天新增一批 key，30 天就是 30 倍

**三条防线**（必须全部实现）：
1. `PFADD` 时**同时设 TTL = 35 天**
2. PV key 在 flush 时用 **GETDEL 取走即删**；UV key **不删**
   （HLL 的值是累计基数，删掉会丢历史访客），靠 TTL 回收
3. 加 Micrometer 指标 `shortlink.stats.uv.keys`，超阈值告警

⚠️ **dirty set 也必须有 TTL**：否则「有流量但映射不到 link_id」的短码
（例如短链被物理删除）会永久驻留，白吃内存。

### 3.2 UV 的语义边界

HyperLogLog 的标准误差 **0.81%**，且**不存明细**。

> ⚠️ UV 只能用于**趋势展示**，不能用于精确对账或用户级查询。
> 这条必须写进 API.md，不能被「UV 就是去重人数」的误解。

⚠️ **UV 每天落一行，绝不跨天累加**。查询多日 UV 时用**日求和**，
并在业务层标注「估算偏高」（各日 HLL 基数相加，同一访客跨天会被重复计）。
数据库字段命名为 `uv_estimated`，让读 SQL 的人一眼看出这是估算值。

---

## 4. 异步统计基座

### 4.1 为什么用「有界队列 + 单消费线程」而不是 `@Async`

| 方案 | 问题 |
|---|---|
| `@Async` | 自调用失效、异常被吞、线程池满时行为不明确（默认丢弃且无感知） |
| 线程池 + 每任务一次 Redis | 每任务一次往返，5 万 QPS 下队列本身就成瓶颈 |
| **有界队列 + 单消费线程批量写** | ✅ 一次 `HINCRBY` 带多个 field，Redis 往返数与队列长度**解耦** |

### 4.2 队列满时的行为：**直接丢弃，不降级**

```java
if (!queue.offer(task)) {
    droppedCounter.increment();   // ⚠️ 不写 Redis，不阻塞
    return;                //   绝不回退到主线程同步写
}
```

> **为什么不能降级同步写**：队列满说明消费端跟不上，此时最稀缺的是
> **主线程的时间**。为了记一个统计数字而让跳转变慢，是本末倒置。
> 统计丢失可以事后从日志补，跳转延迟无法挽回。
>
> 这与限流「Redis 不可用时降级放行」是同一个原则的镜像：
> **保护措施失败时，宁可放弃保护的目标，也不能影响被保护的对象。**

### 4.3 消费端批量写

每 100ms 或队列满 500 条时触发，**一次 Redis 往返**：

```lua
-- PV
HINCRBY shortlink:pv:{code}:{yyyymmdd} 1
-- UV
PFADD  shortlink:uv:{code}:{yyyymmdd} {visitor}
PEXPIRE shortlink:uv:{code}:{yyyymmdd} 259200000   -- 3 天
```

⚠️ PV 用 `HINCRBY` 而非 `INCR`，是为了后续能按多 field 批量累加
（`HINCRBY key 1 f1 f2 f3`）。

### 4.4 UV 的 visitor标识

用 `X-Forwarded-For` 的第一个值（与限流的 `resolveClientId` 同源）。
拿不到时回退 `"unknown"` —— **所有匿名用户共用一个标识**，
会低估 UV。这是保守方向（不会高估），要在文档里说明。

---

## 5. 定时落库

> ⚠️ **本节已按阶段 3 的实际实现更新**（2026-10-21）。设计稿与实现有 5 处偏差，
> 逐条列在 5.7「设计 → 实现的偏差」里。实现与设计不一致时，**以代码为准**，
> 但偏差必须被记录而不是被遗忘——否则下一个人会按设计稿去改代码。

### 5.1 分布式锁

多实例部署时，定时任务会跑多遍。**重复累加的统计无法修正**（见 5.3）。

```java
// ⚠️ 实际实现：RedisDistributedLock.tryLock()
// SET key value NX EX ttl —— 加锁与设过期必须是**一条命令**。
// 拆成 SETNX + EXPIRE 时，两条之间崩溃会留下**永不过期**的锁，
// 只能人工删 key 才能恢复。
Boolean ok = redis.opsForValue().setIfAbsent(lockKey, instanceId, ttl);
```

⚠️ 释放时**必须用 Lua 校验 value**：

```lua
if redis.call('get', KEYS[1]) == ARGV[1] then
    return redis.call('del', KEYS[1])
else
    return 0
end
```

朴素写法会踩一个隐蔽故障：A 加锁后业务跑了 70 秒（锁已 60 秒过期），
B 接管了锁，此时 A 执行 `DEL lock` —— **把 B 的锁删了**，
第三个实例于是也能同时进入，三者并发落库，PV 累加出错且不可逆。

⚠️ 锁的 TTL（60 秒）必须**大于**单轮落库的最坏耗时，否则会出现
「实例 A 还在跑、锁已过期、实例 B 又进来」的并发落库。
本实现的算术：重试退避 1s + 4s + 16s = 21s，PV/UV 两路各一次 = 42s，
加 IO 约 45s < 60s。**改重试次数必须同步改 TTL。**

⚠️ 锁的 value 必须是**每次启动都不同**的随机 UUID。若所有实例共用主机名，
value 校验就形同虚设——A 可以删掉 B 的锁。

⚠️ 拿到锁之后、真正干活之前，**再校验一次 value**（`holdsLock`）。
这是防「超时实例继续破坏」的兜底：此时本实例已不是持有者，放弃本轮。

### 5.2 原子取出

⚠️ **实际实现与设计稿不同**：设计稿写的是「HGETALL + DEL 的 Lua 脚本」，
实现改成了 **dirty set + SPOP + GETDEL** 三个动作，理由如下。

#### 5.2.1 怎么找到「今天有流量」的短码：dirty set

```
shortlink:stats:dirty:{yyyymmdd}   Set   当日「有待落库的短码」
```

- **写侧登记**：PV 的 Lua 脚本（`pv_incr_by.lua`）在 `INCRBY` 的同一次执行里
  追加 `SADD dirty {code}` —— **不增加任何往返次数**。
- **读侧取走**：`SPOP key count`（单条命令，原子完成「取 N 个并移除」）。

⚠️ **为什么不用 `SCAN shortlink:pv:*`**：
- SCAN 是**游标遍历不是快照**，扫描期间新产生的 key 可能被漏掉（游标回绕机制决定），
  漏掉的 key 要等 35 天 TTL 到期才自然消失；
- 无法区分「今天有量」与「35 天内有量」，每分钟扫全量 key 是纯粹的浪费。

⚠️ **为什么绝不用 `SMEMBERS` + `SREM`**：
两者之间若有新流量登记进来，`SREM` 会把它一起删掉 ——
那批数据**既不会被本轮处理、也不会被下一轮处理**，
静静烂在 Redis 里直到 35 天 TTL 过期。
这是典型的「看起来有清理逻辑」的静默数据丢失。

⚠️ dirty set **也必须有 TTL**：否则「有流量但映射不到 link_id」的短码
（例如短链被物理删除）会永久驻留，白吃内存。

#### 5.2.2 取走即删：SPOP + GETDEL

| 数据 | 取数命令 | 语义 |
|---|---|---|
| dirty set 里的短码 | `SPOP key count` | 增量，取走即删 |
| PV 计数 | `GETDEL key`（Redis 6.2+） | 增量，取走即删 |
| UV 基数 | `PFCOUNT key` | **累计值，只读不删** |

⚠️ PV 的 `GETDEL` 必须是**一条命令**。先 GET 再 DEL 的窗口里若进程崩溃，
下次 flush 会把**同一份增量再落一次库** → PV 翻倍。

⚠️ UV 的 HLL key **不能删**：`PFCOUNT` 返回的是「当日至今的累计基数」，
删掉会把历史访客基数清零。残留由 35 天 TTL 回收。
下次同一短码再有流量时会重新登记 dirty，读到的仍是**更大**的累计值，
由 MySQL 侧的 `GREATEST` 天然去重。

### 5.3 幂等策略：宁可丢，不可重

⚠️ **实际实现按 `link_id` 聚合，不是 `short_code`**（V1 迁移的既定设计：
「短码编码规则若变化，历史统计无需回填」）。
Redis 里的 key 以短码命名，落库前必须做一次「短码 → 主键」映射：

```sql
SELECT id, short_code FROM short_link WHERE short_code IN (...)   -- 走 uk_short_code，不回表
```

⚠️ 映射**不过滤 status**：软删除的短链仍有历史访问量，那部分统计不该丢。

#### PV 与 UV 是两条独立的 SQL（关键）

```sql
-- PV：增量累加
INSERT INTO link_stats_daily (link_id, stat_date, pv, uv_estimated, referer_domain)
VALUES (?, ?, ?, 0, 'direct') ON DUPLICATE KEY UPDATE pv = pv + VALUES(pv)

-- UV：覆盖（GREATEST）
INSERT INTO link_stats_daily (link_id, stat_date, pv, uv_estimated, referer_domain)
VALUES (?, ?, 0, ?, 'direct') ON DUPLICATE KEY UPDATE uv_estimated = GREATEST(uv_estimated, VALUES(uv_estimated))
```

⚠️ **为什么必须分成两条**（两条理由都不可妥协）：

1. **累加语义相反**。PV 是「自上次落库以来新增的量」，多次落库要叠加；
   UV 的 `PFCOUNT` 是「当日至今的基数」，**不是增量**。
   若也用累加，一天 flush 1440 次会把同一个访客计 1440 遍。
   `GREATEST` 既符合语义（当日值只增不减），又让重试天然幂等。
2. **靶向重试的前提**。PV 成功、UV 失败时**只重试 UV**。
   若合并成一条语句，重试会把已成功的 PV 再累加一遍 ——
   而 PV 一旦多加**无法回滚**（UV 用 GREATEST，重复执行反而无害）。
   「数据丢了」是少，「数据多了」是错，后者严重得多。

> **明确取舍：宁可丢，不可重。**
>
> - **重复**累加 → 统计虚高，且**无法修正**（不知道多加了哪一次）
> - **丢失** → 只影响某一天的数据，可从死信队列或业务日志补
>
> 取「取出即删除」而非「取出→落库→确认→删除」，正是为了保证这一点。

### 5.4 失败处理

```
MySQL 落库失败 → 重试 3 次（退避 1s / 4s / 16s）
               → 仍失败 → 写入 shortlink:stats:dl:{yyyymmdd}，payload 带 stage=mysql
               → 下一轮 replayDlq 重放（LRANGE 窥探 + LREM 精确删除）
               → 重放仍失败 → 保留在队列，等下一轮
```

⚠️ **定时任务里绝不抛未捕获异常** —— Spring 的 `@Scheduled`
在任务抛异常后，同一任务的后续调度会被取消（单任务模式下），
且 Spring **不会重建**它。表现是「跑了一次之后统计再也没落过库」。
所以 `@Scheduled` 方法体内是 **catch-all `Throwable`**（不是 `Exception`——
`NoClassDefFoundError` 这类 Error 同样会让调度永久失效）。

⚠️ **DLQ 用 `LRANGE` 窥探 + `LREM` 精确删除**，不是 `LPOP`：
`LPOP` 一取出就出队，若取出后崩溃/失败，那条数据就没了 ——
死信队列会退化成「一次性丢弃队列」。

⚠️ **DLQ payload 必须带 `stage` 维度**：
`stage=mysql` 表示「MySQL 落库阶段失败」（重放动作是写数据库），
`stage=redis` 表示「Redis 写入阶段失败」（重放动作是写 Redis）。
用错会导致 PV 凭空翻倍或 UV 被清零。

⚠️ **DLQ 靶向重试的核心约束**：解析 payload 的 channel，
`pv` 只走 `accumulatePv`、`uv` 只走 `mergeUv`。
**绝不在一次重试里同时更新两者**。

⚠️ **DLQ 的 key 用「处理当天」而非 `stat_date`**：
DLQ 是「处理队列」，按哪一天发现失败来分桶；真正的归属日写在 payload 里。
若按 `stat_date` 分桶，一条 3 天前失败的数据会被塞进 3 天前的队列，
而 replay 只回看 `lookback-days` 天 —— 它就永远不会被处理了。

### 5.5 运行状态守卫

flush 执行前**必须先检查 `queue.isRunning()`**：

- `AsyncStatsQueue` 的消费线程**不可重启**（`Thread` 只能 `start()` 一次）。
  应用上下文重启后消费线程已死，此时继续 flush 只会持续抛异常，
  并触发 5.4 里的「调度永久失效」。
- 守卫的语义：**消费端不在工作，就不要做它下游的事。**

### 5.6 调度线程池

```yaml
spring:
  task:
    scheduling:
      pool:
        size: 2
```

⚠️ Spring Boot 的 `@Scheduled` 默认**单线程**调度：所有 cron 任务共用一个线程，
一个任务没跑完，后面排队的全部不执行。
而统计落库的坏情况会跑很久（重试 21s × 2 路 = 42s）——
数据库抖动期间，一个 42 秒的落库会**冻住整个调度器**，
阶段 4 的过期清理、阶段 5 的报表聚合全都会被一起拖死。

取 2：一个占位跑统计落库，另一个留给其它任务；
统计落库本身靠 Redis 全局锁保证只有一个实例真正执行。

### 5.7 设计 → 实现的偏差

| # | 设计稿 | 实际实现 | 理由 |
|---|---|---|---|
| 1 | 落库按 `short_code` | 按 `link_id` | V1 迁移既定设计（编码规则变化时历史统计无需回填）；flush 时做一次批量映射 |
| 2 | HGETALL + DEL 的 Lua | dirty set + SPOP + GETDEL | 4.2.1 / 4.2.2：HGETALL 方案无法表达「哪些短码今天有量」，且 SMEMBERS+SREM 有静默丢数据的窗口 |
| 3 | `uv = uv + VALUES(uv)` | `uv_estimated = GREATEST(...)` | UV 是当日累计值不是增量，累加会把同一访客计 1440 遍 |
| 4 | 字段名 `uv` | `uv_estimated` | HLL 是估算值（误差约 0.81%），字段名要让人一眼看出不能精确对账；V4 迁移完成重命名 |
| 5 | 锁 TTL 30 秒 | 60 秒 | 单轮最坏耗时约 45s（重试 42s + IO），30s 会导致双跑 |
| 6 | PV/UV 一条 SQL | 两条独立 SQL | 靶向重试的前提：合并会让「PV 已成功、UV 失败」的重试重复累加 PV |

---

## 6. 过期清理

### 6.1 软删除，不物理删

```sql
UPDATE short_link
SET status = 'DELETED'
WHERE expire_at < UTC_TIMESTAMP()
  AND status = 'ACTIVE'
LIMIT 5000
```

**为什么软删**：
1. 保留记录，后台统计能查到历史
2. 删除与「过期」是**不同的语义**——用户「过期」不等于「主动删除」
3. 物理删除会让统计失去关联

⚠️ **批量上限 5000**：避免长事务锁表。每轮扫完记剩余数量，下一轮继续。

### 6.2 缓存失效

```java
// L2 主动删
redis.delete(CacheKeys.code(shortCode));
// L1 只能等 5 分钟 TTL ⚠️ 已知限制
```

> **已知限制（接受并记录）**：Caffeine 的 `expireAfterWrite = 5 分钟`，
> 软删除后 L1 里的旧值仍可能返回 302，最长持续 5 分钟。
>
> **不改的原因**：
> - 为此在 L1 里存「删除时间戳」并每次判断，等于给 L1 增加一次 Redis 查询
> - 或缩短 L1 TTL 到秒级，会让 L1 命中率断崖式下降
> - 5 分钟的窗口对「已过期链接」这个场景可接受
>
> 若第 5 周需要修正，方向是「L1 存值 + 全局删除版本号，读时比对版本号」，
> 版本号从 Redis 读但可本地缓存几秒。

### 6.3 与 DR-038 的一致性

`DELETED` 优先级高于 `EXPIRED`（DR-038 已定）。
本任务只把 `ACTIVE` → `DELETED`，不碰已经是 `DELETED` 的记录，
因此不与该规则冲突。

---

## 7. 后台查询接口

```
GET /admin/api/v1/links/{code}/stats?from=2026-10-01&to=2026-10-19
```

```json
{
  "shortCode": "0000Q0u",
  "totalPv": 12345,
  "totalUv": 789,
  "daily": [
    { "date": "2026-10-18", "pv": 1024, "uv": 512 }
  ],
  "uvEstimated": true
}
```

### 7.1 只查聚合，不查明细

- PV 明细 ≈ 访问日志，几亿条，查询必然超时，**且无业务价值**
- `link_stats_daily` 按 `(short_code, stat_date)` 唯一，查询是**索引前缀扫描**
- ⚠️ **做不到的**：查「某天某短码的所有访问者」—— HLL 本就不存明细

### 7.2 查询约束

| 约束 | 值 | 理由 |
|---|---|---|
| 时间跨度上限 | **90 天** | 防止一次查几年数据 |
| 缺失日期 | 返回空行而非省略 | 前端不用处理稀疏数组 |
| `uvEstimated` 字段 | 恒为 `true` | 提醒调用方 UV 是近似值 |

---

## 8. 待验证项与已知限制

| # | 项| 状态 | 第 5 周处理 |
|---|---|---|---|
| 1 | 统计路径的 A/B 对照（DR-056 教训） | **必须做** | 开工第一件事 |
| 2 | UV key 的实际内存占用（验证 §3.1 的估算） | 待验证 | 加 `MEMORY USAGE` 指标实测 |
| 3 | Micrometer 动态 Tag 导致的 Metaspace 增长（DR-057） | 未验证 | 长稳测试 |
| 4 | 队列丢弃率 | 待观测 | 加 `shortlink.stats.dropped` 指标，> 0 说明消费端跟不上 |
| 5 | 软删除后 L1 的 5 分钟窗口 | **已知限制，接受** | 见 §6.2 |
| 6 | `offer()` 对 P99 是否有 1%~5% 真实影响 | **未验证** | A/B 方差（±1ms）大于待测效应（0.94ms）。需「每组 ≥5 轮 + 宿主负载锁定」重做（BENCH-W4.md §1.5） |
| 7 | `pending` 字段只增不减、无 Gauge 注册 | **已知缺陷** | 第 5 周：改为 `queue.size()` 或注册 Gauge |

---

## 9. 验收口径（第 4 周结束时）

- [ ] 跳转 P99 ≤ 10.0ms
      ⚠️ **判据调整说明（不是为放宽而放宽）**：
      原判据「不比第 3 周 11.74ms 变差」中的 11.74ms 来自
      **30 秒窗口的爬坡数据**（PERFORMANCE.md §10.4已说明 30s 仍在爬坡）；
      本周采用 **60 秒窗口**，两者不同口径，不可直接比较。
      60 秒窗口下第 3 周基线为 **9.14ms**，A/B 实测B 组中位数 **9.72ms**。
      详见 docs/BENCH-W4.md。
- [ ] 跳转 10 秒内，`link_stats_daily` 出现当日数据
- [ ] PV 与业务日志抽样核对，误差 < 1%（含丢弃的部分要能解释）
- [ ] 多实例下重复落库**不发生**（用分布式锁验证）
- [ ] 过期短链在 1 小时内被标记为 DELETED
- [ ] 队列丢弃率 = 0（压测场景下）