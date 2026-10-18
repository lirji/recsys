package com.recsys.offline.persistence;

import com.recsys.persistence.spring.SpringSqlSessions;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 离线跨库复制的短期源会话；沿用PG环境参数，不新增连接池或改变源库选择。 */
public final class OfflineSourceSessions {
    private OfflineSourceSessions() { }

    /** XML路径只能由调用作业固定指定；SqlSessionTemplate每次调用后释放无池连接。 */
    public static SqlSessionTemplate create(String database, String mapperResource) {
        return create(database, mapperResource, "");
    }

    /** C18源读取显式限制连接及网络等待，其他作业原路径保持不变。 */
    public static SqlSessionTemplate createBounded(String database, String mapperResource) {
        return create(database, mapperResource, "?connectTimeout=5&socketTimeout=10&tcpKeepAlive=true");
    }

    private static SqlSessionTemplate create(String database, String mapperResource, String parameters) {
        var dataSource = new DriverManagerDataSource(
                "jdbc:postgresql://" + env("PG_HOST", "localhost") + ":" + env("PG_PORT", "5432") + "/" + database + parameters,
                env("PG_USER", "recsys"), env("PG_PASSWORD", "recsys"));
        dataSource.setDriverClassName("org.postgresql.Driver");
        try {
            return new SqlSessionTemplate(SpringSqlSessions.create(dataSource, mapperResource));
        } catch (Exception e) {
            throw new IllegalStateException("离线源Mapper装配失败", e);
        }
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }
}
