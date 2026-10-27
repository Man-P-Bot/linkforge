# LinkForge 排障手册（TROUBLESHOOTING）

> **这份文档记录的是「踩过的坑」，不是「应该怎么用」。**
>
> 每一条都对应一次真实的失败与排查。写它的目的有两个：
> 1. 下一个人遇到同样的现象时，**不用重新推导一遍**；
> 2. 避免「换个环境就好了」的假修复 —— 每条都记了**根因**而不只是**现象**。
>
> ⚠️ **第 5 周的 4 个 Docker 部署静默故障（T-021~T-024）已单独整理成
> [防呆清单 PITFALLS.md](PITFALLS.md)** —— 那一份讲的是
> **「怎么让同类错误不再发生」**（共性判据 + 自动化防线 + 一分钟自检），
> 比本文档的「怎么诊断」更适合在部署前阅读。

---

## 目录

- [环境与依赖](#环境与依赖)
  - [T-001 Maven Wrapper 报 "extracted distribution has no bin/mvn"](#t-001-maven-wrapper-报-extracted-distribution-has-no-binmvn)
  - [T-002 Testcontainers 拉取 ryuk 镜像卡死](#t-002-testcontainers-拉取-ryuk-镜像卡死)
  - [T-003 Spring 上下文启动失败：ADMIN_API_TOKEN 未设置](#t-003-spring-上下文启动失败admin_api_token-未设置)
  - [T-019 Windows 的 CRLF 行尾会让 `/bin/sh` 脚本崩在第一行](#t-019-windows-的-crlf-行尾会让-binsh-脚本崩在第一行)
- [数据库](#数据库)
  - [T-010 `VALUES()` 在 MySQL 8.0.20+ 已 deprecated —— 已修复（2026-10-24）](#t-010-values-在-mysql-8020-已-deprecated--已修复2026-10-24)
  - [T-011 过期扫描查询走全表扫描](#t-011-过期扫描查询走全表扫描)
  - [T-011b 小表上 EXPLAIN 的 `key` 不可信 —— 断言必须换个方式](#t-011b-小表上-explain-的-key-不可信--断言必须换个方式)
  - [T-011c `information_schema.statistics` 是**每列一行**](#t-011c-information_schemastatistics-是每列一行)
  - [T-012 `IncorrectResultSizeDataAccessException`：查询少了一个维度](#t-012-incorrectresultsizedataaccessexception查询少了一个维度)
- [Redis](#redis)
  - [T-028 统计的 HLL key 驻留 35 天，而不是「当天」→ 内存上界比设计高 35 倍](#t-028-统计的-hll-key-驻留-35-天而不是当天-内存上界比设计高-35-倍)
- [接口契约](#接口契约)
  - [T-029 自定义短码「创建 201 但跳转永远 404」—— 契约两端长度不一致](#t-029-自定义短码创建-201-但跳转永远-404-契约两端长度不一致)
  - [T-030 后台接口传了错误 Token 却返回 500 —— 鉴权写在 Controller 里，被路由解析绕过](#t-030-后台接口传了错误-token-却返回-500--鉴权写在-controller-里被路由解析绕过)
- [缓存与布隆](#缓存与布隆)
  - [T-027 布隆重建的「原子切换」覆盖了重建期间新建的短码 → 假阴性 404](#t-027-布隆重建的原子切换覆盖了重建期间新建的短码--假阴性-404)
- [监控与可观测性](#监控与可观测性)
  - [T-015 Micrometer 的 `baseUnit` 会给 Prometheus 指标名**追加后缀**](#t-015-micrometer-的-baseunit-会给-prometheus-指标名追加后缀)
  - [T-017 Grafana 的 datasource provisioning 不支持环境变量插值](#t-017-grafana-的-datasource-provisioning-不支持环境变量插值)
  - [T-018 Grafana provisioning 缺子目录 → **datasource 静默不加载**](#t-018-grafana-provisioning-缺子目录--datasource-静默不加载)
  - [T-020 Micrometer 的 `jvm.memory.used` 里，Metaspace 的 `area` 是 **nonheap**](#t-020-micrometer-的-jvmmemoryused-里metaspace-的-area-是-nonheap)
  - [T-022 Grafana 的 `secureJsonData` **不会**被 upsert 覆盖（改密码必踩）](#t-022-grafana-的-securejsondata-不会被-upsert-覆盖改密码必踩)
- [部署与 Docker Secrets](#部署与-docker-secrets)
  - [T-021 Docker Compose v2 在 Secret 文件**缺失**时不报错（静默故障）](#t-021-docker-compose-v2-在-secret-文件缺失时不报错静默故障)
  - [T-023 Secret 文件必须是**纯明文单行**（注释会被当成密码）](#t-023-secret-文件必须是纯明文单行注释会被当成密码)
  - [T-024 Windows 的 CRLF 会让 **Secret 密码多一个 `\r`**（Windows 专有）](#t-024-windows-的-crlf-会让-secret-密码多一个-rwindows-专有)
- [测试与压测工具](#测试与压测工具)
  - [T-013 Testcontainers 的 `@Container` 注解会让**多个测试类互相踩死**](#t-013-testcontainers-的-container-注解会让多个测试类互相踩死)
  - [T-014 `System.getenv` 会**绕过 Spring 的属性源**](#t-014-systemgetenv-会绕过-spring-的属性源)
  - [T-016 `docker compose pull` 会被 Docker Hub 限速卡住（几分钟到几十分钟）](#t-016-docker-compose-pull-会被-docker-hub-限速卡住几分钟到几十分钟)
  - [T-025 从带标签的输出里取数：**不要用「会匹配到标签本身」的正则**](#t-025-从带标签的输出里取数不要用会匹配到标签本身的正则)
  - [T-026 用 Docker 镜像加速拉取，绕开 Docker Hub 限速](#t-026-用-docker-镜像加速拉取绕开-docker-hub-限速)

---

## 环境与依赖

### T-001 Maven Wrapper 报 "extracted distribution has no bin/mvn"

**现象**

```
$ ./mvnw -o clean install
Downloading Maven apache-maven-3.9.12 from https://maven.aliyun.com/...
Error: extracted distribution has no bin/mvn under /c/Users/pjh15/.m2/wrapper/dists/apache-maven-3.9.12-bin
```

**根因**

Maven Wrapper 的 `only-script` 模式（`distributionType=only-script`）会自己解析
`distributionUrl` 决定缓存目录名。它期望的目录名是
`apache-maven-<version>-bin`，而本机实际缓存的是
`apache-maven-apache-maven-<version>-bin`（多了一段 `apache-maven-`）。

两个目录名都存在，但 wrapper 只认自己推导的那个 —— 它在**那个**目录下找 `bin/mvn`，
而那个目录是空的（解压到了另一个名字的目录里）。

**解决**

直接用已解压的 Maven，绕过 wrapper 的路径推导：

```bash
MVN=~/.m2/wrapper/dists/apache-maven-apache-maven-3.9.12-bin/apache-maven-3.9.12/bin/mvn
"$MVN" -o clean install
```

**注意**：`-o`（离线）模式下，**任何未下载过的依赖都会直接失败**，
报 `Cannot access ... in offline mode and the artifact ... has not been downloaded from it before`。
这不是「网络问题」，是「本地仓库缺这个 jar」。解决方式是先联网拉一次：

```bash
"$MVN" dependency:get -Dartifact=org.testcontainers:mysql:1.20.6
```

---

### T-002 Testcontainers 拉取 ryuk 镜像卡死

**现象**

```
15:47:45 INFO tc.testcontainers/ryuk:0.11.0 -- Starting to pull image
15:47:45 INFO tc.testcontainers/ryuk:0.11.0 -- Pulling image layers:  0 pending,  0 downloaded,  0 extracted, (0 bytes/0 MB)
15:48:11 INFO tc.testcontainers/ryuk:0.11.0 -- Pulling image layers:  2 pending,  1 downloaded,  0 extracted, (7 MB/? MB)
（此后 5 分钟无任何进展，测试挂起）
```

**根因**

`ryuk` 是 Testcontainers 的**资源回收辅助容器**：它负责在 JVM 退出时清理
「Testcontainers 启动的但没被正常停止的容器」。

卡死的直接原因是 **Docker Hub 拉取限速**（`index.docker.io` 对匿名拉取有速率限制），
而 ryuk 镜像小、层少，恰恰是最容易卡在「下载了 7MB 就不动」的那个。

**解决**

关掉 ryuk：

```bash
TESTCONTAINERS_RYUK_DISABLED=true mvn test
```

**代价**：失去「JVM 异常退出时自动清理容器」的保护，
容器会残留到手动 `docker rm` 为止。
在本项目的场景里可接受——**测试用的容器是无状态的**，
下次跑时 Testcontainers 会用新容器，残留容器只是占着磁盘。

⚠️ 长期方案是配置镜像仓库镜像源（`~/.testcontainers.properties` 里的
`hub.image.name.prefix`），但那是环境配置，不该进代码库。

---

### T-003 Spring 上下文启动失败：ADMIN_API_TOKEN 未设置

**现象**

```
Caused by: java.lang.IllegalStateException: ADMIN_API_TOKEN 未设置：后台接口 /admin/** 依赖它做鉴权。
    请设置环境变量 ADMIN_API_TOKEN 为一个足够长的随机串后重启。
    fail-fast 校验存在是为了避免「后台接口未鉴权就上线」
    at com.linkforge.admin.config.AdminAuthConfig.<init>(AdminAuthConfig.java:27)
```

**根因**

`AdminAuthConfig` 的构造器是**故意 fail-fast** 的：
没配 `ADMIN_API_TOKEN` 就抛异常拒绝启动。这是正确的生产行为
（后台接口 `/admin/**` 绝不能不设防）。

但它让**测试环境必须提供这个变量**才能启动上下文 ——
而 `@SpringBootTest` 加载的是**完整应用上下文**，绕不开。

**解决**

在 `linkforge-app/src/test/resources/application-test.yml` 里固定给一个值：

```yaml
# 测试环境的 token 没有保密价值，它只需要「非空且够长」来通过启动期校验。
# ⚠️ 这不是在生产配置里放宽校验——生产仍走环境变量，缺失时依然 fail-fast。
ADMIN_API_TOKEN: test_only_admin_token_for_integration_tests
```

**教训**

> **fail-fast 的配置校验，会把所有「不打算用这个功能」的测试也一起卡住。**
>
> 这不是 fail-fast 错了，是「测试环境的配置边界没有显式定义」。
> 正确的做法是在 test profile 里**如实复现**生产必需的启动条件
> （就像 `SHORTLINK_BASE_URL` 那样），而不是绕过校验。

---

### T-019 Windows 的 CRLF 行尾会让 `/bin/sh` 脚本崩在第一行

**现象**

Grafana 容器反复重启，日志：

```
/etc/grafana/entrypoint.sh: line 26: : not found
/etc/grafana/entrypoint.sh: set: line 27: illegal option -
```

**根因**

在 Windows 上用编辑器/工具写的 `.sh` 文件是 **CRLF** 行尾。
`/bin/sh` 读到 `set -e\r` 时，把 `\r` 当成参数的一部分 → `illegal option -`。

⚠️ 症状出现在**第 26/27 行**，而真正的错误在**文件开头**——
`set -e` 那行就已经坏了，只是错误信息指向后续行。

**解决**

确保是 LF 行尾：

```python
s = io.open(p, encoding="utf-8", newline="").read()
s = s.replace("\r\n", "\n").replace("\r", "\n")
io.open(p, "w", encoding="utf-8", newline="\n").write(s)
```

⚠️ 更根本的做法：在 `.gitattributes` 里强制
```
*.sh text eol=lf
```
否则每个从 Windows clone 仓库的人都会踩。

**教训**

> **跨平台的行尾问题在容器里表现为「脚本语法错误」，但报错位置会误导。**
>
> `illegal option -` 这条信息指向 `set` 命令，而 `set` 本身没问题 ——
> 真正的问题是它后面多了个 `\r`。

---

## 数据库

### T-010 `VALUES()` 在 MySQL 8.0.20+ 已 deprecated —— 已修复（2026-10-24）

> **状态：已修复。** 本项目已改用 `AS new` 别名语法，
> 迁移记录见下方「修复」一节与 `StatsMapper.xml` 的注释。
> 本节保留原始排查过程，作为「为什么当初没直接改」的背景。

**现象**

`INSERT ... ON DUPLICATE KEY UPDATE` 里的 `VALUES(col)` 语法在 MySQL 8.0.20 起被标记为 deprecated：

```
[WARNING] [MY-010068] [Server] VALUES statement is deprecated and will be removed in a future release.
```

**当时的状态（本项目使用 `VALUES()`）**

- `linkforge-core/src/main/resources/mapper/StatsMapper.xml:40`
  → `pv = pv + VALUES(pv)`
- `linkforge-core/src/main/resources/mapper/StatsMapper.xml:64`
  → `uv_estimated = GREATEST(uv_estimated, VALUES(uv_estimated))`

**已验证**：在 **Testcontainers 的 MySQL 8.4 镜像**（`mysql:8.4`，与生产同版本）上
实际执行通过，行为与预期完全一致：

| 断言 | 结果 |
|---|---|
| PV 三次落库 5 / 3 / 2 → `pv = 10` | ✅ 累加语义正确 |
| UV 重复落同一估算值 5 → `uv_estimated = 5` | ✅ 不累加 |
| UV 落 5 → 12 → 8 → `uv_estimated = 12` | ✅ GREATEST 生效 |
| PV 与 UV 互不清零 | ✅ 两条语句各自只更新自己的列 |

验证位置：`linkforge-app/src/test/java/com/linkforge/app/persistence/StatsRepositoryMyBatisTest.java`
（真实 MySQL 8.4 容器，非 mock）。

**✅ 修复（2026-10-24 已完成）**

```sql
-- ❌ 旧写法（deprecated，8.0.20+ 有警告，未来版本可能移除）
INSERT INTO t (a, b) VALUES (1, 2)
ON DUPLICATE KEY UPDATE b = b + VALUES(b)

-- ✅ 新写法：AS new 别名
INSERT INTO t (a, b) VALUES (1, 2) AS new
ON DUPLICATE KEY UPDATE b = t.b + new.b
```

本项目的两条语句已改为：

```sql
-- StatsMapper.xml accumulatePvBatch
INSERT INTO link_stats_daily (link_id, stat_date, pv, uv_estimated, referer_domain)
VALUES (...), (...)
AS new
ON DUPLICATE KEY UPDATE pv = pv + new.pv

-- StatsMapper.xml mergeUvBatch
INSERT INTO link_stats_daily (link_id, stat_date, pv, uv_estimated, referer_domain)
VALUES (...), (...)
AS new
ON DUPLICATE KEY UPDATE uv_estimated = GREATEST(uv_estimated, new.uv_estimated)
```

⚠️ **语义等价性已验证**（不是「文档说等价」而是「跑过了」）：
`new.pv` 引用的是**正在插入的这一行**的 pv 值。
多行 VALUES 时逐行处理，每行的 `new` 指向当前行 —— 与 `VALUES(pv)` 一致。

验证方式：Testcontainers 的 MySQL 8.4 容器上跑
`StatsRepositoryMyBatisTest` 的全部累加/GREATEST/互不清零断言，
覆盖了「单行」「多行」「重复落库」「更大值覆盖更小值」四种组合。

⚠️ **这组断言就是为拦住这类改动而存在的** ——
未来若有人把 `AS new` 改回 `VALUES()`（或反过来），测试会立刻失败。

⚠️ **MariaDB 不支持 `AS new` 语法**。本项目用 MySQL（`docker-compose.yml` 固定 `mysql:8.4`），
若将来换数据库，需要重新评估这两条语句。

#### ⚠️ 踩坑：行别名与目标表列名同名 → `Column 'pv' in field list is ambiguous`

第一次改成 `AS new` 时，直接照搬了「左边也用列名」的写法：

```sql
-- ❌ 报错
INSERT INTO link_stats_daily (link_id, stat_date, pv, ...) VALUES (...) AS new
ON DUPLICATE KEY UPDATE pv = pv + new.pv

-- ✅ 报错信息
java.sql.SQLIntegrityConstraintViolationException:
    Column 'pv' in field list is ambiguous
```

**原因**：`AS new` 的**行别名**与**目标表的列名**同名（`new` 是别名，
但 `pv` 这个列名在表和别名里都能匹配上）。MySQL 无法判断左边的 `pv`
指的是表里的旧值还是别名的值，于是报「歧义」。

**解法**：左值用**表名限定**：

```sql
-- ✅
ON DUPLICATE KEY UPDATE pv = link_stats_daily.pv + new.pv
ON DUPLICATE KEY UPDATE uv_estimated = GREATEST(link_stats_daily.uv_estimated, new.uv_estimated)
```

⚠️ 这正是 MySQL 官方文档里那个例子的写法：

```sql
INSERT INTO t1 VALUES (1,2,3),(4,5,6) AS new
ON DUPLICATE KEY UPDATE c = new.a+new.b;
                      ^^^^^^^^^^^^^^ 注意左值用的是 t1.c
```

**教训**

> **别名语法的「消歧」不是可选的润色，而是必须做。**
>
> `VALUES(pv)` 只有一个来源，不存在歧义；
> `AS new` 引入了一个新的命名空间，**表里的列名和别名会撞车**。
>
> 判据：用了行别名后，UPDATE 子句里**每一个表列引用都要带表名**。
> 漏掉一个就是运行期报错 —— 而这类错误只有真正执行到那条 SQL 才会暴露，
> 编译期与代码审查都看不出来。

**判断依据**：`git log` 里的 Flyway 迁移显示项目当前 MySQL 版本是 8.4.11（`mysql:8.4` 镜像）。
MySQL 的 deprecated 策略是「先警告、若干版本后才移除」，
从 8.0.20 算起至少还有多个 minor 版本的缓冲，但**不能依赖这个缓冲**。

---

### T-011 过期扫描查询走全表扫描

**现象**

阶段 4 的过期清理需要扫「已过期且未删除」的短链：

```sql
SELECT id, short_code FROM short_link WHERE status = 0 AND expire_at < NOW() LIMIT 500
```

在只有 9 行的开发库上 `EXPLAIN`：

```
type: ALL
possible_keys: idx_status_code
key: NULL            ← 索引没被用上
rows: 9
Extra: Using where
```

**根因**

`short_link` 表的索引（V1 定义）只有：

```sql
UNIQUE KEY uk_short_code (short_code)
KEY idx_status_code (status, short_code)
```

**两个都不适用于这个查询**：

- `uk_short_code`：按短码查，跳转路径用；
- `idx_status_code (status, short_code)`：`status` 是**等值前缀**，
  但 `expire_at` 不在索引里 → MySQL 要**先取出所有 `status=0` 的行**（1000 万行里约 1% = 10 万行），
  **回表**读 `expire_at` 再过滤。这叫「索引前缀命中但过滤列不在索引中」，
  是最容易被忽略的一种全表扫描。

**实测对比**（20 万行、2000 条已过期、真实 MySQL 8.4）：

| 索引 | type | rows 估算 | 含义 |
|---|---|---|---|
| A. 现状 `idx_status_code(status, short_code)` | `ref` | **97,181** | 扫 9.7 万行才能筛出 2000 条 |
| B. `idx_expire_status(status, expire_at)` | `range` | **2,000** | 直接定位过期区间，**约 50 倍差距** |

⚠️ 注意 `rows` 从 97,181 降到 2,000 的意义：
在 1000 万行的生产表上，方案 A 每轮扫描约 500 万行，方案 B 只扫 2 万行。

**解决**

新增 V5 迁移：

```sql
ALTER TABLE short_link ADD KEY idx_expire_status (status, expire_at);
```

⚠️ **列顺序是 `(status, expire_at)` 而不是 `(expire_at, status)`**：
`status` 放前面让它成为等值前缀，`expire_at` 承担范围扫描。
两个顺序在 MySQL 8.4 的优化器下都能被正确使用（实测都选 `range`），
但 `(status, expire_at)` 的语义更清晰：**「未删除的、且过期的」**。

⚠️ **为什么这个索引不能省**：
软删除后 `status` 变成 1 的行**不该被反复扫描**。
`(status, expire_at)` 让优化器能同时用上两个条件；
实测在「50% 过期行已软删除」的场景下，估算行数从 2000 降到 1。

**验证方式**（可重复）：

```bash
docker exec linkforge-mysql mysql -uroot -p<密码> linkforge_test -e "
DROP TABLE IF EXISTS idx_probe;
CREATE TABLE idx_probe LIKE linkforge.short_link;
-- 造 20 万行，1% 有 expire_at（已过期），其余 NULL
...
ALTER TABLE idx_probe ADD KEY idx_expire_status (status, expire_at);
EXPLAIN SELECT id, short_code FROM idx_probe WHERE status = 0 AND expire_at < NOW() LIMIT 500\G
"
```

### T-011b 小表上 EXPLAIN 的 `key` 不可信 —— 断言必须换个方式

**现象**

集成测试里断言「扫描查询必须走 `idx_expire_status`」失败了：

```
expected: "idx_expire_status"
 but was: "idx_status_code"
{type=ref, possible_keys=idx_status_code,idx_expire_status, key=idx_status_code, rows=1}
```

**根因**

**优化器的选择基于成本模型 + 表统计信息，不是「哪个索引更合适」。**

测试库里只有几条数据，优化器算出来：
- 走 `idx_expire_status` → 索引定位 + **回表**读 `expire_at`（因为 `expire_at` 在索引里但
  `short_code` 不在）→ 估算成本 > 直接全表扫
- 全表扫 → 扫 1 行

于是它选了 `idx_status_code`。**这是优化器的正确选择，不是索引没用。**

对照实测（同一个查询）：

| 表行数 | 优化器选的 | type | rows |
|---|---|---|---|
| 1 行 | `idx_status_code` | `ref` | 1 |
| 20 万行 | `idx_expire_status` | `range` | 2,000 |

⚠️ **所以「断言 EXPLAIN 的 `key` 等于某个索引」这种写法，在小表上必然不稳定**——
它测的是「表的行数够不够大」，不是「索引对不对」。

**解决**

正确的断言方式（与数据量无关）：

```java
// ① 优化器有资格用它
assertThat(plan.get("possible_keys")).contains("idx_expire_status");

// ② 强制走它时，它真的能做范围扫描
//    EXPLAIN ... FORCE INDEX (idx_expire_status) → type == "range"

// ③ 走它时结果正确（比 EXPLAIN 更实在）
//    SELECT ... FORCE INDEX (idx_expire_status) → 结果集与不加索引时一致
```

**教训**

> **EXPLAIN 告诉你的是「优化器在这个表上会怎么做」，
> 不是「这个索引对不对」。**
>
> 要验证索引本身，用 `FORCE INDEX`；
> 要验证优化器会选它，必须有**真实的数据量**。
> 把「优化器的选择」当成「索引的正确性」来断言，
> 测出来的是行数，不是设计。

### T-011c `information_schema.statistics` 是**每列一行**

**现象**

```java
Integer count = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM information_schema.statistics WHERE ... AND index_name = 'idx_expire_status'",
        Integer.class);
assertThat(count).isEqualTo(1);   // ❌ 实际是 2
```

**根因**

`information_schema.statistics` 的粒度是「**索引的每一列**一行」。
复合索引 `(status, expire_at)` 有 2 列 → 2 行。

**解决**

```sql
SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics WHERE ...
```

要验证**列顺序**，则用 `ORDER BY seq_in_index`：

```java
List<String> columns = jdbcTemplate.queryForList(
        "SELECT column_name FROM information_schema.statistics "
                + "WHERE ... AND index_name = 'idx_expire_status' ORDER BY seq_in_index",
        String.class);
assertThat(columns).containsExactly("status", "expire_at");
```

---

### T-012 `IncorrectResultSizeDataAccessException`：查询少了一个维度

**现象**

```java
Long v = jdbcTemplate.queryForObject(
        "SELECT pv FROM link_stats_daily WHERE link_id = ? AND stat_date = ?",
        Long.class, linkId, DAY);
```

```
org.springframework.dao.IncorrectResultSizeDataAccessException:
Incorrect result size: expected 1, actual 2
```

**根因**

`link_stats_daily` 的唯一键是 `(link_id, stat_date, referer_domain)`，
所以**同一个 `link_id + stat_date` 下可能有多行**（不同来源域）。

测试里手工插了一行 `referer_domain = 'google.com'`，
再用「只按 link_id + date 查」的 SQL 读 `direct` 那一行 → 命中 2 行。

**⚠️ 这不是「数据错了」，是「查询写得不够精确」**——
`queryForObject` 期望恰好 1 行，拿到 2 行就抛异常。

**解决**

查询必须带上 `referer_domain`：

```java
"SELECT pv FROM link_stats_daily WHERE link_id = ? AND stat_date = ? AND referer_domain = ?"
```

**教训**

> **聚合表的唯一键有几个维度，查询就必须带齐几个维度。**
>
> 少带一个的后果不是「少查了数据」，而是「查到多行然后抛异常」——
> 报错信息（`expected 1, actual 2`）完全不指向真正的原因，
> 很容易让人误以为是数据被写坏了。

---

## Redis

### T-028 统计的 HLL key 驻留 35 天，而不是「当天」→ 内存上界比设计高 35 倍

**现象**

按 `docs/DATABASE.md` §9.2 的估算，HLL（HyperLogLog）内存上界约 **1.2GB**（10 万活跃链路 × 12KB）。
但在 Redis 里实际看到的 HLL key 数量远超预期，内存占用也比设计高一个量级。

**根因**

设计要求「HLL key 只在当天存活，落库后立即释放」，
但**实现只做了一半**：

| 设计要求 | 实现 | 出处 |
|---|---|---|
| 落库后释放 UV key | ❌ **不释放**：flush 用 `PFCOUNT`（只读）取 UV 估算值 | `StatsFlushService` 落库流程 |
| TTL 到当天结束即过期 | ❌ **TTL 是 35 天** | `StatsKeys.KEY_TTL = Duration.ofDays(35)` |
| 双重保证（TTL + 落库清理） | ⚠️ 只有 TTL 一重 | — |

对照 **PV key** 就更明显：PV 用的是 `GETDEL`（「取走即删」），
所以 PV key 落库后**当场消失**；UV 只 `PFCOUNT` 不删，于是**留到 TTL 到期**。

**实际驻留上界**：35 天 × 10 万活跃链路 × 12KB ≈ **42GB**，
是 §9.2 原估（1.2GB）的 **35 倍**。

**为什么现在没炸**：真实负载下活跃链路数远低于 PRD 的 10 万假设
（压测语料只有 1 万条），且 Redis 有 `maxmemory` 保护。
**但这是「量还没到」，不是「设计对了」。**

**解决**

⚠️ **本条尚未修复**。推荐做法（与 T-027 一起做更划算）：

1. flush 成功后**主动删除**当日 UV key（对齐 PV 的「取走即删」）；
2. 把 UV key 的 TTL 改成「当天结束 + 余量」，而不是固定 35 天；
3. 保留 35 天 TTL 作为**兜底**（防止漏删的 key 永久驻留）；
4. 补一个**内存上界监控**：Redis `used_memory` + HLL key 数量，
   并在 Grafana 里画出来 —— 上界问题应该在**发生前**被看见。

**教训**

> **「取走即删」是累加型统计能安全重试的前提**（见 §8.2）。
> 但它只对**增量**成立：UV 是**累计估算**，`PFCOUNT` 天然是只读的，
> 于是「取走即删」这条链路在 UV 上断了 ——
> **同一个设计里，两种语义需要两种处理方式，不能只实现一种。**

---


## 接口契约

> 这一节是**对外契约**层面的坑：不是「实现写错了」，
> 而是「契约的两端（创建 / 使用）各自定义了同一个概念，然后漂移了」。

### T-029 自定义短码「创建 201 但跳转永远 404」—— 契约两端长度不一致

**现象**

用自定义短码创建短链，接口返回 **201 Created**、短码也返回了：

```
POST /api/v1/links  {"targetUrl":"https://example.com/x","customCode":"Abcdef"}
→ 201  {"data":{"shortCode":"Abcdef","shortUrl":"http://localhost:8080/Abcdef",...}}
```

但**访问这个链接永远 404**：

```
GET /Abcdef → 404 {"code":"LINKFORGE-404-01","message":"短码不存在: Abcdef"}
```

数据库里**明明有**这条记录（`status=0`、`expire_at=NULL`），布隆与缓存也都正常。

**什么长度会踩到**

| `customCode` | 长度 | 创建 | 跳转 |
|---|---|---|---|
| `abcd` | 4 | 201 | ❌ 404 |
| `Abcde` | 5 | 201 | ❌ 404 |
| `Abcdef` | 6 | 201 | ❌ 404 |
| `Abcdefg` | **7** | 201 | ✅ 302 |
| `Abcdefgh` | 8 | 201 | ❌ 404 |

**只有 7 位能跳。**

**根因**

契约的两端用了**不同的长度规则**：

| 侧 | 规则 | 出处 |
|---|---|---|
| 创建 | `^[a-zA-Z0-9]{4,8}$` —— 4~8 位**全部放行** | `ShortCodeFormat` / API.md §5.2 |
| 跳转 | 长度必须 `== IdSpace.SHORT_CODE_LENGTH`（= **7**），否则按「不存在」→ 404 | `LinkValidator.validateForRedirect()` |

⚠️ **代码里其实早就写明了** —— `validateForRedirect()` 的 javadoc 里写着：

> 「⚠️ 自定义短码（4~8 位）在这里会被判为 404，这是契约的既定行为而非遗漏：
> 跳转接口按默认短码长度定位，自定义短码是否可跳转属于**待确认项**，
> 在契约改写之前不擅自放宽。」

所以这不是「实现跑偏」，而是**一个被有意推迟的契约问题** ——
但它**从没进过 `docs/API.md` §13 的待确认清单**，
于是对外文档反而在**鼓励**用户用一个会产出死链的长度。

**为什么前 5 周没发现**

- 压测语料**全是 7 位自动码**；
- 单元/集成测试断言了「创建成功（201）」与「7 位码能跳（302）」，
  但**没有一条断言「创建出来的短码一定能被访问」**——
  测试用例的取值分布决定了它能发现哪一类缺陷。

**解决**

⚠️ **v1 未修复**（DR-063：改代码会破坏已冻结的性能基线），**v2 处理**。

| 方案 | 做法 | 代价 |
|---|---|---|
| **A（推荐）** | **契约收窄**：`customCode` 正则改为 `^[a-zA-Z0-9]{7}$`，创建时就拒绝非 7 位 | 失去「取有意义的名字」的灵活性，但契约自洽 |
| B | **契约放宽**：跳转路径接受 4~8 位，删掉长度检查 | 改动在**压测热路径**上；且要重新审视 DR-037 的判定顺序 |
| C | **分流路径**：`/{code}` 只收 7 位，另开前缀路由承载自定义短码 | 改动面最大 |

**v2 落地时必须补的测试**（本次缺失的那一类）：

```java
// 遍历所有允许的长度，断言「创建出来的短码一定能被访问」
@ParameterizedTest
@ValueSource(strings = {"abcd", "Abcde", "Abcdef", "Abcdefg", "Abcdefgh"})
void 创建出来的短码必须可访问(String customCode) {
    var created = createLink(customCode);          // 201
    var resp = redirect(created.shortCode());       // 必须 302
    assertThat(resp.status()).isEqualTo(302);
}
```

**教训**

> **契约的两端必须同时验证：能创建出来的，必须能被访问到。**
>
> 「创建接口返回 201」不等于「链接可用」——
> 中间隔着**路径路由的长度校验**这一层，
> 而这一层的规则与创建层的规则**各自独立演进**时就必然漂移。
>
> 泛化地说：**两个地方各自定义同一个概念时，先问「谁是唯一来源」**。
> 本项目里 `IdSpace.SHORT_CODE_LENGTH = 7` 是发号侧的长度，
> 但**自定义短码的长度范围（4~8）是另一条独立规则**，
> 两者从没被放在一起验证过。

---

---

### T-030 后台接口传了错误 Token 却返回 500 —— 鉴权写在 Controller 里，被路由解析绕过

**现象**

本地验收时手动 curl 后台接口，**故意传错的 Token**，结果拿到 500：

```
GET /admin/api/v1/links/0000VF0   Authorization: Bearer 你的ADMIN_API_TOKEN
→ 500 {"code":"LINKFORGE-500-01","message":"internal server error"}
```

按契约（API.md §7.3）应该是 **401** `LINKFORGE-401-01`。

**⚠️ 关键对照：同一个错误 Token，`DELETE` 却是对的**

```
DELETE /admin/api/v1/links/0000VF0   Authorization: Bearer 错误的中文Token
→ 401 {"code":"LINKFORGE-401-01","message":"缺少或无效的后台 Token"}   ✅
```

**同一个 Token，比对逻辑同一份，只是方法不同，结果一个 401 一个 500。**

**根因**

⚠️ **不是 Token 比对抛异常** —— 最初的猜测是「`MessageDigest.isEqual` 遇到 null 炸了」，
实测排除了：`AdminToken.matches(null)` 早就被挡住，`DELETE` 也一直返回 401。

真实根因有两层：

| 层 | 事实 |
|---|---|
| **1. 鉴权的位置错了** | `/admin/api/v1/links/{code}` **只定义了 `DELETE`**。Spring 在**路由解析阶段**就抛了 `HttpRequestMethodNotSupportedException`；而鉴权检查写在 **Controller 方法体内**，**根本没被执行到** |
| **2. 异常没有映射** | 该异常没有被任何 `@ExceptionHandler` 映射 → 掉进兜底分支 → **500** |

应用日志里的实锤：
```
ERROR GlobalExceptionHandler : 未预期异常
org.springframework.web.HttpRequestMethodNotSupportedException: Request method 'GET' is not supported
```

> **只要鉴权在 Controller 里，「路由不匹配」这条分支就永远绕过鉴权** ——
> 而绕过鉴权的表现不是「放行」，是「500」。

**解决**

✅ **已修复（DR-064）**：

1. 鉴权前移为 **Servlet 过滤器** `AdminAuthFilter`（注册在 `/admin/*`）——
   Filter 在 `DispatcherServlet` 之前执行，**先于路由解析**；
2. 判定集中到 `AdminToken.matchesAuthorizationHeader(String)`，
   对 null / 空串 / 缺 `Bearer ` 前缀 / 乱码 / 长度不符 **一律返回 false，永不抛异常**；
   Controller 内保留同样的调用作为纵深防御；
3. `HttpRequestMethodNotSupportedException` → **405** `LINKFORGE-405-01`（带 `Allow` 头）；
4. 错误体构造下沉到 core 的 `ErrorBody`，
   让过滤器与全局异常处理器产出**完全一致**的结构。

修复后的行为：

| 请求 | 响应 |
|---|---|
| `/admin/**` + Token 缺失 / 格式错 / 不匹配 | **401** `LINKFORGE-401-01` |
| `/admin/**` + Token 正确但方法不对 | **405** `LINKFORGE-405-01` + `Allow: DELETE` |
| `/admin/**` + Token 正确 + 方法正确 | 正常响应 |

**⚠️ 附带的客户端坑（排查时浪费了时间，值得单独记）**

用 `RestTemplate` + `SimpleClientHttpRequestFactory` 写端到端测试时，
**DELETE 的 401 响应体读不出来**（`getBody()` 返回 null，
而响应头里明明有 `Content-Length: 117`）——
是底层 `HttpURLConnection` 在 401 响应上的鉴权处理路径把错误流吃掉了。

> ⚠️ **用错 HTTP 客户端，断言会失败在「读不到 body」上，而那与服务端行为无关** ——
> 极易被误判成「服务端的 bug」，从而去改本来正确的服务端代码。
> 用 `curl` 对照一次即可区分：**curl 能读到 body ⇒ 服务端没问题，是客户端的问题。**
> 本项目改用 JDK 的 `HttpClient`（默认不跟随重定向，对任意方法/状态码都能稳定读到 body）。

**教训**

> **「守卫要站在被保护的资源之前」，而不是「站在它的某个分支里」。**
>
> 写在 Controller 里的鉴权，看起来「在任何业务逻辑之前」——
> 事实上只在「路由能匹配上」的前提下才在业务逻辑之前。
> **路由解析失败是一条独立的路径**，它绕过了你写在方法体里的所有前置检查。
>
> 泛化地说：**任何「先检查再执行」的守卫，都要问一句「检查失败时会不会走到另一个分支」。**
>
> ⚠️ 同款陷阱的另一个版本（DR-054）：「验证两层 ≠ 验证链路」。
> 组件级测试全绿，链路照样断 —— 这次是**客户端**在骗你。

---

---

## 缓存与布隆

> 这一节的坑都围绕「缓存/布隆的正确性」，而不是「性能」。
> 性能类结论在 `docs/PERFORMANCE.md`，决策理由在 `docs/DECISIONS.md`。

### T-027 布隆重建的「原子切换」覆盖了重建期间新建的短码 → 假阴性 404

**现象**

一个**刚创建**的短码，第一次访问就返回 **404**。
但短码明明刚写进 MySQL，`status=0`、未过期、`expire_at IS NULL`。

**什么情况下出现**

| 条件 | 说明 |
|---|---|
| 时间窗口 | 恰好落在**布隆定时重建的运行期间**（默认每小时一次） |
| 短码创建时刻 | 在重建的数据库扫描**游标越过该短码 id 之后** |
| 复现频率 | 窗口很窄（分钟级），所以表现为「偶发」 |

**根因**

重建是「双缓冲 + 原子切换」，但**写穿没有双写**：

| 环节 | 实际实现 | 出处 |
|---|---|---|
| 创建时写穿 | 只写**现行 key** `shortlink:bloom` | `RedisBloomFilter.put()` |
| 重建 | 只写 `:building`，最后 `RENAME` **覆盖**现行 key | `RedisBloomFilter.rebuild()` |
| 重建扫描 | 按 `id > afterId` 游标分批扫全库 | `BloomFilterMaintenance.rebuild()` |

时间线：

```
t0  重建开始：扫 id <= 1000，写入 :building
t1  重建扫过 id=1000，游标继续往上走
t2  用户创建短码 id=1001  →  put() 写进【现行 key】
t3  重建扫到 id=1001（也可能没扫到，取决于扫描进度）
t4  RENAME :building → shortlink:bloom   ← 现行 key 被【覆盖】
t5  访问 id=1001 → 布隆判「不存在」→ 404
```

⚠️ **关键**：`RENAME` 是**覆盖**语义（Redis 的 `RENAME` 覆盖目标 key），
不是合并。`:building` 里没有的位，切换后就**彻底没了**。

**为什么这条设计当初是对的、现在错了**

原始设计（DR-007）明确要求「构建期间，创建操作的写穿**同时写新旧两份**」。
**代码里从来没实现这个双写** —— 文档写了，设计是对的，实现漏了。
这是「文档与实现不一致」的一个典型：设计评审通过 ≠ 实现完成。

⚠️ **这条其实早就被发现过**（`docs/DECISIONS.md` **DR-055**，第 3 周压测前实测到），
但当时给出的处置是「v1 先绕过，v2 再优化」：
压测脚本造数后**必须等日志出现「布隆全量重建完成：N 条」才能开始打流量**。
第 6 周收尾核对时发现：**DR-007 的正文至今仍写着「写穿同时写新旧两份」**，
读者会以为双写已经实现 —— 这是**文档与实现不一致**，本条就是来补这个洞的。

**解决**

⚠️ **本条尚未修复**，是第 6 周收尾审计新发现的问题。三种修法：

| 方案 | 做法 | 代价 |
|---|---|---|
| **A（推荐）** | 引入 `rebuilding` 状态标志；标志为真时 `put()` **同时写现行 key 与 `:building`** | 需要一个跨实例可见的状态（Redis key 或 DB 标志） |
| B | 重建结束时不做覆盖式 `RENAME`，而是把**现行 key 的位并入** `:building` 再切换 | `SETBIT` 逐位并集成本高（9500 万位），需要改用 `BITOP` |
| C | 缩短重建间隔，缩小窗口 | 只降低概率，不消除问题；且重建本身有开销 |

**推荐的验证方式**（修复后必须补）：

1. 在测试里**卡住**重建（mock 住扫描，让它停在游标中间）；
2. 期间创建一个新短码；
3. 放行重建完成；
4. 断言该短码**仍能被布隆判为存在**（或至少：跳转不返回 404）。

**教训**

> **「双缓冲 + 原子切换」这个模式本身是对的，
> 前提是切换前新数据已经进了新缓冲。**
>
> 没有双写，双缓冲只是把「删除即丢」换成了「切换即丢」——
> 窗口从「DEL 到 RENAME 之间」变成了「整个重建期间」。

---

## 监控与可观测性

### T-015 Micrometer 的 `baseUnit` 会给 Prometheus 指标名**追加后缀**

**现象**

在 Grafana 里查 `shortlink_meter_count`，**查不到数据**。
但应用侧 `/actuator/prometheus` 端点里明明有：

```
shortlink_meter_count_meters{application="linkforge"} 182.0
```

**根因**

Micrometer 的 `Gauge.builder(...).baseUnit("meters")` 会把单位**追加到指标名后面**：

| 代码 | Prometheus 指标名 |
|---|---|
| `Gauge.builder("shortlink.meter.count", ...)` | `shortlink_meter_count` |
| `Gauge.builder("shortlink.meter.count", ...).baseUnit("meters")` | `shortlink_meter_count_meters` |

⚠️ 这个后缀在 Micrometer 自己的世界里是「单位」，
但 Prometheus 生态的惯例是**把单位放在指标名里**（`..._bytes`、`..._seconds`）。
两者叠加就会得到 `xxx_meters` 这种别扭的名字。

**解决**

**不设 `baseUnit`**，单位信息写在 `description` 里：

```java
Gauge.builder("shortlink.meter.count", registry, r -> r.getMeters().size())
    .description("MeterRegistry 中的 meter 总数。...")
    // ⚠️ 不设 baseUnit：它会让 Prometheus 名字变成 shortlink_meter_count_meters
    .register(registry);
```

**教训**

> **指标名是「对外契约」，改一次就要同步改所有查询。**
>
> 加指标时先想清楚最终在 Prometheus 里的名字是什么，
> 再写代码 —— 因为 Grafana 看板、告警规则、别人的查询都依赖这个名字。
>
> 验证方法：`curl localhost:8080/actuator/prometheus | grep <关键词>`，
> 看**实际暴露的名字**，不要凭代码里的名字去猜。

---

### T-017 Grafana 的 datasource provisioning 不支持环境变量插值

**现象**

Prometheus 开了 Basic Auth，Grafana 的数据源健康检查一直 401：

```json
{"message":"401 Unauthorized - There was an error returned querying the Prometheus API.","status":"ERROR"}
```

在 `datasource.yml` 里写 `basicAuthPassword: $__env{PROMETHEUS_PASSWORD}`
或 `${PROMETHEUS_PASSWORD}` 都**不生效**。

**根因**

Grafana 的 provisioning **不做任何变量插值** —— `secureJsonData` 里的
`basicAuthPassword` 只接受**字面值**。实测在 Grafana 12.1.0 的
`public/build/*.js` 里搜 `__env` 是 **0 处命中**。

**解决**

用一个 entrypoint 包装脚本在 Grafana 启动前**渲染**一份临时 provisioning 文件：

```sh
# 从 secret 读明文密码，用 awk 替换占位符
awk -v pw="$PROM_PASSWORD" '
  /^[[:space:]]*basicAuthPassword:/ {
    match($0, /^[[:space:]]*/)
    indent = substr($0, 1, RLENGTH)
    print indent "basicAuthPassword: \"" pw "\""
    next
  }
  { print }
' "$TEMPLATE" > "$RENDERED"
```

⚠️ **必须保留原行缩进**：`basicAuthPassword` 是 `secureJsonData` 的**子键**。
缩进丢了它就变成兄弟键 —— YAML 能解析，但 Grafana 拿不到密码。
症状：`secureJsonFields` 为空、健康检查 401、**日志里毫无异常**。

**教训**

> **「配置生效了」和「配置被读取了」是两件事。**
>
> Grafana 的症状极具迷惑性：provisioning 日志一切正常，
> 数据源也确实「存在」，只是**密码没存进去**。
> 唯一可靠的验证是 `GET /api/datasources/1` 看 `secureJsonFields` ——
> 它必须是 `{"basicAuthPassword": true}`，空对象就是没生效。

---

### T-018 Grafana provisioning 缺子目录 → **datasource 静默不加载**

**现象**

日志里只有：

```
logger=provisioning.dashboard ... "finished to provision dashboards"
```

**完全没有 `provisioning.datasources` 的记录** ——
但数据源却「存在」（有 id、有 name），只是没有密码。

**根因**

Grafana 按固定顺序加载 provisioning 子目录，
且**某个子目录缺失会报错并可能中断后续加载**。
`alerting` 目录不存在时报：

```
logger=provisioning.alerting level=error msg="can't read alerting provisioning files from directory"
```

而 `datasource` 的加载排在**最后** —— 前序报错让它根本没执行。
Grafana 用内置默认配置兜底建了一个数据源，于是「看起来有」。

⚠️ 最迷惑的地方：**数据源确实存在**，所以任何「检查数据源在不在」的验证都会通过。

**解决**

entrypoint 里**建齐所有**子目录（哪怕是空的）：

```sh
mkdir -p /tmp/provisioning/datasources \
         /tmp/provisioning/dashboards \
         /tmp/provisioning/plugins \
         /tmp/provisioning/alerting \
         /tmp/provisioning/notifiers \
         /tmp/provisioning/access-control
```

**教训**

> **「数据源存在」不等于「数据源被正确配置」。**
>
> 验证配置是否真正生效，要看**字段的值**，不要看**对象是否存在**。
> 这里如果只看 `GET /api/datasources`（返回了一个 Prometheus 数据源），
> 就会误判为「配置成功」。

---

### T-020 Micrometer 的 `jvm.memory.used` 里，Metaspace 的 `area` 是 **nonheap**

**现象**

查 `jvm_memory_used_bytes{area="metaspace"}` —— **返回空**。
明明 JVM 明明有 Metaspace（`jcmd` 能看到），Prometheus 也确实在采集 `jvm.memory.used`。

**根因**

Micrometer 的 `jvm.memory.used` 把内存池分成 `heap` 与 `nonheap` 两类。
**Metaspace 与 Compressed Class Space 都归在 `nonheap`**：

```
area=heap     id=G1 Eden Space
area=heap     id=G1 Old Gen
area=heap     id=G1 Survivor Space
area=nonheap  id=Metaspace
area=nonheap  id=Compressed Class Space
area=nonheap  id=CodeHeap 'profiled nmethods'
...
```

没有 `area="metaspace"` 这个值 —— 查它是**语法合法但语义不存在**。

**解决**

正确写法：

```promql
jvm_memory_used_bytes{area="nonheap", id="Metaspace"}
```

**教训**

> **PromQL 查不到数据时，先确认「这个 label 组合是否存在」，
> 而不是立刻怀疑采集出了问题。**
>
> 最快的验证：`GET /api/v1/query?query=jvm_memory_used_bytes`，
> 把返回的**所有** label 组合看一遍。
>
> ⚠️ 这条尤其重要：本项目第 5 周要用 Metaspace 曲线做长稳排查 ——
> 如果查询写错，得到的会是「空曲线」，而空曲线**看起来像「没有泄漏」**，
> 结论会完全反过来。

---

---

### T-022 Grafana 的 `secureJsonData` **不会**被 upsert 覆盖（改密码必踩）

**现象**

改了 Prometheus 的密码并重建了 Prometheus：

```
$ curl -u "linkforge:新密码" http://localhost:9090/-/healthy
HTTP 200                                    ← Prometheus 侧正常
$ curl -u "admin:xxx" http://localhost:3000/api/datasources/1/health
{"status":"ERROR", ...}                     ← Grafana 数据源失败
```

而且 Grafana 日志里 **provisioning 一切正常**，看板全是 No data。

**根因**

Grafana 的 datasource provisioning 对 `secureJsonData` 的处理是：
**仅当数据源不存在时才写入密码**。

这是 Grafana 的**刻意设计** —— 避免每次容器启动都用明文去覆盖已加密的密码
（已存储的是密文，provisioning 里的是明文）。

于是数据源已存在时（存在 Grafana 的数据卷里）：
- `basicAuth: true` / `basicAuthUser` 会被 upsert 更新；
- `secureJsonData.basicAuthPassword` **保持旧值不动**。

⚠️ **迷惑性**：`GET /api/datasources/1` 显示
`"secureJsonFields": {"basicAuthPassword": true}` —— 字段**确实存在**，
只是内容是**旧密码**。看字段是否存在无法判断密码对不对。

**解决**

`scripts/reset-grafana-datasource.sh`（幂等）：

1. 先试 API 删除数据源 —— provisioning 创建的是 `readOnly` 数据源，会 **403**；
2. 403 则降级为「删 Grafana 数据卷重建」。

⚠️ 降级会丢失**手工改过**的看板；本项目的看板全部是 provisioning 的
（`docker/grafana/dashboards/*.json`），重建后自动恢复，所以对本项目无损。

**改密码的完整流程**（`scripts/gen-prometheus-hash.sh` 的输出里也写了）：

```bash
bash scripts/gen-prometheus-hash.sh '新密码'      # 同步哈希 + 明文
docker compose up -d --force-recreate prometheus  # ① Prometheus 加载新哈希
bash scripts/reset-grafana-datasource.sh           # ② 重置 Grafana 数据源
```

> **教训**
>
> **「改了密码」是跨两个系统的操作，而配置同步往往不是自动的。**
>
> Prometheus 用哈希（进 git），Grafana 用密文（存数据库），
> 明文只在 secret 文件里 —— 三份数据，必须显式同步。
> 脚本化的价值不只是「省几行命令」，而是**把顺序写死**（漏了第 ② 步的后果极隐蔽）。

---

## 部署与 Docker Secrets

### T-021 Docker Compose v2 在 Secret 文件**缺失**时不报错（静默故障）

**现象**

新 clone 的仓库跑 `docker compose up -d`，**一切正常**：

```
linkforge-app          Up (healthy)
linkforge-prometheus   Up (healthy)
linkforge-grafana      Up (healthy)
```

但：
- Grafana 登不进去（admin 密码为空）；
- Prometheus 的 Basic Auth 永远 401（连 healthcheck 都过不了，
  但如果 healthcheck 没带凭据，它反而「正常」）。

**根因**

Compose v2 的 `secrets: { file: ./path }` 在文件**不存在**时**不报错**——
它会挂一个**空目录**到 `/run/secrets/<name>`，容器照常启动。

实测（最小复现）：

```yaml
services:
  dummy:
    image: busybox:1.36
    secrets: [my_secret]
secrets:
  my_secret:
    file: ./secrets/my_secret.txt   # ← 文件不存在
```

```
$ docker compose up -d
 Container clonetest2-dummy-1 Started     ← 成功！
```

⚠️ **危害在于它是静默的**：容器状态全是 healthy，没有任何日志提示。
`docker compose config` 也不会报错（它只校验 YAML 结构，不检查文件存在性）。

**解决**

`scripts/setup-local.sh` 在启动前**显式生成** secret 文件（从 `.example` 模板），
并做三项校验：

1. Prometheus 的「明文密码」与「web.config.yml 里的 bcrypt 哈希」是否匹配；
2. Grafana 的数据源健康检查是否 OK；
3. secret 文件是否为纯明文单行（见 T-023）。

> **教训**
>
> **「容器全绿」不等于「服务可用」。**
>
> 这个故障最难发现的地方在于：任何**只检查存在性**的验证都会通过
> （容器在跑、端口在听、healthcheck 通过）。
> 唯一可靠的验证是**检查功能**——真的拿凭据去请求一次。

---

### T-023 Secret 文件必须是**纯明文单行**（注释会被当成密码）

**现象**

`docker/secrets/grafana_admin_password.txt` 是从带注释的模板复制的：

```
# Grafana admin 密码
# 复制为 grafana_admin_password.txt 后修改。
# ⚠️ 真实文件被 .gitignore 排除，不会进 git。
change_me_grafana_admin_password
```

用户 `cat` 出这个文件，把**最后一行**当密码输入 → 登录失败。

**根因**

`GF_SECURITY_ADMIN_PASSWORD__FILE` 会把 secret 文件的**整个内容**当成密码，
**包括每一行注释和中间的换行**。

于是「用户看到的明文」与「程序实际使用的密码」**根本不是同一个字符串**。

同样的问题会影响 Prometheus：
- healthcheck 用 `$(cat /run/secrets/prometheus_basic_auth)` 构造 Basic Auth，
  多行内容会让认证头畸形 → healthcheck 永远失败。

**解决**

**secret 真实文件 = 纯明文单行，一行不多。**

- `.example` 模板也只放纯明文（说明文字移到 `setup-local.sh` 的输出与 README）；
- `setup-local.sh` 复制时**剥离注释行**（兼容手写的带注释模板）。

> **教训**
>
> **「给人看的文件」与「给程序用的文件」不是一回事。**
>
> 模板文件里加一行说明 seemed 很人性，但对「整文件即值」的消费者来说，
> 那行说明就是密码的一部分。
> 判据：**只要一个文件被整体当作配置值读取，它就只能有一个值。**

---

### T-024 Windows 的 CRLF 会让 **Secret 密码多一个 `\r`**（Windows 专有）

**现象**

在 Windows 上按 README 手工复制模板：

```bash
cp docker/secrets/grafana_admin_password.txt.example docker/secrets/grafana_admin_password.txt
```

然后 Grafana 登录失败。用户 `cat` 出来的密码**肉眼完全正确**，
但用 `curl -u "admin:$(cat ...)"` 也是 401。

**根因**

Windows 检出的 `.example` 文件是 **CRLF** 行尾（除非 `.gitattributes` 规定 LF）。
`cp` 是二进制复制，**保留源文件的行尾** → 真实 secret 文件也是 CRLF。

而 `GF_SECURITY_ADMIN_PASSWORD__FILE` 把文件内容**原样**当密码（只去掉末尾换行，
中间的 `\r` 留着）→ 实际密码 = `change_me_grafana_admin_password\r`。

于是：
- 用户输入的密码（复制粘贴，通常不带 `\r`）→ 不匹配；
- `curl -u "admin:$(cat file)"` —— `$(...)` **会去掉尾部换行**，但**保留 `\r`** →
  恰好匹配！所以 **curl 能通、浏览器登不进去**。

⚠️ 这个「curl 能通但浏览器不通」的现象极具迷惑性：
它让人以为「密码是对的，是 Grafana 的问题」。

**解决**

`.gitattributes` 明确指定这些文件必须是 LF：

```
.env.example            text eol=lf
*.example                text eol=lf
.env                     text eol=lf
docker/secrets/*.txt     text eol=lf
```

加上 `scripts/setup-local.sh` 的**双重保险**：复制时用 `printf '%s\n'` 重写
（而不是 `cp`），并剥离注释行 —— 生成的文件一定是 LF + 纯明文单行。

**教训**

> **行尾问题不只影响脚本。**
>
> T-019 里 CRLF 让 `/bin/sh` 崩在第一行（语法错误，很显眼）。
> 而这里 CRLF 让**密码多一个字符**（语义错误，不显眼）——
> 同样一个根因，两种完全不同的表现形式。
>
> 判据：**凡是「整文件即值」的文件**（secret、配置、密码、token），
> 行尾必须是 LF，而且要在**生成环节**保证，而不是指望用户复制时不出错。

---

## 测试与压测工具

### T-013 Testcontainers 的 `@Container` 注解会让**多个测试类互相踩死**

**现象**

三个测试类都继承同一个基类，各自带 `@Container` 字段。单独跑每个类都绿，
一起跑就出现：

```
org.springframework.transaction.CannotCreateTransactionException:
    Could not open JDBC Connection for transaction
（每个用例卡 30 秒超时，一个类 20 个用例 = 613 秒）
```

**根因**

**static 字段在继承关系里是「每个子类各有一份」**，不是共享的。

于是：
- `@DynamicPropertySource`（定义在**基类**的静态方法里）读的是
  `MySqlContainerSupport.MYSQL` —— **基类那一份**；
- `@Container` 扩展在**子类**里扫到的是 `LinkRepositoryMyBatisTest.MYSQL`
  —— **子类自己那一份**（`static` 字段被子类遮蔽）。

两个是**不同的容器对象**。生命周期因此错位：

```
类 A 开始 → 扩展启动容器 A
类 B 开始 → 扩展启动容器 B
类 A 结束 → 扩展 **停止容器 A**
类 C 开始 → 容器 A 已被停掉 → 连不上
```

而 `@DynamicPropertySource` 注入的 URL 指向容器 A —— 它被别的类停掉了。

**解决**

**不用 `@Container` 注解**，改为在**静态初始化块里手动 start()**，
并注册 **JVM shutdown hook** 来 stop()：

```java
private static final MySQLContainer<?> MYSQL = createAndStart();

private static MySQLContainer<?> createAndStart() {
    MySQLContainer<?> c = new MySQLContainer<>("mysql:8.4")...;
    c.start();
    Runtime.getRuntime().addShutdownHook(new Thread(c::stop));
    return c;
}
```

容器在基类首次加载时启动**一次**，三个子类**共享同一个已启动的容器**；
只在 JVM 退出时停止 —— 绝不会在某个测试类结束时把别人的连接池干掉。

**附带收益**：三个类的 Spring 上下文配置完全相同 →
**Spring 会复用同一个 ApplicationContext**（`@SpringBootTest` 的上下文缓存），
启动开销只付一次。

⚠️ 共享容器的安全性前提：三个测试类操作**不同的表**
（short_link / link_stats_daily / id_segment），
且各自用不同的短码与独立的 `@Transactional` 回滚。

---

### T-014 `System.getenv` 会**绕过 Spring 的属性源**

**现象**

E2E 测试的 8 个用例全部失败：

```
后台删除接口需要 ADMIN_API_TOKEN；未设置时本类的场景 4 无法运行
Expecting not blank but was: null
```

而 `application-test.yml` 里**明明配了** `ADMIN_API_TOKEN`。

**根因**

测试里写的是：

```java
adminToken = System.getenv("ADMIN_API_TOKEN");   // ❌ 只认进程环境变量
```

`System.getenv` **不经过 Spring 的 PropertySource**，
所以 `application-test.yml` 里的值完全不会被读到。
干净环境（CI / 新克隆）没有这个环境变量 → 8 个用例全挂。

**解决**

改用 Spring 注入：

```java
@Value("${ADMIN_API_TOKEN:}")
private String adminToken;
```

⚠️ 注意 Spring 的**宽松绑定**：`@Value("${ADMIN_API_TOKEN:}")` 里的大写 key
**不**遵循 `relaxedBinding`（那是 `@ConfigurationProperties` 的行为），
所以 key 必须与 yml 里写的**完全一致**（`ADMIN_API_TOKEN`）。

**教训**

> **`System.getenv` 与 Spring 属性源是两套东西。**
>
> 在测试里用 `System.getenv` 读配置，等于**主动放弃了 test profile 的所有配置**——
> 那些配置明明写了，却完全不生效，而且没有任何警告。
>
> 判据：测试里要读配置，**一律用 `@Value` 注入**。
> `System.getenv` 只在「明确要验证环境变量存在性」的场景才用。

---

---

### T-016 `docker compose pull` 会被 Docker Hub 限速卡住（几分钟到几十分钟）

**现象**

```bash
$ docker compose pull prometheus grafana
 Image prom/prometheus:v3.5.0 Pulling
 Image grafana/grafana:12.1.0 Pulling
 f18232174bc9 Downloading 1.049MB
 f18232174bc9 Downloading 1.049MB      ← 反复停在同一数字
（45 分钟没有任何进展）
```

**根因**

Docker Hub 对**匿名**拉取有速率限制。
本机的 `~/.docker/daemon.json` 配了 `registry-mirrors`，
但 **compose pull 与普通 `docker pull` 走的是同一条路径**，
限速发生时两者一样慢。

⚠️ 而且 `docker compose pull` 会**同时**拉多个镜像，
每个镜像的每个层都在抢同一份带宽 —— 比单独拉一个更慢。

**解决**

1. **逐个镜像单独拉**（每次一个，走 mirror 更快）：
   ```bash
   docker pull prom/prometheus:v3.5.0
   docker pull grafana/grafana:12.1.0
   ```
   实测：并发 pull 卡 45 分钟无进展；单独 pull 4 分钟完成。
2. **不要和 build 同时跑** —— 构建本身也在拉依赖层，两边抢带宽。
3. ⚠️ `docker manifest inspect` **不走 mirror**（它直连 registry），
   所以它失败不代表镜像拉不下来。别用它的结果判断可达性。

**教训**

> **「拉不动」和「拉不到」是两回事。**
>
> 限速的表现是「有进度但极慢」，不是「报错」。
> 看到反复停在同一个字节数，就是限速，不是配置错误 ——
> 这时**换一个方式**（逐个拉、避开并发）比反复重试有效得多。

---

---

### T-025 从带标签的输出里取数：**不要用「会匹配到标签本身」的正则**

**现象**

终极压测跑完了，数据也正确，但汇总表里全是空的：

```
QPS   =
P50   =
P99   =
```

**根因**

wrk 的输出格式与脚本里的正则**对不上**，连续踩了三次：

| # | 脚本原来的写法 | 实际输出 | 后果 |
|---|---|---|---|
| ① | `grep -oE 'Requests/sec:[^ ]+'` | `Requests/sec:␣␣29387.67`（**两个空格**） | `[^ ]+` 匹配到空 |
| ② | `grep -oE '99.00%[^ ]*'` | `99%␣␣10.05ms`（不是 `99.00%`） | 完全匹配不到 |
| ③ | `grep -oE '[0-9.]+'` 取分位数 | `P50 / P90 / P99: 3.08 / 5.36 / 10.05 (ms)` | **`[0-9.]+` 把标签 "P50" 里的 50 也提取出来了** → 得到 `50/90/99/3.08/5.36/10.05` |

第 ③ 条最阴险：它**不是取不到值，而是取到了错的值**。
如果只看「P50 = 50」，一眼就能看出不对；但如果标签叫 `x50` 就完全看不出来了。

**解决**

**用 awk 按空白分割 + 取固定字段号**：

```bash
# "Requests/sec:  29387.67"   -> $2
grep -E '^Requests/sec:' "$F" | head -1 | awk '{print $2}'

# "P50 / P90 / P99: 3.08 / 5.36 / 10.05 (ms)"
#   $1=P50 $2=/ $3=P90 $4=/ $5=P99: $6=3.08 $7=/ $8=5.36 $9=/ $10=10.05 $11=(ms)
grep -E 'P50 / P90 / P99:' "$F" | head -1 | awk '{print $6}'    # P50
grep -E 'P50 / P90 / P99:' "$F" | head -1 | awk '{print $10}'   # P99
```

⚠️ 不受「空格是 1 个还是 2 个」「标签叫什么」「有没有小数点」影响。

**并把它抽成共享库** `scripts/bench-extract.sh`，
让 `judge-bench.sh` 与 `staircase-bench.sh` 共用同一份实现 —— 避免修一处漏一处。

> **教训**
>
> **「取不到值」是幸运，「取到错的值」是危险。**
>
> 第 ① ② 条会明显失败（空值），第 ③ 条会**静默地**给出错值。
> 而更糟的是：它发生在**判定环节**——
> 如果没有「用原始输出文件重新提取」这一步，
> 压测就会以「QPS 为空」的形式作废（4 分钟白跑）；
> 如果判定逻辑恰好只检查「非空」，就会给出**错误的结论**。
>
> 判据：**从带标签的输出里取数，用「按字段分割」而不是「正则匹配数字」。**
> 另外：**提取逻辑必须能对已有数据重跑** ——
> 这就是本项目把判定抽成独立脚本 `judge-bench.sh` 的原因。

---

### T-026 用 Docker 镜像加速拉取，绕开 Docker Hub 限速

**现象**

`docker compose pull prometheus grafana` 卡了 **45 分钟零进展**：

```
 Image grafana/grafana:12.1.0 Pulling
 f18232174bc9 Downloading 1.049MB
 f18232174bc9 Downloading 1.049MB      ← 反复停在同一字节数
```

**根因**

Docker Hub 对**匿名**拉取有速率限制。本机 `~/.docker/daemon.json` 配了
`registry-mirrors`，但限速发生时**并发拉多个镜像**会抢同一份带宽，
反而比单拉更慢。

**解决**

```bash
# ❌ 并发拉：45 分钟无进展
docker compose pull prometheus grafana

# ✅ 逐个单拉：prometheus 4 分钟完成
docker pull prom/prometheus:v3.5.0

# ✅✅ 换镜像源：grafana 60 秒完成
docker pull docker.m.daocloud.io/grafana/grafana:12.1.0
docker tag docker.m.daocloud.io/grafana/grafana:12.1.0 grafana/grafana:12.1.0
```

⚠️ **加速只在本机生效**：compose 文件里**保留官方镜像名**（可复现性优先），
本地 `docker tag` 打回官方名即可。

> **教训**
>
> **「拉不动」和「拉不到」是两回事。**
> 限速的表现是「有进度但极慢」，不是「报错」——
> 看到反复停在同一个字节数，就是限速，换方式比反复重试有效得多。
>
> 另一个纪律：**不要和 build 同时跑** —— 构建本身也在拉依赖层，两边抢带宽。



