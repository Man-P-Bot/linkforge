# LinkForge 进度台账

> 每轮更新。最新在最上。字段：已完成 / 当前状态 / 下一步 / 阻塞点。
> 目标与约束以 docs/PROJECT_BRIEF.md 为准。

---

## 第 1 周 — MVP 跑通（无缓存）+ 性能基线（🚧 进行中：D2 第一部分完成）

### 仓库基线变更（非我操作，已核对）
- 分支由 `master` 改为 **`main`**，历史被压缩为 `c6c711d chore: bootstrap LinkForge with docs, build skeleton and local environment`。
- 新增 `f792999 test(arch): add package-info to keep module boundary rules effective`：为 4 个模块包各加 `package-info.java`，使 ArchUnit 规则**真正生效**（空包会让规则变成空跑）。**这个补充是必要的**——正是我此前报告中提到的「空跑通过」隐患。
- 核对结果：8 份 docs 全部保留，DR-001 ~ DR-021 完整。

### D2 第一部分已完成：Flyway V1 建表脚本 ✅
| 项 | 结果 |
|---|---|
| 脚本位置 | `linkforge-app/src/main/resources/db/migration/V1__init_schema.sql` |
| 表 | `short_link`、`link_stats_daily`、`id_segment`（3 张） |
| 实跑验证 | ✅ 容器内 MySQL 8.4.11 执行通过，无语法错误 |
| 引擎/字符集 | ✅ 3 张表均 `InnoDB` + `utf8mb4_0900_ai_ci` |
| 索引 | ✅ `uk_short_code(short_code)`、`idx_status_code(status, short_code)`、`uk_link_date_referer(link_id, stat_date, referer_domain)`、`idx_stat_date(stat_date)` |
| `ON UPDATE` 行为 | ✅ 实测：插入后 `updated_at = created_at`；更新 `status` 后 `updated_at` 前进 1 秒，`created_at` 不变 |
| 禁用语句自查 | ✅ 可执行语句中 `DROP TABLE` / `TRUNCATE` / 无 WHERE 的 `DELETE` 均为 **0 处** |
| 环境清理 | ✅ 验证用的 3 张空表已删除，`linkforge` 库当前表数 **0**，等 Flyway 首次迁移做真验证 |

### 本轮加列（按用户确认，理由：现在改 V1 零成本，落库后再加要 ALTER）
- `short_link`：新增 **`is_custom TINYINT NOT NULL DEFAULT 0`**（对外体现为 API 的 `custom` 字段）、**`updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP`**（回答「何时被删」）。
- `link_stats_daily`：新增 **`created_at`**、**`updated_at`**（排查落库延迟）。
- 文档未删改：`docs/DATABASE.md` §4.1/§4.3 本来就写了这些字段，**建表与文档现已一致**。

### ⚠️ 本轮修正：Mapper 位置（DR-021）
- **决策**：MyBatis Mapper 接口与 XML 放 **`linkforge-core`**，不在 `redirect`。
- **原因**：admin 删除短链要访问 `LinkRepository`；Mapper 放 `redirect` 会导致 admin 要么依赖 `redirect`（违反 DR-018），要么看不到实现（**Spring 装配失败**）。
- **同时作废 DR-018 的「core 必须保持薄」**：改为「core 是共享抽象 + 共享持久化访问层」，仍禁止把**只属于某模块**的实现（缓存/限流/布隆/Web）放进 core。
- `core` 将新增依赖：`mybatis-spring-boot-starter`、`mysql-connector-j`。
- ⏳ 待办：`linkforge-core` 的 `package-info.java` 中「保持薄，不装具体实现」的 javadoc 需在数据访问层落地时同步修正。

### 下一步（D2 剩余部分）
1. ✅ `linkforge-app/application.yml`：数据源统一由 **`SPRING_DATASOURCE_URL`** 覆盖（**不分 profile**），Flyway 开关 + `classpath:db/migration`。
2. ✅ 补依赖：`flyway-mysql`、`mysql-connector-j`、`mybatis-spring-boot-starter:3.0.5`。
3. ✅ 数据访问层骨架：`core` 的 `Link` / `LinkStatus` / `LinkRepository` / `LinkMapper` / `LinkStatusTypeHandler` / `LinkMapper.xml`。
4. ✅ 修正 `core` 的 `package-info.java` 过期 javadoc；`.env.example` 补 `SPRING_DATASOURCE_URL`。
5. ⏸ **交用户跑 `.\mvnw.cmd clean install` 并启动应用**，验收 `flyway_schema_history` 与 `SHOW TABLES`。

