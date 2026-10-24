package com.linkforge.app.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.linkforge.core.domain.Link;
import com.linkforge.core.domain.LinkStatus;
import com.linkforge.core.repository.LinkRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * 仓储层**真实 MyBatis 集成测试**（docs/DECISIONS.md DR-031）。
 *
 * <h2>为什么必须有这个类</h2>
 * 224 个单元测试全部使用 mock 仓储，{@code LinkControllerTest} 是 {@code @WebMvcTest} 切片，
 * 二者都**不经过真实 MyBatis 反射构造器**。于是 DR-029 的
 * {@code javaType="boolean"} 缺陷在 224 个测试全绿的情况下让接口返回 500——
 * 这是结构性问题：mock 仓储在原理上测不到这条路径。
 *
 * <h2>为什么加 {@link EnabledIfEnvironmentVariable}</h2>
 * 干净环境（CI、新克隆仓库）没有 MySQL，测试必须能自动跳过而不是让构建失败。
 * 选它而不是 {@code @Tag} + surefire {@code excludedGroups}：后者要改父 POM 引构建配置，
 * 前者是 JUnit 5 自带注解，零构建改动。
 *
 * <h2>类名为什么以 Test 结尾而不是 IT</h2>
 * surefire 默认只拾取 {@code *Test / Test* / *Tests / *TestCase}；{@code *IT} 属于
 * failsafe 的职责范围，若命名为 {@code ...IT} 且不引入 failsafe 插件，
 * **这个测试会永远不被执行**——那 DR-031 就成了一句空承诺。
 *
 * <h2>怎么跑</h2>
 * <pre>
 * # 跳过（默认 clean install 行为）
 * .\mvnw.cmd clean install
 *
 * # 执行（需要先建好 linkforge_test 库并设置 MYSQL_PASSWORD）
 * RUN_MYBATIS_IT=true .\mvnw.cmd clean install
 * </pre>
 *
 * <h2>前置条件</h2>
 * 独立测试库 {@code linkforge_test} 已存在（绝不共用开发库 {@code linkforge}）：
 * <pre>
 * CREATE DATABASE IF NOT EXISTS linkforge_test CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
 * GRANT ALL PRIVILEGES ON linkforge_test.* TO 'linkforge'@'%';
 * FLUSH PRIVILEGES;
 * </pre>
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("仓储层真实 MyBatis 集成测试（DR-031）")
class LinkRepositoryMyBatisTest extends MySqlContainerSupport {

    @Autowired
    private LinkRepository linkRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 专用短码，避免与任何真实数据撞码。 */
    private static final String CODE_A = "ita0001";
    private static final String CODE_B = "ita0002";

    /**
     * 仅大小写不同的一对短码（DR-040 回归专用）。
     *
     * <p>刻意**不用** {@code CODE_A}/{@code CODE_B} 的变体：它们是全小写，
     * 变体（{@code ITA0001}）在 ci 比较下会与原值撞码，清理逻辑会互相干扰。
     * 这里用独立的、库中不会自然产生的大小写组合。
     */
    private static final String CODE_UPPER = "0Q1A";
    private static final String CODE_LOWER = "0Q1a";

    private static final String URL_A = "https://example.com/it/alpha";
    private static final String URL_B = "https://example.com/it/beta?x=1";
    private static final String URL_UPPER = "https://example.com/upper";
    private static final String URL_LOWER = "https://example.com/lower";

    @BeforeEach
    void cleanUp() {
        // 测试库可能残留上一轮数据，先清干净保证断言可重复
        jdbcTemplate.update("DELETE FROM short_link WHERE short_code IN (?, ?)", CODE_A, CODE_B);
        // ⚠️ 这里用**二进制比较**删除（V3 之后 short_code 已是 ascii_bin），
        //    因此两条语句分别删。若排序规则仍是旧的 ci，
        //    第一条 DELETE 会连带删掉 CODE_LOWER，导致本组测试失去意义。
        jdbcTemplate.update("DELETE FROM short_link WHERE BINARY short_code IN (?, ?)",
                CODE_UPPER, CODE_LOWER);
    }

