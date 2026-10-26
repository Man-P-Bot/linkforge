# LinkForge 路线图（ROADMAP）

> 上游依据：`docs/PRD.md`（v1 范围与验收标准）、`docs/PROJECT_BRIEF.md`（约束）。
> 排期决策与变更原因见 `docs/DECISIONS.md`。
> 执行节奏：每天约 3 小时，每周约 **21 小时**。

---

## 1. 排期总览

| 周次 | 目标 | 产出物 | 验收命令 | 产出压测对比数据 |
|---|---|---|---|---|
| 第 0 周 | 环境收口 + Redis 入门 | 起 MySQL/Redis；缓存 Demo；`mvnw` 验证 | `docker compose up -d` + 一条 Redis 读写命令 | ❌ |
| 第 1 周 | MVP 跑通（无缓存） | 创建短链 + 302 跳转；**性能基线 + 环境快照** | curl 创建 → curl -I 拿 302；并发去重检查 | ✅ **基线（分母）** |
| 第 2 周 | 引入 Redis 缓存 | 跳转先查 Redis、未命中回填、TTL + 抖动 | 同脚本重跑，命中率 > 95% | ✅ 对比第 1 周 |
| 第 3 周 | 防穿透/击穿/雪崩 + 限流 + **删除短链** | 布隆过滤器、空值缓存、Caffeine、Redis+Lua 限流；admin 删除接口 | 不存在短码被拦截；限流 429；删除后立即 404 | ✅ 对比第 1/2 周 |
| 第 4 周 | 异步统计 + 过期 + **查统计接口** | Redis INCR PV、HLL UV、10s 批量落库、按天聚合；admin 查统计 | 跳转后 10 秒内统计可查 | ✅ 对比第 1/2/3 周 |
| 第 5 周 | 压测 + 监控 + 部署 | 压测脚本固化、Prometheus、Grafana、一键 compose | 干净目录按 README 跑出 A-4 四项指标 | ✅ **最终报告** |
| 第 6 周 | 交付物收口 | README、架构图、压测报告、故事与简历条目 | 人工评审 | ❌ |

**关键依赖**：

1. 第 2 周起的所有对比数据都依赖第 1 周的**基线与环境快照**（DR-004）。第 1 周没留基线，整个 A-3 验收无法完成。
2. 第 4 周的「查统计」依赖第 4 周的统计落库完成，**不能提前**（提前只能返回空壳）。
3. 第 3 周末的「删除短链」依赖第 2/3 周成型的缓存体系（DR-005）。
4. 第 5 周的一键部署依赖第 0 周起的 compose 环境。

**第 4 周额外待办**（第 3 周末压测时发现，不阻塞压测）：

- ⏳ **自定义短码与自动号段的边界**：如果用户手工创建纯数字 7 位自定义短码
  （如 `0000Q10`），会与自动发号撞——自动短码正是 `Base62.encodePadded(seq)`，
  纯数字串落在号段范围内。考虑在自定义短码校验里拒绝落在自动号段范围内的短码。
  关联 DR-053（号段游标的持久性）与 DR-022（号段回退）。

---

## 2. 阶段划分原则

- **每阶段必须可运行、可验证**：每阶段末尾附**可执行验收命令**与期望输出，不接受「代码写完了但没法证明」。
- **一次提交一个小节点**：Conventional Commits，`<type>(<scope>): <描述>`，`type` ∈ feat/fix/docs/refactor/test/chore/perf。
- **较大功能开 feature 分支**，合回 `main` 后删除；小改动直接提交 `main`。
- **每阶段开始前** `git status` 确认工作区干净；**每阶段结束**更新 `docs/PROGRESS.md`。
- **验证门槛**：提交前编译通过、相关测试通过、服务能启动（`PROJECT_BRIEF.md` §12.2）。

---

## 3. 分支与提交约定

### 3.1 分支命名

格式：`feature/w<N>-<短横线主题>`，`N` 为该功能**归属的周次**。

| 周次 | 分支名 |
|---|---|
| 第 1 周 | `feature/w1-mvp-shortlink` |
| 第 2 周 | `feature/w2-redis-cache` |
| 第 3 周 | `feature/w3-bloom-limiter` |
| 第 3 周末（删除短链） | `feature/w3-admin-delete` |
| 第 4 周 | `feature/w4-async-stats` |
| 第 5 周 | `feature/w5-bench-monitoring` |

