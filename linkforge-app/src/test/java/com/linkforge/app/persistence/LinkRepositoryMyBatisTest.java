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
@EnabledIfEnvironmentVariable(named = "RUN_MYBATIS_IT", matches = "true")
@DisplayName("仓储层真实 MyBatis 集成测试（DR-031）")
class LinkRepositoryMyBatisTest {

    @Autowired
    private LinkRepository linkRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 专用短码，避免与任何真实数据撞码。 */
    private static final String CODE_A = "ita0001";
    private static final String CODE_B = "ita0002";

    private static final String URL_A = "https://example.com/it/alpha";
    private static final String URL_B = "https://example.com/it/beta?x=1";

    @BeforeEach
    void cleanUp() {
        // 测试库可能残留上一轮数据，先清干净保证断言可重复
        jdbcTemplate.update("DELETE FROM short_link WHERE short_code IN (?, ?)", CODE_A, CODE_B);
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
}
