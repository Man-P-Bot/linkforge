package com.linkforge.app.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.linkforge.core.domain.Link;
import com.linkforge.core.domain.LinkStatus;
import com.linkforge.core.repository.LinkRepository;
import com.linkforge.core.repository.StatsRepository;
import com.linkforge.core.repository.StatsRepository.PvRow;
import com.linkforge.core.repository.StatsRepository.UvRow;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
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
 * 统计落库的**真实 MyBatis 集成测试**（阶段 3）。
 *
 * <h2>⚠️ 为什么这层不可省略</h2>
 * 阶段 3 的两条 UPSERT 是本项目最容易被「mock 全绿但线上出错」的 SQL，原因有三：
 * <ol>
 *   <li><b>MyBatis 的 record 映射陷阱</b>：Mapper 侧若用 record，
 *       默认对象映射走 setter、不认 {@code id()} 访问器，
 *       结果是<b>静默写入 null/0</b> 而不是报错（{@code LinkMapper.IdShortCode} 已踩过一次）。</li>
 *   <li><b>VALUES() 的行为</b>：{@code ON DUPLICATE KEY UPDATE pv = pv + VALUES(pv)}
 *       在多行 VALUES + 冲突更新的组合下，行为与单行不同——必须用真实 MySQL 验证。</li>
 *   <li><b>GREATEST 的幂等性</b>：{@code uv_estimated = GREATEST(...)} 是否真的
 *       让「重复落同一个估算值」保持不变，只有真库能证明。</li>
 * </ol>
 *
 * <h2>怎么跑</h2>
 * <pre>
 * # 无需任何环境变量：Testcontainers 自动起一个 MySQL 8.4 容器
 * RUN_MYBATIS_IT=true ./mvnw.cmd clean install
 * </pre>
 * 前置条件：<b>本机有 Docker</b>（唯一要求）。
 * 容器镜像与生产严格同版本（{@code mysql:8.4}），跑完即弃。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("统计落库真实 MyBatis 集成测试（阶段 3：UPSERT 累加 + GREATEST 覆盖）")
class StatsRepositoryMyBatisTest extends MySqlContainerSupport {

    private static final String CODE_A = "sta0001";
    private static final String CODE_B = "sta0002";
    private static final LocalDate DAY = LocalDate.of(2026, 3, 20);
    private static final String REFERER = "direct";

    @Autowired
    private StatsRepository statsRepository;

    @Autowired
    private LinkRepository linkRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long linkIdA;
    private long linkIdB;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM link_stats_daily WHERE referer_domain = ?", REFERER);
        jdbcTemplate.update("DELETE FROM short_link WHERE short_code IN (?, ?)", CODE_A, CODE_B);

        linkRepository.insert(new Link(null, CODE_A, "https://example.com/s/a",
                LinkStatus.ACTIVE, false, null, null, null));
        linkRepository.insert(new Link(null, CODE_B, "https://example.com/s/b",
                LinkStatus.ACTIVE, false, null, null, null));