规则：

- 一个分支只做一件事，对应上表一个主题；跨周的功能按**归属周次**命名，不按实际开工日期命名。
- 分支合回 `main` 后删除本地与远程分支，保持 `git branch` 干净。
- 修复分支用 `fix/<主题>`，文档分支用 `docs/<主题>`，不占用 `feature/w*` 命名空间。
- **不使用 `git push --force`**，不使用 `git reset --hard`（除非明确同意）。

### 3.2 提交信息示例

```
feat(shortlink): generate base62 short code from snowflake id
feat(cache): add redis cache-aside on redirect path
perf(cache): add ttl jitter to avoid avalanche
feat(limiter): implement token bucket with redis lua
feat(admin): delete shortlink with cache eviction
feat(stats): batch flush pv counters to mysql every 10s
docs(roadmap): define 6-week plan and milestones
test(shortcode): cover uniqueness under concurrent creation
```

---

## 4. 第 0 周 — 环境收口 + Redis 入门

**分支**：无（环境与文档改动直接提交 `main`）

### 任务

1. **JDK 对齐**：把生效 JDK 统一到 **17 LTS**（本机已装 `D:\javajdk\jdk-17.0.0.1`）。当前 PATH 生效的是 JDK 11、`JAVA_HOME` 指向 JDK 23，必须收口。
2. **构建工具**：生成 Maven Wrapper（`mvnw` / `mvnw.cmd`），固定 Maven 版本（DR-001）。
3. **Docker**：启动 Docker Desktop 守护进程，确认 `docker info` 可用。
4. **起依赖**：`docker compose` 起 **MySQL 8** 与 **Redis 7**，健康检查通过。
5. **Redis 入门 Demo**：跑通 set / get / expire / incr，理解 TTL 与原子自增。
6. **MySQL 连通**：能连上并执行基本 CRUD。
7. **⚠️ 沙箱写入验证（DR-001 退出条件）**：实测 `mvnw -v` 与一次完整构建。`mvnw` 首次运行要写 `~/.m2/wrapper`，而该路径在**工作区之外**，本机沙箱可能拒绝写入。若被拒绝，按 DR-001 的备选方案处理（把本地仓库指向工作区内），**不允许跳过**。

### 验收

```powershell
docker compose up -d
docker compose ps                       # 期望：mysql、redis 均 healthy
docker compose exec redis redis-cli ping   # 期望：PONG
.\mvnw.cmd -v                           # 期望：打印固定 Maven 版本（沙箱写入验证）
```

**退出条件**：以上命令全部成功，且 Redis 读写 Demo 能跑出可观察结果。

---

## 5. 第 1 周 — MVP 跑通（无缓存）+ 性能基线

**分支**：`feature/w1-mvp-shortlink`

### 任务

1. **Maven 多模块骨架**：**5 模块** `linkforge-app` / `linkforge-core` / `linkforge-redirect` / `linkforge-stats` / `linkforge-admin`，依赖规则见 DR-018。
2. 创建短链接口：接收长链与可选过期时间，返回短码。
3. 短码生成：**发号器分配** —— Redis `INCR` 取序号 → Base62 → 左补零到**固定 7 位**；Redis 不可用时**降级到数据库号段**发号（DR-011）。数据库 `short_code` 唯一索引仅作兜底。
4. 302 跳转接口，走根路径 `/{code}`，直接查 MySQL。
5. 目标地址协议白名单：仅 http/https。
6. **本阶段故意不加缓存**——它的唯一目的是产出**性能基线**。

### 环境快照（DR-004，必填）

在跑基线**之前**执行并原样记录到压测报告：

```powershell
(Get-CimInstance Win32_Processor).Name
(Get-CimInstance Win32_Processor).NumberOfCores
(Get-CimInstance Win32_Processor).NumberOfLogicalProcessors
[math]::Round((Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory/1GB,1)
java -version
docker info --format '{{.NCPU}} {{.MemTotal}}'
docker compose exec -T mysql mysql --version
docker compose exec -T redis redis-server --version
```

记录表（**已采集**；第 2、3、4、5 周的对比数据必须在此同一环境上取得）：

