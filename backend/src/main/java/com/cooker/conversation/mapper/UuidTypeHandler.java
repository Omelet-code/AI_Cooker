package com.cooker.conversation.mapper;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

/** PostgreSQL UUID 映射器：JDBC 驱动返回 UUID 或其标准文本形式。 */
public class UuidTypeHandler extends BaseTypeHandler<UUID> {
    @Override public void setNonNullParameter(PreparedStatement ps, int index, UUID value, JdbcType jdbcType) throws SQLException {
        ps.setObject(index, value);
    }
    @Override public UUID getNullableResult(ResultSet rs, String column) throws SQLException { return parse(rs.getObject(column)); }
    @Override public UUID getNullableResult(ResultSet rs, int column) throws SQLException { return parse(rs.getObject(column)); }
    @Override public UUID getNullableResult(CallableStatement cs, int column) throws SQLException { return parse(cs.getObject(column)); }
    private UUID parse(Object value) {
        if (value == null) return null;
        return value instanceof UUID uuid ? uuid : UUID.fromString(value.toString());
    }
}