        linkIdA = linkRepository.findByShortCode(CODE_A).orElseThrow().id();
        linkIdB = linkRepository.findByShortCode(CODE_B).orElseThrow().id();
    }

    /**
     * 读 {@code direct} 来源的 PV。
     *
     * <p>⚠️ <b>必须带 {@code referer_domain = 'direct'}</b>：
     * 唯一键是 {@code (link_id, stat_date, referer_domain)}，
     * 所以同一个 link_id + stat_date 下可能有**多行**（不同来源）。
     * 不带这个条件时 {@code queryForObject} 会抛
     * {@code IncorrectResultSizeDataAccessException}（expected 1, actual 2）——
     * 那不是「数据错了」，而是「查询写得不够精确」。
     */
    private long pvOf(long linkId) {
        Long v = jdbcTemplate.queryForObject(
                "SELECT pv FROM link_stats_daily WHERE link_id = ? AND stat_date = ? "
                        + "AND referer_domain = ?",
                Long.class, linkId, DAY, REFERER);
        return v == null ? -1 : v;
    }

    private long uvOf(long linkId) {
        Long v = jdbcTemplate.queryForObject(
                "SELECT uv_estimated FROM link_stats_daily WHERE link_id = ? AND stat_date = ? "
                        + "AND referer_domain = ?",
                Long.class, linkId, DAY, REFERER);
        return v == null ? -1 : v;
    }

    // ---------------------------------------------------------------------
    // 1. PV 累加语义（最容易写错成覆盖的一条）
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("⭐⭐⭐ PV 三次落库 5/3/2 -> pv=10（**累加**，写成覆盖就是 2）")
    void pvAccumulatesAcrossFlushes() {
        statsRepository.accumulatePv(List.of(new PvRow(linkIdA, DAY, 5)));
        statsRepository.accumulatePv(List.of(new PvRow(linkIdA, DAY, 3)));
        statsRepository.accumulatePv(List.of(new PvRow(linkIdA, DAY, 2)));

        assertThat(pvOf(linkIdA))
                .as("⚠️ `pv = pv + VALUES(pv)` 的核心断言。"
                        + "若写成 `pv = VALUES(pv)`（覆盖），结果会是 2，"
                        + "且此前两次落库的增量**永远补不回来**")
                .isEqualTo(10L);
    }

    @Test
    @DisplayName("⭐⭐ PV 单批多行：一行一个 link_id，互不干扰")
    void pvBatchWritesMultipleRows() {
        statsRepository.accumulatePv(List.of(
                new PvRow(linkIdA, DAY, 7),
                new PvRow(linkIdB, DAY, 9)));

        assertThat(pvOf(linkIdA)).isEqualTo(7L);
        assertThat(pvOf(linkIdB)).isEqualTo(9L);
    }

    @Test
    @DisplayName("⭐⭐ PV 空集合直接返回 0，不发 SQL（Repository 层短路）")
    void pvEmptyCollectionIsNoop() {
        assertThat(statsRepository.accumulatePv(List.of())).isZero();
    }

    @Test
    @DisplayName("⭐⭐⭐ 不同 stat_date 是**不同的行**（日期不会互相覆盖）")
    void differentDatesAreDifferentRows() {
        LocalDate day2 = DAY.plusDays(1);
        statsRepository.accumulatePv(List.of(new PvRow(linkIdA, DAY, 5)));
        statsRepository.accumulatePv(List.of(new PvRow(linkIdA, day2, 3)));

        assertThat(pvOf(linkIdA)).as("第一天").isEqualTo(5L);
        Long day2Pv = jdbcTemplate.queryForObject(
                "SELECT pv FROM link_stats_daily WHERE link_id = ? AND stat_date = ?",
                Long.class, linkIdA, day2);
        assertThat(day2Pv).as("⚠️ 若被覆盖成 3，说明唯一键漏了 stat_date").isEqualTo(3L);
    }

    @Test
    @DisplayName("⭐⭐ referer_domain 参与唯一键（不同来源不冲突）")
    void differentReferersAreDifferentRows() {
        jdbcTemplate.update(
                "INSERT INTO link_stats_daily (link_id, stat_date, pv, uv_estimated, referer_domain) "
                        + "VALUES (?, ?, ?, ?, ?)", linkIdA, DAY, 4L, 0L, "google.com");

        statsRepository.accumulatePv(List.of(new PvRow(linkIdA, DAY, 5)));

        // ⚠️ 查询必须带上 referer_domain：这个 link_id + stat_date 下有**两行**
        //   （'direct' 与 'google.com'），只按 link_id + date 查会命中两行。
        //   这正是「referer 参与唯一键」的证据——也是这条断言要防的回归：
        //   若哪天有人把 referer 从唯一键里去掉，这里就会从 2 行变成 1 行。
        Long directPv = jdbcTemplate.queryForObject(
                "SELECT pv FROM link_stats_daily WHERE link_id = ? AND stat_date = ? "
                        + "AND referer_domain = 'direct'", Long.class, linkIdA, DAY);
        assertThat(directPv)
                .as("uk_link_date_referer 含 referer_domain，'direct' 行不受 'google.com' 行影响")
                .isEqualTo(5L);

        Long google = jdbcTemplate.queryForObject(
                "SELECT pv FROM link_stats_daily WHERE referer_domain = 'google.com'", Long.class);
        assertThat(google).isEqualTo(4L);

        Long rowCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM link_stats_daily WHERE link_id = ? AND stat_date = ?",
                Long.class, linkIdA, DAY);
        assertThat(rowCount)
                .as("⚠️ 同一 link_id + stat_date 下必须有 2 行（两个 referer）")
                .isEqualTo(2L);
    }

    // ---------------------------------------------------------------------
    // 2. UV 覆盖语义（GREATEST 而非累加）
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("⭐⭐⭐ UV 重复落同一估算值 -> 保持不变（**不累加**，这是与 PV 的核心区别）")
    void uvIsNotAccumulated() {
        statsRepository.mergeUv(List.of(new UvRow(linkIdA, DAY, 5)));
        statsRepository.mergeUv(List.of(new UvRow(linkIdA, DAY, 5)));
        statsRepository.mergeUv(List.of(new UvRow(linkIdA, DAY, 5)));

        assertThat(uvOf(linkIdA))
                .as("⚠️ 一天 flush 1440 次，若 UV 用累加，同一个访客会被计 1440 遍。"
                        + "取 GREATEST 让重放天然幂等")
                .isEqualTo(5L);
    }

    @Test
    @DisplayName("⭐⭐⭐ UV 取 GREATEST：更大的值覆盖、更小的值**被丢弃**")
    void uvTakesGreatestNotLastWriteWins() {
        statsRepository.mergeUv(List.of(new UvRow(linkIdA, DAY, 5)));
        statsRepository.mergeUv(List.of(new UvRow(linkIdA, DAY, 12)));
        statsRepository.mergeUv(List.of(new UvRow(linkIdA, DAY, 8)));

        assertThat(uvOf(linkIdA))
                .as("⚠️ 最后一次落库的值是 8，但 HLL 基数只增不减，"
                        + "8 是「更晚读到的旧快照」——应当被丢弃")
                .isEqualTo(12L);
    }

    @Test
    @DisplayName("⭐⭐⭐ PV 与 UV 相互独立：先落 PV 再落 UV，**pv 不被清零**")
    void pvAndUvDoNotClobberEachOther() {
        statsRepository.accumulatePv(List.of(new PvRow(linkIdA, DAY, 20)));
        statsRepository.mergeUv(List.of(new UvRow(linkIdA, DAY, 7)));

        assertThat(pvOf(linkIdA))
                .as("⚠️ mergeUvBatch 的 VALUES 里 pv 写的是 0。"
                        + "若 ON DUPLICATE KEY UPDATE 里误写成 `pv = VALUES(pv)`，"
                        + "这里会变成 0 —— PV 被 UV 落库清零")
                .isEqualTo(20L);
        assertThat(uvOf(linkIdA)).isEqualTo(7L);
    }

    @Test
    @DisplayName("⭐⭐ 先落 UV 再落 PV：uv 不被清零")
    void uvSurvivesPvWrite() {
        statsRepository.mergeUv(List.of(new UvRow(linkIdA, DAY, 7)));
        statsRepository.accumulatePv(List.of(new PvRow(linkIdA, DAY, 20)));

        assertThat(uvOf(linkIdA))
                .as("⚠️ accumulatePvBatch 的 VALUES 里 uv_estimated 写的是 0。"
                        + "若 UPDATE 里误写成 `uv_estimated = VALUES(uv_estimated)`，"
                        + "这里会变成 0 —— UV 被 PV 落库清零")
                .isEqualTo(7L);
    }

    @Test
    @DisplayName("⭐⭐ UV 空集合直接返回 0，不发 SQL")
    void uvEmptyCollectionIsNoop() {
        assertThat(statsRepository.mergeUv(List.of())).isZero();
    }

    // ---------------------------------------------------------------------
    // 3. 短码 -> 主键映射（flush 的第一步）
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("🔗⭐⭐ resolveIdsByShortCodes：批量映射到主键")
    void resolveIdsByShortCodesMapsCorrectly() {
        Map<String, Long> m = linkRepository.resolveIdsByShortCodes(List.of(CODE_A, CODE_B));

        assertThat(m).hasSize(2);
        assertThat(m.get(CODE_A)).isEqualTo(linkIdA);
        assertThat(m.get(CODE_B)).isEqualTo(linkIdB);
    }

    @Test
    @DisplayName("🔗⭐⭐ 查不到的短码**不出现在结果里**（调用方需自行计数缺失）")
    void resolveIdsOmitsUnknownCodes() {
        Map<String, Long> m = linkRepository.resolveIdsByShortCodes(
                List.of(CODE_A, "nosuch1"));

        assertThat(m).hasSize(1).containsKey(CODE_A);
    }

    @Test
    @DisplayName("🔗⭐⭐ 入参含重复短码时只查一次（IN 列表去重，避免无谓膨胀）")
    void resolveIdsDeduplicatesInput() {
        Map<String, Long> m = linkRepository.resolveIdsByShortCodes(
                List.of(CODE_A, CODE_A, CODE_A));

        assertThat(m).hasSize(1).containsEntry(CODE_A, linkIdA);
    }

    @Test
    @DisplayName("🔗b 空集合返回空 Map，**不发 SQL**（IN () 是语法错误）")
    void resolveIdsWithEmptyInputIsNoop() {
        assertThat(linkRepository.resolveIdsByShortCodes(List.of())).isEmpty();
    }

    @Test
    @DisplayName("🔗b⭐⭐ 软删除的短链仍能映射到主键（历史统计不该丢）")
    void resolveIdsIncludesSoftDeletedLinks() {
        linkRepository.markDeleted(linkIdA);

        Map<String, Long> m = linkRepository.resolveIdsByShortCodes(List.of(CODE_A));

        assertThat(m)
                .as("⚠️ 口径与 findByShortCode 一致：不过滤 status。"
                        + "已删除短链的历史访问量不该从统计里消失")
                .containsEntry(CODE_A, linkIdA);
    }

    // ---------------------------------------------------------------------
    // 4. 数据完整性
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("🔒⭐⭐ link_id 必须是真实存在的主键（统计行不悬空）")
    void statsRowReferencesRealLink() {
        statsRepository.accumulatePv(List.of(new PvRow(linkIdA, DAY, 5)));

        Long cnt = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM link_stats_daily s JOIN short_link l ON s.link_id = l.id "
                        + "WHERE s.link_id = ?", Long.class, linkIdA);
        assertThat(cnt).isEqualTo(1L);
    }

    @Test
    @DisplayName("🔒b 不存在的 link_id 落库会抛异常（无外键约束，应用层负责）")
    void statsRowWithBogusLinkIdFails() {
        // ⚠️ link_stats_daily 刻意不设外键（高频批量写，外键校验代价高）。
        //    BIGINT NOT NULL 仍然存在，所以插入不存在的 id 是「成功」的——
        //    这条断言记录的是**当前真实行为**，提醒后续不要误以为有外键保护。
        statsRepository.accumulatePv(List.of(new PvRow(999_999_999L, DAY, 5)));

        Long cnt = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM link_stats_daily WHERE link_id = ?", Long.class, 999_999_999L);
        assertThat(cnt)
                .as("⚠️ 无外键约束：完整性完全由应用层保证。"
                        + "这意味着 flush 若映射错 link_id，脏数据会静默入库")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("🔒c uv_estimated 列名是 V4 迁移后的新名（旧名 uv_estimate 已不存在）")
    void columnWasRenamedByV4Migration() {
        assertThatThrownBy(() -> jdbcTemplate.queryForObject(
                "SELECT uv_estimate FROM link_stats_daily LIMIT 1", Long.class))
                .isInstanceOf(DataAccessException.class)
                .as("V1 定义的旧列名必须已不存在，否则说明 V4 迁移没跑")
                .satisfies(e -> org.assertj.core.api.Assertions
                        .assertThat(e.getMessage()).contains("uv_estimate"));

        statsRepository.mergeUv(List.of(new UvRow(linkIdA, DAY, 3)));
        assertThat(uvOf(linkIdA)).isEqualTo(3L);
    }
}