### 本步新增文件（D2 第 1~4 步）
| 文件 | 说明 |
|---|---|
| `linkforge-app/src/main/resources/application.yml` | 数据源走 `SPRING_DATASOURCE_URL`；Flyway `classpath:db/migration`；MyBatis `classpath*:mapper/*.xml` |
| `linkforge-core/src/main/java/com/linkforge/core/domain/Link.java` | 不可变实体，8 个字段与 `short_link` 一一对应，无 setter |
| `linkforge-core/src/main/java/com/linkforge/core/domain/LinkStatus.java` | `ACTIVE(0)` / `DELETED(1)`；`fromCode` 遇未知值**抛异常不静默降级** |
| `linkforge-core/src/main/java/com/linkforge/core/repository/LinkRepository.java` | 仓储接口，5 个方法 |
| `linkforge-core/src/main/java/com/linkforge/core/infrastructure/persistence/LinkMapper.java` | MyBatis 接口，`@Mapper` 显式标注 |
| `linkforge-core/src/main/java/com/linkforge/core/infrastructure/persistence/LinkStatusTypeHandler.java` | `LinkStatus` ↔ `TINYINT` 转换（内置 `EnumTypeHandler` 按 name 存字符串，与本表不匹配） |
| `linkforge-core/src/main/resources/mapper/LinkMapper.xml` | 6 条语句，注释写明各自走哪个索引 |

### 本步关键决策点
- **数据源单一来源**：`application.yml` 只写 `${SPRING_DATASOURCE_URL}`，**不设默认值**、**不分 profile**。理由：12-Factor；缺失时启动即失败，避免静默连错库。
- **Mapper 放 core**（DR-021）：admin 与 redirect 都要访问 `LinkRepository`，放任一侧都会破坏隔离或导致装配失败。
- **`LinkStatusTypeHandler` 必须自写**：MyBatis 默认 `EnumTypeHandler` 按 `name()` 存字符串，而本表 `status` 是 `TINYINT`（DATABASE.md §4.1 刻意不用 ENUM 列），不写会在运行时报类型错误。
- **`fromCode` 遇未知取值抛异常**：静默当成 `ACTIVE` 会让已删除的短链重新可跳转，属数据正确性问题，必须早失败。
- **布隆重建 SQL 只取 `short_code` 一列**：走覆盖索引 `idx_status_code(status, short_code)`，`EXPLAIN` 的 `Extra` 应为 `Using index`，避免读取 2048 字节的 `target_url`。
- **`updateStatusToDeleted` 带 `AND status = 0`**：让重复删除天然影响 0 行，作为「404 vs alreadyDeleted」的判定依据。
- **`markDeleted` 只改库、不删缓存**：删缓存是 Cache Aside 第二步，必须在事务提交后由上层执行（ARCHITECTURE §10）。

### D1 已完成：5 模块 Maven 骨架
- ✅ 模块划分由 4 改为 **5 模块**（新增 `linkforge-core`），决策记录 **DR-018**。
- ✅ 父 POM + 5 个子模块 POM，全部 XML 校验通过。
- ✅ 应用入口 `LinkForgeApplication`（`scanBasePackages = "com.linkforge"`）。
- ✅ `ModuleBoundaryArchTest`：把 DR-018 的依赖规则变成**可执行的 ArchUnit 测试**。
- ⏸ **首次 `.\mvnw.cmd clean install` 待用户在终端执行**（需联网拉依赖树）。

### 5 模块结构（DR-018）
| 模块 | 职责 | 依赖 |
|---|---|---|
| `linkforge-core` | `Link` 实体、`LinkRepository`、`ShortCodeGenerator`、`CacheService` 接口 | `spring-boot-starter` |
| `linkforge-redirect` | `RedirectController`、缓存实现、布隆、限流 | `core` + `stats` + web |
| `linkforge-stats` | `StatsService`、定时落库 | `core` |
| `linkforge-admin` | `AdminController`、鉴权、统计查询 | `core` + `stats` + web |
| `linkforge-app` | 启动 + 装配（唯一可执行 jar） | 全部 |

依赖禁令：`redirect → admin` ❌、`admin → redirect` ❌（**双向隔离**）、`stats → redirect/admin` ❌。

### 为什么必须补 `core`（本轮修正的设计洞）
「admin 删除短链」需要 `Link` 实体与 `LinkRepository`，原 4 模块下两条路都堵死：
1. `Link` 放 `redirect` ⇒ `admin` 必须依赖 `redirect` ⇒ admin 被拖进跳转链路全部依赖（缓存/布隆/限流），与 DR-009 物理隔离的初衷相反；
2. `Link` 放 `admin` ⇒ `redirect` 必须依赖 `admin` ⇒ **直接违反 DR-009**。
⇒ 领域模型必须有一个既不属跳转也不属后台的归属，这就是 `core`。

