# LinkForge 架构设计（ARCHITECTURE）

> 上游依据：`docs/PRD.md`（v1 范围与验收）、`docs/ROADMAP.md`（排期）。
> 决策来源：`docs/DECISIONS.md`（DR-001 ~ DR-061）。
> 本文档**只做设计描述**：不含实体类、不含 SQL 建表语句，字段与表结构见 `docs/DATABASE.md`，接口契约见 `docs/API.md`。

---

## 1. 上游约束与架构目标

架构不是自由发挥，是被下面这些来自 PRD 的硬约束**反推**出来的：

| 来源 | 硬约束 | 对架构的直接要求 |
|---|---|---|
| PRD A-5 | 跳转 P99 < 50ms，且**无同步数据库写** | 跳转路径必须只读缓存；统计必须异步 |
| PRD A-6 | 缓存命中率 > 95% | 必须有分层缓存且回填策略正确 |
| PRD §7.2 / D-4 | 跳转链路**不得依赖** admin 模块 | 由模块依赖规则强制（§3） |
| PRD §7.7 | 限流组件不可用时**放行** | 降级路径必须先于限流实现（§9、§12） |
| PRD §6.3 | 缓存层整体不可用时可回退查库 | 缓存不是单点依赖，需降级通道 |
| PRD §6.3 | 创建后立即可跳、删除后立即可失效 | 创建/删除为强一致；删除需 Cache Aside |
| PRD §6.2 | 1000 万短链 / 日 500 万跳转 | 单库单表足够，**不做**分库分表 |

**架构目标（一句话）**：把跳转做成一条「绝大多数请求不碰数据库」的只读短路径，并把统计、限流、后台这三类非核心流量**隔离**到不影响这条短路径的地方。

**非目标**：不追求微服务的可扩展性、不追求多机房容灾、不追求强一致统计。这些在 v1 是纯粹的复杂度成本。

---

## 2. 部署形态

**单机 Spring Boot 单体 + Docker Compose。**

```
                    ┌─────────────────────────── Docker Compose ───────────────────────────┐
                    │                                                                       │
   client ──────────┼──► linkforge-app (Spring Boot, 单体, 多模块)                          │
        (302)       │      ├── linkforge-redirect  ── 跳转链路（核心）                       │
                    │      ├── linkforge-stats     ── 统计（异步）                           │
                    │      ├── linkforge-admin     ── 后台（/admin/api/v1/**）               │
                    │      └── linkforge-app       ── 装配与启动                             │
                    │              │                        │                               │
                    │              ▼                        ▼                               │
                    │        Redis 7 (缓存/布隆/计数/限流)   MySQL 8 (短链与聚合统计)          │
                    │                                                                       │
                    │   prometheus ──► grafana          wrk (压测容器, 第 5 周)              │
                    └───────────────────────────────────────────────────────────────────────┘
```

**明确不做**：微服务拆分、分库分表、多机房、服务网格。量化依据见 PRD §6.2——1000 万短链表在 MySQL 8 单表舒适区，日 500 万跳转折算均值仅约 58 QPS，瓶颈在**峰值与热点**，靠缓存与限流解决，不靠拆分解决。

---

## 3. 模块划分与依赖规则

### 3.1 五个模块

| 模块 | 职责 | 对外暴露 |
|---|---|---|
| `linkforge-core` | 领域模型（`Link` / `Base62` / `IdSpace` / `ReservedCodes`）、错误码、**缓存与布隆契约**、**MyBatis 数据访问实现**（DR-021） | 仓储与缓存接口（被 redirect/stats/admin 调用） |
| `linkforge-redirect` | 跳转链路：限流、布隆、缓存查找、回源、异步计数触发、302 | 跳转 Controller |
| `linkforge-stats` | 统计：计数、按天聚合、定时落库 | 统计服务接口（被 redirect/admin 调用） |
| `linkforge-admin` | 后台：短链查询、删除、**过期短链清理**；Token 鉴权 | `/admin/api/v1/**` |
| `linkforge-app` | 装配与启动：配置、Bean 装配、健康检查、监控端点 | 可执行程序 |

> ⚠️ `linkforge-core` **不是**「纯领域层」——它同时承载 MyBatis Mapper 与 XML
> （DR-021 修正了 DR-018 里「core 必须保持薄」的原始约定）。
> 它**不含**任何 Web 层与 Redis 客户端实现，因此不违反 §3.2 的隔离意图。

### 3.2 依赖规则（硬性）

```
        linkforge-app
              │  依赖全部
   ┌──────────┼──────────┐
   ▼          ▼          ▼
redirect    stats      admin
   │          ▲          │
   └──────────┴──────────┘
     redirect 与 admin 都依赖 stats
     ✗ redirect 不得依赖 admin

   另：core 是**公共底座**，被 redirect / stats / admin 三者依赖；
       stats 不直接依赖 redirect / admin。
```

- ✅ `redirect` → `stats`：允许（跳转要触发异步计数）。
- ✅ `redirect` → `core`：允许（领域模型与数据访问）。
- ✅ `admin` → `stats`：允许（后台要查统计）。
- ❌ `redirect` → `admin`：**禁止**。这是 PRD D-4「物理隔离」的落地方式。
- ❌ `admin` → `redirect` / `stats` → `redirect`：**禁止**（依赖必须单向）。
- ✅ `app` → 全部：允许（装配）。

**强制手段有两层**：

1. **编译期**：Maven 模块依赖 —— `linkforge-redirect/pom.xml` 里根本没有 `linkforge-admin`，
   写错就是编译不过。
2. **构建期**：`linkforge-app` 的 `ModuleBoundaryArchTest`（ArchUnit）断言
   「redirect 不依赖 admin」「admin 不依赖 redirect」「stats 不依赖 redirect/admin」。

**为什么用模块而不是「分包 + 自觉」**：只有模块依赖能让编译期就拦住 `redirect` 引用 `admin` 的代码。靠测试事后发现的问题，通常已经写完了整条错误依赖。

> ✅ **DR-009 的回退规则已作废**：DR-009 原文为「若第 1 周骨架搭建卡住超过一天，立即回退到 3 模块」。
> 实际 DR-018 已把划分改为 **5 模块**（补 `linkforge-core`），DR-021 进一步把 MyBatis 归入 core，
> ArchUnit 也已落地 —— **回退条件未触发，规则不再适用**。

### 3.3 隔离还包括运行期资源

