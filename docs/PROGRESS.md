# LinkForge 进度台账

> 每轮更新。最新在最上。字段：已完成 / 当前状态 / 下一步 / 阻塞点。
> 目标与约束以 docs/PROJECT_BRIEF.md 为准。

---

## 第 1 周 — MVP 跑通（无缓存）+ 性能基线（🚧 进行中：**D1~D6 完成，基线已记录**）

### D5 启动验证结果（✅ 全部通过，D5 正式完成）

| 验收项 | 结果 |
|---|---|
| 容器状态 | ✅ `linkforge-mysql` / `linkforge-redis` 均 **healthy** |
| 应用启动 | ✅ Tomcat **8080**，`Started LinkForgeApplication in 4.259 seconds` |
| 启动日志 | ✅ **无 ERROR**（曾观察到一条来源未定位的 Flyway WARN，见下） |
| ① 创建短链 | ✅ `201`，`shortCode = 0000Q0u`（7 位 Base62），`createdAt = 2026-10-14T21:40:03+08:00` |
| ② **跳转** | ✅ `302`，`Location: https://example.com/very/long/path`，`Cache-Control: no-store`，**body 0 字节** |
| ③ 不存在的短码 | ✅ `404` + `LINKFORGE-404-01` |
| ④ 非法字符（`abc_123`） | ✅ `400` + `LINKFORGE-400-01` |
| ⑤ 尾斜杠（`0000Q0u/`） | ✅ `400` + `LINKFORGE-400-01`，**未重定向纠正** |

**第 2 条的三项重点核对**（全部符合 API.md §4.2/§4.3）：
- `Location` 是**目标绝对 URL**，以 `https://` 开头 —— 不是 `shortUrl`
  （填 `shortUrl` 会让浏览器跳回自己，形成多余往返）；
- `Cache-Control: no-store` 存在；
- 响应**无 body**（`Content-Length: 0`，`size_download = 0`）。

**失败路径的实测响应**（统一错误体，`timestamp` 按服务端时区 `+08:00` 渲染）：
```
GET /zzzzzzz   → 404 {"code":"LINKFORGE-404-01","message":"短码不存在: zzzzzzz",...}
GET /abc_123   → 400 {"code":"LINKFORGE-400-01","message":"短码只能包含 Base62 字符（0-9A-Za-z）: \"abc_123\"",...}
GET /0000Q0u/  → 400 {"code":"LINKFORGE-400-01","message":"短码路径不允许尾斜杠: \"/0000Q0u/\"",...}
```

**⚠️ 尾斜杠为什么需要显式注册 `/{code}/`**（DR-037，实测确认）：
Spring 6 默认**不匹配尾斜杠**，若只注册 `GET /{code}`，`/0000Q0u/` 会落成
「找不到处理器」的 404 —— 状态码看似对，但 body 不是统一错误体，属于契约违规。
实测加了显式映射后才拿到 `400 + LINKFORGE-400-01`。

**启动时曾观察到的一条 WARN（真实来源未定位，记录在案）**：
启动日志中曾出现 `Detected 2 non-empty schema history table(s)`。
**初版归因「D4 集成测试污染 `linkforge` 库」已被实测推翻**——Flyway 按库工作，
`linkforge_test` 的元数据不会触发 `linkforge` 库的该条告警。实测结论：

| 排查项 | 结果 |
|---|---|
| 两个 non-empty schema | `linkforge` 与 `linkforge_test`，**两库各 1 张** `flyway_schema_history`（均 V1/V2，`success=1`） |
| 是否同一物理库的不同 schema | ❌ 不是。`application.yml` → `linkforge`，`application-test.yml` → `linkforge_test`，是**同一 MySQL 实例下的两个独立库**，各自独立连接串与连接池 |
| 主配置是否有交叉路径 | ❌ 无。主配置不含任何指向 `linkforge_test` 的路径 |
| 能否复现 | ❌ **不能**。连续 3 次冷启动（全新 JVM）日志完全一致，该消息再未出现 |
| 是否 Flyway 输出 | ❌ 不是。在 `flyway-core-11.7.2.jar` / `flyway-mysql-11.7.2.jar` 字节码常量池中全文搜索该文案：**零匹配** |

**结论**：真实来源未定位。当前启动日志**不含 ERROR**，迁移正常
（`Current version of schema 'linkforge': 2`、up to date），**不影响迁移，也不影响 D6 压测**
（D6 用 `linkforge` 库，与 `application-test.yml` 是两条独立路径）。**不做修复**——
不为一条复现不出的现象改配置。

### D5 跳转接口（已完成）

**已实现**：
- `core` 新增 `LinkState`（跳转视角状态推导，含 `Instant` 语义过期判定）、
  `ShortCodeFormat`（路径短码字符/长度/尾斜杠规则）；
- `redirect` 新增 `RedirectController`（`GET /{code}` + `/{code}/`）、`RedirectService`、
  `LinkValidator.validateForRedirect`；