⚠️ **`core` 必须保持薄**：只放领域模型、仓储接口与共享抽象，**不得**放缓存/限流/布隆实现，否则会退化成「什么都能装」的杂物间。因此 `core` 目前**只依赖 `spring-boot-starter`**，连 JDBC 驱动都不放（具体数据访问实现待 D2 定）。

### 本轮其他变更
| 变更 | 落实 |
|---|---|
| D6 基线工具明确为**容器内 wrk** + 具体命令（同 compose 网络） | ROADMAP §5「基线记录」；**DR-019** |
| 活跃短链比例**推迟到第 4 周**度量（第 1 周无统计数据可用） | ROADMAP §5「本阶段不做的事」；**DR-020** |
| README 注明 Redis Demo 是环境验证 Demo、第 1 周起不再维护 | README「运行 Redis 演示」 |
| 回退规则改为 **4 模块**（`app`/`core`/`redirect`(含 stats)/`admin`），非 3 模块 | DR-018（理由：3 模块会让 core 过重） |

### 首次构建的关键信息（交给用户终端执行）
```powershell
.\mvnw.cmd clean install     # 首次会下载 Spring Boot 3.5.8 依赖树，数百 MB，耗时较长属正常
```
- 父 POM：`spring-boot-starter-parent:3.5.8`（**已联网核实阿里云仓库存在**）。
- ArchUnit：`archunit-junit5:1.3.0`（**已核实存在**）。
- 全部 POM 已通过 UTF-8 XML 解析校验。
- 构建失败请把错误贴回，我据实修（**不要改 DR-018 的依赖规则来绕开错误**）。

### 当前状态
- 阶段：**第 1 周 D1**（骨架），MVP 与性能基线尚未开始。
- 分支建议：`feature/w1-mvp-shortlink`。

### 阻塞点
- ⏸ **首次构建待用户执行**（本沙箱默认无外网，无法下载依赖树）。
- ⚠️ 本沙箱无法访问 Docker 命名管道；`docker`/`compose` 相关命令需用户终端执行或临时提权。

### 下一步
1. 用户执行首次构建 → 回报结果 → 我修问题（若有）。
2. D2：Flyway `V1__init_schema.sql`（3 张表 + 覆盖索引 `(status, short_code)`，**禁止 `DROP TABLE`**）。
3. D3：发号器（Redis `INCR` → Base62 → 7 位，降级数据库号段）。
4. D4/D5：创建接口 + 跳转接口（按 API 契约的错误码）。
5. D6：并发唯一性验证 + **容器内 wrk 基线** + 环境快照。
6. D7：补单测、README 更新、定号段步长 `step`（D-4）。

---

## 第 0 周 — 环境收口（✅ 已完成）

### 第 0 周验收结果（全部通过）
| 验收项 | 结果 |
|---|---|
| `docker compose ps` | `linkforge-mysql`、`linkforge-redis` **均 healthy** |
| MySQL 版本 | **8.4.11** |
| MySQL 端口映射 | `0.0.0.0:3307 -> 3306`（宿主机 3306 被本机 MySQL80 占用，已避让） |
| MySQL 认证 | ✅ 正确密码通过；✅ 错误密码被拒 |
| Redis 端口 | 6379，`redis-cli ping` 返回 **PONG** |
| Redis RDB | ✅ `/data` 下已生成 `dump.rdb`，Windows 绑定挂载无权限问题 |
| `.\mvnw.cmd -v` | ✅ `Apache Maven 3.9.12` / Java 17.0.0.1 |
| Maven 本地仓库 | ✅ `D:\homework\projects\linkforge\.mvn-repo`（工作区内） |
| Redis Demo | ✅ SET / GET / EXPIRE / INCR **四项断言全部通过**（退出码 0） |
| 编码校验 | ✅ `.env`、`.env.example` 等 7 个文件均为 **UTF-8 无 BOM**，中文无损坏 |

### 环境快照补充（已写入 ROADMAP §5）
- **宿主机 3306 被本机 MySQL80 服务占用 ⇒ MySQL 映射到 3307**，容器内部端口恒为 3306（DR-017）。
- ⚠️ 宿主机直连用 `localhost:3307`；**compose 网络内应用必须连 `mysql:3306`**。这两个值不一致是有意的，不要"统一"。
- ⚠️ 误填 3306 会连到**宿主机自己的 MySQL80**（另一个实例、可能没有 `linkforge` 库），症状是「表不存在」，极易误判为迁移失败。

### 本轮新增/修改文件
| 文件 | 说明 |
|---|---|
| `scripts/redis-demo/RedisDemo.java` | 纯 Java 单文件 Redis 演示（内置最小 RESP 客户端，无 Spring Boot、无第三方依赖） |
| `README.md` | **新建**：当前进度、快速开始、Redis 演示运行方式、文档索引 |
| `.env.example` | `MYSQL_PORT=3307` + 避让原因注释 |
| `docker-compose.yml` | 端口默认值改 3307 + 注释；`name: linkforge` |
| `docs/DECISIONS.md` | 追加 **DR-016**（Maven 阿里云来源）、**DR-017**（MySQL 3307） |
| `docs/ROADMAP.md` | §5 环境快照表已填实 |