> ⚠️ **本节已按最终实现订正**：v1 是**单体部署**，
> **没有**「redirect 一个 Tomcat 线程池、admin 另一个」这种进程内隔离。
> 下面写的是**实际**的隔离边界与它的局限。

| 资源 | 实际情况 | 隔离手段 |
|---|---|---|
| Web 请求线程池 | **单一 Tomcat 线程池**（未配置 `server.tomcat.threads.*`） | 跳转与后台**共用**。后台慢查询会占用同一批线程 —— v1 靠「后台接口少、Token 鉴权、统计查询走独立路径」控制，不靠线程池隔离 |
| 定时任务线程池 | `task.scheduling.pool.size=2` | ⭐ **必须是 >1**：Spring Boot 的 `@Scheduled` 默认**单线程**，一个 42s 的统计落库会**冻住整个调度器**，把过期清理等任务一起拖死（阶段 3 实测） |
| 数据源连接池 | **单数据源**（未配置多数据源 / `maximum-pool-size`） | redirect / stats / admin **共用**同一池 |
| 统计链路 | **有界队列 + 非阻塞投递**（`AsyncStatsQueue`） | 背压时丢弃并计数（`shortlink.stats.dropped`），**不阻塞跳转** |
| 限流计数 Key | `shortlink:limit:{clientId}:{scope}` | 按 scope 区分（redirect/create/query/admin），后台阈值更严 |

> ⚠️ **这一节是 v1 的已知局限**，不是「已实现的隔离」：
> 真正的资源级隔离需要独立线程池 / 独立连接池 / 独立部署单元，
> 属于 v2 的演进项（`docs/ROADMAP.md`）。**文档写清楚现状，比写一个没实现的蓝图更有用。**

---

## 4. 分层与职责边界

```
Controller        只做协议转换：参数绑定、校验入口、状态码映射
    ↓
Application       用例编排：事务边界、调用顺序、降级判断
    ↓
Domain            业务规则：短码生成规则、过期判定、保留字校验、协议白名单
    ↓
Repository        数据访问接口（实现细节不进 Domain）
    ↓
Infrastructure    缓存、Redis、定时任务、布隆过滤器、指标
```

职责边界规则：

- **状态码与错误码映射只允许出现在 Controller 层**（保证 `docs/API.md` 的契约是唯一出口）。
- **事务边界只允许出现在 Application 层**（§10）。
- **系统时间获取不得直接调用**：必须走可注入的时间源，否则过期场景无法写单测（PRD §7.3）。
- **Domain 层不得出现 Redis/MySQL 的具体类型**，只依赖接口。

---

## 5. 核心请求流程

### 5.1 创建短链（`POST /api/v1/links`）

```
1. 参数校验
   ├─ 协议白名单：仅 http/https（拒绝 javascript:/data:/file:）
   ├─ 长度上限校验
   └─ 自定义短码：字符集 + 长度 + 保留字黑名单（§6.4）
2. 生成短码（默认）：**发号器分配** —— Redis `INCR` 取序号 → Base62 编码 → 左补零到**固定 7 位**（DR-011）
   └─ Redis 不可用：降级到**数据库号段**发号，创建功能不中断
3. 写入数据库：`short_code` 唯一索引（**兜底防线**，非防碰撞主手段；碰撞不应发生）
4. 事务提交（§10）
5. 提交后：写穿布隆过滤器（失败只记日志，不阻塞返回）
6. 返回短码
```

### 5.2 跳转（`GET /{code}`）— 核心短路径

每步标注**耗时预算**（总预算 P99 < 50ms）：

| 步 | 动作 | 预算 | 命中则终止 |
|---|---|---|---|
| 1 | 按 IP 限流（Redis + Lua） | ≤ 1ms | 否（超限返回 429） |
| 2 | **L1 本地 Caffeine 正结果缓存** | ≤ 0.1ms | 是 → 302 |
| 3 | **布隆检查 + L2 缓存查询合并为一次 Redis 往返**（DR-052） | ≤ 3ms | 布隆判不存在 → 404 |
| 4 | 回源 MySQL 并回填缓存 | ≤ 200ms 超时 | 是 |
| 5 | 异步计数（PV/UV）非阻塞投递 | 不等待 | — |
| 6 | 返回 302 | ≤ 1ms | — |

**关键设计**：

- ⭐ **顺序必须是「L1 → L2 → DB」，布隆在 L2 这一步里**（DR-051）。
  曾经的实现是「布隆 → L2 → L1」，
  后果是**每次请求都要先问一次 Redis**，L1 命中率恒为 0，
  QPS 从 81,289 掉到 **3,153**（25.8x）。
  **这不是取舍，是缺陷** —— 本地缓存的价值恰恰在于「不发网络请求」。
- 第 3 步把布隆与 L2 合并为**一次往返**（`EVAL`），避免跳转路径上出现两次独立网络调用（DR-052）。
- 第 4 步只在 L2 未命中时发生。
- 第 5 步**不等待结果**，投递失败也不影响 302（§11）。

### 5.3 删除短链（`DELETE /admin/api/v1/links/{shortCode}`）

```
1. Token 鉴权（环境变量）
2. 事务内：改数据库状态（软删除/失效标记）    ← 强一致
3. 事务提交
4. 提交后：删缓存（Cache Aside）              ← 不在事务内
   ├─ 成功：结束
   └─ 失败：记录指标 + 重试一次；仍失败则记录错误日志 + 指标
            ⚠️ 不抛回请求（用户已看到成功，抛错会造成「删了却报失败」的错觉）
5. 返回成功
```

- 布隆过滤器**不移除**该短码（布隆不支持删除），删除后该码会多一次回源查库，属可接受代价（DR-007）。
- 已知残留窗口：本地 Caffeine 正结果在 TTL 到期前可能仍返回 302（DR-008），窗口上限等于本地 `expireAfterWrite`。

### 5.4 统计落库（定时任务，每分钟）

```
1. 扫描本周期有计数的 Key（PV 计数 / UV HLL）
2. 聚合为「短码 + 日期」维度
3. 批量写 MySQL（UPSERT 累加，MySQL 8.4 的 `AS new` 写法）
4. 写成功后清理已落库的计数 Key
   └─ 未成功：保留 Key，下轮重试（幂等）
```

