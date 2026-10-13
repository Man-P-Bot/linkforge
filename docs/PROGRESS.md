# LinkForge 进度台账

> 每轮更新。最新在最上。字段：已完成 / 当前状态 / 下一步 / 阻塞点。
> 目标与约束以 docs/PROJECT_BRIEF.md 为准。

---

## 第 1 周 — MVP 跑通（无缓存）+ 性能基线（🚧 进行中：D2 完成，D3 发号器已写待构建）

### D3 已完成（待用户构建验证）
| 内容 | 位置 |
|---|---|
| `IdSpace`（号空间不变量：区间、长度、上界） | `core/domain/` |
| `Base62`（**自写编码表 `0-9A-Za-z`**，编解码 + 定长补零） | `core/domain/` |
| `ShortCodeGenerator` 接口 + `ShortCodeGenerationException` | `core/repository/` |
| `IdSegmentMapper` + `IdSegmentMapper.xml` | `core/infrastructure/persistence/` + `core/resources/mapper/` |
| `RedisShortCodeGenerator`（主路径，`INCR` + `SETNX` 初始化） | `redirect/infrastructure/id/` |
| `IdSegmentAllocator`（**独立 Bean 保证 `@Transactional` 生效**） | `redirect/infrastructure/id/` |
| `SegmentShortCodeGenerator`（降级路径，`getAndIncrement` 先取后判） | `redirect/infrastructure/id/` |
| `FallbackShortCodeGenerator`（单向降级 + 降级计数） | `redirect/infrastructure/id/` |
| `ShortCodeGeneratorConfig`（装配 + 启动自检） | `redirect/infrastructure/id/` |
| `V2__init_id_segment.sql`（`max_id = 999999999`） | `linkforge-app/src/main/resources/db/migration/` |
| `Base62Test`（30+ 用例：编码表、进位、补零、双射、边界） | `core/src/test/` |
| `ShortCodeGeneratorTest`（号段推进、并发、单向降级、区间不重叠） | `redirect/src/test/` |

### D3 构建结果（✅ 全绿）
`.\mvnw.cmd clean install` → **BUILD SUCCESS**，6/6 模块，**102 个测试，0 失败**（Core 75 + Redirect 24 + App 3），总耗时 16.5s。

### D3 修复的三个真实缺陷（均由实际运行发现，非推理）
1. **⚠️ `Base62Test` 的第二条断言写错了**：`"0000000".replaceFirst("^0+", "")` 得到的是 **`""`**（正则把整个字符串吃掉），而 `Base62.encode(0L)` 是 `"0"`，两者永不相等。
   - 报错 `expected: "0" but was: ""` 里，`"0"` 是 `encode(0L)`，`""` 是 `replaceFirst` 的结果。
   - 修法：改为「把 `encode` 结果左侧补齐到 7 位再比较」，`0` 的情况自然成立。实测 `Base62.encodePadded(0L) = [0000000]`，长度 7 —— **实现本来就是对的**。
2. **⚠️ 号段每次取段浪费一个号**：`current` 与 `limit` 都初始化为「首号 − 1」，首次调用 `next == limit` 触发虚假取段并吃掉区间首号。
   - 实测证据：连发 8000 个号只得 **7939** 个唯一值；`oneAdvancePerSegment` 期望 1 次取段却发生 2 次。
3. **⚠️ 并发下丢失号**（更隐蔽）：快路径用 `getAndIncrement`，而取段会把 `current` **重置**回新段段首；一个「读到旧值、却在重置之后才写回」的线程会用陈旧值覆盖计数器，跳过整个区间的号。
   - 实测证据：改为「先取后判 + 算术推进」后，连发 8000 个号只得到 **7947** 个唯一值。
   - 最终修法：**CAS 抢占** + 取段全程持锁。CAS 在值被改动时会失败，因此不会用陈旧值覆盖 `current`。修复后 8000/8000 唯一。
   - ⚠️ 另一个被否掉的写法：`current` 用 `Long.MIN_VALUE` 作哨兵配合「加一后比较」——`Long.MIN_VALUE + 1` 仍小于 limit，哨兵漏进业务逻辑，实测报错「序号不能为负: -9223372036854775808」。
4. **取段改为惰性**：构造时取段会让「只为装配而创建的实例」也消耗一段号，并让单元测试互相污染（实测 `ensureInitializedAcceptsValidRange` 期望 999999999 却得到 1000000999）。

