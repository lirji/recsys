package com.recsys.persistence.typehandler;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

import java.sql.Array;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/** float8[] 使用驱动数组传参，保留元素顺序及 NULL，不拼接数值文本。 */
public final class DoubleArrayTypeHandler extends BaseTypeHandler<Double[]> {

    @Override
    public void setNonNullParameter(PreparedStatement statement, int index, Double[] value, JdbcType jdbcType) throws SQLException {
        statement.setArray(index, statement.getConnection().createArrayOf("float8", value));
    }

    @Override
    public Double[] getNullableResult(ResultSet result, String column) throws SQLException {
        return read(result.getArray(column));
    }

    @Override
    public Double[] getNullableResult(ResultSet result, int column) throws SQLException {
        return read(result.getArray(column));
    }

    @Override
    public Double[] getNullableResult(CallableStatement statement, int column) throws SQLException {
        return read(statement.getArray(column));
    }

    private static Double[] read(Array array) throws SQLException {
        if (array == null) {
            return null;
        }
        try {
            return (Double[]) array.getArray();
        } finally {
            array.free();
        }
    }
}