> ⚠️ **落库频率是「每分钟」，不是「每 10 秒」**（`linkforge.stats.flush.cron = 0 * * * * *`）。
> 用 `cron` 而非 `fixedRate`：`fixedRate` 在上一轮未跑完时会**堆积**，
> 而落库本身有重试（1s + 4s + 16s），最坏一轮 42s。
> 多实例部署时由 **Redis 分布式锁**保证同一时刻只有一个实例真正执行（DR-058）。

---

## 6. 缓存设计

### 6.1 分层

| 层 | 实现 | 位置 | 内容 | 生效周 |
|---|---|---|---|---|
| L1 | Caffeine | 进程内 | **只缓存正结果**（DR-008） | 第 2 周 |
| L2 | Redis | 全局共享 | 正结果 + 空值 | 第 2 周 |
| 前置 | Redis 布隆过滤器 | 全局权威 | 短码存在性（DR-007） | 第 3 周 |

**查询顺序**：**L1 → L2（含布隆）→ MySQL**。任一层命中即返回，未命中则继续下沉，最终回源并逐层回填。

> ⚠️ **不要写成「布隆 → L1 → L2」**（DR-051）：那样每次请求都要先打一次 Redis，
> 本地缓存永远命中不了，QPS 会掉一个数量级。**布隆是 L2 查询的一部分，不是独立的一层。**

### 6.2 Key 设计

遵循 PRD 约定，全部带统一前缀：

| Key | 值 | TTL | 说明 |
|---|---|---|---|
| `shortlink:code:{shortCode}` | 原始 URL | 1 小时 + 随机 0~5 分钟 | 正结果（L2） |
| `shortlink:code:{shortCode}` （空值态） | 空标记 | **30 秒** | 防穿透；TTL 必须短。**不下沉 L1**（DR-047） |
| `shortlink:bloom` | 位数组 | 无（由重建维护） | 布隆过滤器（全局唯一权威） |
| `shortlink:bloom:building` | 位数组 | 无 | 重建中的临时 key（`BUILDING_SUFFIX`），构建完成后 `RENAME` 原子替换上面的现行 key |
| `shortlink:pv:{shortCode}:{yyyyMMdd}` | PV 计数 | 落库后清理（兜底 35 天 TTL） | `StatsKeys.PV_PREFIX` |
| `shortlink:uv:{shortCode}:{yyyyMMdd}` | HLL 结构 | 同上（兜底 35 天 TTL） | `StatsKeys.UV_PREFIX`，约 12KB/key |
| `shortlink:limit:{clientId}:{scope}` | 令牌桶状态 | 限流窗口（`PEXPIRE` 原子续期，DR-056） | `scope` ∈ redirect / create / query / admin |

### 6.3 TTL 策略

- **正结果**：基础 **1 小时 + 随机 0~5 分钟抖动**。抖动是防雪崩的核心手段——不加抖动，同一批写入的 key 会在同一秒集体失效。
- **空值**：**30 秒**。空值缓存是防穿透的第二道防线，但 TTL 必须短，否则正常的「先建后查」流程和短码空间被长期污染。
- **本地缓存（已定稿）**：`expireAfterWrite` = **5 分钟**（`CaffeineConfig.LOCAL_TTL`），
  `maximumSize` = **10000**（`CaffeineConfig.LOCAL_MAX_SIZE`）。
  ⭐ 5 分钟**由业务需求决定**（能接受「删掉短链后仍可跳转 5 分钟」这个不一致窗口），
  **不是**从 Redis TTL 的 1/12 倒推出来的 —— 改 Redis TTL 不应该联动改本地 TTL（DR-046 / DR-047）。
- **本地缓存只存正结果**：空值**不下沉** L1，否则「先查后建」的正常流程会被粘住 5 分钟（DR-047）。

### 6.4 保留路径与短码冲突

跳转走根路径 `GET /{code}`，因此 `code` 的取值空间与系统路径共享命名空间。**保留字黑名单**（配置化，新增无需改代码）：

```
api, admin, actuator, health, favicon.ico, static, error
```

- 创建时校验：自定义短码落在黑名单内 → 参数错误。
- **启动时断言**：黑名单必须覆盖所有已注册的系统路径前缀，缺一个就是生产事故（例如 `/{code}` 吃掉 `/api/v1/links`）。
- 黑名单是**配置项**而不是硬编码常量，便于后续新增端点时不改代码。

---

## 7. 防穿透 / 击穿 / 雪崩的机制

| 问题 | 机制 | 触发条件 | 系统行为 | 可观测信号 |
|---|---|---|---|---|
| **穿透** | Redis 布隆过滤器 | 请求的短码从不存在 | 布隆判不存在 → 直接 404，**不查缓存不查库** | 布隆拦截计数 |
| | 空值缓存 30 秒 | 布隆误判为存在但库中确实没有 | 缓存空值，后续同码请求 30 秒内不再回源 | 空值命中计数 |
| **击穿** | 本地 Caffeine | 热点短码在 Redis 失效瞬间 | L1 命中直接返回，不打到 Redis/DB | L1 命中率 |
| | 回源互斥（单飞） | 同一短码并发未命中 | 只放一个请求回源，其余等待其回填结果 | 互斥等待计数 |
| **雪崩** | TTL 随机抖动 | 大批 key 同时写入 | 失效时间被打散，不会集体失效 | — |
| | 降级查库 | Redis 整体不可用 | 跳转回退到查 MySQL，性能下降但可用 | 降级次数 |

### 7.1 布隆过滤器规格与重建（DR-007）

**规格（写死，不运行时计算）**：

| 参数 | 值 | 含义 |
|---|---|---|
| `n` | 1000 万 | 容量上界，等于 PRD §6.2 的短链总量假设 |
| `p` | 0.01 | 可接受误判率 1% |
| `m` | **≈ 12MB** | 位数组大小 |
| `k` | **7** | 哈希函数个数 |

**为什么规格写死**：让内存占用**可预测、可写进文档**，而不是运行时算出一个说不清的数字。1000 万容量下约 12MB，完全可接受。

#### 规格推导（逐项验算）

```
m = -n · ln(p) / (ln2)²

  ln(p)      = ln(0.01)  = -4.605170
  (ln2)²     = 0.693147² =  0.480453

  m = -(10,000,000 × -4.605170) / 0.480453
    =  46,051,700 / 0.480453
    =  95,850,584 位          ← 位数

  字节   = 95,850,584 / 8= 11,981,323 字节
  MiB    = 11,981,323 / 1024 / 1024 = 11.43 MiB ≈ 12 MB

k = (m / n) · ln2
  = (95,850,584 / 10,000,000) × 0.693147
  = 9.5851 × 0.693147
  = 6.6439  →  向上取整 7
```