| 项 | 值 |
|---|---|
| 操作系统 | Windows 11 家庭版（中文版），版本 `10.0.26200` |
| 宿主机 CPU | **18 个逻辑处理器** |
| 宿主机内存 | **23.5 GB** |
| Docker 分配 CPU | **18** |
| Docker 分配内存 | **11.42 GiB** |
| Docker 版本 | **29.6.2** / Compose **v5.3.1**（WSL2 内核 **6.18**） |
| 镜像加速器 | **4 个已生效** |
| JDK | **OpenJDK 17.0.0.1**（2024-07-02） |
| MySQL 版本 | `mysql:8.4`（容器内 **8.4.11**；**宿主机映射 3307**，因本机 3306 被 MySQL80 占用，见 DR-017） |
| Redis 版本 | `redis:7.4`（宿主机 6379，已启用 RDB，`dump.rdb` 已生成） |

**环境快照的三条使用规则**：

1. 第 1 周基线压测**必须**在此环境上执行，并把本表原样抄进压测报告。
2. 任何一项发生变化（换机器、改 Docker 配额、升级 JDK），**基线作废，全部对比数据重取**（DR-004）。
3. ⚠️ **Docker 分配内存 11.42 GiB 是本环境最紧的约束**：它要同时承载 MySQL（含 InnoDB 缓冲池）、Redis（布隆约 12MB、当日 HLL 约 1.2GB 上界，见 `docs/DATABASE.md` §9.2）、应用 JVM，以及第 5 周的 Prometheus 与 Grafana。第 2 周起若出现容器被 OOM Kill，**优先怀疑此处**，而不是先怀疑代码。

### 验收

```powershell
# 创建短链，期望返回 7 位短码
curl.exe -s -X POST http://localhost:8080/api/v1/links -H "Content-Type: application/json" -d '{\"targetUrl\":\"https://example.com/very/long/path\"}'

# 跳转，期望 HTTP/1.1 302 且 Location 指向原链
curl.exe -s -I http://localhost:8080/<shortCode>
```

- 并发唯一性：并发创建 10000 个短链，检查数据库 `short_code` 无重复、无失败。
- `git status` 干净；测试通过。

### 基线记录（A-3 的分母）

**工具：容器内 wrk**（DR-019），压测容器与被测服务处于**同一 compose 网络**：

```powershell
docker run --rm --network linkforge_default williamyeh/wrk -t12 -c400 -d30s http://<app-service>:8080/<shortCode>
```

- ⚠️ URL 用**服务名**而不是 `localhost`——容器内的 `localhost` 指向压测容器自身，这是最容易犯的错。
- 与第 5 周使用**完全相同**的命令形式与口径，保证基线与最终结果可直接对比（DR-003 / DR-019）。

必须记录：QPS、P99、缓存命中率（本阶段为 0%）、数据库写入次数。**未记录基线 = 本阶段未完成。**

### ⚠️ 本阶段不做的事

- **不度量活跃短链比例**：第 1 周没有按天统计数据可查，度量只能得到编造的数字。该实测推迟到**第 4 周统计落库之后**（DR-020），届时回填 `docs/DATABASE.md` §9.3 与 PRD §6.2。

---

## 6. 第 2 周 — 引入 Redis 缓存

**分支**：`feature/w2-redis-cache`

### 任务

1. 跳转先查 Redis，命中直接 302。
2. 未命中回源 MySQL，存在则回填缓存。
3. 缓存 Key：`shortlink:code:{shortCode}`；TTL **1 小时 + 随机 0~5 分钟抖动**（防雪崩）。
4. 缓存异常时回退查库，保证跳转可用（PRD §6.3 降级要求）。
5. **Caffeine 本地缓存接入第 1 层**（DR-008，RDIS 正结果）。
6. **空值缓存 30 秒防穿透**；空值**不下沉到本地缓存**（DR-047）。
7. **三个 Micrometer 指标**：`shortlink.cache.local.hit` / `.redis.hit` / `.miss`，
   压测时从 `/actuator/metrics` 直接读（不从日志数）。
8. **healthcheck 升级为 `/actuator/health/readiness`**（下方 TODO 已兑现）。

### 验收

- 同一脚本重跑，**缓存命中率 > 95%**（PRD A-6）。
- QPS 与 P99 与第 1 周基线**并列记录**，同一环境、同一脚本、同一并发。
- 验证缓存未命中路径：清空单个 key 后仍能正常跳转并回填。

### 对比记录

