package com.linkforge.core.infrastructure.persistence;

import com.linkforge.core.domain.Link;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * {@link com.linkforge.core.repository.LinkRepository} 的 MyBatis 实现接口（docs/DECISIONS.md DR-021）。
 *
 * <p>放在 {@code core} 而非 {@code redirect}：admin 删除短链同样要访问这张表，
 * 放 redirect 会让 admin 要么依赖 redirect（违反 DR-018），要么看不到实现（Spring 装配失败）。
 *
 * <p>SQL 写在 {@code core/src/main/resources/mapper/LinkMapper.xml}，不写注解 SQL：
 * 表结构、索引与执行计划是本项目要讲清楚的重点，XML 里更便于写清「这条 SQL 走哪个索引」。
 *
 * <p>{@code @Mapper} 是双保险：真正让本接口被注册的是启动类
 * {@code com.linkforge.app.LinkForgeApplication} 上的
 * {@code @MapperScan("com.linkforge.core.infrastructure.persistence")}。
 * 为什么必须显式扫描，见该类 javadoc（组件扫描与 Mapper 扫描是两套机制）。
 */
@Mapper
public interface LinkMapper {

    /** 按短码查询；返回 {@code null} 表示未命中（MyBatis 约定）。不过滤状态，交由上层判定 404 / 410。 */
    Link selectByShortCode(@Param("shortCode") String shortCode);

    /** 按主键查询。 */
    Link selectById(@Param("id") long id);

    /**
     * 插入新记录，并把数据库生成的自增主键回填到入参对象（{@code useGeneratedKeys}）。
     * {@code created_at} / {@code updated_at} 由数据库默认值生成，不在此处赋值。
     */
    int insertLink(Link link);

    /**
     * 逻辑删除：{@code status} 置为 1。
     *
     * @return 影响行数；0 表示记录不存在或已是删除态
     */
    int updateStatusToDeleted(@Param("id") long id);

    /**
     * 布隆过滤器重建：游标分批拉取有效短码。
     *
     * <p>只 {@code SELECT short_code}，走覆盖索引 {@code idx_status_code(status, short_code)}，
     * 避免读取 {@code target_url}（最长 2048 字节）造成大量无谓 IO。
     * 用 {@code id > afterId ORDER BY id LIMIT} 推进，避免大 OFFSET 的深分页代价。
     */
    List<String> selectActiveShortCodesAfter(@Param("afterId") long afterId, @Param("limit") int limit);

    /**
     * 布隆重建：游标分批拉取 {@code (id, shortCode)}。
     *
     * <p>⚠️ <b>为什么必须返回 id</b>：只返回短码时调用方无法知道本批的
     * {@code id > afterId} 该推进到哪里，只能「按批大小猜」。
     * 猜错的后果是漏扫——重建出的布隆缺一批短码，
     * 那些短码会被判「不存在」→ <b>真实用户收到 404</b>（DR-007 的假阴性故障）。
     *
     * <p>⚠️ <b>ORDER BY 必须与 afterId 语义一致</b>：{@code ORDER BY id} 让
     * 「本批最大 id」就是下一轮的 {@code afterId}，游标严格单调推进。
     * 若改成 {@code ORDER BY short_code}，id 与短码的顺序无关，游标会来回跳。
     */
    List<IdShortCode> selectIdAndShortCodeAfter(@Param("afterId") long afterId,
                                                 @Param("limit") int limit);

    /**
     * 批量按短码查主键（统计落库用，阶段 3）。
     *
     * <p>只 {@code SELECT id, short_code}，走唯一索引 {@code uk_short_code}，
     * <b>不回表</b>读 {@code target_url}（2048 字节 × 批量 = 无谓 IO）。
     *
     * <p>⚠️ <b>不过滤 status</b>：软删除的短链仍有历史访问量，统计不该丢。
     *
     * <p>⚠️ <b>入参必须由调用方保证非空且去重</b>：{@code IN ()} 是 SQL 语法错误，
     * MyBatis 只会把空 foreach 渲染成空括号，直接抛 SQL 异常。
     * 去重是必要的：flush 每分钟对同一批 dirty 短码反复查询，
     * 重复短码会让 IN 列表膨胀到超出 {@code max_allowed_packet}。
     */
    List<IdShortCode> selectIdsByShortCodes(@Param("shortCodes") List<String> shortCodes);

    /**
     * {@code (id, shortCode)} 行。
     *
     * <p>⚠️ 必须提供两个 getter 而不只是 record 访问器：
     * MyBatis 的默认对象映射走 <b>setter</b>，record 的访问器 {@code id()}
     * 不被识别（除非显式配 {@code argNameBasedConstructorAutoMapping}），
     * 结果是静默返回 {@code null} 而不是报错——很难排查。
     */
    class IdShortCode {
        private long id;
        private String shortCode;

        public long getId() {
            return id;
        }

        public void setId(long id) {
            this.id = id;
        }

        public String getShortCode() {
            return shortCode;
        }

        public void setShortCode(String shortCode) {
            this.shortCode = shortCode;
        }
    }
}
