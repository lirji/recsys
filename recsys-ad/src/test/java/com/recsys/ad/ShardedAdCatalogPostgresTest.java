package com.recsys.ad;

import com.recsys.common.ad.AdCandidate;
import com.recsys.ad.persistence.config.AdPersistenceConfiguration;
import com.recsys.ad.persistence.mapper.sharded.AdCatalogMapper;
import com.recsys.ad.persistence.mapper.addb.AdEmbeddingMapper;
import com.recsys.ad.persistence.mapper.derived.AdUserEmbeddingMapper;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.shardingsphere.driver.api.yaml.YamlShardingSphereDataSourceFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 两个独占PG实例执行项目真实ShardingSphere规则，不能以单库直连替代路由验证。 */
@Testcontainers
class ShardedAdCatalogPostgresTest {

    @Container
    static final PostgreSQLContainer<?> SHARD0 = new PostgreSQLContainer<>("postgres:16-alpine");
    @Container
    static final PostgreSQLContainer<?> SHARD1 = new PostgreSQLContainer<>("postgres:16-alpine");

    private static DataSource sharded;
    private static AnnotationConfigApplicationContext context;
    private static ShardedAdCatalogReader catalog;
    private static AdRepository repository;

    @BeforeAll
    static void prepare() throws Exception {
        HikariDataSource ds0 = source(SHARD0);
        HikariDataSource ds1 = source(SHARD1);
        JdbcTemplate zero = new JdbcTemplate(ds0);
        JdbcTemplate one = new JdbcTemplate(ds1);
        for (JdbcTemplate jdbc : List.of(zero, one)) {
            create(jdbc, "advertiser", "advertiser_id BIGINT PRIMARY KEY, status TEXT", "advertiser_id", "status");
            create(jdbc, "ad", "ad_id BIGINT PRIMARY KEY, advertiser_id BIGINT, item_id BIGINT, title TEXT, quality_score DOUBLE PRECISION, "
                    + "optimization_type TEXT, target_cpa DOUBLE PRECISION, audience_id BIGINT, status TEXT, review_status TEXT",
                    "ad_id", "advertiser_id", "item_id", "title", "quality_score", "optimization_type", "target_cpa", "audience_id", "status", "review_status");
            create(jdbc, "bidword", "id BIGINT PRIMARY KEY, ad_id BIGINT, keyword TEXT, bid DOUBLE PRECISION", "id", "ad_id", "keyword", "bid");
            create(jdbc, "ad_creative", "creative_id BIGINT PRIMARY KEY, ad_id BIGINT", "creative_id", "ad_id");
        }
        zero.update("INSERT INTO advertiser VALUES (2,'active'),(4,'paused'),(6,'active')");
        one.update("INSERT INTO advertiser VALUES (1,'active'),(3,'active')");
        zero.update("INSERT INTO ad VALUES (102,2,12,'even',1,'CPC',NULL,22,'active','approved'),"
                + "(104,4,14,'owner-paused',1,'CPC',0,NULL,'active','approved'),"
                + "(106,6,16,'review-pending',1,'CPC',0,NULL,'active','pending'),"
                + "(108,2,18,NULL,NULL,NULL,NULL,NULL,'active','approved')");
        one.update("INSERT INTO ad VALUES (101,1,11,'odd',1.5,'OCPC',3,21,'active','approved'),"
                + "(103,3,13,'paused',1,'CPC',0,NULL,'paused','approved'),"
                + "(111,1,19,'extra',2,'CPC',0,NULL,'active','approved')");
        zero.update("INSERT INTO bidword VALUES (201,102,'space',2),(202,102,'space',4),(205,108,'missing',NULL)");
        one.update("INSERT INTO bidword VALUES (203,101,'alien',5),(204,111,'space',3)");
        String yaml;
        try (var input = ShardedAdCatalogPostgresTest.class.getResourceAsStream("/sharding.yaml")) {
            yaml = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        // 只替换物理连接，规则/绑定表/算法与项目资源相同；不访问共享开发库。
        sharded = YamlShardingSphereDataSourceFactory.createDataSource(Map.of("ds_0", ds0, "ds_1", ds1),
                yaml.substring(yaml.indexOf("\nrules:") + 1).getBytes(StandardCharsets.UTF_8));
        context = new AnnotationConfigApplicationContext();
        context.getBeanFactory().registerSingleton("adShardingDataSource", sharded);
        context.getBeanFactory().registerSingleton("adDbDataSource", ds0);
        context.getBeanFactory().registerSingleton("derivedDbDataSource", ds1);
        context.register(AdPersistenceConfiguration.class);
        context.refresh();
        catalog = new ShardedAdCatalogReader(context.getBean(AdCatalogMapper.class));
        repository = new AdRepository(context.getBean(AdUserEmbeddingMapper.class),
                context.getBean(AdEmbeddingMapper.class), context.getBean(AdCatalogMapper.class), catalog);
    }

    @AfterAll
    static void close() throws Exception {
        if (context != null) context.close();
        if (sharded instanceof AutoCloseable closeable) closeable.close();
    }

    @Test
    void shardedFactoryUsesTheOriginalLogicalDataSource() {
        SqlSessionFactory factory = context.getBean("shardedSqlSessionFactory", SqlSessionFactory.class);
        assertThat(factory.getConfiguration().getEnvironment().getDataSource()).isSameAs(sharded);
        assertThat(factory.getConfiguration().hasMapper(AdCatalogMapper.class)).isTrue();
        assertThat(factory.getConfiguration().hasMapper(AdEmbeddingMapper.class)).isFalse();
    }

    @Test
    void filteredAndFullCatalogQueriesKeepServingRulesAcrossBothShards() {
        assertThat(catalog.activeAdDetails(null)).containsOnlyKeys(101L, 102L, 108L, 111L);
        assertThat(catalog.activeAdDetails(List.of(101L, 102L, 103L, 104L, 106L)))
                .containsOnlyKeys(101L, 102L);
        assertThat(catalog.activeAdDetails(List.of(108L)).get(108L).quality()).isZero();
        assertThat(catalog.activeAdDetails(List.of())).isEmpty();
    }

    @Test
    void titlesOptimizationAndAudiencesPreserveNullAndEmptySemantics() {
        assertThat(catalog.titles(List.of(101L, 102L, 108L, 999L))).containsEntry(101L, "odd").containsEntry(102L, "even")
                .containsEntry(108L, null).doesNotContainKey(999L);
        assertThat(catalog.ocpcParams(List.of(101L, 108L))).containsEntry(101L, new AdRepository.OcpcParams("OCPC", 3))
                .containsEntry(108L, new AdRepository.OcpcParams("CPC", 0));
        assertThat(catalog.audiencesByAd(List.of(101L, 102L, 108L))).containsExactlyInAnyOrderEntriesOf(Map.of(101L, 21L, 102L, 22L));
        assertThat(catalog.titles(List.of())).isEmpty();
        assertThat(catalog.ocpcParams(List.of())).isEmpty();
        assertThat(catalog.audiencesByAd(null)).isEmpty();
    }

    @Test
    void groupedBidsAndHotRecallMergeBothShards() {
        assertThat(catalog.bidMap(null)).containsExactlyInAnyOrderEntriesOf(Map.of(101L, 5.0, 102L, 4.0, 108L, 0.0, 111L, 3.0));
        assertThat(catalog.bidMap(List.of(101L, 102L))).containsExactlyInAnyOrderEntriesOf(Map.of(101L, 5.0, 102L, 4.0));
        assertThat(catalog.bidMap(List.of())).isEmpty();
        assertThat(repository.hot(2)).extracting(AdCandidate::adId).containsExactly(101L, 111L);
    }

    @Test
    void keywordRecallBindsArraysAndRespectsMergedLimit() {
        assertThat(repository.kwByDb(List.of("space", "alien"), Set.of("alien"), 2)).extracting(AdCandidate::adId)
                .containsExactly(101L, 102L);
        assertThat(repository.kwByDb(List.of("' OR true --"), Set.of(), 10)).isEmpty();
        assertThat(repository.kwByDb(List.of(), Set.of(), 10)).isEmpty();
    }

    private static HikariDataSource source(PostgreSQLContainer<?> container) {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(container.getJdbcUrl());
        ds.setUsername(container.getUsername());
        ds.setPassword(container.getPassword());
        ds.setMaximumPoolSize(3);
        return ds;
    }

    private static void create(JdbcTemplate jdbc, String table, String columns, String... names) {
        jdbc.execute("CREATE TABLE " + table + " (" + columns + ")");
        jdbc.execute("COMMENT ON TABLE " + table + " IS '广告真实分片契约测试独占表'");
        for (String name : names) jdbc.execute("COMMENT ON COLUMN " + table + "." + name + " IS '测试契约字段 " + name + "'");
    }
}