    // ---------------------------------------------------------------------
    // 1. DR-029 的直接回归测试
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("insert 后 findByShortCode 能回读成功（DR-029 回归：javaType 必须 _boolean）")
    void insertThenReload_shouldReturnEntity() {
        linkRepository.insert(new Link(null, CODE_A, URL_A, LinkStatus.ACTIVE, false, null, null, null));

        Optional<Link> found = linkRepository.findByShortCode(CODE_A);

        // 修复前这里会抛 NoSuchMethodException，表现为 POST /api/v1/links 返回 500
        assertThat(found).isPresent();
        Link link = found.orElseThrow();
        assertThat(link.shortCode()).isEqualTo(CODE_A);
        assertThat(link.targetUrl()).isEqualTo(URL_A);
        assertThat(link.status()).isEqualTo(LinkStatus.ACTIVE);
        assertThat(link.custom()).isFalse();
        assertThat(link.id()).isNotNull();
    }

    @Test
    @DisplayName("自定义短码 is_custom=true 能正确回读（覆盖 boolean 的另一种取值）")
    void reload_shouldPreserveTrueForCustomFlag() {
        linkRepository.insert(new Link(null, CODE_A, URL_A, LinkStatus.ACTIVE, true, null, null, null));

        Link link = linkRepository.findByShortCode(CODE_A).orElseThrow();

        assertThat(link.custom()).isTrue();
    }

    // ---------------------------------------------------------------------
    // 2. createdAt 时区正确性（DR-027）
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("createdAt 读回瞬间与写入瞬间一致（DR-032：会话时区为 UTC，无双重换算）")
    void createdAt_shouldNotBeShiftedByServerTimezone() {
        Instant before = Instant.now();

        linkRepository.insert(new Link(null, CODE_A, URL_A, LinkStatus.ACTIVE, false, null, null, null));

        Link link = linkRepository.findByShortCode(CODE_A).orElseThrow();

        // 读回的是 LocalDateTime（UTC 语义，见 DR-027），按 UTC 解释成 Instant 后
        // 必须落在 before/after 区间内。
        // 若发生双重换算（存 UTC 却被驱动按 Asia/Shanghai 再解释一次），
        // 读回值会整体偏移 8 小时，下面两条断言都会失败。
        Instant created = link.createdAt().toInstant(ZoneOffset.UTC);
        Instant after = Instant.now();

        assertThat(created)
                .as("createdAt 读回值应与写入时刻一致，不应被数据库会话时区二次换算")
                .isBetween(before.minusSeconds(5), after.plusSeconds(5));

        // 显式钉住「8 小时」这个具体偏移量：双重换算的特征就是正好差 8 小时（28800 秒）
        long driftSeconds = Math.abs(Duration.between(created, after).getSeconds());
        assertThat(driftSeconds)
                .as("若出现接近 28800 秒（8 小时）的偏差，即为 DR-027 预警的双重换算")
                .isLessThan(60);
    }

    @Test
    @DisplayName("expireAt 与 createdAt 是不同列，读回不串（UTC 语义正确）")
    void expireAt_shouldRoundTripInUtcSemantics() {
        LocalDateTime expireAtUtc = LocalDateTime.of(2026, 12, 31, 15, 59, 59);
        LocalDateTime sentinel = LocalDateTime.of(2000, 1, 1, 0, 0, 0);

        // expireAt 显式写入；createdAt/updatedAt 传 null 交由数据库生成
        linkRepository.insert(new Link(null, CODE_A, URL_A, LinkStatus.ACTIVE, false,
                sentinel, sentinel, expireAtUtc));

        Link link = linkRepository.findByShortCode(CODE_A).orElseThrow();

        assertThat(link.expireAt()).isEqualTo(expireAtUtc);
        // createdAt 是数据库生成的真值，绝不能被 expireAt 或 sentinel 覆盖
        assertThat(link.createdAt()).isNotEqualTo(sentinel);
        assertThat(link.createdAt()).isAfter(sentinel);
    }