⚠️ **算错过的记录**：初稿曾把 `m` 写成「119.3 万位 ≈ 146KB」。
反推可知 146KB 对应 `n ≈ 12.4 万`，**差了 80 倍**——
错因是把 `n = 1,000,000`（百万）当成了 `n = 10,000,000`（千万）。
**公式本身没错，错的是代入的 n。** 任何容量规格都必须逐项验算，
不能凭「听起来合理」估——12MB 与 146KB 都能让规格表看起来很正常。

**k 取 7 而非 6.64**：布隆的 k 必须整数。向下取 6 会让实际误判率高于目标 p；
向上取 7 会让实际误判率略低于目标 p（约 0.0081%），代价是每 key 多一次哈希。
**宁可 p 更低**，因为 p 直接决定「不存在的短码有多少比例会穿透到 DB」。

**重建策略**：

1. **创建时写穿**：新短码创建后立即写入过滤器。**失败只记日志**，不阻塞创建——布隆是性能优化而非正确性来源。
2. **每小时从 DB 定时全量重建**：扫全量短码重建位数组。

**⚠️ 重建期间必须避免假阴性**——这是本设计最容易出事故的地方：

- ❌ **错误做法**：先删旧 key，再逐条建新 key。窗口期内真实短码会被判「不存在」→ 返回 **404**（对用户是真实故障）。
- ✅ **已实现的做法（双缓冲 + 原子切换）**：
  1. 在**临时 key** `shortlink:bloom:building` 上构建新位数组；
  2. 构建期间，请求读的是**现行 key** `shortlink:bloom`，完全不受影响；
  3. 构建完成后 `RENAME shortlink:bloom:building shortlink:bloom` **原子切换**
     （RENAME 在 Redis 内单线程执行，切换瞬间不存在「两个都不对」的状态）。

**误判的后果是安全的**：布隆的 false positive 只导致多一次回源查库，**不会返回错误结果**；false negative 才是故障，因此重建必须保证「宁可暂时多留旧数据，也不能有窗口期查不到」。

**删除短链不从布隆移除**：布隆不支持删除。删除后该码仍会被布隆判为「可能存在」，于是多一次回源查库并命中空值缓存，属可接受代价。

#### ⚠️ 已知缺口：重建窗口内的写穿**没有双写**（已定位，尚未修复）

> ⚠️ **本条问题在 `docs/DECISIONS.md` DR-055 已有记录**（第 3 周实测到），
> 当时的处置是「v1 先绕过，v2 再优化」。此处补充的是**实现的准确现状**，
> 因为 DR-007 正文至今仍写着「写穿同时写新旧两份」，容易被误读成已实现。

**原始设计**要求「构建期间创建操作的写穿**同时写新旧两份**」，
但**当前代码没有实现双写**：

| 环节 | 实际实现 | 出处 |
|---|---|---|
| 创建时写穿 | 只写**现行 key** `shortlink:bloom` | `RedisBloomFilter.put()` |
| 重建 | 只写 `:building`，最后 `RENAME` **覆盖**现行 key | `RedisBloomFilter.rebuild()` |
| 重建扫描 | 按 `id > afterId` 游标分批扫全库 | `BloomFilterMaintenance.rebuild()` |

**后果**：重建期间创建、且**在扫描游标越过其 id 之后**创建的短码，
被 `put()` 写进了现行 key，却**不在** `:building` 里 ——
`RENAME` 覆盖现行 key 时，这批位信息**被丢弃** →
布隆产生 **false negative** → 该短码在**下一次重建前**（默认 1 小时）返回 **404**。

**窗口大小**：重建耗时（分钟级）内创建的短码。
**影响面**：新建短码立即被访问时可能 404；访问失败会回落到「重试」，
但对用户就是一次真实的「刚生成的链接打不开」。

**推荐的修法**（未实施，需单独一轮变更 + 回归）：

1. 引入 `rebuilding` 状态标志（Redis key 或本地 `AtomicBoolean`）；
2. 标志为真时，`put()` **同时写现行 key 与 `:building`**；
3. 或者：重建结束时不做覆盖式 `RENAME`，而是把现行 key 的位**并入** `:building` 再切换
   （`SETBIT` 逐位并集成本高，但 `RENAME` 前的最后一批增量合并是可行的折中）。

> 本条记录在 `docs/TROUBLESHOOTING.md`（T-027），
> 与 `docs/DECISIONS.md` DR-007 的原始设计一并对照阅读。

---

## 8. 异步统计链路

### 8.1 为什么不用消息队列

v1 **不引入 MQ**（PRD §5.2）。「Redis 计数 + 定时批量落库」已满足「跳转不阻塞、统计分钟级最终一致」的需求，而 MQ 会引入额外的部署组件、消费幂等、消息丢失处理三类复杂度，在当前量级下收益不成比例。MQ 是 v2 备选。

### 8.2 数据流

```
跳转 → [非阻塞投递] → 本地计数缓冲 / 直接 INCR
                          ↓
              Redis: shortlink:pv:{code}:{yyyyMMdd}  (PV, INCR)
              Redis: shortlink:uv:{code}:{yyyyMMdd} (UV, HyperLogLog PFADD)
                          ↓  每分钟（cron: 0 * * * * *）
              定时任务：聚合 → 批量 UPSERT 写 MySQL（Redis 分布式锁保证单实例执行）
                          ↓
              写成功后清理已落库计数 Key（未成功则下轮重试，保证幂等）
```

### 8.3 UV 口径（PRD §7.4）

- 使用 **Redis HyperLogLog**：标准误差率约 **0.81%**，内存约 **12KB / key**。
- **UV 是估算值，不是精确值**。这个代价必须写进文档、压测报告与简历——面试必然追问精度。
- 为什么不用精确去重（Set 存每个访客）：内存随访客数线性增长，500 万次/日的量级下成本不可控；12KB 固定成本换 0.81% 误差，在「看推广效果」的场景下完全够用。

### 8.4 落库失败的处理

- **统计落库失败不影响跳转**：跳转只投递计数，不等待落库结果。
- 落库本身**幂等**：计数 Key 只在写成功后清理，失败则下轮重试；聚合写入用累加语义，避免重复计数。

---

## 9. 限流设计（DR-002、DR-010、DR-056）

