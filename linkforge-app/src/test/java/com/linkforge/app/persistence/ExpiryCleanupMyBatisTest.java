package com.linkforge.app.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.linkforge.core.domain.Link;
import com.linkforge.core.domain.LinkStatus;
import com.linkforge.core.repository.LinkRepository;
import com.linkforge.core.repository.LinkRepository.ExpiredLink;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * 过期清理的**真实 MyBatis 集成测试**（阶段 4）。
 *
 * <h2>⚠️ 为什么这层不可省略</h2>
 * 阶段 4 的核心是一条 SQL 与一个索引：
 * <pre>
 *   SELECT id, short_code, expire_at FROM short_link
 *   WHERE status = 0 AND expire_at &lt; ? ORDER BY id LIMIT ?
 * </pre>
 * <ul>
 *   <li><b>索引是否真的被用上</b>：mock 仓储测不到 EXPLAIN，
 *       而「看起来有索引、实际是全表扫」是本项目真实踩过的坑
 *       （docs/TROUBLESHOOTING.md T-011：rows 从 97,181 降到 2,000）。</li>
 *   <li><b>{@code expire_at IS NULL} 的排除</b>：永久有效的短链
 *       （{@code expire_at IS NULL}）必须被排除，且不能报错。</li>
 *   <li><b>软删除的幂等性</b>：{@code WHERE status = 0} 兜底的效果，
 *       只能靠真实 UPDATE 的影响行数验证。</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("过期清理真实 MyBatis 集成测试（阶段 4：扫描索引 + 不改状态契约）")
class ExpiryCleanupMyBatisTest extends MySqlContainerSupport {

    private static final String CODE_A = "exp0001";
    private static final String CODE_B = "exp0002";
    private static final String CODE_C = "exp0003";