| 指标 | 第 1 周基线 | 第 2 周实测 | 变化 |
|---|---|---|---|
| QPS | 13426.86 | **81289.94** | **+505.4%（6.05x）** |
| P50 | 6.59ms | **0.97ms** | **−85.3%** |
| P99 | 36.32ms | **4.58ms** | **−87.4%** |
| 命中率（正式窗口） | 0% | **100.00%** | — |
| 非 302 响应 | 0 | **0** | 无回归 |

✅ **验收全部达成**：命中率 100%（> 95% 阈值）、同环境同脚本同并发、
清空单key 后回源并回填已在冒烟中验证。

⚠️ **6 倍提升的解读**：第 1 周的 13426 是在「整表20MB 全内存 + `EXPLAIN type=const`」下的
理想上界，数据库侧几乎没有优化空间可省。这个 6 倍恰好印证了瓶颈确实在数据库侧；
生产环境数据库不可全内存时，缓存收益会更大（省掉真实磁盘 IO）。
详见 `docs/PERFORMANCE.md` §5.1 与 §7.1。

---

## 7. 第 3 周 — 防穿透/击穿/雪崩 + 限流 + 删除短链

**分支**：`feature/w3-bloom-limiter`（缓存防护与限流）、`feature/w3-admin-delete`（删除短链，周末）

> 本阶段是全程**第二重**的一周（DR-005）：防护、限流、删除三块内容集中在此。

### ⚠️ 范围调整（第 2 周后重梳理，2026-10-16）

原计划第 3 周的任务里有 3 项**已在第 2 周提前完成**：

| 原任务 | 状态 |
|---|---|
| 空值缓存 30 秒 | ✅ 第 2 周已完成（DR-045，`putEmpty`） |
| Caffeine 本地缓存 | ✅ 第 2 周已完成（DR-046/047） |
| TTL 随机抖动（防雪崩） | ✅ 第 2 周已完成（DR-045，1h + 0~5min） |
| 布隆过滤器 | ⬜ 移到第 3 周 |
| 回源互斥（单飞） | ⬜ 移到第 3 周（第 2 周**故意不做**，见 DR-048） |
| 限流 | ⬜ 移到第 3 周 |
| 删除短链 | ⬜ 提前到第 3 周末（DR-005） |

**因此第 3 周实际剩余 5 项**：
1. 布隆过滤器（防穿透，Redis 唯一权威，DR-007；规格 n=1000万 p=0.01 m≈12MB k=7）
2. 回源互斥/单飞（第 2 周故意留的，实测冷启动预热产生 32180 次重复回源）
3. Redis + Lua 令牌桶限流（按 IP：跳转 20/s，创建 10/min）
4. 删除短链接口（`DELETE /admin/**`，DR-005）
5. **targetUrl 拒控制字符**（DR-050，已在第 2 周末补上，本周只需回归验证）

### 任务

1. **防穿透**：Redis 布隆过滤器前置判断（空值缓存第 2 周已完成，本周不重复做）。
2. **防击穿**：回源互斥（单飞），避免热点 key 失效瞬间大量请求同时打穿数据库。
3. **防雪崩**：TTL 随机抖动第 2 周已完成，本阶段**验证效果**（冷启动集中写入 → 不集体过期）。
4. **限流**：Redis + Lua 令牌桶（DR-002），按 IP 限流，阈值配置化（跳转 20/s、创建 10/min）。
5. **异常降级**：限流组件不可用时**放行**，不拒绝请求。
6. **删除短链（第 3 周末，DR-005）**：`DELETE /admin/**`，鉴权走环境变量 Token。
   - 缓存一致性采用 **Cache Aside：先改库状态，再删缓存**，**不使用改缓存**（PRD D-7）。
   - 删缓存失败必须**记录并暴露指标**，不得静默吞掉。
   - 删除幂等：重复删除同一短码不报错。
7. **跳转链路不得依赖 admin 模块**（PRD D-4）：代码、依赖、限流配置、线程池均隔离。

### 验收

```powershell
# 不存在的短码：被布隆过滤器拦截，期望 404
curl.exe -s -o NUL -w "%{http_code}" http://localhost:8080/zzzzzz

# 限流：快速请求超过阈值，期望出现 429 且带 Retry-After
1..30 | ForEach-Object { curl.exe -s -o NUL -w "%{http_code}`n" http://localhost:8080/<shortCode> }

# 删除后立即失效：期望先 302，删除后 404
curl.exe -s -o NUL -w "%{http_code}" -X DELETE http://localhost:8080/admin/links/<shortCode> -H "Authorization: Bearer <token>"
curl.exe -s -o NUL -w "%{http_code}" http://localhost:8080/<shortCode>