- **实现**：Redis + **Lua 脚本**（令牌桶）。**主路径唯一实现**。
  ⚠️ **未引入 Redisson**：DR-002 原文把 Redisson 列为「兜底对照保留」，
  但**最终实现里没有引入 Redisson**（`pom.xml` 与源码均无）——
  实际只有自研的 `RedisTokenBucketLimiter` + 内联 Lua 令牌桶。
- **为什么必须 Lua**：限流需要「读计数 → 判断 → 写计数」的原子性。分成多条命令会存在竞态窗口，高并发下阈值会被突破。Lua 脚本在 Redis 中单线程原子执行，从根本上消除该窗口。
- **Key 与续期**：`shortlink:limit:{clientId}:{scope}`，
  `scope` ∈ `redirect` / `create` / `query` / `admin`（`RateLimitScope`）。
  过期用 **`PEXPIRE` 在同一个 Lua 里原子续期**（DR-056），
  避免「先 SET 再 EXPIRE」两步之间的竞态导致的永不过期。
- **作用域与默认阈值**（配置项 `RATE_LIMIT_*`，可覆盖）：

  | scope | 默认阈值 | 说明 |
  |---|---|---|
  | `redirect` | **20 / 秒** | 跳转 |
  | `create` | **10 / 分钟** | 创建短链 |
  | `query` | **60 / 秒** | 后台/统计查询 |
  | `admin` | **30 / 分钟** | 后台写操作，阈值最严 |

  ⚠️ 后台与跳转**共用 `shortlink:limit:` 前缀**，靠 **scope 后缀**区分，
  不是「独立前缀」。
- **降级（DR-010，v1 只做放行）**：Redis 或 Lua 脚本异常时**一律放行**，**不做**本地内存兜底。理由：本地兜底在多实例下阈值形同虚设，会给出虚假安全感。
  - ⚠️ 该窗口期平台**没有防刷能力**，必须有可观测信号（指标 + 告警），否则会静默失去防护。
  - v2 可加本地兜底，届时须说明其多实例阈值失真问题。
- **压测用开关**：另有 `linkforge.ratelimit.enabled`（默认 `true`），
  关闭时跳过 Lua 调用并累加 `shortlink.ratelimit.bypassed`。
  ⚠️ 这个开关**只用于压测 A/B 对照**（第 3 周正是用它定位到「QPS 降 66% 全部由限流造成」），
  **不是** DR-010 所说的「生产降级开关」——生产降级策略仍然是「Redis 异常即放行」。

---

## 10. 事务边界

事务边界只出现在 Application 层，规则如下：

| 操作 | 事务内 | 事务外（提交后） | 理由 |
|---|---|---|---|
| **创建短链** | 校验 + 发号 + 唯一索引插入（**唯一索引为兜底**） | **写穿布隆过滤器** | 布隆写入不属于数据库事务；失败只记日志，不回滚已创建的短链 |
| **删除短链** | 改数据库状态（强一致） | **删缓存**（Cache Aside） | 删缓存不在事务内：否则事务回滚会连带缓存状态不确定 |
| **统计落库** | 批量 UPSERT | 清理已落库计数 Key | 清理必须在写入成功之后，保证失败可重试且不丢数据 |

**创建为什么用 `@Transactional`**：发号与唯一索引插入需要事务边界清晰——事务覆盖「一次插入」，保证短码与短链记录的原子性；唯一索引冲突属于**异常兜底路径**（正常不应触发），一旦触发应记指标而不是无限重试。

**删除为什么把删缓存放在提交后**：Cache Aside 的正确顺序是「先改库、提交成功、再删缓存」。若在事务提交前删缓存，事务回滚后缓存已被清空（影响不大但语义混乱）；更要紧的是**不能在事务内删除缓存后任由事务失败**，否则出现「库里还在、缓存没了」——虽然只是性能损失，但会让「删除是否生效」的排查变得困难。

**删除缓存失败的完整路径**（PRD §7.5、§5.3）：

1. 删缓存失败 → **重试一次**；
2. 仍失败 → 记录**指标 + 错误日志**（不得静默吞掉）；
3. **不把异常抛回请求**：数据库状态已提交，用户看到成功是正确的；抛错会造成「删了却报失败」的错觉，反而诱导用户重复操作；
4. 残留脏数据的窗口由 Redis TTL（最长约 1 小时 5 分钟）兜底。

---

## 11. 线程池与背压

### 11.1 隔离原则

统计与后台**绝不与跳转共享线程池**。跳转是核心链路，任何非核心流量的排队都不得表现为跳转变慢。

| 线程池 | 用途 | 队列 | 拒绝策略 |
|---|---|---|---|
| redirect-web | 跳转请求 | 容器线程池 | 容器默认 |
| stats-async | 统计计数投递与聚合 | **有界队列** | **丢弃统计 + 记录指标，绝不阻塞跳转** |
| admin-web | 后台请求 | 独立、容量最小 | 快速失败（503） |

### 11.2 背压策略

**核心原则：宁可丢统计，不可丢跳转。**

- 统计队列满 → 丢弃该次统计并累加 `stats_dropped_total` 指标 → 跳转照常返回 302。
- 后台队列满 → 直接快速失败，并暴露指标。
- 不允许任何非核心任务使用无界队列：无界队列会把「流量高峰」转化成「内存耗尽」，比丢弃统计严重得多。

### 11.3 超时配置（压测调参基准）

| 依赖 | 超时 | 理由 |
|---|---|---|
| **Redis 连接/命令超时** | **50ms** | 跳转总预算 P99 < 50ms，Redis 若慢于此已无意义，应立即降级查库而不是继续等 |
| **MySQL 查询超时** | **200ms** | 回源是兜底路径；超过此值说明查询或索引有问题，应快速失败而非拖垮线程 |

这些数值是**设计基线**，第 5 周压测时以实测 P99 为依据微调，并把调整结果回写本文档。

---

## 12. 异常处理与降级矩阵

**优先级：跳转可用性 > 统计数据准确性 > 后台可用性。**

