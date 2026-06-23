package com.recsys.offline;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

import javax.sql.DataSource;

/**
 * 离线侧分库分表数据源装配(与读侧 {@code recsys-ad/AdShardingConfig} 同构;offline 不依赖 recsys-ad,故独立一份)。
 *
 * <ul>
 *   <li><b>主</b>({@code @Primary} {@code dataSource}/{@code jdbcTemplate}):普通 Postgres(ds_0=recsys)。
 *       绝大多数作业(import/embedding/cf/样本/eval、以及 {@code ad_event} 的校准/报表/EE/质量度)走它,行为不变。</li>
 *   <li><b>次</b>({@code adShardingJdbc}):ShardingSphere-JDBC,仅广告分片表(advertiser/ad/bidword/ad_creative)
 *       的读写走它(seed-ads/sim-ad-events/ad-ocpc),按分片键路由到 ds_0/ds_1。</li>
 * </ul>
 *
 * <p>定义了自有 DataSource 会让 Spring Boot 自动配置退避,故主、次都显式声明、主标 {@code @Primary}
 * (其它作业注入 {@code JdbcTemplate} 零改动)。
 */
@Configuration
public class OfflineAdShardingConfig {

    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource")
    public DataSourceProperties dataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean
    @Primary
    public DataSource dataSource(@Qualifier("dataSourceProperties") DataSourceProperties props) {
        return props.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    }

    @Bean
    @Primary
    public JdbcTemplate jdbcTemplate(@Qualifier("dataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean(name = "adShardingDataSource")
    public DataSource adShardingDataSource() throws Exception {
        SimpleDriverDataSource ds = new SimpleDriverDataSource();
        ds.setDriver((java.sql.Driver)
                Class.forName("org.apache.shardingsphere.driver.ShardingSphereDriver")
                        .getDeclaredConstructor().newInstance());
        ds.setUrl("jdbc:shardingsphere:classpath:sharding.yaml");
        return ds;
    }

    @Bean(name = "adShardingJdbc")
    public JdbcTemplate adShardingJdbc(@Qualifier("adShardingDataSource") DataSource ds) {
        return new JdbcTemplate(ds);
    }
}
