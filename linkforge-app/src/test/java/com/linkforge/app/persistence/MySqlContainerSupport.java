package com.linkforge.app.persistence;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;

/**
 * ⚠️⚠️ <b>真实 MySQL 的 Testcontainers 基类</b>（阶段 4 前置收口）
 *
 * <h2>为什么把「宿主机 MySQL + 环境变量」换成 Testcontainers</h2>
 * 原来的做法（{@code RUN_MYBATIS_IT=true} + {@code TEST_MYSQL_PASSWORD}）有四个问题，
 * 每一个都会让「测试通过」这句话失去意义：
 * <ol>
 *   <li><b>干净环境跑不了</b>：CI / 新克隆的机器上没有 {@code linkforge_test} 库，
 *       也没有密码环境变量 → 测试被静默跳过（{@code @EnabledIfEnvironmentVariable}）。
 *       <b>「跳过」在 CI 上看起来和「通过」一样</b>——这是最危险的一种失败。</li>
 *   <li><b>共享一个库</b>：并行构建时多个测试类同时连同一个 schema。
 *       测试类都有 {@code @Transactional} 回滚，但 <b>Flyway 的 DDL 迁移不是事务</b>，
 *       且测试之间的数据清理靠手写 DELETE —— 顺序一变就互相干扰。</li>
 *   <li><b>版本不可控</b>：宿主机上的 MySQL 是什么版本？开发机装的是 8.0 还是 8.4？
 *       而生产用 8.4（{@code docker-compose.yml} 固定 {@code mysql:8.4}）。
 *       <b>在 8.0 上测过的 SQL 不代表在 8.4 上能跑</b>。</li>
 *   <li><b>「本机装了什么」成了测试的前置条件</b>——这正是 DR-031 想消灭的东西，
 *       只是当时没有更好的选择。</li>
 * </ol>
 *
 * <h2>Testcontainers 怎么解决</h2>
 * 一次性 MySQL 8.4 容器（版本与生产镜像严格一致），用完即弃。容器启动有开销
 * （首次还要拉镜像），但换来的是：
 * <ul>
 *   <li>干净环境也能跑 —— <b>不再有「静默跳过」这种伪通过</b></li>
 *   <li>测试之间完全隔离（独立的 schema）</li>
 *   <li>版本与生产一致 —— {@code mysql:8.4} 与 {@code docker-compose.yml} 同源</li>
 * </ul>
 *
 * <h2>⚠️⚠️ 为什么**不用** {@code @Container} 注解（踩过的坑）</h2>
 * 最直觉的写法是给容器加 {@code @Container}，让 Testcontainers 的 JUnit 扩展管理它的生命周期。
 * <b>但那会让三个测试类互相踩死</b>：
 * <pre>
 *   类 A 开始 → 扩展启动容器
 *   类 B 开始 → 扩展看到容器已启动（复用）
 *   类 A 结束 → 扩展 **停止容器**  ← B 的连接池瞬间失效
 *   类 C 开始 → 容器已停止 → CannotCreateTransactionException
 * </pre>
 * 实测报错：
 * <pre>
 *   org.springframework.transaction.CannotCreateTransactionException:
 *       Could not open JDBC Connection for transaction
 * </pre>
 * <p>
 * 根因：<b>static 字段在继承关系里是「每个子类各有一份」</b>，
 * 所以 {@code @Container} 扩展在每个子类里看到的是<b>不同的容器实例</b>；
 * 而 {@link #mysql()}（基类的静态方法）返回的是<b>基类那一份</b>。
 * 两边不是同一个对象，生命周期也就对不上。
 * <p>
 * <b>本类的做法</b>：在<b>静态初始化块里手动 start()</b>，并注册
 * <b>JVM shutdown hook</b> 来 stop()。
 * <ul>
 *   <li>容器在基类首次加载时启动<b>一次</b>，三个子类<b>共享同一个</b>已启动的容器；</li>
 *   <li>只在 JVM 退出时停止 —— 绝不会在某个测试类结束时把别人的连接池干掉；</li>
 *   <li>三个类的 Spring 上下文配置完全相同 → <b>Spring 会复用同一个 ApplicationContext</b>
 *       （{@code @SpringBootTest} 的上下文缓存），启动开销只付一次。</li>
 * </ul>
 * ⚠️ 共享容器的安全性：三个测试类操作<b>不同的表</b>
 * （short_link / link_stats_daily / id_segment），
 * 且各自用不同的短码与独立的 {@code @Transactional} 回滚，互不干扰。
 *
 * <h2>⚠️ 镜像版本与 {@code docker-compose.yml} 必须一致</h2>
 * {@code mysql:8.4} 两处都写死。若将来生产升级到 8.5，
 * <b>这里也要同步改</b>——否则「在 8.4 上测过的 SQL 在 8.5 上可能失败」，
 * 而这类问题恰恰是升级时才暴露的（DR-031 的老教训）。
 */