| 依赖故障 | 跳转行为 | HTTP 结果 | 可观测信号 |
|---|---|---|---|
| Redis 整体不可用 | 降级：跳过布隆与缓存，**直接查 MySQL** | 200/302（性能下降，服务可用） | `shortlink.ratelimit.degraded`、`shortlink.cache.miss` 骤降 |
| Redis 命令超时（> 50ms） | 按不可用处理，走降级路径 | 302 | 同上 |
| 布隆过滤器不可用 | **跳过布隆检查**，继续走缓存 → DB | 302 | `shortlink.bloom.put.failed` |
| 限流组件不可用 | **放行**（DR-010） | 302/200 | `shortlink.ratelimit.degraded` ⚠️ 防刷能力缺失，需告警 |
| 缓存删除失败 | 不影响跳转；影响删除语义 | 删除返回成功，缓存按 TTL 过期 | `shortlink.cache.evict.failed`、`shortlink.cleanup.cache.evict.failed` |
| MySQL 不可用 | 未命中缓存的请求失败；命中的仍可 302 | 503 | 数据源错误计数 |
| 统计投递/落库失败 | **完全不影响跳转** | 302 | `shortlink.stats.dropped`、`shortlink.stats.dlq.*` |
| 后台不可用 | 不影响跳转 | 503 | admin 错误计数 |

**降级实现要点**：

- 降级判断必须**廉价**（本地的熔断标记 + 超时），不能为了判断依赖是否可用而再发一次远程调用。
- 每条降级路径都**必须先于**对应功能实现（例如限流放行路径先于限流上线），否则故障时会变成 500。

---

## 13. 过期语义

- 过期时间**精确到秒**，默认不设置（永久有效）。
- 判定以**服务端当前时间**为准，**不接受**客户端传入时间。
- 时间获取必须**可注入**（§4）：业务逻辑中不得直接散落调用系统时间，否则过期场景无法写单测。
- 语义区分（PRD US-4）：
  - 已过期 → **410 Gone**（曾经存在，现已失效）；
  - 不存在（从未创建 / 已删除 / 布隆判不存在）→ **404 Not Found**。
- 删除与过期的区别：删除是主动失效，过期是时间到期；两者对缓存的处理不同（删除要主动删缓存，过期靠 TTL + 判定）。

---

## 14. 可观测性设计

### 14.1 指标清单

> ⚠️ **指标名以 Micrometer 注册名为准**（点分 `shortlink.*`）；
> Prometheus 里对应 `shortlink_*`（下划线），Counter 会自动加 `_total` 后缀。
> 例：`shortlink.cache.local.hit` → Prometheus `shortlink_cache_local_hit_total`。
> 下表「Prometheus 名」一列给出**实际可查**的名字。

| 用途 | Micrometer 名 | Prometheus 名 | 类型 |
|---|---|---|---|
| L1 命中 | `shortlink.cache.local.hit` | `shortlink_cache_local_hit_total` | Counter |
| L2 命中（**含空值命中**） | `shortlink.cache.redis.hit` | `shortlink_cache_redis_hit_total` | Counter |
| 两级都没命中 | `shortlink.cache.miss` | `shortlink_cache_miss_total` | Counter |
| 删缓存失败 | `shortlink.cache.evict.failed` | `shortlink_cache_evict_failed_total` | Counter |
| 布隆拦截 | `shortlink.bloom.blocked` | `shortlink_bloom_blocked_total` | Counter |
| 布隆写穿失败 | `shortlink.bloom.put.failed` | `shortlink_bloom_put_failed_total` | Counter |
| 布隆重建 | `shortlink.bloom.rebuild` | `shortlink_bloom_rebuild_total` | Counter |
| 限流拒绝 | `shortlink.ratelimit.rejected` | `shortlink_ratelimit_rejected_total` | Counter |
| ⚠️ 限流降级放行 | `shortlink.ratelimit.degraded` | `shortlink_ratelimit_degraded_total` | Counter |
| 限流被开关跳过（压测用） | `shortlink.ratelimit.bypassed` | `shortlink_ratelimit_bypassed_total` | Counter |
| 统计投递成功/丢弃 | `shortlink.stats.offered` / `shortlink.stats.dropped` | `shortlink_stats_offered_total` / `..._dropped_total` | Counter |
| 统计队列水位 | `shortlink.stats.queue.size` | `shortlink_stats_queue_size` | Gauge |
| 统计落库耗时 | `shortlink.stats.flush.duration` | `shortlink_stats_flush_duration_seconds` | Timer |
| 过期清理扫描/清理 | `shortlink.cleanup.scanned` / `shortlink.cleanup.cleared` | `shortlink_cleanup_scanned_total` / `..._cleared_total` | Counter |
| 过期清理耗时 | `shortlink.cleanup.duration` | `shortlink_cleanup_duration_seconds` | Timer |
| 短码冲突 | `shortlink.id.conflict` | `shortlink_id_conflict_total` | Counter |
| ⭐ **Meters 总数** | `shortlink.meter.count` | `shortlink_meter_count` | Gauge |

⭐ `shortlink.meter.count`（自建 Gauge，`MeterCountGauge`）是**第 5 周 8 小时长稳的关键对照曲线**：
它直接反映「Micrometer 注册表里有多少个 Meter」，
用来证伪「动态 Tag 导致 Metaspace 泄漏」这个错误因果链（DR-061）。

⚠️ **不存在的指标**（早期设计里有、最终没做，不要去查）：
`redirect_requests_total`、`redirect_latency_seconds`、`cache_hit_total{cache=...}`、
`cache_null_hit_total`（空值命中**并入** `shortlink.cache.redis.hit`）、
`stats_flush_lag_seconds`、`bloom_rebuild_duration_seconds`。

### 14.2 日志分级

- 跳转链路：**不记录每次请求的 INFO 日志**（高 QPS 下会拖垮磁盘），只记录 WARN/ERROR（降级、异常）。
- 创建/删除：记录操作级日志（含短码，不含完整长链以外的敏感信息）。
- 降级事件：必须 WARN 且带依赖名与原因。
- **禁止**记录可长期定位个人的 IP 明细（PRD §6.4 隐私约束）。

### 14.3 健康检查（见 §15.2）

---

## 15. 配置、健康检查与部署

### 15.1 配置

- 全部走**环境变量**，禁止硬编码「本机路径」。
- 敏感项：后台 Token 通过环境变量注入，**禁止进仓库**（`.gitignore` 已覆盖 `.env` 与本地配置）。
- 可调项：限流阈值（`RATE_LIMIT_*`）、缓存 TTL（`CACHE_TTL_SECONDS` / `CACHE_NULL_TTL_SECONDS`）、超时值、保留字黑名单（`RESERVED_CODES`）。
- ⚠️ **不可调项（编译期常量，改了要改代码并重测）**：
  本地缓存 `maximumSize` / `expireAfterWrite`（`CaffeineConfig`）、
  **布隆规格**（`BloomHash.EXPECTED_INSERTIONS` / `BIT_SIZE` / `HASH_COUNT`）、
  短码长度（`IdSpace.SHORT_CODE_LENGTH`）。

