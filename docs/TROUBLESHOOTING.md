# LinkForge 排障手册（TROUBLESHOOTING）

> **这份文档记录的是「踩过的坑」，不是「应该怎么用」。**
>
> 每一条都对应一次真实的失败与排查。写它的目的有两个：
> 1. 下一个人遇到同样的现象时，**不用重新推导一遍**；
> 2. 避免「换个环境就好了」的假修复 —— 每条都记了**根因**而不只是**现象**。

---

## 目录

- [环境与依赖](#环境与依赖)
  - [T-001 Maven Wrapper 报 "extracted distribution has no bin/mvn"](#t-001-maven-wrapper-报-extracted-distribution-has-no-binmvn)
  - [T-002 Testcontainers 拉取 ryuk 镜像卡死](#t-002-testcontainers-拉取-ryuk-镜像卡死)
  - [T-003 Spring 上下文启动失败：ADMIN_API_TOKEN 未设置](#t-003-spring-上下文启动失败admin_api_token-未设置)
- [数据库](#数据库)
  - [T-010 `VALUES()` 在 MySQL 8.0.20+ 已 deprecated](#t-010-values-在-mysql-8020-已-deprecated)
  - [T-011 过期扫描查询走全表扫描](#t-011-过期扫描查询走全表扫描)
  - [T-012 `IncorrectResultSizeDataAccessException`：查询少了一个维度](#t-012-incorrectresultSizedataaccessexception查询少了一个维度)
- [Redis](#redis)
- [测试](#测试)

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

## 数据库

### T-010 `VALUES()` 在 MySQL 8.0.20+ 已 deprecated

**现象**

`INSERT ... ON DUPLICATE KEY UPDATE` 里的 `VALUES(col)` 语法在 MySQL 8.0.20 起被标记为 deprecated：

```
[WARNING] [MY-010068] [Server] VALUES statement is deprecated and will be removed in a future release.
```

**当前状态**

本项目**仍在使用** `VALUES()`：

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

**推荐的替代语法（MySQL 8.0.19+）**

```sql
-- ❌ 旧写法（deprecated，8.0.20+ 有警告，未来版本可能移除）
INSERT INTO t (a, b) VALUES (1, 2)
ON DUPLICATE KEY UPDATE b = b + VALUES(b)

-- ✅ 新写法：AS new 别名
INSERT INTO t (a, b) VALUES (1, 2) AS new
ON DUPLICATE KEY UPDATE b = t.b + new.b
```

⚠️ **本项目暂不改动**，理由：

1. MySQL 8.4 仍**完整支持** `VALUES()`，且**已实测通过**（不是「文档说支持」而是「跑过了」）；
2. 新语法在「多行 VALUES + 冲突更新」组合下的行为需要额外验证
   （`AS new` 在多行插入时指向的是哪一行，容易搞错）；
3. 当前没有升级 MySQL 大版本的计划。

**行动项**：**第 6 周前**改为 `AS new` 别名语法，
避免未来某个 MySQL minor 版本真的把它移除时被迫在紧急状态下改 SQL。
改动时必须重跑 `StatsRepositoryMyBatisTest` 的全部累加/GREATEST 断言 ——
**这组断言就是为拦住这类改动而存在的**。

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

**正确的断言方式**（与数据量无关）：

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

（本阶段暂无）

---

## 测试

（本阶段暂无）

---

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
