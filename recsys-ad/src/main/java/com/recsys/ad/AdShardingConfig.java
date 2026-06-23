package com.recsys.ad;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

import javax.sql.DataSource;

/**
 * 读侧分库分表数据源装配(rec-engine 通过扫描 {@code recsys-ad} 生效;其它未依赖本模块的 app 不受影响)。
 *
 * <p><b>双数据源</b>:
 * <ul>
 *   <li><b>主</b>({@code @Primary} {@code dataSource}/{@code jdbcTemplate}):普通 Postgres(spring.datasource),
 *       承载召回 / 排序 / embedding / pgvector ANN / ad_event 等绝大多数 SQL —— 行为完全不变,
 *       pgvector 的 {@code <=>} 等不进 ShardingSphere 解析路径。</li>
 *   <li><b>次</b>({@code adShardingDataSource}/{@code adShardingJdbc}):ShardingSphere-JDBC,
 *       仅供 {@link AdRepository}/{@link PacingService}/{@link CreativeSelector} 读分片广告表
 *       (advertiser/ad/bidword/ad_creative),按分片键路由到 ds_0/ds_1。</li>
 * </ul>
 *
 * <p>定义了自有 DataSource 会让 Spring Boot 的 DataSource/JdbcTemplate 自动配置退避,故主、次都在此显式声明;
 * 主标 {@code @Primary} 让无 {@code @Qualifier} 的注入仍拿到普通库(其余模块零改动)。
 */
@Configuration
public class AdShardingConfig {

    /** 主库连接参数(沿用 spring.datasource.*;url→jdbcUrl 的差异由 DataSourceProperties 处理)。 */
    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource")
    public org.springframework.boot.autoconfigure.jdbc.DataSourceProperties dataSourceProperties() {
        return new org.springframework.boot.autoconfigure.jdbc.DataSourceProperties();
    }

    @Bean
    @Primary
    public DataSource dataSource(
            @Qualifier("dataSourceProperties")
            org.springframework.boot.autoconfigure.jdbc.DataSourceProperties props) {
        return props.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    }

    @Bean
    @Primary
    public JdbcTemplate jdbcTemplate(@Qualifier("dataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    /** ShardingSphere 逻辑数据源(driver 自管 ds_0/ds_1 内部连接池,这里用无池 driver 包一层即可)。 */
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