# 无 Token 访问后台：期望 401/403
curl.exe -s -o NUL -w "%{http_code}" http://localhost:8080/admin/links/<shortCode>
```

- 删除后再跳转必须**立即** 404（缓存已删，回源发现已删除）。

---

## 8. 第 4 周 — 异步统计 + 过期 + 查统计接口

**分支**：`feature/w4-async-stats`

> 按 DR-005：本阶段**只做**异步统计与过期，**查统计留在本周末**，第 5 周不承接 admin。

### 任务

1. **PV 统计**：跳转时 Redis 自增计数，**跳转链路不碰数据库写**。
2. **UV 统计**：Redis **HyperLogLog**，误差率约 **0.81%**，内存约 **12KB/key**（PRD §7.4）。
3. **来源与 IP**：记录 Referer 用于渠道分布；IP 仅用于当日去重与限流。
4. **异步落库**：定时任务**每 10 秒**批量写 MySQL。
5. **按天聚合**：统计只存按天聚合结果，**不长期留存原始明细**（PRD D-5）；保留 **90 天**。
6. **过期语义**：过期判定以服务端时间为准；时间获取必须可注入，保证过期场景可写单测。
7. **查统计接口（周末）**：`GET /admin/**`，返回某短链某天的 PV/UV/来源；环境变量 Token 鉴权 + 独立限流。

### 验收

- 跳转后 **10 秒内**统计可查（PRD US-5）。
- 统计落库失败**不影响跳转**：人为让落库失败，跳转仍返回 302。
- 校验「跳转链路无同步数据库写」：观察跳转期间的数据库写入计数，仅定时任务产生写入。
- UV 口径说明写入文档：为估算值，含约 0.81% 误差。

---

## 9. 第 5 周 — 压测 + 监控 + 部署

**分支**：`feature/w5-bench-monitoring`

### 任务

1. **压测脚本固化**：容器内 wrk（DR-003），脚本进仓库，参数不含私有内容。
2. **测量口径声明（DR-003，必须写进报告）**：wrk 跑在容器网络压宿主机服务时，**绝对延迟仅供参考**，**优化前后的比例才是关键结论**。优先把被测服务放进**同一 compose 网络**加压；做不到则在报告中显式标注口径。
3. **Prometheus 指标**：跳转 QPS、缓存命中率、限流拒绝数、统计落库延迟。
4. **Grafana 面板**：可视化上述指标。
5. **一键部署**：`docker compose up` 起应用 + MySQL + Redis（PRD A-1）。
6. **最终对比报告**：第 1 周基线 → 第 5 周结果，同一脚本、同一环境。

### TODO（第 6 天补记）

- ✅ **healthcheck 已升级为 `/actuator/health/readiness`**（第 2 周随指标需求引入 actuator 时兑现）。原 TODO 记录：D6 给应用加 `app` 服务时，
  项目尚未引入 `spring-boot-starter-actuator`，因此 healthcheck 只能退化为
  `bash -c '</dev/tcp/localhost/8080'` 探测端口——它只能证明「Tomcat 起来了」，
  **不能证明依赖（MySQL / Redis）可用**，而 `depends_on: service_healthy` 的语义正是后者。
  已改为 `/actuator/health/readiness`（readiness 只报依赖就绪状态，
  不会因 liveness 探针误重启），与 `docs/ARCHITECTURE.md` §15.2 一致。
  实测：容器 `Up (healthy)`，`{"status":"UP"}`。actuator 端点暴露范围已最小化
  为 `metrics,health`（不开 `*`：env/heapdump 会泄露数据库口令与内存镜像）。
  在此之前，**容器 healthy 不等于应用可服务**，第 1 周的验证仍以真实 curl 为准。

### 验收（对应 PRD §8）

| PRD 编号 | 验收内容 | 验证方式 |
|---|---|---|
| A-1 | `docker compose up` 一键起全套 | 干净目录按 README 逐字执行 |
| A-2 | 压测脚本可直接跑 | 评审者不填私有参数即可复现 |
| A-3 | 有优化前后对比数据 | 第 1 周基线与本阶段结果并列 |
| A-4 | 四项性能指标达标 | QPS / P99 / 命中率 / DB 写入压力 |
| A-5 | P99 < 50ms 且无同步 DB 写 | 指标 + DB 写入计数 |

**裁剪预案（DR-006）**：若第 4 周溢出，**Grafana 面板深度是本阶段第一顺位裁剪项**，主线（基线、对比、一键部署、跳转性能）不得削减。

---

## 10. 第 6 周 — 交付物收口

**分支**：`docs/w6-deliverables`

> README 与架构图**从第 1 周起渐进维护**，本周只做收口，避免最后补文档导致描述与实现失真。

### 任务

1. **README**：架构图、启动方式、压测报告（含环境快照与口径声明）。
2. **架构图**：与 `docs/ARCHITECTURE.md` 保持一致。
3. **3 个故事**：最难的 Bug、最大性能优化、最纠结的技术取舍。
4. **5 个难点故事**：对应 PRD 中的决策记录与面试必问清单。
5. **简历条目**：带具体数字，且数字与压测报告一致。

### 验收

- 简历里的每个数字都能在仓库压测报告中找到出处。
- 5 个难点故事与 `docs/DECISIONS.md` 的决策能对应上。

---

## 11. 里程碑与验收矩阵

把 `docs/PRD.md` §8 的硬性标准映射到周次：

| PRD | 标准 | 交付周次 |
|---|---|---|
| A-1 | `docker compose up` 一键起 | 第 0 周（可起）→ 第 5 周（含应用） |
| A-2 | 压测脚本可直接跑 | 第 5 周 |
| A-3 | 优化前后对比数据 | 第 1 周（基线）+ 第 2~5 周（对比） |
| A-4 | 四项性能指标达标 | 第 5 周 |
| A-5 | P99 < 50ms 且无同步 DB 写 | 第 4 周（无 DB 写）+ 第 5 周（P99） |
| A-6 | 缓存命中率 > 95% | 第 2 周（初验）→ 第 5 周（终验） |
| A-7 | `/admin/**` 缺 Token 一律拒绝 | 第 3 周（删除）+ 第 4 周（查询） |
| A-8 | 非 http/https 一律拒绝 | 第 1 周 |
| A-9 | 核心逻辑有自动化测试 | 贯穿各周，第 5 周汇总 |

---

## 12. 时间预算与缓冲

- 每天约 **3 小时**，每周约 **21 小时**。
- 负载最重的两周：**第 3 周**（防护 + 限流 + 删除）与 **第 4 周**（统计异步化）。这两周若落后，立即执行 DR-006 的裁剪顺位。
- **第 1 周骨架回退规则（DR-009）**：多模块骨架若**卡住超过一天**，立即回退到 **3 模块（`app` / `core` / `admin`）+ ArchUnit** 守住依赖规则，**不硬扛**。骨架不是第 1 周的交付目标，MVP 与性能基线才是。

### 可裁剪项（进度落后时的削减顺序）

| 顺位 | 可裁剪项 | 影响 |
|---|---|---|
| 1 | Grafana 面板深度（仪表盘数量与美观度） | 不影响任何 PRD 验收标准 |
| 2 | Caffeine 本地缓存的调优深度 | 保留功能，减少调参 |
| 3 | 来源（Referer）分布统计的维度 | 保留 PV/UV，减少维度 |
| 4 | JMeter 备选方案 | 只保留 wrk |

### 绝不能砍

- 第 1 周**基线数据**与**环境快照**
- **优化前后对比**数据
- `docker compose up` 一键起
- 跳转链路性能与「无同步 DB 写」

---

## 13. 风险与回退路径

| 风险 | 触发信号 | 应对 |
|---|---|---|
| 第 1 周没留基线 | 直接加了缓存 | 回退到无缓存版本补测基线，**不得用估算值代替** |
| 环境变更导致对比失效 | 换机器/改 Docker 配额 | 基线作废，在同一环境重取全部对比数据 |
| 沙箱拒绝写工作区外路径 | `mvnw` 或 Docker 配置写入失败 | 按 DR-001 备选方案把仓库指向工作区内；第 0 周必须验证 |
| 第 4 周溢出 | 统计落库未按期完成 | 执行 DR-006：裁 Grafana 面板深度，**不顺延** |
| 限流组件故障 | 跳转出现 5xx | 降级为放行（已定策略），并暴露指标 |
| 布隆过滤器误判 | 真实短码返回 404 | 保留回源复核开关；重建过滤器时同步全量短码 |

**每周回退点**：阶段开始前 `git status` 确认干净；阶段内失败用 `git restore <file>`；已提交但需撤销用 `git revert <hash>`；**不使用 `git reset --hard`**。

---

## 14. 待确认问题清单

排期中留给下游文档的未决项：

| 编号 | 问题 | 归属文档 | 影响周次 |
|---|---|---|---|
| R-1 | 布隆过滤器放 Redis 还是本地内存；重建策略 | ARCHITECTURE | 第 3 周 |
| R-2 | Caffeine 本地缓存是否引入、引入的取舍 | ARCHITECTURE | 第 3 周 |
| R-3 | 过期短链的清理方式（定时任务 or 惰性判定） | DATABASE | 第 4 周 |
| R-4 | 限流阈值最终取值；令牌桶 vs 滑动窗口的对比结论 | ARCHITECTURE | 第 3 周 |
| R-5 | 统计聚合表的建模与 90 天数据清理 | DATABASE | 第 4 周 |
| R-6 | 错误码体系与统一错误响应体 | API | 第 1 周（先定，避免后期返工） |
| R-7 | Maven 本地仓库选址（沙箱写入限制的最终解法） | DECISIONS / 第 0 周 | 第 0 周 |

---

## 15. v1 已知限制与 v2 Roadmap（第 6 周收尾归档）

第 6 周交付物收口阶段做了**逐条比对「文档写了什么」与「代码做了什么」**的审计，
发现 4 处**文档与实现不一致**或**设计未落地**的地方。

**决策（DR-062）：一律不改代码，全部保留为 v2 事项**，理由是
① 第 5 周性能数据已冻结，改代码会破坏基线的可复现性；
② 这 4 条在当前部署形态下都不是阻塞问题；
③ 说清取舍边界本身是工程判断力的一部分。

### v2 待办清单

| # | 事项 | 影响 | 触发条件 | 修复方向 | 出处 |
|---|---|---|---|---|---|
| **V2-1** | **布隆重建窗口内写穿双写** | 重建期间新建的短码偶发首次 404 | 引入多实例部署 | `rebuilding` 状态标志 + `put()` 双写；或 `BITOP` 合并位后再切换 | DR-007 / DR-055 / T-027 |
| **V2-2** | **统计 HLL key TTL 收敛到当天 + 落库删除 UV key** | Redis 内存上界 1.2GB → 42GB（高 35 倍） | 活跃链路数达到 PRD 量级 | 落库后主动删 UV key；TTL 改「当天结束 + 余量」；补内存上界监控 | DATABASE §9.2 / T-028 |
| **V2-3** | **统计 90 天保留的批量清理任务** | 统计表无限增长 | 统计行数上来 | 按 `stat_date` 分批 DELETE（索引已建好）；或 range 分区 + drop 分区 | DATABASE §7.3 / §12 D-5 |
| **V2-4** | **readiness 纳入 MySQL / Redis / 布隆** | 「readiness 通过」不代表依赖可用 | 引入多实例部署 | `group.readiness.include=readinessState,db,redis` + 自定义布隆 `HealthIndicator` | ARCHITECTURE §15.2 |
| **V2-5** | ⚠️ **自定义短码长度与跳转路径不一致** —— 创建接受 4~8 位，跳转只认 7 位，**非 7 位是「创建 201 但永久 404」的死链** | ⚠️ **本项严重性最高**：按对外文档正常使用就会踩到（4/8 的允许长度是死链）。主路径（自动 7 位码）不受影响 | 契约改写时 | **首选契约收窄**：`customCode` 正则改为 `^[a-zA-Z0-9]{7}$`；备选：跳转路径放宽 / 前缀路由分流。**必须同时补「创建出来的短码一定可访问」的参数化测试** | **DR-063** / API.md §5.2 / T-029 |

### v2 的其它既有待办（非本次审计新增）

| 事项 | 出处 |
|---|---|
| 拆分 Redis 连接超时与命令超时（当前都是 50ms） | DECISIONS 附录「待验证项」 |
| 来源域（Referer）维度采集 —— 当前 `referer_domain` 硬编码 `'direct'` | DATABASE §4.3 |
| 真正的资源级隔离（独立线程池 / 独立连接池 / 独立部署单元） | ARCHITECTURE §3.3 |
| 数据库账户权限收紧（`.env` 中的 root 凭据 v2 移除） | DR-035 |
| 统计表 range 分区 | DATABASE §7.3 |