public abstract class MySqlContainerSupport {

    /**
     * MySQL 8.4 容器。
     *
     * <p>⚠️ 版本号与 {@code docker-compose.yml} 的 {@code mysql:8.4} 同源。
     * 生产升级数据库大版本时这里必须同步改（见类注释的告警）。
     */
    private static final MySQLContainer<?> MYSQL = createAndStart();

    /**
     * 把容器里的连接信息注入 Spring 上下文。
     *
     * <p>⚠️ <b>为什么必须覆盖 {@code application-test.yml} 里的 URL</b>：
     * test profile 写的是宿主机地址（{@code localhost:3307/linkforge_test}），
     * 而这里要指向容器暴露的随机端口。<b>属性源优先级</b>：
     * {@code @DynamicPropertySource} 注入的优先级<b>高于</b> application-test.yml，
     * 所以能正确覆盖。
     *
     * <p>⚠️ <b>连接串参数必须与生产逐字符一致</b>（DR-032）：
     * {@code connectionTimeZone=UTC} + {@code forceConnectionTimeZoneToSession=true}
     * 是「数据库自己生成的 created_at 也落在 UTC 上」的前提。
     * 少一个参数，测试断言的时间就会整体偏移 8 小时——
     * 而这类偏移在断言里往往表现为「差了几小时」，很难一眼看出是时区问题。
     */
    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> MYSQL.getJdbcUrl()
                + "?useSSL=false&allowPublicKeyRetrieval=true"
                + "&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true"
                + "&characterEncoding=utf8");
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    /**
     * 创建并启动容器。
     *
     * <p>⚠️ <b>启动失败必须给出可读的错误</b>：本类被三个测试类继承，
     * 启动失败时若抛 {@code ExceptionInInitializerError}，
     * 报错会变成 {@code NoClassDefFoundError}——完全看不出是「Docker 没启动」。
     * 所以这里把原始异常包一层，把「先确认 docker ps 能返回」写进消息里。
     */
    private static MySQLContainer<?> createAndStart() {
        MySQLContainer<?> container = new MySQLContainer<>("mysql:8.4")
                .withDatabaseName("linkforge_test")
                .withUsername("linkforge")
                .withPassword("linkforge")
                // ⚠️ 容器内 MySQL 的默认 root 密码策略：
                //    MySQL 8 默认用 caching_sha2_password，
                //    而 Testcontainers 的 JDBC 驱动在某些版本上对 caching_sha2 有兼容问题。
                //    显式指定 root 密码可以绕开「随机生成 root 密码后驱动连不上」的情况。
                .withEnv("MYSQL_ROOT_PASSWORD", "root")
                // ⚠️ 关键：让容器内的 MySQL 会话时区也是 UTC。
                //    本项目的 TIMESTAMP 语义依赖「会话时区 = UTC」（DR-032），
                //    宿主机时区是 +08:00 的话，数据库自己生成的 created_at
                //    会是本地墙上时钟，被当 UTC 解释后整体偏移 8 小时。
                .withEnv("TZ", "UTC")
                .withCommand("--default-time-zone=+00:00");
        try {
            container.start();
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    "无法启动 MySQL Testcontainers 容器。\n"
                            + "  最常见原因：**Docker 未启动**。\n"
                            + "  请先确认 `docker ps` 能正常返回。\n"
                            + "  原始异常：" + e,
                    e);
        }
        // ⚠️ 只在 JVM 退出时停止，绝不在某个测试类结束时停止（见类注释）。
        Runtime.getRuntime().addShutdownHook(new Thread(container::stop, "testcontainers-mysql-stop"));
        return container;
    }
}