### Redis Demo 说明
- 运行：`java "-Dstdout.encoding=UTF-8" scripts/redis-demo/RedisDemo.java`
- 覆盖 **SET / GET / EXPIRE / INCR** 四件事，且**每项都有断言**（读回值一致、TTL 在 1~2 秒、到期后返回 nil、INCR 两次得 2），全通过时退出码 `0`。
- 刻意**不引入 Spring Boot / 第三方客户端**：直接手写最小 RESP 协议，目的是看清「一次 Redis 命令在网络上的样子」，而不是被框架包装掉。
- 默认清理演示 key；`--keep` 可保留观察。

### ⚠️ 本轮实测发现：Maven Central 不可达（DR-016）
- `repo.maven.apache.org`、`dlcdn.apache.org`：curl 返回 **`000`（连接被切断）**；阿里云仓库可达（200）。
- 处置：`distributionUrl` 与依赖镜像全部改用**阿里云**，镜像声明放进**仓库内** `.mvn/settings.xml`（不依赖个人 `~/.m2/settings.xml`），以满足 PRD A-1「干净环境按 README 即可构建」。
- 第 1 周首次构建需联网拉取全量依赖树（`.mvn-repo` 从零填充，数百 MB），**耗时长属正常，不是故障**。

### 当前状态
- 阶段：**第 0 周完成**，即将进入第 1 周（MVP + 性能基线）。
- 文档：五份设计文档 + DECISIONS + PROGRESS 全部定稿。

### 阻塞点
- ✅ 无阻塞。
- ⚠️ 环境提示（不阻塞）：本沙箱默认无外网、无法访问 Docker 命名管道，涉及 `docker` 与依赖下载的命令需在**用户自己的终端**执行，或本会话临时提升访问权限。

### 下一步（第 1 周）
1. 搭 Maven 多模块骨架（`app` / `redirect` / `stats` / `admin`），含 **DR-009 的「卡住超过一天即回退 3 模块 + ArchUnit」** 规则。
2. Flyway `V1__init_schema.sql` 建表（短链主表、统计聚合表、发号序列表；**禁止 `DROP TABLE`**）。
3. 短码发号器：Redis `INCR` → Base62 → 固定 7 位；Redis 不可用降级数据库号段（DR-011）。
4. MVP：创建短链 + 302 跳转（无缓存），按 API 契约实现错误码 `LINKFORGE-4xxx`。
5. **性能基线 + 环境快照**（无缓存版本跑压测，作为 A-3 的分母）——**没有基线 = 第 1 周未完成**。
6. 实测活跃短链比例校验 1% 假设（D-3）；定号段步长 `step`（D-4）。

### 待办（累积，非阻塞）
- 第 2 周：回填本地缓存 `maximumSize` / `expireAfterWrite`（A-5）。
- 第 3 周：回填限流阈值与算法对比结论（A-6）；定布隆重建调度时刻（D-6）；定 `Retry-After` 取值来源（P-5）。
- 第 4 周：落实 HLL key 当天释放的硬约束（D-1）；确定清理批大小（D-5）。
- 第 5 周：创建接口限流阈值与压测场景的平衡（P-1）。

### 历史记录：Git 写入失败（已解决，留档备查）
- 根因：DSH 沙箱进程以**低完整性级别**运行；工作区根目录带 `Mandatory Label\Low Mandatory Level` 标记所以可写，但**早于该标记存在**的 `.git` 仍是中完整性，其子目录拒绝写入。
- 结论：**在根目录标记生效后新建的目录会自动继承低完整性标记**，重建后的 `.git` 读写正常。
- 教训：后续若再遇同类写入被拒，优先「在标记生效后重建目标目录」，不要去改完整性标记（沙箱内无安全权限，会失败）。

---

## 前期文档阶段 — ✅ 五份文档全部定稿（PRD / ROADMAP / ARCHITECTURE / DATABASE / API）

- 已定稿：`docs/PRD.md`（v1.0）、`docs/ROADMAP.md`、`docs/ARCHITECTURE.md`、`docs/DATABASE.md`、`docs/API.md`。
- 决策记录：`docs/DECISIONS.md` DR-001 ~ DR-017。
- 关键修正留档：DR-011（短码生成改为发号器 + 固定 7 位，原「雪花 ID → Base62 → 6~8 位」数学上不成立）、DR-012（补活跃短链比例 1% 假设，修正容量估算不自洽）。
