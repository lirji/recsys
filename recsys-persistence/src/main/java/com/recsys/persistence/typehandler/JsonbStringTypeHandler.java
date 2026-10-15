package com.recsys.persistence.typehandler;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.postgresql.util.PGobject;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/** 将已序列化 JSON 作为 PostgreSQL jsonb 参数绑定，避免 jsonb_build_object 把它存成普通字符串。 */
public final class JsonbStringTypeHandler extends BaseTypeHandler<String> {

    @Override
    public void setNonNullParameter(PreparedStatement statement, int index, String value, JdbcType jdbcType) throws SQLException {
        PGobject json = new PGobject();
        json.setType("jsonb");
        json.setValue(value);
        statement.setObject(index, json);
    }

    @Override
    public String getNullableResult(ResultSet result, String column) throws SQLException {
        return result.getString(column);
    }

    @Override
    public String getNullableResult(ResultSet result, int column) throws SQLException {
        return result.getString(column);
    }

    @Override
    public String getNullableResult(CallableStatement statement, int column) throws SQLException {
        return statement.getString(column);
    }
}
