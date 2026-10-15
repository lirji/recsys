package com.recsys.persistence.typehandler;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

import java.sql.Array;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/** integer[] 使用驱动数组传参，保留元素顺序及 NULL，不拼接数值文本。 */
public final class IntegerArrayTypeHandler extends BaseTypeHandler<Integer[]> {

    @Override
    public void setNonNullParameter(PreparedStatement statement, int index, Integer[] value, JdbcType jdbcType) throws SQLException {
        statement.setArray(index, statement.getConnection().createArrayOf("integer", value));
    }

    @Override
    public Integer[] getNullableResult(ResultSet result, String column) throws SQLException {
        return read(result.getArray(column));
    }

    @Override
    public Integer[] getNullableResult(ResultSet result, int column) throws SQLException {
        return read(result.getArray(column));
    }

    @Override
    public Integer[] getNullableResult(CallableStatement statement, int column) throws SQLException {
        return read(statement.getArray(column));
    }

    private static Integer[] read(Array array) throws SQLException {
        if (array == null) {
            return null;
        }
        try {
            return (Integer[]) array.getArray();
        } finally {
            array.free();
        }
    }
}
