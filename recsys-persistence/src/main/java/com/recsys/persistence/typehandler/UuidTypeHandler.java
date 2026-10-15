package com.recsys.persistence.typehandler;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/** UUID以数据库原生类型绑定，读取时保留NULL；不作为普通varchar比较UUID列。 */
public final class UuidTypeHandler extends BaseTypeHandler<UUID> {
    @Override
    public void setNonNullParameter(PreparedStatement statement, int index, UUID value, JdbcType jdbcType) throws SQLException {
        statement.setObject(index, value);
    }
    @Override
    public UUID getNullableResult(ResultSet result, String column) throws SQLException {
        return parse(result.getString(column));
    }
    @Override
    public UUID getNullableResult(ResultSet result, int column) throws SQLException {
        return parse(result.getString(column));
    }
    @Override
    public UUID getNullableResult(CallableStatement statement, int column) throws SQLException {
        return parse(statement.getString(column));
    }
    private static UUID parse(String value) { return value == null ? null : UUID.fromString(value); }
}