- 单测新增：`LinkStateTest`(7)、`ShortCodeFormatTest`(3)、`RedirectServiceTest`(12)、
  `RedirectControllerTest`(7)、`LinkValidatorTest` 跳转用例(7)。

**D5 的 5 个决策（全部采纳推荐值）**：
1. **不带缓存** —— 每次直查 MySQL，这是 PRD A-3 要求的性能基线分母，不是「漏做」。
2. **`LinkState` 放 core** —— 查询接口（第 4 周）必须给出完全一致的状态码，
   规则只有一份才可能一致（API.md §6.3）。
3. **长度不符按 404、非法字符与尾斜杠按 400**（API.md §4.1），且**字符检查先于长度检查**。
4. **不做限流**（第 3 周）。
5. **两个 Controller 独立**（`LinkController` / `RedirectController`）。

**⚠️ 缓存接口刻意不在 D5 定义**（DR-036）：留到第 2 周与 `RedisRedirectCache` 一起写。
D5 的 `RedirectService` 直接依赖 `LinkRepository`，第 2 周改这一处依赖即可（局部改动，非架构改动）。
理由：为猜不准的需求提前建抽象往往要返工——KISS，现在只需要「查库 → 跳转」。

### 构建与测试总数（实测，D6 缺陷修复后）
`.\mvnw.cmd clean install` → **BUILD SUCCESS**，6/6 模块，
**302 个测试 0 失败**（Core 124 + Redirect 150 + App 28）—— D6 收尾时的快照，第 2 周见下文 343。
⚠️ App 的 28 个中有 12 个是 **真实 MySQL 集成测试**，需带
`RUN_MYBATIS_IT=true` 与 `TEST_MYSQL_PASSWORD` 才会执行（默认跳过）。

### D6：应用容器化 + 两个阻塞级缺陷修复 + 性能基线

**详见 `docs/PERFORMANCE.md`（第 1 周基线报告）。**

**容器化**（D6 决策 Q1）：`Dockerfile` 多阶段 + compose `app` 服务，
`JAVA_OPTS` 固定堆（`-Xms1g -Xmx1g -XX:+UseG1GC`）。

**修复的两个阻塞级缺陷**（均由 D6 造数暴露，D4/D5 低并发验证从未触发）：

| DR | 缺陷 | 症状 | 根因 |
|---|---|---|---|
| **DR-040** | `short_code` 排序规则 `utf8mb4_0900_ai_ci`（大小写不敏感） | 发号约 **36%** 概率返回 503 | V1 建表用了库默认排序规则；Base62 同时用大小写字母，`0Q1A` 与 `0Q1a` 被判同值。实测 66 个码出现 24 组冲突 |
| **DR-042** | `IdSegmentMapper.advanceAndGetMaxId` 声明返回 `max_id` | 降级路径抛「序号不能为负: -998」，**409 个请求返回 500** | MyBatis 的 `<update>` **返回受影响行数**（恒为 1），不是 `max_id`。`newLimit=1` → `segmentStart=1-1000+1=-998` |

⚠️ **DR-042 潜伏的教训**（本项目最值得记的一条）：两重遮蔽叠加——
① `SegmentShortCodeGeneratorTest` 把 `IdSegmentAllocator` mock 掉了，8 个单元测试全绿；
② 该路径只在 Redis 不可用的**降级分支**执行，而 D4/D5 都是低并发，Redis 从未失败。
**「测试全绿」与「路径从未被执行」是同时成立的。**

**DR-043**：号段发号失败现在映射 **503**（`LINKFORGE-503-01`）而非漏到兜底 handler 报 500。
500 与 503 语义不同：500 = 服务有 bug 需人介入，503 = 依赖暂不可用可重试。

**DR-041**：`referer_domain` 保持 `utf8mb4_0900_ai_ci`。
我曾按「唯一键从严」提出改敏感，**经论证推翻**——
DNS 规范本身大小写不敏感，统计表语义是聚合，
ci 让等价域名**合并**（正确归一），敏感会让它们**分裂**（错误拆分）。

### D6 实测结果

| 验收项 | 结果 |
|---|---|
| 并发创建 10000 短码 | ✅ **10000 个 201**，0 个 5xx/503，耗时 33s（303 req/s） |
| 短码唯一性 | ✅ 库内 `BINARY` 口径重复组数 **0** |
| 预热 10s | ✅ 非 302 响应 **0** |
| 正式压测 3×30s | ✅ QPS **13426**（中位数），P50 **6.59ms**，P99 **36.32ms** |
| 压测窗口应用错误 | ✅ **0** |
| 数据清理 | ✅ `DELETE WHERE id > before_id`，保留 D5 手工记录（9 行） |
| 构建测试 | ✅ **302 个测试 0 失败** |