    @Test
    @DisplayName("expireAt 写入后读回的瞬间不变（DR-032：会话时区为 UTC 时应用写入路径也不应错位）")
    void expireAt_shouldRoundTripWithoutInstantShift() {
        // 选一个带秒、且远离「整点」的值：若存在时区换算，误差会是整小时/8 小时，
        // 用非整点值能让任何秒级错位也无所遁形
        LocalDateTime expireAtUtc = LocalDateTime.of(2026, 12, 31, 15, 59, 59);
        Instant expectedInstant = expireAtUtc.toInstant(ZoneOffset.UTC);

        linkRepository.insert(new Link(null, CODE_A, URL_A, LinkStatus.ACTIVE, false,
                null, null, expireAtUtc));

        LocalDateTime readBack = linkRepository.findByShortCode(CODE_A).orElseThrow().expireAt();
        Instant actualInstant = readBack.toInstant(ZoneOffset.UTC);

        // expire_at 由**应用**写入（不是数据库生成），因此这条路径与 created_at 的成因不同：
        // created_at 的错位来自数据库会话时区，expire_at 的风险则来自驱动读回时的隐式换算。
        // 两条路径都要各自被钉住——「一个通过一个失败」正是双重换算最容易被漏判的特征。
        assertThat(readBack).isEqualTo(expireAtUtc);
        assertThat(actualInstant)
                .as("expire_at 写入与读回的瞬间必须完全相同")
                .isEqualTo(expectedInstant);
        assertThat(Duration.between(expectedInstant, actualInstant).getSeconds())
                .as("若出现非 0 偏移，说明应用写入的 UTC 时间在读回时被换算了一次")
                .isZero();
    }

    // ---------------------------------------------------------------------
    // 3. status 往返（LinkStatusTypeHandler 真实读写 TINYINT）
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("status 往返正确（LinkStatusTypeHandler 真实读写 TINYINT）")
    void status_shouldRoundTripThroughTinyIntColumn() {
        linkRepository.insert(new Link(null, CODE_A, URL_A, LinkStatus.ACTIVE, false, null, null, null));

        // 直接查库确认列里存的是数字，不是字符串（内置 EnumTypeHandler 会存 "ACTIVE"）
        Integer rawStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM short_link WHERE short_code = ?", Integer.class, CODE_A);
        assertThat(rawStatus).isEqualTo(0);

        linkRepository.markDeleted(linkRepository.findByShortCode(CODE_A).orElseThrow().id());

        Integer deletedStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM short_link WHERE short_code = ?", Integer.class, CODE_A);
        assertThat(deletedStatus).isEqualTo(1);
        assertThat(linkRepository.findByShortCode(CODE_A).orElseThrow().status())
                .isEqualTo(LinkStatus.DELETED);
    }

    @Test
    @DisplayName("markDeleted 重复调用第二次影响 0 行（幂等，API.md §7.1 的判定依据）")
    void markDeleted_shouldBeIdempotent() {
        linkRepository.insert(new Link(null, CODE_A, URL_A, LinkStatus.ACTIVE, false, null, null, null));
        long id = linkRepository.findByShortCode(CODE_A).orElseThrow().id();

        assertThat(linkRepository.markDeleted(id)).isEqualTo(1);
        assertThat(linkRepository.markDeleted(id))
                .as("重复删除应影响 0 行，让上层区分「已删除(200)」与「不存在(404)」")
                .isZero();
    }

    // ---------------------------------------------------------------------
    // 4. 多条记录字段不串（防 <constructor> 映射错位）
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("连续插入两条不同 short_code，字段不串（防 constructor 映射错位）")
    void twoRows_shouldNotLeakFieldsAcrossEachOther() {
        linkRepository.insert(new Link(null, CODE_A, URL_A, LinkStatus.ACTIVE, false, null, null, null));
        linkRepository.insert(new Link(null, CODE_B, URL_B, LinkStatus.DELETED, true,
                null, null, LocalDateTime.of(2027, 1, 1, 0, 0, 0)));

        Link a = linkRepository.findByShortCode(CODE_A).orElseThrow();
        Link b = linkRepository.findByShortCode(CODE_B).orElseThrow();

        // 两条记录在每个字段上都不同，任何一项意外相等即说明映射错位
        assertThat(a.shortCode()).isEqualTo(CODE_A);
        assertThat(a.targetUrl()).isEqualTo(URL_A);
        assertThat(a.status()).isEqualTo(LinkStatus.ACTIVE);
        assertThat(a.custom()).isFalse();
        assertThat(a.expireAt()).isNull();

        assertThat(b.shortCode()).isEqualTo(CODE_B);
        assertThat(b.targetUrl()).isEqualTo(URL_B);
        assertThat(b.status()).isEqualTo(LinkStatus.DELETED);
        assertThat(b.custom()).isTrue();
        assertThat(b.expireAt()).isEqualTo(LocalDateTime.of(2027, 1, 1, 0, 0, 0));

        // id 是主键，绝不能相同（相同说明 useGeneratedKeys 回填串了）
        assertThat(a.id()).isNotNull();
        assertThat(b.id()).isNotNull();
        assertThat(a.id()).isNotEqualTo(b.id());
    }

