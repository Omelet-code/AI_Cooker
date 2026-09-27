package com.cooker.recipe.mapper;

import java.sql.Array;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

/** 只读资格查询把 PostgreSQL 文本数组转换成命中的规范原料名。 */
public class StringArrayListTypeHandler extends BaseTypeHandler<List<String>> {
    @Override public void setNonNullParameter(PreparedStatement ps, int i, List<String> value, JdbcType jdbcType) throws SQLException {
        ps.setArray(i, ps.getConnection().createArrayOf("text", value.toArray()));
    }
    @Override public List<String> getNullableResult(ResultSet rs, String columnName) throws SQLException { return read(rs.getArray(columnName)); }
    @Override public List<String> getNullableResult(ResultSet rs, int columnIndex) throws SQLException { return read(rs.getArray(columnIndex)); }
    @Override public List<String> getNullableResult(CallableStatement cs, int columnIndex) throws SQLException { return read(cs.getArray(columnIndex)); }
    private List<String> read(Array array) throws SQLException {
        return array == null ? List.of() : Arrays.asList((String[]) array.getArray());
    }
}