**⚠️ 基线解读（新增 §5）**：13426 QPS 是「1 万条数据 + 全内存 + 常数级索引查找」下的
**理想上界**，不是无缓存的真实生产水平。整表约 20 MB，InnoDB 全内存命中、零磁盘 IO，
`EXPLAIN type=const`。**不要用它做容量规划**，详见 `docs/PERFORMANCE.md` §5。

**✅ 遗留缺陷已修（DR-044）**：`GET /`（根路径）曾返回 **500** 而非 404
（`NoResourceFoundException` 落到兜底 handler）。已补
`@ExceptionHandler(NoResourceFoundException.class)` → 404 + `LINKFORGE-404-01`，
`NoResourceFoundHandlerTest` 4 个测试覆盖；实测 `GET /`、`GET /nope`、
`GET /api/v1/unknown` 均返回 404，跳转路径 302 未回归。

**核实结论**：压测前那 122042 条 `NoResourceFoundException` **全部落在 22:13~22:14，
压测窗口（22:16:23/ 22:16:56 / 22:17:29）内0 条**，基线数据未受污染，无需重跑。
起因是 wrk 脚本的一版把路径写成了根路径 `/`（`No static resource .`），
并非此前推测的「`-s /dev/null` 探针」——**此前归因错误，已纠正**。

### D6 契约修正：成功响应套 `data` 外壳（DR-039）

**偏差**：实现返回扁平 `{"shortCode":...}`，而 API.md §3.3 / §5.3 定义的是
`{"data": {...}, "timestamp": "..."}`。**以 API.md 为准，改代码不改文档。**

**核对结论（三处）**：
| 项 | API.md 定义 | 修正前实现 | 修正后 |
|---|---|---|---|
| 成功响应 §3.3/§5.3 | `{"data":{...},"timestamp":...}` | ❌ 扁平，无 `data`、无 `timestamp` | ✅ 一致 |
| 失败响应 §3.3 | `{"code","message","timestamp","details"?}` | ✅ 已一致 | ✅ **不动** |

**改动**：
- 新增 `redirect/api/dto/ApiResponse<T>`（协议外壳，与业务 DTO 分离）；
- `LinkController.create` 返回 `ResponseEntity<ApiResponse<CreateLinkResponse>>`，
  构造签名增加 `ZoneId`（外壳的 `timestamp` 需要展示时区）；
- `LinkControllerTest` jsonPath 从 `$.xxx` 改为 `$.data.xxx`，
  **新增** `topLevelHasOnlyDataAndTimestamp`（断言旧扁平字段不再出现于顶层）。

**实测（容器化，DR-039 生效）**：
```
POST /api/v1/links → 201
{"data":{"shortCode":"0000Q10","shortUrl":"http://localhost:8080/0000Q10",
  "targetUrl":"https://example.com/envelope/verify","custom":false,
  "expireAt":null,"createdAt":"2026-10-15T20:50:06+08:00"},
 "timestamp":"2026-10-15T20:50:06.558834417+08:00"}

GET  /zzzzzzz        → 404 {"code":"LINKFORGE-404-01","message":"短码不存在: zzzzzzz","timestamp":...}
POST /api/v1/links {} → 400 {"code":"LINKFORGE-400-01","message":"请求参数校验失败",
                            "timestamp":...,"details":{"targetUrl":"targetUrl 不能为空"}}
```
失败体确认**不套 `data`**，且 `details` 在参数校验类错误中如期出现（API.md §3.3）。

### D4 启动验证结果（✅ 全部通过，D4 正式完成）

| 验收项 | 结果 |
|---|---|
| 应用启动 | ✅ Tomcat 监听 **8080**，`Started LinkForgeApplication in 3.804 seconds` |
| 启动日志 | ✅ **无 ERROR** |
| 号段发号初始化 | ✅ `biz_tag=short_code max_id=999999999 step=1000` |
| Redis 发号初始化 | ✅ `shortlink:id:seq` 初始化为 **99999** |
| Flyway | ✅ 2 条 migration 已校验，`Schema up to date` |
| `clean install` | ✅ **BUILD SUCCESS**，6/6 模块 |
| 集成测试 | ✅ `RUN_MYBATIS_IT=true` 下 **13/13 通过，0 skipped**（确认真的执行） |
| API §12 五条 curl | ✅ 5/5 符合契约（详见下表） |

**5 条 curl 实测**（`.env` 当前时刻 `13:37:31+08:00`）：

| # | 用例 | 期望 | 实际 |
|---|---|---|---|
| 1 | 创建（自动码） | 201 + 7 位 | **201** `shortCode=0000Q0w`（7 位） |
| 2 | 自定义短码 | 201 | **201** `promo27` `custom:true` |
| 3 | 重复短码 | 409 + `LINKFORGE-409-01` | **409** `LINKFORGE-409-01` |
| 4 | 协议非法 | 400 + `LINKFORGE-400-02` | **400** `LINKFORGE-400-02` |
| 5 | 保留字 `API` | 400 + `LINKFORGE-400-04` | **400** `LINKFORGE-400-04` |