### 15.2 健康检查

| 端点 | 检查内容 | 用途 |
|---|---|---|
| **liveness** | **只查进程存活**（`livenessState`） | 决定是否重启容器 |
| **readiness** | **只查 `readinessState`**（进程能否服务） | 决定是否接流量 |
| **根 `/actuator/health`** | 额外含 MySQL、Redis 等自动装配的组件 | 人工排查用 |

- ⚠️ **readiness 当前不包含 MySQL / Redis / 布隆**：
  Spring Boot 的 readiness 组默认只有 `readinessState`，
  项目**没有**配置 `management.endpoint.health.group.readiness.include`，
  也**没有**自定义 `HealthIndicator`（布隆更是没有）。
  因此「readiness 通过」= 「进程起来了」，**不代表依赖可用**。
  - 若要变成真正的依赖就绪，需要补 `group.readiness.include=readinessState,db,redis`
    并新增一个布隆 `HealthIndicator` —— **v2 事项**。
- **Compose 使用容器 healthcheck 作为依赖就绪判据**（`depends_on: condition: service_healthy`），
  MySQL / Redis 各自的 healthcheck 保证应用在依赖就绪后才被判定为可用。
- liveness 不能查外部依赖：否则 Redis 抖动会导致容器被反复重启，把「可降级」的故障放大成「不可用」。

### 15.3 Redis 持久化

- **使用 RDB**（快照）。
- **明确声明：缓存丢失是可接受的**。Redis 重启后缓存为空，请求回源 MySQL 并逐步回填，性能短暂下降但**数据不丢**（短链的真相在 MySQL）。
- **布隆过滤器靠定时重建恢复**（§7.1），不依赖持久化——这正是「每小时全量重建」的另一个价值：它让布隆不成为持久化依赖。
- **代价**：Redis 重启后的重建窗口内，跳转延迟上升、缓存命中率下降，属于可接受的降级，但必须在压测报告与 README 中说明，避免被误认为 bug。

### 15.4 部署拓扑与启动顺序

```
mysql (healthcheck) ──┐
                      ├──► app (readiness 通过后接流量)
redis (healthcheck) ──┘
prometheus ──► grafana          （第 5 周）
wrk 压测容器                      （第 5 周）
```

- 启动顺序由健康检查保证，应用自身要能容忍依赖**暂时**不可用（重试连接，不因启动期 Redis 未就绪而崩溃）。
- 压测容器与被测服务**优先放入同一 compose 网络**（DR-003），否则绝对延迟不可用于对外结论。

---

## 16. 架构决策记录（ADR）

**权威记录在 `docs/DECISIONS.md`**（单一来源，避免两处不一致）。
截至第 6 周收尾共 **61 条**（DR-001 ~ DR-061），`docs/DECISIONS.md` 开头有**总目录**。

下面只列**影响架构边界与主链路**的决策，其余按主题索引：

| 主题 | 相关决策 | 关键结论 |
|---|---|---|
| 构建与模块 | [DR-001](DECISIONS.md)、DR-009、**DR-018**、**DR-021** | Maven + Wrapper；**5 模块**（补 `linkforge-core`）；MyBatis 归 core |
| 短码生成 | DR-011、DR-022、DR-037 | 发号器（Redis INCR / DB 号段），固定 **7 位** Base62；号空间区间划分 |
| 布隆与缓存 | **DR-007**、DR-008、**DR-046**、**DR-047**、**DR-051**、**DR-052**、**DR-054** | Redis 唯一权威 + 规格定死 + 定时重建；L1 只缓存正结果；**L1 优先**（三段式）；布隆与 L2 合并为一次往返 |
| 限流 | DR-002、DR-010、**DR-056** | 自研 Redis + Lua 令牌桶；v1 降级即放行；`PEXPIRE` 原子续期 |
| 数据与时区 | DR-021、**DR-032**、DR-034、DR-035、**DR-040** | MyBatis 数据访问；**数据库会话时区强制 UTC**；库权限边界；`short_code` 排序规则 `ascii_bin` |
| 接口契约 | DR-013、DR-014、DR-015、DR-024、DR-025、DR-026、DR-028、DR-029、DR-030、DR-039 | 错误码格式、绝对 URL Location、时间格式、保留字大小写不敏感 |
| 异步统计 | DR-053、DR-058 | 统计落库用 **MySQL 8.4 `AS new`**；**Redis 分布式锁**保证单实例执行 |
| 过期与清理 | **DR-060** | 过期清理**只清缓存、不改 `status`**，以保留 **410** 语义 |
| 压测与口径 | DR-003、DR-004、DR-019、DR-038、DR-052、DR-057 | 容器内 wrk；口径预注册；GC 日志统一 `-Xlog:gc*`；**宿主被抢占即作废数据** |
| 长稳与可观测 | **DR-061**、DR-055 | 8 小时长稳证伪「Metaspace 泄漏」；**Metaspace 是类加载，动态 Tag 涨的是堆** |
| 部署与凭据 | DR-016、DR-017、DR-038 | 阿里云 Maven 仓库；MySQL 宿主机映射 3307；Prometheus 凭据走 Docker Secrets |

> ⚠️ **本表是索引，不是副本** —— 决策的**完整理由、选项与代价**只在 `docs/DECISIONS.md` 里。

---

## 16bis. 待确认问题清单

> ✅ **全部 7 项已关闭**（第 6 周收尾核对）。保留此表是为了说明「当时的未知」与「最终落点」，
> 便于回看当时的信息缺口是否被正确补上。

