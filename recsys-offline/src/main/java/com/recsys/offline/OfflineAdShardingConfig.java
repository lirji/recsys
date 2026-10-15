package com.recsys.offline;

import com.recsys.persistence.spring.PrimaryMybatisConfiguration;
import com.recsys.persistence.spring.AdDbMybatisConfiguration;
import com.recsys.persistence.spring.DerivedMybatisConfiguration;
import com.recsys.persistence.spring.ShardedMybatisConfiguration;
import com.zaxxer.hikari.HikariDataSource;
import com.recsys.offline.persistence.OfflinePersistenceConfiguration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

import javax.sql.DataSource;

/**
 * 宿主四路数据源的所有权与连接设置集中于此，Mapper工厂复用实际DataSource对象。
 * 主源承载普通目录/行为读取；逻辑分片承载广告目录；adDb承载广告自有数据；derived承载派生向量。
 * 显式主DataSource标记Primary，保持现有无Qualifier注入与事务管理器的资源选择。
 * 所有业务SQL已迁入各自Mapper/XML，无需保留过渡JdbcTemplate Bean。
 */
@Configuration
@Import({PrimaryMybatisConfiguration.class, AdDbMybatisConfiguration.class,
        DerivedMybatisConfiguration.class, ShardedMybatisConfiguration.class, OfflinePersistenceConfiguration.class})
public class OfflineAdShardingConfig {

    /** 保留宿主主库配置来源，避免持久框架自行创建第二套连接池。 */
    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource")
    public DataSourceProperties dataSourceProperties() {
        return new DataSourceProperties();
    }

    /** 主库连接池仍归offline宿主管理，MyBatis只复用该资源。 */
    @Bean
    @Primary
    public DataSource dataSource(@Qualifier("dataSourceProperties") DataSourceProperties props) {
        return props.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    }

    /** 保留原ShardingSphere逻辑源与环境占位规则，不能改成直连某个物理分片。 */
    @Bean(name = "adShardingDataSource")
    public DataSource adShardingDataSource() throws Exception {
        SimpleDriverDataSource ds = new SimpleDriverDataSource();
        ds.setDriver((java.sql.Driver)
                Class.forName("org.apache.shardingsphere.driver.ShardingSphereDriver")
                        .getDeclaredConstructor().newInstance());
        // placeholder-type=environment:sharding.yaml 的 $${PG_HOST::}/$${PG_PORT::} 从环境变量解析(5.5 默认 none 不替换)
        ds.setUrl("jdbc:shardingsphere:classpath:sharding.yaml?placeholder-type=environment");
        return ds;
    }

    /**
     * #3 ad-serving 物理拆库(离线侧):{@code ad_event}/{@code ad_embedding} 的 ad-serving 自有库数据源,
     * 供教学写作业(sim-ad-events/seed-ads)写入。{@code AD_PG_DB} 未设 → {@code PG_DB} → {@code recsys}
     * (默认与主库同库,行为不变);设 {@code AD_PG_DB=recsys_ad} 即写 ad-serving 自有库。
     * 离线读 {@code ad_event} 的分析作业改读 {@code ad_event_log} 读仓(见 SyncAdEventLogJob / AdEventQuery),不用本源。
     */
    @Bean(name = "adDbDataSource")
    public DataSource adDbDataSource() {
        String db = env("AD_PG_DB", env("PG_DB", "recsys"));
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl("jdbc:postgresql://" + env("PG_HOST", "localhost") + ":" + env("PG_PORT", "5432") + "/" + db);
        ds.setUsername(env("PG_USER", "recsys"));
        ds.setPassword(env("PG_PASSWORD", "recsys"));
        ds.setDriverClassName("org.postgresql.Driver");
        ds.setConnectionInitSql("SET hnsw.ef_search = 200");
        ds.setMaximumPoolSize(Integer.parseInt(env("AD_DB_POOL_MAX", "10")));
        return ds;
    }

    /**
     * #3 rec-serving 派生向量库(离线侧):烘焙者(backfill-embedding/import-tower/import-semantic-id/user-embedding/
     * backfill-multimodal)+ 读者(seed-ads/lookalike/data-quality)读写 item_embedding/item_tower_embedding/
     * item_semantic_id/user_embedding 走此源。{@code DERIVED_PG_DB} 未设 → {@code PG_DB} → recsys(默认不变)。
     */
    @Bean(name = "derivedDbDataSource")
    public DataSource derivedDbDataSource() {
        String db = env("DERIVED_PG_DB", env("PG_DB", "recsys"));
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl("jdbc:postgresql://" + env("PG_HOST", "localhost") + ":" + env("PG_PORT", "5432") + "/" + db);
        ds.setUsername(env("PG_USER", "recsys"));
        ds.setPassword(env("PG_PASSWORD", "recsys"));
        ds.setDriverClassName("org.postgresql.Driver");
        ds.setConnectionInitSql("SET hnsw.ef_search = 200");
        ds.setMaximumPoolSize(Integer.parseInt(env("DERIVED_DB_POOL_MAX", "20")));
        return ds;
    }

    private static String env(String k, String def) {
        String v = System.getenv(k);
        return v == null || v.isBlank() ? def : v;
    }
}