**createdAt 时区验证（DR-032 的核心验收点）**：
```
真实当前时刻 : 2026-10-13T13:37:31+08:00   (UTC 05:37:31Z)
响应 createdAt: 2026-10-13T13:37:32+08:00   (UTC 05:37:32Z)   ✅ 秒级吻合
```
偏移为 `+08:00`（非 `Z`），且**与真实时刻一致**——8 小时双重换算已消除。
`expireAt` 写入 `2026-12-31T23:59:59+08:00` 也原样返回，往返无损。

### D4 启动验证抓到的两个真实缺陷（均由实际运行发现，非推理）

⚠️ **这两个缺陷在 224 个单元测试全绿的情况下被放过去了**，暴露的是测试结构本身的缺口。

#### 缺陷 1：MyBatis `boolean` 别名解析到包装类 → 创建接口 500（DR-029）

- **现象**：`POST /api/v1/links` 返回 500 `LINKFORGE-500-01`；数据库**其实已写入成功**，
  报错发生在写完回查阶段。
- **根因链路**：
  ```
  LinkMapper.xml  <arg column="is_custom" javaType="boolean"/>
    → TypeAliasRegistry 把别名 "boolean" 解析为 Boolean.class（包装类）
    → <constructor> 按该类型反射查找 Link 构造器
       要找：Link(..., Boolean, ...)
       实际：Link(..., boolean, ...)     ← 签名不匹配
    → NoSuchMethodException → 500
  ```
  （注：`_boolean` 才解析为基本类型 `boolean`。）
- **修法**：**只改 XML 别名为 `_boolean`**，`Link` 完全不动（DB 侧 `is_custom` 是 `NOT NULL`，用基本类型语义正确）。
- **为什么 224 个测试没抓到**：单测全部 mock 仓储，`LinkControllerTest` 是 `@WebMvcTest` 切片，
  **都不经过真实 MyBatis 反射构造器**——结构上测不到。
- **附带修复**：第 3 条「重复短码」此前被 500 掩盖，修好后才正确返回 409。

#### 缺陷 2：数据库会话时区导致 8 小时双重换算（DR-032）

- **现象**：修复缺陷 1 后，201 成功但 `createdAt` 返回 `21:15:33+08:00`，
  而真实时刻是 `13:15:33+08:00`——**晚 8 小时**。
- **根因**：连接串 `serverTimezone=Asia/Shanghai` 让 MySQL 会话按本地时区工作，
  于是**数据库自己生成**的 `created_at` 是本地墙上时钟（库中实测 `2026-10-13 13:15:33`），
  应用读回后当 UTC 处理，`TimeConverter` 又加一次 `+08:00` → 多出 8 小时。
- **关键分歧点**：`created_at`/`updated_at` 由**数据库生成**，`expire_at` 由**应用写入**。
  DR-027 假定的「持久层统一是 UTC」对 `expire_at` 成立，对数据库生成的列**不成立**。
  实测印证：旧配置下 `expire_at` 用例**碰巧通过**，只有 `created_at` 失败——
  **「一个通过一个失败」正是双重换算最易被误判为偶发的特征。**
- **修法**：移除 `serverTimezone`（Connector/J 8.0.23+ 已废弃），改用
  `connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true`，让驱动建连时执行
  `SET time_zone='+00:00'`。**不需要数据迁移**（`TIMESTAMP` 内部存 UTC epoch，改会话时区只改显示）。
- **影响面**：`serverTimezone` 在仓库中共 15 处引用（4 处连接串 + 11 处文档/注释），已全部更新。

#### 由此补上的测试能力（DR-031）

新增真实 MyBatis 集成测试 `LinkRepositoryMyBatisTest`（**13 个用例**），
复用本地 MySQL 容器的**独立库 `linkforge_test`**，覆盖：
insert 回读（缺陷 1 的回归测试）、`createdAt` 时区、`expireAt` Instant 不变、
`status` TINYINT 往返、`markDeleted` 幂等、**两条记录字段不串**（防 resultMap 错位）、
`is_custom` NOT NULL 约束、唯一索引兜底、布隆重建扫描与游标。

- **跳过机制**：用 `@EnabledIfEnvironmentVariable(named="RUN_MYBATIS_IT", matches="true")`，
  默认 `clean install` 跳过（干净环境无 MySQL 也不失败），显式设变量才执行。
- ⚠️ **类名必须是 `*Test` 而非 `*IT`**：surefire 默认不拾取 `*IT.java`（那是 failsafe 的职责），
  命名成 `IT` 会导致该测试**永远不执行**，DR-031 就成了空承诺。
- ⚠️ 测试连接串**必须与生产逐字符一致**，否则时区断言失去意义。
- ⚠️ `application-test.yml` 必须显式给 `SHORTLINK_BASE_URL`：`RedirectConfig` 是
  `@Value(...)` 无默认值（DR-014/DR-026 的正确设计），选择**如实复现**该校验而非在测试里放宽。