### D3 关键决策（DR-022）
- **号空间区间划分**（取代原「共用同一号空间」）：
  - Redis `[100_000, 1_000_000_000)` → 9.999 亿个号，按日创建 **10 万**条可用约 **27.4 年**
  - 数据库号段 `[1_000_000_000, 62^7)` → 约 3.52 万亿
  - 不重叠 ⇒ 降级期间**不需要任何跨存储同步**即可保证唯一性
  - ⚠️ **口径更正**：此前写的「约 74 年」是**算术错误**。正确：`999_900_000 / 100_000 / 365 = 27.39 年`。74 年对应日创建 `37,020` 条，与「日创建 10 万条」假设矛盾，以 27.4 年为准；已由测试断言锁住。
- **⚠️ 修掉了一个会重复发号的边界错**：原方案 `id_segment.max_id = 100000`，而号段发出 `max_id + 1`，会让号段从 **100001** 起，与 Redis 首个发出的号重合。现为 **999999999**，由 `IdSpace` 常量推导并由单测断言锁住。
- **`ensureInitialized()` 补齐了「key 存在但值异常」的分支**（D3 构建后修正）：原实现只用 `SETNX`，而 `SETNX` **不覆盖已存在的 key**——Redis 被清空/RDB 回退后计数器从小值继续，会重发历史短码。现三分支：不存在→`SETNX` 初始化；存在且值 `< 99999` 或非整数→`SET` 修正到 99999 **并记 WARN**；存在且值正常→不动（**绝不重置**）。
- **⚠️ 修掉了一个会重复发号的边界错**：原方案 `id_segment.max_id = 100000`，而号段发出的是 `max_id + 1`，会让号段从 **100001** 起，与 Redis 首个发出的号重合。现为 **999999999**，由 `IdSpace` 常量推导并由单测断言锁住。
- **单向降级**：Redis 抖动后该实例永久走号段，不回切（回切必须先同步号段进度，否则必然重号）。重启实例即恢复。
- **⚠️ 已知风险**：Redis 被清空（RDB 回退/`FLUSHDB`）会让 `INCR` 从头开始 → 短码可能重复。v1 三层处置：`SETNX` 启动初始化 + **`uk_short_code` 唯一索引兜底** + 新增指标 **`shortlink_id_conflict_total`** 让运维立即发现。**v2 根治：`short_link` 加 `seq` 列，启动取 `MAX(seq)` 作为 Redis 初值**。
- **两条被写进代码注释的评审重点**：`@Transactional` 不能写在自调用方法上（故抽 `IdSegmentAllocator`）；自增必须「先取后判」（`getAndIncrement` 再比较），写成「先判后取」会让两个线程发出同一个号。
- **Base62 编码表自写**：JDK 的 `toString(n, 62)` 用 `0-9a-zA-Z`（序号 10 → `"a"`），本项目用 `0-9A-Za-z`（序号 10 → `"A"`）。已用测试把两者差异显式钉住。
- **删除了 `countActiveCreatedBefore`**（用户指出）：全仓零引用，是第 4 周「活跃短链比例」的预留，属提前引入。D3 发号器不需要它。

### 待验证（交用户执行）
```powershell
.\mvnw.cmd clean install     # 期望 6 模块 BUILD SUCCESS，Base62Test + ShortCodeGeneratorTest 全绿
.\scripts\run-local.ps1      # 期望 Flyway 执行 V1 + V2，应用启动成功
```
启动后验收：
```powershell
docker compose exec mysql mysql -uroot -p linkforge -e "SELECT version, description, success FROM flyway_schema_history;"
docker compose exec mysql mysql -uroot -p linkforge -e "SELECT * FROM id_segment;"
# 期望：max_id = 999999999，step = 1000
```

### ⚠️ `.env` 必须包含 `SPRING_DATASOURCE_URL`（否则 `run-local.ps1` 会按设计拦住）
```
SPRING_DATASOURCE_URL=jdbc:mysql://localhost:3307/linkforge?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&characterEncoding=utf8
```
- **`allowPublicKeyRetrieval=true` 是必需的**：MySQL 8.4 默认认证插件是 `caching_sha2_password`，
  JDBC 驱动首次连接需要向服务器索取 RSA 公钥来加密密码，默认被禁止，否则启动报
  `Public Key Retrieval is not allowed`（SQL State 08001）。
- ⚠️ **仅本地开发使用**。生产环境应改为 `useSSL=true` 并配置证书，**不要开 `allowPublicKeyRetrieval`**
  （允许明文索取公钥会削弱中间人防护）。
- 参数顺序保持 `useSSL=false` 在前、`allowPublicKeyRetrieval=true` 紧随其后，与 `.env.example` 一致。

### D2 已完成：Flyway V1 + 数据访问层骨架

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