| 编号 | 问题 | 最终结论 | 关闭于 |
|---|---|---|---|
| A-1 | 过期短链的清理方式 | ✅ **定时扫描 + 只清缓存，不改 `status`**（保 410），扫描走 `idx_expire_status(status, expire_at)` | DR-060 |
| A-2 | 统计聚合表的建模与 90 天清理策略 | ✅ 表 `link_stats_daily` 已定；⚠️ **90 天批量清理任务 v1 未实现**（见 `docs/DATABASE.md` §7.3） | DR-053 / DATABASE.md §4.3 |
| A-3 | 软删除与过期字段如何共同表达「失效」 | ✅ 删除 → `status=已删除` → **404**；到期 → `status=有效` 但 `expire_at` 已过 → **410** | DR-013 / ARCHITECTURE §13 |
| A-4 | 错误码与 HTTP 状态映射表 | ✅ 格式 `LINKFORGE-<HTTP状态码>-<两位序号>` | DR-024 |
| A-5 | 本地缓存 `maximumSize` / `expireAfterWrite` | ✅ **10000 / 5 分钟**（由业务不一致窗口决定，非从 Redis TTL 倒推） | DR-046 / DR-047 |
| A-6 | 限流阈值最终取值 | ✅ redirect 20/s、create 10/min、query 60/s、admin 30/min | DR-002 / DR-056 |
| A-7 | Maven 本地仓库选址 | ✅ 项目内 `.mvn-repo`（阿里云镜像源） | DR-016 |

---

## 17. 监控栈（第 5 周）

### 17.1 组件与拓扑

```
                    ┌──────────────── linkforge_default 网络 ────────────────┐
                    │                                                        │
  wrk（压测）  ──────┼──→ app:8080 ──→ redis:6379                            │
                    │      │                                                 │
  prometheus ───────┼──────┴──→ app:8080/actuator/prometheus                │
                    │                                                        │
  grafana ──────────┼──→ prometheus:9090                                    │
                    └────────────────────────────────────────────────────────┘

  app:8080 ──→ mysql:3306（容器内）
```

| 组件 | 版本 | 镜像 | 端口 | 凭据 |
|---|---|---|---|---|
| MySQL | 8.4 | `mysql:8.4` | **3307**（宿主机）/ 3306（容器内） | `.env`（`MYSQL_PASSWORD`） |
| Redis | 7.4 | `redis:7.4` | 6379 | 无密码（本地开发已知风险） |
| 应用 | — | 本项目构建 | 8080 | 无（公开接口） |
| Prometheus | 3.5.0 | `prom/prometheus:v3.5.0` | 9090 | ✅ **Docker Secret 已启用**（bcrypt 哈希 + secret 明文） |
| Grafana | 12.1.0 | `grafana/grafana:12.1.0` | 3000 | ✅ **Docker Secret 已启用** |

### 17.2 为什么全部声明式，不用 UI

- **Prometheus 配置**：`docker/prometheus/prometheus.yml` 挂载进容器
- **Grafana 数据源**：`docker/grafana/provisioning/datasources/datasource.yml`
- **Grafana 看板**：`docker/grafana/provisioning/dashboards/dashboard.yml` + `docker/grafana/dashboards/*.json`

理由：手工在 UI 里配的东西**只存在 Grafana 的数据库里**，
换个环境（CI / 别人的机器 / 重建容器）就要重配一遍。
声明式让「`docker compose up -d` 之后看板直接可用」。

⚠️ `allowUiUpdates: false`：看板是**代码**的一部分。
允许 UI 改会让人在界面上拖出一个「本地版本」，
而仓库里的 JSON 仍是旧的 —— 两者不一致时没人知道哪个是真的。

### 17.3 凭据：Docker Secrets

| 组件 | 传递方式 | 可见性 |
|---|---|---|
| Grafana admin 密码 | `GF_SECURITY_ADMIN_PASSWORD__FILE` + secret 挂载 | `docker inspect` **看不到** |
| Prometheus Basic Auth | ✅ **已启用**：哈希在 `docker/prometheus/web.config.yml`（进 git，bcrypt），明文在 secret 里（gitignore） | `docker inspect` **看不到明文** |

⚠️ 真实文件被 `.gitignore` 排除（`docker/secrets/*`），仓库里只有 `.example` 模板。

**新机器 clone 后的初始化（已自动化）**：

```bash
bash scripts/setup-local.sh
```

该脚本**幂等**地生成 `.env` 与两个 secret 文件，并**校验 Prometheus 明文与哈希是否匹配**
（不匹配时直接报错并给出修复命令）。
**已存在的文件一律跳过、不覆盖**；已存在但内容不可用（空 / 只有注释 / 缺关键变量）时会
**明确报错并停止**，而不是静默重建。

⚠️ **改密码必须三步**（DR-055）：
① `bash scripts/gen-prometheus-hash.sh '新密码'`（同步哈希与明文）
② `docker compose up -d --force-recreate prometheus`
③ `bash scripts/reset-grafana-datasource.sh`
—— 缺 ③ 时 Grafana 数据源里存的还是**旧密码的密文**，
症状是「面板 No data 但 provisioning 日志一切正常」。

### 17.4 ⚠️ Actuator 暴露面的最小化

```yaml
management:
  endpoints:
    web:
      exposure:
        include: metrics,health,prometheus   # ⚠️ 绝不开 '*'
```

| 端点 | 是否暴露 | 理由 |
|---|---|---|
| `metrics` | ✅ | JSON 格式的指标，只含数值与 tag |
| `health` | ✅ | 探针用；`show-details: never` 不泄露依赖细节 |
| `prometheus` | ✅ | metrics 的**另一种格式**（Prometheus 文本），泄露面同级 |
| `env` / `configprops` | ❌ | **泄露数据库口令** |
| `heapdump` | ❌ | 内存镜像，含敏感数据 |
| `loggers` | ❌ | 可被用来改日志级别 |
| `*` | ❌ | 一次性放开上面所有 |

### 17.5 ⚠️ 绝对不要开 `http.server.requests` 的 `path` tag

Spring Boot 3 的 `http.server.requests` 默认 tag 集合：
`uri`（**URI 模板**，如 `/{code}`）、`status`、`method`、`outcome`、`exception`。

`path`（**实际路径**，如 `/0000Q0u`）**默认关闭**，必须保持关闭。

开了它会怎样——每个被访问过的短码都是一个新 tag 值：

1. meter 数量随**请求量**线性增长（不是随代码路径增长）；
2. 堆持续增长（每个 Meter 都是普通堆对象）；
3. Prometheus 每次抓取要序列化全部 meter，CPU 与内存双重压力；
4. Prometheus 自己可能因 series 爆炸而 OOM。

本项目的短码**每条短链唯一**，`path` 的基数 = 短码总数 = **无界**。

> 这正是 DR-057 怀疑的「Micrometer 动态 Tag」的真实机制 ——
> 但它影响的是**堆**，不是 Metaspace（类加载）。见 DR-061。

需要按路径统计时用 `uri`（模板，基数固定）就够了。