#### 本轮新增决策

| 编号 | 主题 |
|---|---|
| **DR-029** | MyBatis `boolean` 别名解析到包装类，基本类型必须用 `_boolean` |
| **DR-030** | `server.port` 必须显式声明（`.env` 的 `APP_PORT` 曾是「配了但不生效」的死变量）；附 `SERVER__PORT` 等环境变量优先级陷阱 |
| **DR-031** | 仓储层集成测试：复用真实 MySQL + 独立 schema + `@EnabledIfEnvironmentVariable` 跳过 |
| **DR-032** | 数据库会话时区强制为 UTC，消除 8 小时双重换算 |
| **DR-033** | 含特殊字符的配置文件改写**禁止用 sed**（D4 实测踩坑：`&` 被当通配符，静默损坏 `.env`） |
| **DR-034** | 数据库权限边界：本地可自建测试库，生产应用账户只给 DML + Flyway 独立账户 |
| **DR-035** | `.env` 中的 root 凭据是待收紧项（v1 保留不动，v2 或第 5 周处理）；**已补记当前状态表**：本地放宽 / 生产只 DML / D5 后评估是否收紧 |
| **DR-036** | D5 不引入跳转缓存，且**不预建缓存接口**（第 2 周与 `RedisRedirectCache` 一起写） |
| **DR-037** | 路径短码「字符/尾斜杠 → 400、长度不符 → 404」及**字符检查先于长度检查**的判定顺序 |
| **DR-038** | GC 日志统一用 `-Xlog:gc*`；`-XX:+PrintGCDetails`（JDK 17 已废弃）仅作历史记录，**保留是为了口径不漂移** |
| **DR-039** | 成功响应统一套 `{"data":{...},"timestamp":...}` 外壳（API.md §3.3），**失败响应不套** |
| **DR-040** | `short_code` 排序规则改 `ascii_bin`（原 ci 让Base62 约 36% 概率撞码，Flyway V3） |
| **DR-041** | `referer_domain` **保持** `utf8mb4_0900_ai_ci`——「唯一键从严」的直觉在此是错的，记录以防改回去 |
| **DR-042** | 号段取段拆成 `advanceMaxId`(返回受影响行数) + `selectMaxId`；**MyBatis `<update>` 不返回 `max_id`** |
| **DR-043** | 号段发号失败映射 **503** 而非漏到兜底 handler 报 500 |
| **DR-044** | 未匹配路由映射 **404**（`NoResourceFoundException`）而非漏到兜底 handler 报 500 |
| **DR-045** | 跳转两级缓存：Caffeine → Redis → MySQL；key `shortlink:code:{shortCode}`；Redis 故障降级为回源 |
| **DR-046** | Caffeine TTL 5 分钟**由业务不一致窗口决定，不由 Redis TTL 倒推**；maximumSize 10000 是数据量依赖 |
| **DR-047** | 本地缓存只缓存 Hit——用泛型在**编译期**挡住 NegativeHit，防止刚创建的短码持续 404 达5 分钟 |
| **DR-048** | 回源异常绝不进缓存；缓存命中也必须走状态判定；evict 顺序先本地后 Redis |
| **DR-049** | 异常日志必须去重或限流，否则会淹没真实故障信号（第 2 周实证：610 万行 ERROR → 30 条 WARN） |

### 数据库权限边界（详见 DR-034）

| 环境 | 应用账户权限 | DDL 执行者 |
|---|---|---|
| **本地开发** | `linkforge`：业务库 DML + `CREATE`/`DROP` 全局 + `linkforge_test.*` 的 ALL | 应用同账户 |
| **生产 / CI** | 应用账户**只有 DML**（`SELECT`/`INSERT`/`UPDATE`/`DELETE`） | **Flyway 独立账户** |

**为什么生产不给应用 DDL**：
① 最小必要原则——应用运行期不需要 DDL，给了就等于把「破坏表结构」的能力一并交给潜在的注入/RCE 攻击面；
② DML 与 DDL 分离才能回答「谁改了表结构」；
③ Flyway 独立账户让「谁能改 schema」变成可配置的部署参数，而非「谁跑应用手里有什么权限」。

**本地补权限命令**（⚠️ **仅本地手工执行一次**，禁止写进 Flyway 脚本或生产文档）：
```sql
GRANT CREATE, DROP ON *.* TO 'linkforge'@'%';
GRANT ALL PRIVILEGES ON linkforge_test.* TO 'linkforge'@'%';
FLUSH PRIVILEGES;
```

⚠️ **不能进迁移脚本的四个理由**：
① `GRANT` 是管理语句，Flyway 是 schema 版本化机制，职责不同；
② 迁移会在评审者干净环境执行，等于把本地便利强加给所有人；
③ 需要 `GRANT OPTION`/管理员权限，迁移账户往往没有，写进去会直接失败；
④ 权限是**环境配置**不是 **schema 结构**，schema 会变，权限不该跟着版本走。

