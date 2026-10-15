package com.recsys.advertiser;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import com.recsys.persistence.spring.SpringSqlSessions;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * advertiser主源由Boot按spring.datasource创建ShardingSphere，派生向量读源沿原环境配置单独构造。
 * 派生DataSource保持非Bean、无池生命周期，直接交给MyBatis工厂，不新增DataSource Bean触发Boot退避。
 * 主分片与派生Mapper仍绑定不同会话工厂，删除迁移期间的JdbcTemplate包装不改变资源归属。
 */
@Configuration
public class AdvertiserDataSourceConfig {

    /** 原派生源仍由宿主构造，Boot只看到主DataSource Bean并继续正常自动装配。 */
    @Bean
    public SqlSessionFactory derivedSqlSessionFactory() throws Exception {
        String db = env("DERIVED_PG_DB", env("PG_DB", "recsys"));
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:postgresql://" + env("PG_HOST", "localhost") + ":" + env("PG_PORT", "5432") + "/" + db,
                env("PG_USER", "recsys"), env("PG_PASSWORD", "recsys"));
        ds.setDriverClassName("org.postgresql.Driver");
        return SpringSqlSessions.create(ds, "classpath*:mapper/*/derived/**/*.xml");
    }

    private static String env(String k, String def) {
        String v = System.getenv(k);
        return v == null || v.isBlank() ? def : v;
    }
}
