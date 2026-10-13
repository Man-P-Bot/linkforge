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
}