**本轮实际执行与决策意图的偏差（留档）**：先用 `linkforge` 试 `CREATE DATABASE` 失败
（`ERROR 1044 Access denied`），随后改用 `.env` 里的 root 凭据建库并 GRANT。
这偏离了「本地用 `linkforge` 自建」的意图；保留偏差的原因是
`.env` 里放着 root 口令本身就是待收紧点（DR-035 已记入清单），
若日后收紧，应改用上面的 `GRANT CREATE, DROP` 让 `linkforge` 自建库。

---

## 第 1 周 — D1~D4 代码完成记录（历史）

### D4 代码完成（启动验证前的状态）
`.\mvnw.cmd clean install` → **BUILD SUCCESS**，6/6 模块，**224 个测试 0 失败**
（Core 114 + Redirect 107 + App 3），耗时 30.9s。

| 内容 | 位置 |
|---|---|
| `ErrorCode`（11 个码，含 code + `int httpStatus`，**零 web 依赖**） | `core/error/` |
| `LinkForgeException` | `core/error/` |
| `TimeConverter`（唯一时区转换入口，DR-027） | `core/time/` |
| `ShortLinkBaseUrl`（校验 + 归一化 + 拼接，DR-026） | `core/domain/` |
| `ReservedCodes`（保留字黑名单，**大小写不敏感**，DR-025） | `core/domain/` |
| `LinkRepositoryImpl`（**补齐 D2 遗留缺口**） | `core/infrastructure/persistence/` |
| `CreateLinkRequest` / `CreateLinkResponse` | `redirect/api/dto/` |
| `LinkController`（`POST /api/v1/links`，**201，无 Location 头**，DR-028） | `redirect/api/` |
| `LinkValidator`（协议白名单 / 保留字 / expireAt 将来性） | `redirect/application/` |
| `LinkCreationService`（校验 → 发号 → 落库 → 冲突重试 → 拼 shortUrl） | `redirect/application/` |
| `GlobalExceptionHandler`（错误码 → HTTP 映射） | `redirect/error/` |
| `RedirectConfig`（配置值对象 + `Clock` + 时区，**启动期校验配置**） | `redirect/config/` |
| 测试：`TimeConverterTest` / `ShortLinkBaseUrlTest`（core）+ `LinkValidatorTest` / `LinkCreationServiceTest` / `LinkControllerTest`（redirect） | 各模块 `src/test/` |

### D4 实现中发现并修掉的 4 个真实问题（均由实际运行发现）
1. **⚠️ 校验顺序导致错误码与事实不符**（真 bug）：黑名单含 `favicon.ico`（含点），**不匹配** `^[a-zA-Z0-9]{4,8}$`。
   原实现在一个方法里「先格式后保留字」，于是 `API`、`STATIC` 这类**保留字**因长度不足先被判为格式错，
   返回 `400-03` 而非 `400-04` —— **掩盖了「调用方在猜系统路径」这个更重要的信号**。
   修法：拆成 `isReserved` / `validateCustomCodeFormat` / `validateCustomCodeNotReserved` 三个独立方法，
   `validateCustomCode` 内部改为**保留字优先**。
2. **`//example.com` 被归错错误码**：它让 `URI` 解析直接抛 `URISyntaxException`，落到 `400-01`（格式错误），
   而事实是**协议缺失**（安全语义，应为 `400-02`）。
   修法：**先做 `http://`/`https://` 前缀字符串判断，再做 URI 解析**——从安全视角「没有白名单 scheme」
   与「scheme 不在白名单」是同一类问题。
3. **我的测试数据错了 3 处**（不是实现问题）：`promo2026` 是 **9 位**，超出 4~8 位规则（应改 `promo26`）；
   `expireAt = 10:30+08:00` 恰好**等于**固定时钟的 `02:30Z`，属「不是将来」的边界；
   `@WebMvcTest` 找不到 `@SpringBootConfiguration`（启动类在 app、测试在 redirect，包路径不同源），
   需显式 `@ContextConfiguration`。
4. **缺两个依赖**：`spring-boot-starter-validation`（`@NotBlank/@Valid` 不再随 web 传递引入）、
   `spring-boot-starter-actuator`（Micrometer `Counter`，第 5 周 Prometheus 本来也要用）。

### D4 契约层修订（写代码前完成）
见下方「D4 契约层修订」表（错误码格式 `LINKFORGE-<状态码>-<序号>`、400 段拆 5 个、保留字大小写不敏感、BASE_URL 校验、时区链、201 不加 Location）。

