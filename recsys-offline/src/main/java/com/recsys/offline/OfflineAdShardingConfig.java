package com.recsys.offline;

import com.recsys.persistence.spring.PrimaryMybatisConfiguration;
import com.recsys.persistence.spring.AdDbMybatisConfiguration;
import com.recsys.persistence.spring.DerivedMybatisConfiguration;
import com.recsys.persistence.spring.ShardedMybatisConfiguration;
import com.zaxxer.hikari.HikariDataSource;
import com.recsys.offline.persistence.mapper.primary.PositiveFeedbackMapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
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
 * 四路MyBatis会话复用同一个原DataSource对象；JdbcTemplate仅在逐作业迁移期间保留。
 */
@Configuration
@Import({PrimaryMybatisConfiguration.class, AdDbMybatisConfiguration.class,
        DerivedMybatisConfiguration.class, ShardedMybatisConfiguration.class})
@MapperScan(basePackageClasses=PositiveFeedbackMapper.class,sqlSessionTemplateRef="sqlSessionTemplate")
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

    /** 未迁移作业继续绑定原主库，避免过渡阶段出现双写入口。 */
    @Bean
    @Primary
    public JdbcTemplate jdbcTemplate(@Qualifier("dataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
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

    /** 分片作业逐个迁移前继续使用同一逻辑数据源。 */
    @Bean(name = "adShardingJdbc")
    public JdbcTemplate adShardingJdbc(@Qualifier("adShardingDataSource") DataSource ds) {
        return new JdbcTemplate(ds);
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

    /** 保留尚未迁移的广告事件/向量写作业入口。 */
    @Bean(name = "adDbJdbc")
    public JdbcTemplate adDbJdbc(@Qualifier("adDbDataSource") DataSource ds) {
        return new JdbcTemplate(ds);
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

    /** 派生库旧作业与新Mapper在过渡期共享同一连接池。 */
    @Bean(name = "derivedJdbc")
    public JdbcTemplate derivedJdbc(@Qualifier("derivedDbDataSource") DataSource ds) {
        return new JdbcTemplate(ds);
    }

    private static String env(String k, String def) {
        String v = System.getenv(k);
        return v == null || v.isBlank() ? def : v;
    }
}
