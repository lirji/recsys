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
        // placeholder-type=environment:sharding.yaml 的 $${PG_HOST::}/$${PG_PORT::} 从环境变量解析(5.5 默认 none 不替换)
        ds.setUrl("jdbc:shardingsphere:classpath:sharding.yaml?placeholder-type=environment");
        return ds;
    }

    @Bean(name = "adShardingJdbc")
    public JdbcTemplate adShardingJdbc(@Qualifier("adShardingDataSource") DataSource ds) {
        return new JdbcTemplate(ds);
    }

    /**
     * #3 ad-serving 物理拆库:{@code ad_event}/{@code ad_embedding}/{@code ad_servable}(ad-serving 自有,非分片)
     * 专用数据源。{@code AD_PG_DB} 未设 → 退回 {@code PG_DB} → {@code recsys}(默认与主库同库,行为完全不变);
     * 设 {@code AD_PG_DB=recsys_ad} 即把这三张表读写指向 ad-serving 自有库(DB-per-service)。
     *
     * <p>注:主 {@code @Primary} 仍留 {@code item}/{@code item_embedding}/{@code user_embedding} 等<b>共享读</b>
     * (AdRepository 读 user_embedding、rank ContentService 读 item、AdEmbeddingSimilarity 读 item_embedding),
     * 故不能整体搬主库——只把三张 ad 自有表切到本数据源。含 ad_embedding ANN 所需的 {@code hnsw.ef_search}。
     */
    @Bean(name = "adDbDataSource")
    public DataSource adDbDataSource() {
        String db = env("AD_PG_DB", env("PG_DB", "recsys"));
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(pgUrl(env("PG_HOST", "localhost"), env("PG_PORT", "5432"), db));
        ds.setUsername(env("PG_USER", "recsys"));
        ds.setPassword(env("PG_PASSWORD", "recsys"));
        ds.setDriverClassName("org.postgresql.Driver");
        ds.setConnectionInitSql(annInitSql());   // ad_embedding pgvector ANN 检索宽度 + 服务端 statement_timeout
        ds.setMaximumPoolSize(Integer.parseInt(env("AD_DB_POOL_MAX", "10")));
        return ds;
    }

    @Bean(name = "adDbJdbc")
    public JdbcTemplate adDbJdbc(@Qualifier("adDbDataSource") DataSource ds) {
        return new JdbcTemplate(ds);
    }

    /**
     * #3 rec-serving 派生向量读模型库:{@code item_embedding}/{@code item_tower_embedding}/{@code item_semantic_id}/
     * {@code user_embedding}(离线烘焙的派生工件,走"读模型复制")专用数据源。{@code DERIVED_PG_DB} 未设 → {@code PG_DB}
     * → {@code recsys}(默认与主库同库,行为不变);设 {@code DERIVED_PG_DB=recsys_vec} 即把这四张表读写指向 rec-serving
     * 自有派生库。含 pgvector ANN 所需 {@code hnsw.ef_search}。主 {@code @Primary} 仍留 item_local/user_behavior 等读。
     */
    @Bean(name = "derivedDbDataSource")
    public DataSource derivedDbDataSource() {
        String db = env("DERIVED_PG_DB", env("PG_DB", "recsys"));
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(pgUrl(env("PG_HOST", "localhost"), env("PG_PORT", "5432"), db));
        ds.setUsername(env("PG_USER", "recsys"));
        ds.setPassword(env("PG_PASSWORD", "recsys"));
        ds.setDriverClassName("org.postgresql.Driver");
        ds.setConnectionInitSql(annInitSql());   // item_* pgvector ANN 检索宽度 + 服务端 statement_timeout
        ds.setMaximumPoolSize(Integer.parseInt(env("DERIVED_DB_POOL_MAX", "20")));
        return ds;
    }

    /**
     * 派生/ad 快查数据源的 JDBC URL,带底层 {@code socketTimeout}/{@code connectTimeout}(秒)。
     * <p>这是"彻底回收"的关键:PG 网络挂起时 socket 读默认无限阻塞,{@code cancel(true)} 对阻塞的 socket 读无效,
     * 召回工作线程被永久占住;设 socketTimeout 后读超时即抛 SQLException → 连接被 Hikari 弃用、线程被真正回收。
     */
    private static String pgUrl(String host, String port, String db) {
        return "jdbc:postgresql://" + host + ":" + port + "/" + db
                + "?socketTimeout=" + env("PG_SOCKET_TIMEOUT", "30")
                + "&connectTimeout=" + env("PG_CONNECT_TIMEOUT", "5");
    }

    /**
     * ANN 快查数据源连接初始化:pgvector 检索宽度 + <b>服务端 {@code statement_timeout}(毫秒)</b>。
     * <p>statement_timeout 让慢查询在服务端按 ms 粒度中止(比秒级 socketTimeout 更快回收慢池线程),
     * 仅用于 derived/adDb 这类<b>快查</b>数据源(向量 ANN 单查亚秒级);较重的主库不设此紧界(见 application.yml)。
     */
    private static String annInitSql() {
        return "SET hnsw.ef_search = 200; SET statement_timeout = " + env("PG_ANN_STATEMENT_TIMEOUT_MS", "2000");
    }

    @Bean(name = "derivedJdbc")
    public JdbcTemplate derivedJdbc(@Qualifier("derivedDbDataSource") DataSource ds) {
        return new JdbcTemplate(ds);
    }

    private static String env(String k, String def) {
        String v = System.getenv(k);
        return v == null || v.isBlank() ? def : v;
    }
}