    @Test
    @DisplayName("按主键回查与按短码回查指向同一条记录")
    void findById_andFindByShortCode_shouldBeConsistent() {
        linkRepository.insert(new Link(null, CODE_A, URL_A, LinkStatus.ACTIVE, false, null, null, null));
        long id = linkRepository.findByShortCode(CODE_A).orElseThrow().id();

        Optional<Link> byId = linkRepository.findById(id);

        assertThat(byId).isPresent();
        assertThat(byId.orElseThrow().shortCode()).isEqualTo(CODE_A);
        assertThat(byId.orElseThrow().targetUrl()).isEqualTo(URL_A);
    }

    // ---------------------------------------------------------------------
    // 5. is_custom 的 null 语义（决策 4）
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("is_custom 的 null 在数据库层就被拒绝（NOT NULL），Java 基本类型不会收到 null")
    void isCustom_shouldNeverReceiveNullBecauseColumnIsNotNull() {
        // 列定义：is_custom TINYINT NOT NULL DEFAULT 0。
        // 因此「DB 值为 null」这个状态在数据库层就不存在，Java 侧用基本类型 boolean 是安全的。
        // 本用例用真实 SQL 证明该约束确实生效，而不是靠注释声明。
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO short_link (short_code, target_url, status, is_custom, expire_at)"
                        + " VALUES (?, ?, 0, NULL, NULL)", "itnull1", URL_A))
                .as("is_custom 是 NOT NULL 列，写入 NULL 必须被数据库拒绝")
                .isInstanceOf(DataAccessException.class);

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM short_link WHERE short_code = ?", Integer.class, "itnull1");
        assertThat(count).as("被拒绝的行不应留下痕迹").isZero();

        // 省略 is_custom 时用列默认值 0，读回来是 false
        jdbcTemplate.update(
                "INSERT INTO short_link (short_code, target_url, status, expire_at) VALUES (?, ?, 0, NULL)",
                CODE_A, URL_A);
        assertThat(linkRepository.findByShortCode(CODE_A).orElseThrow().custom())
                .as("省略 is_custom 时用列默认值 0，读回应为 false")
                .isFalse();
    }

    // ---------------------------------------------------------------------
    // 6. 唯一索引兜底（DR-011 / DATABASE.md §6）
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("重复 short_code 被唯一索引拒绝（uk_short_code 是兜底防线）")
    void duplicateShortCode_shouldBeRejectedByUniqueIndex() {
        linkRepository.insert(new Link(null, CODE_A, URL_A, LinkStatus.ACTIVE, false, null, null, null));

        assertThatThrownBy(() -> linkRepository.insert(
                new Link(null, CODE_A, URL_B, LinkStatus.ACTIVE, false, null, null, null)))
                .as("唯一索引 uk_short_code 必须拦住重复短码")
                .isInstanceOf(DataAccessException.class);
    }

    // ---------------------------------------------------------------------
    // 7. 布隆重建扫描（走覆盖索引 idx_status_code）
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("scanActiveShortCodes 只返回 ACTIVE 记录")
    void scanActiveShortCodes_shouldReturnOnlyActiveRows() {
        linkRepository.insert(new Link(null, CODE_A, URL_A, LinkStatus.ACTIVE, false, null, null, null));
        linkRepository.insert(new Link(null, CODE_B, URL_B, LinkStatus.ACTIVE, false, null, null, null));
        linkRepository.markDeleted(linkRepository.findByShortCode(CODE_B).orElseThrow().id());

        assertThat(linkRepository.scanActiveShortCodes(0L, 100)).containsExactly(CODE_A);
    }

    @Test
    @DisplayName("scanActiveShortCodes 的 afterId 游标只返回 id 更大的记录")
    void scanActiveShortCodes_shouldRespectAfterIdCursor() {
        linkRepository.insert(new Link(null, CODE_A, URL_A, LinkStatus.ACTIVE, false, null, null, null));
        long firstId = linkRepository.findByShortCode(CODE_A).orElseThrow().id();
        linkRepository.insert(new Link(null, CODE_B, URL_B, LinkStatus.ACTIVE, false, null, null, null));

        assertThat(linkRepository.scanActiveShortCodes(firstId, 100)).containsExactly(CODE_B);
    }

    // ---------------------------------------------------------------------
    // 8. short_code 排序规则大小写敏感（DR-040）
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("⭐ 仅大小写不同的短码是两个不同记录（DR-040 回归）")
    void shortCodesDifferingOnlyByCaseAreDistinct() {
        // 修复前：short_code 是 utf8mb4_0900_ai_ci，`0Q1A` 与 `0Q1a` 是同一个值，
        // 第二行 insert 抛 DuplicateKeyException，发号器最终返回 503。
        // 实测 D6 造数时 66 个码就出现 24 组冲突（约 36%）。
        linkRepository.insert(new Link(null, CODE_UPPER, URL_UPPER, LinkStatus.ACTIVE, false,
                null, null, null));
        linkRepository.insert(new Link(null, CODE_LOWER, URL_LOWER, LinkStatus.ACTIVE, false,
                null, null, null));

        assertThat(linkRepository.findByShortCode(CODE_UPPER).orElseThrow().targetUrl())
                .as("大写短码必须查到自己的 targetUrl")
                .isEqualTo(URL_UPPER);
        assertThat(linkRepository.findByShortCode(CODE_LOWER).orElseThrow().targetUrl())
                .as("小写短码必须查到自己的 targetUrl，不能被大写记录顶替")
                .isEqualTo(URL_LOWER);
    }

    @Test
    @DisplayName("⭐ 完全相同的短码仍被唯一索引拒绝（ascii_bin 不能放松唯一性）")
    void identicalShortCode_shouldStillBeRejected() {
        linkRepository.insert(new Link(null, CODE_UPPER, URL_UPPER, LinkStatus.ACTIVE, false,
                null, null, null));

        assertThatThrownBy(() -> linkRepository.insert(
                new Link(null, CODE_UPPER, URL_LOWER, LinkStatus.ACTIVE, false, null, null, null)))
                .as("改成大小写敏感不等于放弃唯一性：完全相同的码仍必须被拒绝")
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    @DisplayName("⭐ short_code 的 WHERE 查询走 uk_short_code 索引（ascii_bin 不破坏索引可用性）")
    void shortCodeLookup_shouldUseUniqueIndex() {
        linkRepository.insert(new Link(null, CODE_UPPER, URL_UPPER, LinkStatus.ACTIVE, false,
                null, null, null));

        // EXPLAIN 的 type 必须是 ref/const（走索引），不能是 ALL（全表扫）
        List<Map<String, Object>> plan = jdbcTemplate.queryForList(
                "EXPLAIN SELECT * FROM short_link WHERE short_code = ?", CODE_UPPER);

        assertThat(plan).as("EXPLAIN 应返回一行计划").hasSize(1);
        String type = String.valueOf(plan.get(0).get("type"));
        String key = plan.get(0).get("key") == null ? null : String.valueOf(plan.get(0).get("key"));
        String rows = String.valueOf(plan.get(0).get("rows"));

        assertThat(type)
                .as("short_code 查询不应全表扫（target_url 是 2048 字节的列，全表扫代价极高）")
                .isIn("ref", "const", "eq_ref");
        assertThat(key)
                .as("应命中 uk_short_code 唯一索引")
                .isEqualTo("uk_short_code");
        assertThat(rows).as("唯一索引等值查询应只扫 1 行").isEqualTo("1");
    }

    // ---------------------------------------------------------------------
    // 5. scanActiveWithId：布隆重建的游标扫描（DR-007 假阴性防线）
    // ---------------------------------------------------------------------

    /** 本组测试专用的短码前缀，与 CODE_A/B/UPPER/LOWER 都不冲突。 */
    private static final String SCAN_PREFIX = "its";

    private void cleanupScanCodes() {
        jdbcTemplate.update("DELETE FROM short_link WHERE short_code LIKE ?", SCAN_PREFIX + "%");
    }

    private long insertScanCode(String shortCode, LinkStatus status) {
        linkRepository.insert(new Link(null, shortCode, "https://example.com/scan", status,
                false, null, null, null));
        return linkRepository.findByShortCode(shortCode)
                .orElseThrow(() -> new AssertionError("插入后回查不到: " + shortCode))
                .id();
    }

    @Test
    @DisplayName("⭐ 批次边界：N<limit / N==limit / N>limit 三种都返回 min(N, limit)")
    void scanActiveWithId_shouldRespectBatchLimitAtAllBoundaries() {
        cleanupScanCodes();

        // N=3 < limit=5
        long a = insertScanCode(SCAN_PREFIX + "001", LinkStatus.ACTIVE);
        long b = insertScanCode(SCAN_PREFIX + "002", LinkStatus.ACTIVE);
        long c = insertScanCode(SCAN_PREFIX + "003", LinkStatus.ACTIVE);

        assertThat(linkRepository.scanActiveWithId(0L, 5))
                .as("N=3 < limit=5：应全部返回")
                .hasSize(3);

        // N==limit=5：补 2 条，恰好 5 条
        long d = insertScanCode(SCAN_PREFIX + "004", LinkStatus.ACTIVE);
        long e = insertScanCode(SCAN_PREFIX + "005", LinkStatus.ACTIVE);
        assertThat(linkRepository.scanActiveWithId(0L, 5))
                .as("N=5 == limit=5：应恰好返回 5 条，不多不少")
                .hasSize(5);

        // N>limit：补 1 条变6 条，limit 仍是 5
        long f = insertScanCode(SCAN_PREFIX + "006", LinkStatus.ACTIVE);
        assertThat(linkRepository.scanActiveWithId(0L, 5))
                .as("N=6 > limit=5：必须被截断到 5 条，**返回更多就是 LIMIT 没生效**")
                .hasSize(5);

        // 确认第三个场景确实是 6 条数据，只是不返回第 6 条
        long all = linkRepository.scanActiveWithId(0L, 100).size();
        assertThat(all).as("库里应有 6 条").isEqualTo(6);
        assertThat(linkRepository.scanActiveWithId(0L, 5))
                .as("截断结果里不应含最大 id（应由下一批取）")
                .noneMatch(r -> r.id() == f);
        assertThat(List.of(a, b, c, d, e)).allSatisfy(id ->
                assertThat(linkRepository.scanActiveWithId(0L, 100))
                        .anyMatch(r -> r.id() == id));

        cleanupScanCodes();
    }

    @Test
    @DisplayName("⭐⭐ 游标严格递增：下一批最小 id **严格大于**上一批最大 id（漏扫会让真实短码404）")
    void scanActiveWithId_cursorShouldStrictlyIncrease() {
        cleanupScanCodes();
        for (int i = 1; i <= 10; i++) {
            insertScanCode(SCAN_PREFIX + String.format("%03d", i), LinkStatus.ACTIVE);
        }

        List<LinkRepository.IdShortCode> firstBatch =
                linkRepository.scanActiveWithId(0L, 4);
        List<LinkRepository.IdShortCode> secondBatch =
                linkRepository.scanActiveWithId(firstBatch.get(firstBatch.size() - 1).id(), 4);

        assertThat(firstBatch).hasSize(4);
        assertThat(secondBatch).hasSize(4);

        long firstBatchMax = firstBatch.get(firstBatch.size() - 1).id();
        long secondBatchMin = secondBatch.get(0).id();

        // ⚠️ 必须**严格大于**。若写成 >= 或误用「最后一个 id - 1」，
        // 边界那条记录会被重复取或跳过；跳过的后果是布隆缺该短码
        // -> 判「不存在」-> 真实用户收到 404（DR-007 假阴性故障）
        assertThat(secondBatchMin)
                .as("游标必须严格递增，否则会漏扫或重扫")
                .isGreaterThan(firstBatchMax);

        // 批内也必须严格递增（ORDER BY id 生效）
        assertThat(firstBatch).isSortedAccordingTo(
                java.util.Comparator.comparingLong(LinkRepository.IdShortCode::id));
        assertThat(secondBatch).isSortedAccordingTo(
                java.util.Comparator.comparingLong(LinkRepository.IdShortCode::id));

        // 连续扫 3 批，覆盖全部 10 条且无重复——这是「不漏扫」的直接证据
        java.util.Set<Long> seen = new java.util.HashSet<>();
        long cursor = 0L;
        for (int batchNo = 0; batchNo < 3; batchNo++) {
            List<LinkRepository.IdShortCode> batch =
                    linkRepository.scanActiveWithId(cursor, 4);
            if (batch.isEmpty()) {
                break;
            }
            for (LinkRepository.IdShortCode row : batch) {
                assertThat(seen.add(row.id()))
                        .as("短码 id %d 被重复取到——游标推进有误", row.id())
                        .isTrue();
            }
            cursor = batch.get(batch.size() - 1).id();
        }
        assertThat(seen).as("3 批 × 4 条应覆盖全部 10 条").hasSize(10);

        cleanupScanCodes();
    }

    @Test
    @DisplayName("⭐ 只返回 ACTIVE：DELETED 不出现；全 DELETED 时返回空")
    void scanActiveWithId_shouldReturnOnlyActiveRows() {
        cleanupScanCodes();
        insertScanCode(SCAN_PREFIX + "a1", LinkStatus.ACTIVE);
        insertScanCode(SCAN_PREFIX + "d1", LinkStatus.DELETED);
        insertScanCode(SCAN_PREFIX + "a2", LinkStatus.ACTIVE);
        insertScanCode(SCAN_PREFIX + "d2", LinkStatus.DELETED);

        List<LinkRepository.IdShortCode> rows = linkRepository.scanActiveWithId(0L, 100);

        assertThat(rows).as("DELETED 不应被重建进布隆").hasSize(2);
        assertThat(rows).extracting(LinkRepository.IdShortCode::shortCode)
                .containsExactlyInAnyOrder(SCAN_PREFIX + "a1", SCAN_PREFIX + "a2");

        // 全 DELETED 的边界：必须返回空列表而不是 null
        cleanupScanCodes();
        insertScanCode(SCAN_PREFIX + "x1", LinkStatus.DELETED);
        insertScanCode(SCAN_PREFIX + "x2", LinkStatus.DELETED);

        assertThat(linkRepository.scanActiveWithId(0L, 100))
                .as("全 DELETED 时应返回空列表")
                .isEmpty();

        // 库里其实有 DELETED 数据——确认上面的空不是因为数据没插进去
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM short_link WHERE short_code LIKE ?", Integer.class,
                SCAN_PREFIX + "%")).isPositive();

        cleanupScanCodes();
    }

    @Test
    @DisplayName("⭐⭐ 覆盖索引生效：key=idx_status_code 且 Extra 含 Using index（不回表）")
    void scanActiveWithId_shouldUseCoveringIndexWithoutTableLookup() {
        // ⚠️ EXPLAIN 断言**依赖表里有数据**：MySQL 优化器在空表时会选全表扫
        // （扫 0 行的代价低于走索引），此时 key 会是 NULL，断言必然失败。
        // 因此先插入足够的数据让优化器愿意用索引。
        cleanupScanCodes();
        for (int i = 1; i <= 50; i++) {
            insertScanCode(SCAN_PREFIX + String.format("e%03d", i), LinkStatus.ACTIVE);
        }

        // ⚠️ SQL 必须与 LinkMapper.xml 里selectIdAndShortCodeAfter **逐字一致**，
        // 否则 EXPLAIN 测的是另一条语句、优化器的选择可能不同。
        List<Map<String, Object>> plan = jdbcTemplate.queryForList(
                "EXPLAIN SELECT id, short_code FROM short_link "
                        + "WHERE status = 0 AND id > ? ORDER BY id LIMIT ?", 0L, 10);

        assertThat(plan).as("EXPLAIN 应返回一行计划").hasSize(1);
        Map<String, Object> row = plan.get(0);
        String key = row.get("key") == null ? null : String.valueOf(row.get("key"));
        String extra = String.valueOf(row.get("Extra"));
        String type = String.valueOf(row.get("type"));

        assertThat(key)
                .as("应命中 idx_status_code 覆盖索引（status, short_code）")
                .isEqualTo("idx_status_code");
        assertThat(extra)
                .as("Extra 应含 Using index（覆盖索引，不回表读 target_url）")
                .contains("Using index");
        assertThat(type)
                .as("type 应为 ref（走索引等值+范围），不能是 ALL（全表扫）")
                .isNotEqualTo("ALL");

        // ⚠️ 顺带记录一个**已知性能特征**：Extra 里还有 "Using filesort"。
        // 原因是 idx_status_code 的列顺序为 (status, short_code)，
        // ORDER BY id 无法利用其有序性，排序在内存/文件里完成。
        // 这**不是缺陷**：布隆重建每批 1000 条，排序代价相对 1000 万行的扫描可忽略；
        // 改成 (status, id, short_code) 反而要多维护一个索引。
        // 写在这里是为了免得后人看到 filesort 就误判为缺陷、或盲目加索引。

        cleanupScanCodes();
    }
}
