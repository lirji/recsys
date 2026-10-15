package com.recsys.persistence.typehandler;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

import java.sql.Array;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;

/** text[] 保留顺序、重复项与元素 NULL；SQL NULL 是否变为空列表由各业务映射决定。 */
public final class TextListTypeHandler extends BaseTypeHandler<List<String>> {

    @Override
    public void setNonNullParameter(PreparedStatement statement, int index, List<String> value, JdbcType jdbcType) throws SQLException {
        statement.setArray(index, statement.getConnection().createArrayOf("text", value.toArray(String[]::new)));
    }

    @Override
    public List<String> getNullableResult(ResultSet result, String column) throws SQLException {
        return read(result.getArray(column));
    }

    @Override
    public List<String> getNullableResult(ResultSet result, int column) throws SQLException {
        return read(result.getArray(column));
    }

    @Override
    public List<String> getNullableResult(CallableStatement statement, int column) throws SQLException {
        return read(statement.getArray(column));
    }

    private static List<String> read(Array array) throws SQLException {
        if (array == null) {
            return null;
        }
        try {
            return Arrays.asList((String[]) array.getArray());
        } finally {
            array.free();
        }
    }
}