### D4 实现强制约束（DR-027 落地，写代码时不得偏离）
| 内容 | 结论 |
|---|---|
| **错误码格式** | 改为 **`LINKFORGE-<HTTP状态码>-<两位序号>`**（DR-024），覆盖 400~599；原连写形式有两个问题：无法一眼看出 HTTP 状态、`4xxx` 段容纳不下 5xx（格式与实例自相矛盾） |
| **400 段拆分** | 由 3 个扩为 **5 个**（DR-024）：`400-01` 通用参数 / **`400-02` 协议不允许**（安全边界，独立成码以便从错误码分布识别开放重定向探测） / `400-03` 短码格式 / `400-04` 保留字 / `400-05` `expireAt` 非将来。**判据：修复动作不同就拆** |
| **保留字大小写** | **不敏感**（DR-025）。短码值大小写敏感，但保留字保护的是**路径**：HTTP 路径大小写归一化行为在不同实现/容器/代理下不一致，只拦小写会让 `Api` 在某些部署下撞 `/api` |
| **`SHORTLINK_BASE_URL`** | 空串/全空白/纯斜杠/无 scheme/非 http(s) → **启动失败**；末尾斜杠归一化，拼接不得出现 `//`（DR-026） |
| **时间时区链** | 传输 `OffsetDateTime` → 持久层 `LocalDateTime(UTC)` → 响应按可配置时区渲染；**D5 过期判定必须统一到 `Instant` 比较**（DR-027） |
| **201 不加 `Location`** | DR-028：指向 `shortUrl` 语义混乱、指向 `/api/v1/links/{code}` 该接口 D5 才有（指向空处）；body 已有 `shortUrl`。D5 完成后可再评估 |

### D4 实现强制约束（DR-027 落地，写代码时不得偏离）
- `Link` 实体的时间字段用 **`LocalDateTime`（UTC）**。
- `LinkRepositoryImpl` 的**入参与返回值都是 `LocalDateTime`（UTC）**，**不做任何转换**。
- 转换**只发生在 Service 边界**：请求进来 `TimeConverter.toUtc`，响应出去 `TimeConverter.toOffset`。
- `LinkCreationService` **构造器注入 `TimeConverter`**。
- **仓储只认 UTC 的 `LocalDateTime`，Service 负责边界转换** —— 这条是硬约束，不允许 Mapper / DTO 各写一遍换算。

### D4 单测补充要求
- **`TimeConverter` 边界**：`null` 输入 → 返回 `null` **不抛异常**；`+05:30` 偏移换算正确；**同一瞬间不同偏移**（`2026-01-15T10:30:00+08:00` 与 `2026-01-15T02:30:00Z`）转 UTC 后**相等**。
- **自定义短码并发冲突**：用 mock 让第一次 insert 成功、第二次抛 `DuplicateKeyException`；
  断言**一个返回 201、另一个返回 409**，**不是两个都 500**。

### D3 启动验证结果（✅ 全部通过，用户实测）
| 验收项 | 结果 |
|---|---|
| 应用启动 | ✅ 成功，Tomcat 监听 **8080** |
| 号段发号初始化 | ✅ `biz_tag=short_code max_id=999999999 step=1000，下一段从 1000000000 起` |
| Redis 发号初始化 | ✅ `shortlink:id:seq` 初始化为 **99999**（首个发出的号为 100000） |
| Flyway | ✅ `flyway_schema_history` 中 **V1、V2 两条 success=1** |
| `id_segment` 表 | ✅ `short_code / 999999999 / 1000` |
| Redis key | ✅ `shortlink:id:seq` = **99999** |
| 启动日志 | ✅ **无 WARN** |

### D3 收尾补充（本轮）
- ✅ **Redis 连接配置化**：`application.yml` 加 `spring.data.redis.host=${REDIS_HOST:localhost}`、
  `port=${REDIS_PORT:6379}`、`timeout: 50ms`（对齐 ARCHITECTURE §11.3 的 Redis 超时基线）。
- ✅ `.env` / `.env.example` 补 `REDIS_HOST=localhost`；**第 5 周 compose 内需设为 `redis`**
  （`localhost` 在容器内指向应用容器自身）。
- ✅ **DR-023** 记录 `allowPublicKeyRetrieval`（背景、决策、安全代价、生产要求、4 处一致性清单）。

### 构建与测试（实测）
`.\mvnw.cmd clean install` → **BUILD SUCCESS**，6/6 模块，**102 个测试 0 失败**
（Core 75 + Redirect 24 + App 3）。

### D3 修复的缺陷汇总（全部由实际运行发现）
1. `Base62Test` 第二条断言错：`"0000000".replaceFirst("^0+","")` 得 `""` 而非 `"0"`（实现本身正确）。
2. 号段每次取段浪费一个号（`current` 与 `limit` 初值都为首号 − 1）。实测 8000 → 7939。
3. **并发丢号**：快路径 `getAndIncrement` 与取段时的 `current` 重置冲突，陈旧值覆盖计数器。实测 8000 → 7947。
   最终改为 **CAS 抢占 + 取段持锁 + 双检**，实测 **8000/8000 唯一**。
