# LinkForge

> 分布式短链平台 —— Java 后端求职项目。
> 目标：把「长链 → 短码 → 302 跳转」这条读多写少链路做到**可量化**的高性能，并且每个优化都有前后对比数据。

---

## 当前进度

**第 0 周：环境收口 —— 已完成。**

| 项 | 状态 |
|---|---|
| JDK | OpenJDK **17.0.0.1** |
| 构建 | Maven Wrapper（**Maven 3.9.12**），本地仓库在项目内 `.mvn-repo` |
| MySQL | `mysql:8.4`（容器内 3306，**宿主机映射 3307**） |
| Redis | `redis:7.4`（宿主机 6379，已启用 RDB） |

**前置需求**：Docker Desktop 已启动；`JAVA_HOME` 指向 **JDK 17**。

---

## 快速开始

```powershell
# 1. 准备环境变量（.env 只放本地占位值，已被 .gitignore 忽略）
Copy-Item .env.example .env

# 2. 启动 MySQL 与 Redis
docker compose up -d
docker compose ps          # 期望 linkforge-mysql、linkforge-redis 均 healthy

# 3. 验证 Redis
docker compose exec redis redis-cli ping        # 期望 PONG

# 4. 验证构建工具
.\mvnw.cmd -v              # 期望 Apache Maven 3.9.12（JDK 17）

# 5. 启动应用（从 .env 注入环境变量后运行）
.\scripts\run-local.ps1
```

> **启动为什么用脚本而不是直接 `mvnw spring-boot:run`**：Spring Boot 不会自动读取 `.env` 文件，
> 而数据源只认环境变量 `SPRING_DATASOURCE_URL`（无默认值，缺失即启动失败）。
> `scripts\run-local.ps1` 会解析 `.env`（跳过注释与空行、去掉值两侧引号、正确处理 URL 里的 `&`），
> 注入**当前进程**环境变量后启动应用，脚本内不含任何密码。
> 另可用 `-PrintEnv` 只打印解析结果（密码脱敏）用于排查。

> ⚠️ **MySQL 请连 3307**：宿主机 3306 已被本机 MySQL80 服务占用，容器端口通过 `.env` 的
> `MYSQL_PORT=3307` 避让；容器内部端口始终是 3306，compose 网络内应用直连 `mysql:3306`。
> 详见 [docs/DECISIONS.md](docs/DECISIONS.md) DR-017。
>
> 应用的数据源**只认一个环境变量** `SPRING_DATASOURCE_URL`（不分 profile）：开发时 `.env` 里指向
> `jdbc:mysql://localhost:3307/linkforge?...`，应用进容器后由 compose 覆盖为
> `jdbc:mysql://mysql:3306/linkforge?...`。另外 `MYSQL_USER` / `MYSQL_PASSWORD` 也由环境变量注入，
> 代码里不硬编码任何账号密码。

---

## 运行 Redis 演示

> ⚠️ **这是第 0 周的环境验证 Demo，不是项目代码。**
> 它的唯一用途是证明「Java 能连上 Redis 且四类基本命令行为符合预期」，**自第 1 周起不再维护**
> （不随项目演进、不加新特性、依赖或接口变化时不保证可用）。项目正式代码从 `linkforge-core` 等模块开始。

第 0 周的 Redis 入门演示：**SET / GET / EXPIRE / INCR** 四件事，纯 Java 单文件，不引入 Spring Boot、不引入任何第三方依赖。

```powershell
# 在项目根目录执行（Windows 上加 -Dstdout.encoding=UTF-8 以免中文输出乱码）
java "-Dstdout.encoding=UTF-8" scripts/redis-demo/RedisDemo.java

# 指定 host / port
java "-Dstdout.encoding=UTF-8" scripts/redis-demo/RedisDemo.java localhost 6379

# 保留演示 key，便于手动观察
java "-Dstdout.encoding=UTF-8" scripts/redis-demo/RedisDemo.java localhost 6379 --keep
```

脚本自带断言：GET 读回值、TTL 落在预期区间、到期后返回 nil、INCR 两次得 2，全部通过时退出码为 `0`。

只想用 `redis-cli` 看看等价效果：

```powershell
docker compose exec redis redis-cli SET linkforge:demo:manual hello
docker compose exec redis redis-cli GET linkforge:demo:manual
docker compose exec redis redis-cli EXPIRE linkforge:demo:manual 10
docker compose exec redis redis-cli TTL linkforge:demo:manual
docker compose exec redis redis-cli INCR linkforge:demo:counter
docker compose exec redis redis-cli DEL linkforge:demo:manual
```

---

## 项目结构

Maven 多模块（**5 个模块**），依赖规则见 [docs/DECISIONS.md](docs/DECISIONS.md) DR-018：

```
linkforge-parent           父 POM：版本管理、编译配置
├── linkforge-core         领域模型、仓储接口、共享服务抽象（保持薄）
├── linkforge-redirect     跳转链路：控制器、缓存、布隆过滤器、限流
├── linkforge-stats        统计：计数、按天聚合、定时落库
├── linkforge-admin        后台：删除短链、统计查询、Token 鉴权
└── linkforge-app          启动 + 装配（唯一产出可执行 jar）
```

依赖方向：

```
app      → core + redirect + stats + admin
redirect → core + stats          admin → core + stats
stats    → core
redirect → admin   ❌ 禁止        admin → redirect ❌ 禁止
```

隔离由编译器强制，并由 `linkforge-app` 中的 `ModuleBoundaryArchTest`（ArchUnit）在构建时二次校验。

构建：

```powershell
.\mvnw.cmd clean install        # 首次会下载依赖树，耗时较长属正常
.\mvnw.cmd clean verify         # 含架构约束测试
```

---

## 文档

设计与决策文档在 [`docs/`](docs/)：

| 文档 | 内容 |
|---|---|
| [PROJECT_BRIEF.md](docs/PROJECT_BRIEF.md) | 项目目标与约束（唯一来源） |
| [PRD.md](docs/PRD.md) | v1 范围、用户故事、验收标准 |
| [ROADMAP.md](docs/ROADMAP.md) | 6 周排期、里程碑、环境快照 |
| [ARCHITECTURE.md](docs/ARCHITECTURE.md) | 模块边界、缓存分层、降级矩阵 |
| [DATABASE.md](docs/DATABASE.md) | 表设计、索引意图、保留策略 |
| [API.md](docs/API.md) | 接口契约、错误码、版本策略 |
| [DECISIONS.md](docs/DECISIONS.md) | 决策记录 DR-001 起 |
| [PROGRESS.md](docs/PROGRESS.md) | 进度台账 |
