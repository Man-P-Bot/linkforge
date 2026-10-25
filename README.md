# LinkForge

> 分布式短链平台 —— Java 后端求职项目。
> 目标：把「长链 → 短码 → 302 跳转」这条读多写少链路做到**可量化**的高性能，并且每个优化都有前后对比数据。

---

## 当前进度

**第 5 周：监控、部署与终极压测 —— 进行中（阶段 1~3 已完成）。**

| 项 | 状态 |
|---|---|
| JDK | OpenJDK **17.0.0.1** |
| 构建 | Maven Wrapper（**Maven 3.9.12**），本地仓库在项目内 `.mvn-repo` |
| MySQL | `mysql:8.4`（容器内 3306，**宿主机映射 3307**） |
| Redis | `redis:7.4`（宿主机 6379，已启用 RDB） |
| 监控 | Prometheus `v3.5.0` + Grafana `12.1.0`（全声明式 provisioning） |
| 部署 | Docker Compose **一键起全套 5 个容器** |
| 测试 | **611 全绿**（含 58 个真实 MySQL 容器测试 + 20 个真实 Redis 集成测试） |

**前置需求**：Docker Desktop 已启动；`JAVA_HOME` 指向 **JDK 17**。

---

## 快速开始

### 方式一：Docker Compose 一键起全套（推荐，含监控栈）

```bash
# 1. 初始化本地环境（生成 .env + Docker Secrets，并校验凭据一致性）
bash scripts/setup-local.sh

# 2. 启动全套 5 个容器
docker compose up -d
docker compose ps        # 期望 5 个服务全部 healthy

# 3. 验证监控链路
curl -u "linkforge:$(cat docker/secrets/prometheus_basic_auth.txt)" \
     http://localhost:9090/api/v1/targets           # 期望 health="up"
curl -u "admin:$(cat docker/secrets/grafana_admin_password.txt)" \
     http://localhost:3000/api/datasources/1/health  # 期望 status="OK"
```

| 服务 | 地址 | 凭据 |
|---|---|---|
| 应用 | http://localhost:8080 | 无（公开接口） |
| Prometheus | http://localhost:9090 | 用户名 `linkforge`，密码 `cat docker/secrets/prometheus_basic_auth.txt` |
| Grafana | http://localhost:3000 | 用户名 `admin`，密码 `cat docker/secrets/grafana_admin_password.txt` |
| MySQL | localhost:3307 | 见 `.env` |
| Redis | localhost:6379 | 无密码（**已知风险**，见 [docs/PROGRESS.md](docs/PROGRESS.md)） |

> ⚠️ **`setup-local.sh` 不是可选的装饰**。Docker Compose v2 在 Secret 文件**缺失**时
> **不报错**——它会挂一个空目录进去，容器照常启动：
> Grafana 的 admin 密码变空（登不进去）、Prometheus 的明文密码为空（永远 401），
> 而**没有任何错误提示**。`setup-local.sh` 把这个静默故障变成启动前的显式校验。
>
> 📌 **本项目提供一键启动脚本 `scripts/setup-local.sh`，已为你自动规避了 4 个常见的
> Docker 部署静默故障（详见 [docs/PITFALLS.md](docs/PITFALLS.md)）**：
>
> | # | 故障 | 为什么危险 |
> |---|---|---|
> | 1 | Compose 在 Secret 文件**缺失**时**不报错** | 容器全 healthy，但密码是空的，**没有任何提示** |
> | 2 | Grafana 的 `secureJsonData` **不会被 upsert 覆盖** | 改密码后数据源 401、面板全 No data，而 **provisioning 日志一切正常** |
> | 3 | Secret 文件带了注释 → **注释成了密码的一部分** | 用户看到的明文 ≠ 程序实际用的密码 |
> | 4 | Windows 的 CRLF → **密码多一个 `\r`** | `curl` 能通、**浏览器登不进去**，最易误判 |
>
> 这 4 条的共同点是：**静态检查全过 ≠ 功能可用**。
> 所以 `setup-local.sh` 的校验全部是**功能级**的——真的拿凭据请求一次。

### 方式二：本地跑应用（开发调试用）

```powershell
# 1. 准备环境变量（.env 只放本地占位值，已被 .gitignore 忽略）
Copy-Item .env.example .env
Copy-Item docker/secrets/grafana_admin_password.txt.example docker/secrets/grafana_admin_password.txt
Copy-Item docker/secrets/prometheus_basic_auth.txt.example docker/secrets/prometheus_basic_auth.txt

# 2. 启动 MySQL 与 Redis
docker compose up -d mysql redis
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

### 改监控凭据

```bash
# 生成 bcrypt 哈希，并同步「哈希（进 git）」与「明文（gitignore）」两处
bash scripts/gen-prometheus-hash.sh '你的新密码'

# ⚠️ 密码改完必须重建 Prometheus **并重置 Grafana 数据源**
docker compose up -d --force-recreate prometheus
bash scripts/reset-grafana-datasource.sh
```

> ⚠️ **第二步不能省**：Grafana 的 datasource provisioning **不会覆盖已存在数据源的
> `secureJsonData`**（Grafana 刻意设计：避免每次启动都用明文覆盖已加密的密码）。
> 于是 Prometheus 用了新密码、Grafana 数据源里存的还是**旧密码的密文** →
> 数据源 401、面板全 No data，而 provisioning 日志一切正常。
> 详见 [docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md)。

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
| [PERFORMANCE.md](docs/PERFORMANCE.md) | 压测数据、口径声明、资源水位 |
| [TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md) | 排障手册：20 条踩过的坑（现象 → 根因 → 解法） |
| [PITFALLS.md](docs/PITFALLS.md) | **防呆清单**：4 个 Docker 部署静默故障的共性判据 + 自动化防线 + 一分钟自检 |
| [RESUME-DRAFT.md](docs/RESUME-DRAFT.md) | 简历素材草稿（STAR 法则，3 个可追问的技术故事） |
