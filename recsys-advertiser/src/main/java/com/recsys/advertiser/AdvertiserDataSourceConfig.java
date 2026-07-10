package com.recsys.advertiser;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;

/**
 * advertiser 数据源装配(#3 rec-serving 派生向量库)。advertiser 自身的 {@code spring.datasource} 是
 * ShardingSphere(广告分片目录);另需读 rec-serving 的 {@code item_embedding}(灌 ad_embedding / 目录事件带向量)。
 *
 * <p>做法:主 {@code @Primary jdbcTemplate} 仍包 Spring 自动装配的 ShardingSphere DataSource(不声明 DataSource
 * bean → 不触发 DataSourceAutoConfiguration 退避);另加 {@code derivedJdbc}(普通 PG,env {@code DERIVED_PG_DB}
 * 未设 → {@code PG_DB} → recsys,默认与主库同库、行为不变;设则读 rec-serving 派生库)。DataSource 在 @Bean 方法内
 * new(非 bean),故不干扰 ShardingSphere 主源自动装配。
 */
@Configuration
public class AdvertiserDataSourceConfig {

    @Bean
    @Primary
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean(name = "derivedJdbc")
    public JdbcTemplate derivedJdbc() {
        String db = env("DERIVED_PG_DB", env("PG_DB", "recsys"));
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:postgresql://" + env("PG_HOST", "localhost") + ":" + env("PG_PORT", "5432") + "/" + db,
                env("PG_USER", "recsys"), env("PG_PASSWORD", "recsys"));
        ds.setDriverClassName("org.postgresql.Driver");
        return new JdbcTemplate(ds);
    }

    private static String env(String k, String def) {
        String v = System.getenv(k);
        return v == null || v.isBlank() ? def : v;
    }
}