    @Autowired
    private LinkRepository linkRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM short_link WHERE short_code IN (?, ?, ?)",
                CODE_A, CODE_B, CODE_C);
    }

    private long insert(String code, LocalDateTime expireAtUtc) {
        linkRepository.insert(new Link(null, code, "https://example.com/e/" + code,
                LinkStatus.ACTIVE, false, null, null, expireAtUtc));
        return linkRepository.findByShortCode(code).orElseThrow().id();
    }

    // ---------------------------------------------------------------------
    // 1. 扫描
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("🔍⭐⭐⭐ 扫描只返回「已过期且未删除」的短链")
    void scanReturnsOnlyExpiredAndActive() {
        Instant now = Instant.parse("2026-03-20T10:00:00Z");
        long expiredId = insert(CODE_A, LocalDateTime.of(2026, 3, 19, 0, 0));
        insert(CODE_B, LocalDateTime.of(2026, 3, 21, 0, 0));   // 未过期
        insert(CODE_C, null);                                    // 永久有效

        List<ExpiredLink> found = linkRepository.scanExpired(100, now);

        assertThat(found).hasSize(1);
        assertThat(found.get(0).id()).isEqualTo(expiredId);
        assertThat(found.get(0).shortCode()).isEqualTo(CODE_A);
    }

    @Test
    @DisplayName("🔍b 永久有效的短链（expire_at IS NULL）被排除，**不报错**")
    void permanentLinksAreExcluded() {
        Instant now = Instant.parse("2026-03-20T10:00:00Z");
        insert(CODE_C, null);

        assertThat(linkRepository.scanExpired(100, now))
                .as("⚠️ SQL 里 expire_at < ? 对 NULL 求值为 NULL（不为真），"
                        + "所以永久有效的短链天然被排除")
                .isEmpty();
    }

    @Test
    @DisplayName("🔍c 已软删除的短链不再被扫到（不该反复清理同一条）")
    void softDeletedLinksAreNotScanned() {
        Instant now = Instant.parse("2026-03-20T10:00:00Z");
        long id = insert(CODE_A, LocalDateTime.of(2026, 3, 19, 0, 0));
        linkRepository.markDeleted(id);

        assertThat(linkRepository.scanExpired(100, now))
                .as("⚠️ 软删除后 status=1，扫描条件 status=0 会跳过它。"
                        + "这条断言是「清理幂等」的数据库侧保证")
                .isEmpty();
    }

    @Test
    @DisplayName("🔍d limit 生效（单批上限）")
    void scanRespectsLimit() {
        Instant now = Instant.parse("2026-03-20T10:00:00Z");
        insert(CODE_A, LocalDateTime.of(2026, 3, 19, 0, 0));
        insert(CODE_B, LocalDateTime.of(2026, 3, 19, 0, 0));

        assertThat(linkRepository.scanExpired(1, now)).hasSize(1);
    }

    @Test
    @DisplayName("🔍e 按 id 升序返回（清理顺序稳定，便于测试断言）")
    void scanReturnsOrderedById() {
        Instant now = Instant.parse("2026-03-20T10:00:00Z");
        long idA = insert(CODE_A, LocalDateTime.of(2026, 3, 19, 0, 0));
        long idB = insert(CODE_B, LocalDateTime.of(2026, 3, 19, 0, 0));

        List<ExpiredLink> found = linkRepository.scanExpired(100, now);

        assertThat(found).hasSize(2);
        assertThat(found.get(0).id()).isEqualTo(Math.min(idA, idB));
        assertThat(found.get(1).id()).isEqualTo(Math.max(idA, idB));
    }

    // ---------------------------------------------------------------------
    // 2. 索引：这是阶段 4 的核心断言
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("⚡⭐⭐⭐ 扫描查询**能走 idx_expire_status 索引**（不是全表扫描）")
    void scanQueryUsesExpireIndex() {
        // ⚠️⚠️ **不能用「优化器实际选了哪个索引」来断言**。
        //
        //   原因：优化器的选择基于**成本模型 + 表统计信息**。
        //   本测试库里只有几条数据，优化器算出来「全表扫 1 行」比「走索引再回表」更便宜，
        //   于是它会选 idx_status_code（type=ref）甚至 ALL。
        //   **这不是索引没用**——是表太小，优化器的正确选择。
        //
        //   实测：20 万行时它选 idx_expire_status（type=range, rows=2000）；
        //   1 行时它选 idx_status_code（type=ref, rows=1）。
        //   完整数据见 docs/TROUBLESHOOTING.md T-011。
        //
        //   所以这里断言两件**与数据量无关**的事：
        //   ① possible_keys 里有它 —— 优化器**有资格**用它；
        //   ② FORCE INDEX 时 type=range 且结果正确 —— 它**确实能**定位到目标行。
        List<Map<String, Object>> plan = jdbcTemplate.queryForList(
                "EXPLAIN SELECT id, short_code, expire_at FROM short_link "
                        + "WHERE status = 0 AND expire_at < NOW() LIMIT 100");
        assertThat(plan).hasSize(1);

        String possibleKeys = String.valueOf(plan.get(0).get("possible_keys"));
        assertThat(possibleKeys)
                .as("⚠️ 优化器的候选索引必须包含 idx_expire_status——"
                        + "这说明「这个查询有索引可用」。实际执行计划：%s", plan.get(0))
                .contains("idx_expire_status");

        // ② 强制走这个索引，验证它真的能做范围扫描
        List<Map<String, Object>> forced = jdbcTemplate.queryForList(
                "EXPLAIN SELECT id, short_code, expire_at FROM short_link "
                        + "FORCE INDEX (idx_expire_status) "
                        + "WHERE status = 0 AND expire_at < NOW() LIMIT 100");
        assertThat(String.valueOf(forced.get(0).get("key")))
                .as("FORCE INDEX 后必须真的用上它。执行计划：%s", forced.get(0))
                .isEqualTo("idx_expire_status");
        assertThat(String.valueOf(forced.get(0).get("type")))
                .as("⚠️ type 必须是 range（范围扫描），"
                        + "不能是 ALL（全表扫）也不能是 ref（等值前缀 + 回表过滤）"
                        + "——ref 意味着 expire_at 不在索引里，得回表才能过滤")
                .isEqualTo("range");
    }

    @Test
    @DisplayName("⚡b FORCE INDEX 走 idx_expire_status 时，**结果与不加索引时一致**")
    void forcedIndexReturnsSameRows() {
        // ⚠️ 这一条比 EXPLAIN 更实在：它证明「走这个索引」不是只出现在执行计划里，
        //   而是真能返回正确的结果集（索引里的列覆盖了查询需要的列）。
        Instant now = Instant.parse("2026-03-20T10:00:00Z");
        insert(CODE_A, LocalDateTime.of(2026, 3, 19, 0, 0));
        insert(CODE_B, LocalDateTime.of(2026, 3, 21, 0, 0));

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, short_code FROM short_link FORCE INDEX (idx_expire_status) "
                        + "WHERE status = 0 AND expire_at < ?", now);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("short_code")).isEqualTo(CODE_A);
    }

    @Test
    @DisplayName("⚡c V5 迁移已生效：idx_expire_status 索引存在（**列顺序是 status, expire_at**）")
    void expireIndexExists() {
        // ⚠️ 必须用 COUNT(DISTINCT index_name) 而不是 COUNT(*)：
        //    information_schema.statistics 是**每个索引的每一列一行**，
        //    复合索引 (status, expire_at) 会有 2 行。
        //    用 COUNT(*) 会得到 2，误判成「索引没建好」。
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics "
                        + "WHERE table_schema = DATABASE() AND table_name = 'short_link' "
                        + "AND index_name = 'idx_expire_status'",
                Integer.class);

        assertThat(count)
                .as("⚠️ V5 迁移必须已应用。没有这个索引，过期扫描会退化成伪索引全表扫")
                .isEqualTo(1);

        // 列顺序也必须对：status 是等值前缀，expire_at 承担范围扫描
        List<String> columns = jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.statistics "
                        + "WHERE table_schema = DATABASE() AND table_name = 'short_link' "
                        + "AND index_name = 'idx_expire_status' ORDER BY seq_in_index",
                String.class);

        assertThat(columns)
                .as("⚠️ 列顺序必须是 (status, expire_at)："
                        + "status 做等值前缀、expire_at 承担范围扫描")
                .containsExactly("status", "expire_at");
    }

    // ---------------------------------------------------------------------
    // 3. 软删除
    // ---------------------------------------------------------------------

    // ---------------------------------------------------------------------
    // 4. 核心契约：过期清理**不修改数据库**
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("🔒⭐⭐⭐ 扫描过期短链**不改变 status**（保住 410 语义）")
    void scanDoesNotChangeStatus() {
        Instant now = Instant.parse("2026-03-20T10:00:00Z");
        long id = insert(CODE_A, LocalDateTime.of(2026, 3, 19, 0, 0));

        // 扫描只是「读」，绝不写
        List<ExpiredLink> found = linkRepository.scanExpired(100, now);
        assertThat(found).hasSize(1);

        Link after = linkRepository.findByShortCode(CODE_A).orElseThrow();
        assertThat(after.status())
                .as("⚠️ **核心契约**：过期清理不修改 status。"
                        + "若它把 status 置为 DELETED，对外状态码会从 410 变成 404"
                        + "——410 的价值正是告诉调用方「这个码曾经有效」")
                .isEqualTo(LinkStatus.ACTIVE);
        assertThat(after.id()).isEqualTo(id);
    }

    @Test
    @DisplayName("🔒b 过期短链**仍然能被扫描到**（不改状态 ⇒ 每轮都会扫到，是幂等的）")
    void expiredLinkIsScannableRepeatedly() {
        Instant now = Instant.parse("2026-03-20T10:00:00Z");
        insert(CODE_A, LocalDateTime.of(2026, 3, 19, 0, 0));

        // ⚠️ 第一次扫描
        assertThat(linkRepository.scanExpired(100, now)).hasSize(1);
        // ⚠️ 第二次扫描——同样能扫到。
        //    因为 status 没变，所以清理是**幂等**的：
        //    清缓存做两遍也只是多删一次不存在的 key。
        assertThat(linkRepository.scanExpired(100, now))
                .as("⚠️ 不改状态 ⇒ 每次都会扫到。这是幂等的，"
                        + "不是 bug：清理的副作用只有「删缓存」，删两次无害")
                .hasSize(1);
    }

    @Test
    @DisplayName("🔒c 过期短链的统计映射**不受影响**（link_id 仍可解析）")
    void expiredLinkStillResolvesForStats() {
        Instant now = Instant.parse("2026-03-20T10:00:00Z");
        long id = insert(CODE_A, LocalDateTime.of(2026, 3, 19, 0, 0));
        linkRepository.scanExpired(100, now);

        Map<String, Long> m = linkRepository.resolveIdsByShortCodes(List.of(CODE_A));

        assertThat(m)
                .as("⚠️ 阶段 3 的口径：resolveIdsByShortCodes 不过滤 status。"
                        + "过期短链的历史访问量仍要能落到它的 link_id 上")
                .containsEntry(CODE_A, id);
    }
}
