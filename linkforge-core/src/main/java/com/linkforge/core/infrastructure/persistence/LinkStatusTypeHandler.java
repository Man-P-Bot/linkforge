package com.linkforge.core.infrastructure.persistence;

import com.linkforge.core.domain.LinkStatus;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;

/**
 * {@link LinkStatus} 与数据库 {@code TINYINT} 之间的转换。
 *
 * <p>为什么必须自己写：MyBatis 内置的 {@code EnumTypeHandler} 默认按 {@code name()} 存**字符串**
 * （"ACTIVE"/"DELETED"），而本表的 {@code status} 是 {@code TINYINT}（0/1，
 * 见 docs/DATABASE.md §4.1：刻意不用 ENUM 列）。两者不匹配，直接用内置处理器会在运行时报类型错误。
 *
 * <p>反解走 {@link LinkStatus#fromCode(int)}：遇到未知取值**抛异常而不是静默当成 ACTIVE**——
 * 静默降级会让一条已删除的短链重新可跳转，这是数据正确性问题，必须早失败。
 */
@MappedTypes(LinkStatus.class)
@MappedJdbcTypes(JdbcType.TINYINT)
public class LinkStatusTypeHandler extends BaseTypeHandler<LinkStatus> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, LinkStatus parameter, JdbcType jdbcType)
            throws SQLException {
        ps.setInt(i, parameter.code());
    }

    @Override
    public LinkStatus getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return toStatus(rs.getInt(columnName), rs.wasNull());
    }

    @Override
    public LinkStatus getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return toStatus(rs.getInt(columnIndex), rs.wasNull());
    }

    @Override
    public LinkStatus getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return toStatus(cs.getInt(columnIndex), cs.wasNull());
    }

    private LinkStatus toStatus(int code, boolean wasNull) {
        if (wasNull) {
            throw new IllegalStateException("short_link.status 为 NULL，但该列定义为 NOT NULL，数据已损坏");
        }
        return LinkStatus.fromCode(code);
    }

    /** 供 XML 中显式指定 {@code javaType} 时使用，保持与 MyBatis 默认构造约定一致。 */
    public static final int JDBC_TYPE = Types.TINYINT;
}