4. `Long.MIN_VALUE` 哨兵配合「加一后比较」会漏进业务逻辑（报「序号不能为负」）。
5. 取段改为惰性（构造时取段会让「只为装配的实例」也消耗一段号，并污染单测）。
6. MyBatis Mapper 未被扫描（`scanBasePackages` 管不到 Mapper）→ 显式 `@MapperScan`。
7. `@EventListener` 方法写了两个参数 → 改为无参 + 字段持有生成器。
8. MySQL 8.4 连接缺 `allowPublicKeyRetrieval=true` → 见 DR-023。

### D3 关键决策（DR-022）

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
SPRING_DATASOURCE_URL=jdbc:mysql://localhost:3307/linkforge?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&characterEncoding=utf8
```
- **`allowPublicKeyRetrieval=true` 是必需的**：MySQL 8.4 默认认证插件是 `caching_sha2_password`，
  JDBC 驱动首次连接需要向服务器索取 RSA 公钥来加密密码，默认被禁止，否则启动报
  `Public Key Retrieval is not allowed`（SQL State 08001）。
- ⚠️ **时区两个参数也不能少（DR-032）**：`connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true`
  让驱动执行 `SET time_zone='+00:00'`，数据库会话时区为 UTC。
  **不要用 `serverTimezone=Asia/Shanghai`**（Connector/J 8.0.23+ 已废弃该参数）：
  会话时区为本地时，数据库自己生成的 `created_at` 是本地墙上时钟，
  应用按 UTC 解释后会产生**正好 8 小时**的双重换算（D4 启动验证实测到）。
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

---

## D7（第 2 周）：Redis + Caffeine 两级缓存

### 实测结果（与第1 周同口径：同1 万条数据、同 `-t4 -c100`、同 3 次 30s 取中位数）

| 指标 | 第 1 周 | 第 2 周 | 变化 |
|---|---|---|---|
| 跳转 QPS | 13426.86 | **81289.94** | **+505.4%（6.05x）** |
| P50 | 6.59ms | **0.97ms** | **−85.3%** |
| P99 | 36.32ms | **4.58ms** | **−87.4%** |
| 正式窗口命中率 | 0% | **100.00%** | — |
| 非 302 响应 | 0 | **0** | 无回归 |
| 压测窗口 ERROR | 0 | **0** | — |

**判读**（按压测前预注册的规则，见 PERFORMANCE.md §5.2bis）：提升 505% ≥ 20% → **缓存有效**。

⚠️ **6 倍不能按字面理解**：第 1 周的 13426 是在「整表 20MB 全内存 + `EXPLAIN type=const`」
下的理想上界，数据库侧几乎没有优化空间可省。这6 倍恰好印证了瓶颈确实在数据库侧；
生产环境数据库不可全内存时，缓存收益会**更大**（省掉真实磁盘 IO）。

### 关键决策

- **DR-045** 两级结构 + key `shortlink:code:{shortCode}` + 长度前缀序列化 + TTL 抖动 + Redis 故障降级
- **DR-046** Caffeine TTL 5 分钟**由业务不一致窗口决定，不由 Redis TTL 倒推**；
  maximumSize 10000 是**数据量依赖**，生产应按「活跃短码数 × 0.1~0.3」重估
- **DR-047** 本地缓存**只缓存 Hit**，用泛型在编译期挡住 NegativeHit
- **DR-048** 回源异常绝不进缓存；缓存命中也必须走状态判定；evict 顺序先本地后 Redis
- **DR-049** 异常日志必须去重或限流——第 2 周的实证：同量级信息从 610 万行ERROR 降到 30 条 WARN

### 降级路径首次真实触发

压测中约 30 条 WARN：`Redis command timed out`（50ms 超时，DR-011基线）→
缓存自动放弃并回源查库，**无任何请求失败**。这是 DR-045 设计的降级路径首次在生产压测中生效。

### 预热的实测必要性

冷启动预热 10s产生 **32180 次回源**（1 万条数据各回源约 3 次）——这是「第 2 周不加单飞」的代价。
预热后正式窗口 `miss` 增量为 **0**，命中率 **100%**。
⚠️ **没有预热，这 32180 次回源会混进压测窗口污染数据**——预热是必需项，不是可选项。

### 顺带兑现

- **healthcheck 升级为 `/actuator/health/readiness`**（ROADMAP 第 5 周 TODO，第 2 周提前兑现）。
  端点暴露范围最小化为 `metrics,health`，不开 `*`。
- 三个 Micrometer 指标建成：`shortlink.cache.local.hit` / `.redis.hit` / `.miss`，
  压测时从 `/actuator/metrics` 直接读，不从日志数。

### 测试

**343 个测试 0 失败**（Core 124 + Redirect 191 + App 28）。
新增 41 个：RedisRedirectCacheTest（18）、TwoLevelRedirectCacheTest（10）、
RedirectServiceCacheTest（10）、NoResourceFoundHandlerTest 已在 D6 收尾计入。
