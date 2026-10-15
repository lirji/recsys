package com.recsys.advertiser;

import com.recsys.persistence.spring.SpringSqlSessions;

import com.zaxxer.hikari.HikariDataSource;
import com.recsys.advertiser.persistence.config.AdvertiserPersistenceConfiguration;
import com.recsys.advertiser.persistence.mapper.primary.AdvertiserMapper;
import com.recsys.advertiser.persistence.mapper.derived.AdvertiserItemEmbeddingMapper;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.apache.shardingsphere.driver.api.yaml.YamlShardingSphereDataSourceFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 真实双分片保护生成主键、广播更新、单表向量和跨库读取；不连接共享开发环境。 */
@Testcontainers
class AdvertiserRepositoryPostgresTest {
    @Container static final PostgreSQLContainer<?> ZERO = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
    @Container static final PostgreSQLContainer<?> ONE = new PostgreSQLContainer<>("postgres:16-alpine");
    private static DataSource sharded;
    private static AnnotationConfigApplicationContext context;
    private static JdbcTemplate zero, one, derived;
    private static AdvertiserRepository repository;

    @BeforeAll
    static void prepare() throws Exception {
        HikariDataSource ds0 = source(ZERO), ds1 = source(ONE);
        zero = new JdbcTemplate(ds0); one = new JdbcTemplate(ds1);
        for (JdbcTemplate jdbc : List.of(zero, one)) {
            create(jdbc, "advertiser", "advertiser_id BIGINT PRIMARY KEY,name TEXT,daily_budget DOUBLE PRECISION,status TEXT");
            create(jdbc, "ad", "ad_id BIGINT PRIMARY KEY,advertiser_id BIGINT,item_id BIGINT,title TEXT,landing_url TEXT,quality_score DOUBLE PRECISION,status TEXT,review_status TEXT,optimization_type TEXT,target_cpa DOUBLE PRECISION,audience_id BIGINT,review_reason TEXT");
            create(jdbc, "bidword", "id BIGINT PRIMARY KEY,ad_id BIGINT,keyword TEXT,match_type TEXT,bid DOUBLE PRECISION,bid_mode TEXT");
            create(jdbc, "ad_creative", "creative_id BIGINT PRIMARY KEY,ad_id BIGINT,title TEXT,landing_url TEXT,status TEXT");
        }
        zero.execute("CREATE EXTENSION vector");
        create(zero, "item", "item_id BIGINT PRIMARY KEY");
        create(zero, "ad_embedding", "ad_id BIGINT PRIMARY KEY,embedding vector(3),model TEXT");
        create(zero, "ad_event", "ad_id BIGINT,event_type TEXT,charged_price DOUBLE PRECISION");
        zero.execute("CREATE SCHEMA derived");
        derived = new JdbcTemplate(new DriverManagerDataSource(ZERO.getJdbcUrl() + "&currentSchema=derived,public", ZERO.getUsername(), ZERO.getPassword()));
        create(derived, "item_embedding", "item_id BIGINT PRIMARY KEY,embedding vector(3),model TEXT");
        String yaml;
        try (var in = AdvertiserRepositoryPostgresTest.class.getResourceAsStream("/sharding.yaml")) {
            yaml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        sharded = YamlShardingSphereDataSourceFactory.createDataSource(Map.of("ds_0", ds0, "ds_1", ds1),
                yaml.substring(yaml.indexOf("\nrules:") + 1).getBytes(StandardCharsets.UTF_8));
        context = new AnnotationConfigApplicationContext();
        context.getBeanFactory().registerSingleton("dataSource", sharded);
        context.getBeanFactory().registerSingleton("derivedSqlSessionFactory", SpringSqlSessions.create(derived.getDataSource(), "classpath*:mapper/*/derived/**/*.xml"));
        context.register(AdvertiserPersistenceConfiguration.class);
        context.refresh();
        var mapper = context.getBean(AdvertiserMapper.class);
        repository = new AdvertiserRepository(mapper, context.getBean(AdvertiserItemEmbeddingMapper.class), new DbAdReportReader(mapper));
    }

    @AfterAll
    static void close() throws Exception {
        if (context != null) context.close();
        if (sharded instanceof AutoCloseable c) c.close();
    }

    @BeforeEach
    void resetExclusiveFixtures() {
        for (JdbcTemplate jdbc : List.of(zero, one)) jdbc.execute("TRUNCATE advertiser,ad,bidword,ad_creative");
        zero.execute("TRUNCATE item,ad_embedding,ad_event");
        derived.execute("TRUNCATE item_embedding");
        zero.update("INSERT INTO advertiser VALUES(2,'even',100,'active')");
        one.update("INSERT INTO advertiser VALUES(1,'odd',200,'active')");
        zero.update("INSERT INTO item VALUES(10),(20)");
    }

    @Test
    void generatedKeysAndPhysicalRoutingRemainOwnedByShardingSphere() {
        long advertiser = repository.insertAdvertiser("new", 50, "active");
        assertThat(advertiser).isPositive();
        assertThat(physical(advertiser).queryForObject("SELECT name FROM advertiser WHERE advertiser_id=?", String.class, advertiser)).isEqualTo("new");
        for (long owner : List.of(1L, 2L)) {
            long ad = insertAd(owner);
            long bidword = repository.insertBidword(ad, "space", "exact", 2.5, "CPC");
            long creative = repository.insertCreative(ad, "creative", "/creative", "active");
            assertThat(List.of(ad, bidword, creative)).allSatisfy(id -> assertThat(id).isPositive());
            assertThat(physical(owner).queryForObject("SELECT advertiser_id FROM ad WHERE ad_id=?", Long.class, ad)).isEqualTo(owner);
            assertThat(physical(ad).queryForObject("SELECT id FROM bidword WHERE ad_id=?", Long.class, ad)).isEqualTo(bidword);
            assertThat(physical(ad).queryForObject("SELECT creative_id FROM ad_creative WHERE ad_id=?", Long.class, ad)).isEqualTo(creative);
            assertThat(repository.advertiserOfAd(ad)).isEqualTo(owner);
            assertThat(repository.adOfCreative(creative)).isEqualTo(ad);
        }
        assertThat(repository.advertiserExists(advertiser)).isTrue();
        assertThat(repository.advertiserExists(999)).isFalse();
        assertThat(repository.itemExists(10)).isTrue();
        assertThat(repository.itemExists(999)).isFalse();
        assertThat(repository.listAdvertisersRaw()).extracting(v -> v.advertiserId()).isSorted();
    }

    @Test
    void partialUpdatesKeepNullFieldsExceptExplicitAudienceClear() {
        long ad = insertAd(1);
        assertThat(repository.updateAdvertiser(1, null, 300.0, null)).isOne();
        assertThat(repository.findAdvertiserRaw(1).name()).isEqualTo("odd");
        assertThat(repository.findAdvertiserRaw(1).dailyBudget()).isEqualTo(300);
        assertThat(repository.findAdvertiserRaw(999)).isNull();
        assertThat(repository.updateAd(ad, null, "changed", null, null, null, null, null, null)).isOne();
        var row = repository.findAdRow(ad);
        assertThat(row.title()).isEqualTo("changed");
        assertThat(row.targetCpa()).isEqualTo(4);
        assertThat(row.audienceId()).isNull();
        assertThat(row.itemId()).isEqualTo(10);
        repository.setAdLandingUrl(ad, "/new");
        assertThat(repository.setAdStatus(ad, "paused")).isOne();
        assertThat(repository.setAdReview(ad, "rejected", "reason")).isOne();
        assertThat(repository.findAdRow(ad).landingUrl()).isEqualTo("/new");
        assertThat(repository.findAdRow(ad).reviewStatus()).isEqualTo("rejected");
        assertThat(repository.updateAd(999, null, null, null, null, null, null, null, null)).isZero();
        assertThat(repository.findAdRow(999)).isNull();
    }

    @Test
    void childCrudAndAdDeletionKeepAllSubresourcesConsistent() {
        long ad = insertAd(2);
        long bid = repository.insertBidword(ad, "space", "exact", 2, "CPC");
        long creative = repository.insertCreative(ad, "title", "/old", "active");
        assertThat(repository.updateBidword(bid, null, null, 3.0, null)).isOne();
        assertThat(repository.findBidword(bid).keyword()).isEqualTo("space");
        assertThat(repository.listBidwords(ad)).hasSize(1).first().satisfies(v -> assertThat(v.bid()).isEqualTo(3));
        assertThat(repository.updateCreative(creative, null, "/changed", null)).isOne();
        assertThat(repository.listCreatives(ad)).hasSize(1).first().satisfies(v -> {
            assertThat(v.title()).isEqualTo("title"); assertThat(v.landingUrl()).isEqualTo("/changed");
        });
        assertThat(repository.deleteBidword(bid)).isOne();
        assertThat(repository.deleteCreative(creative)).isOne();
        assertThat(repository.findBidword(bid)).isNull();
        assertThat(repository.adOfCreative(creative)).isNull();
        repository.insertBidword(ad, "remaining", "exact", 1, "CPC");
        repository.insertCreative(ad, "remaining", "/left", "active");
        zero.update("INSERT INTO ad_embedding VALUES(?, '[1,0,0]', 'old')", ad);
        repository.deleteAd(ad);
        assertThat(repository.adExists(ad)).isFalse();
        assertThat(repository.advertiserOfAd(ad)).isNull();
        assertThat(repository.listBidwords(ad)).isEmpty();
        assertThat(repository.listCreatives(ad)).isEmpty();
        assertThat(zero.queryForObject("SELECT count(*) FROM ad_embedding", Integer.class)).isZero();
    }

    @Test
    void embeddingCopyUsesDerivedSourceAndKeepsUpsertAndMissingSemantics() {
        long ad = insertAd(1);
        derived.update("INSERT INTO item_embedding VALUES(10,'[1,0,0]','v1'),(20,NULL,'null')");
        assertThat(repository.copyEmbeddingFromItem(ad, 10)).isOne();
        assertThat(repository.itemEmbeddingText(10)).isEqualTo("[1,0,0]");
        assertThat(repository.findAdRow(ad).hasEmbedding()).isTrue();
        assertThat(repository.listAdRows(1)).hasSize(1).first().satisfies(v -> assertThat(v.hasEmbedding()).isTrue());
        derived.update("UPDATE item_embedding SET embedding='[0,1,0]',model='v2' WHERE item_id=10");
        assertThat(repository.copyEmbeddingFromItem(ad, 10)).isOne();
        assertThat(zero.queryForObject("SELECT model FROM ad_embedding WHERE ad_id=?", String.class, ad)).isEqualTo("v2");
        assertThat(repository.copyEmbeddingFromItem(ad, 20)).isZero();
        assertThat(repository.copyEmbeddingFromItem(ad, 999)).isZero();
        assertThat(repository.itemEmbeddingText(999)).isNull();
        repository.deleteEmbedding(ad);
        assertThat(repository.findAdRow(ad).hasEmbedding()).isFalse();
        assertThat(repository.listAdRows(999)).isEmpty();
    }

    @Test
    void reportsPreserveAdOrderAndZeroFillWithClickOnlySpend() {
        long ad = insertAd(1), empty = insertAd(1);
        zero.update("INSERT INTO ad_event VALUES(?,'IMPRESSION',100),(?,'IMPRESSION',NULL),(?,'CLICK',1.234),(?,'CONVERSION',9)", ad, ad, ad, ad);
        var rows = repository.reportByAdvertiser(1);
        assertThat(rows).extracting(v -> v.adId()).containsExactly(ad, empty);
        assertThat(rows.get(0).impressions()).isEqualTo(2);
        assertThat(rows.get(0).clicks()).isEqualTo(1);
        assertThat(rows.get(0).conversions()).isEqualTo(1);
        assertThat(rows.get(0).spend()).isEqualTo(1.23);
        assertThat(rows.get(0).ctr()).isEqualTo(0.5);
        assertThat(rows.get(0).cvr()).isEqualTo(1);
        assertThat(rows.get(0).ecpm()).isEqualTo(617);
        assertThat(rows.get(1).spend()).isZero();
        assertThat(repository.reportByAdvertiser(999)).isEmpty();
    }

    @Test
    void nullableNumericColumnsKeepJdbcDefaultsWithoutChangingNullableFields() {
        zero.update("UPDATE advertiser SET daily_budget=NULL WHERE advertiser_id=2");
        zero.update("INSERT INTO ad(ad_id,advertiser_id,item_id,quality_score) VALUES(2000,2,NULL,NULL)");
        zero.update("INSERT INTO bidword(id,ad_id,bid) VALUES(2001,2000,NULL)");
        assertThat(repository.findAdvertiserRaw(2).dailyBudget()).isZero();
        var row = repository.findAdRow(2000);
        assertThat(row.itemId()).isZero(); assertThat(row.qualityScore()).isZero();
        assertThat(row.targetCpa()).isNull(); assertThat(row.audienceId()).isNull();
        assertThat(repository.findBidword(2001).bid()).isZero();
    }

    @Test
    void factoriesKeepOriginalSourcesAndBootPrimaryStillAutoConfigures() {
        assertThat(context.getBean("sqlSessionFactory", SqlSessionFactory.class).getConfiguration().getEnvironment().getDataSource()).isSameAs(sharded);
        assertThat(context.getBean("derivedSqlSessionFactory", SqlSessionFactory.class).getConfiguration().getEnvironment().getDataSource()).isSameAs(derived.getDataSource());
        assertThat(context.getBean("sqlSessionFactory", SqlSessionFactory.class).getConfiguration().hasMapper(AdvertiserItemEmbeddingMapper.class)).isFalse();
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class))
                .withUserConfiguration(AdvertiserDataSourceConfig.class, AdvertiserPersistenceConfiguration.class)
                .withPropertyValues("spring.datasource.url=" + ZERO.getJdbcUrl(), "spring.datasource.username=" + ZERO.getUsername(),
                        "spring.datasource.password=" + ZERO.getPassword(), "spring.datasource.driver-class-name=org.postgresql.Driver")
                .run(c -> {
                    assertThat(c).hasNotFailed().hasSingleBean(DataSource.class);
                    assertThat(c.getBean("sqlSessionFactory", SqlSessionFactory.class).getConfiguration().getEnvironment().getDataSource()).isSameAs(c.getBean(DataSource.class));
                    var derivedSource = c.getBean("derivedSqlSessionFactory", SqlSessionFactory.class).getConfiguration().getEnvironment().getDataSource();
                    assertThat(derivedSource).isInstanceOf(DriverManagerDataSource.class).isNotSameAs(c.getBean(DataSource.class));
                    assertThat(c).doesNotHaveBean("derivedJdbc");
                    assertThat(c.getBeansOfType(SqlSessionFactory.class)).hasSize(2);
                });
    }

    @Test
    void mapperWritesParticipateInExistingSpringTransactionConnection() {
        long[] id = new long[1];
        new TransactionTemplate(new DataSourceTransactionManager(sharded)).executeWithoutResult(status -> {
            id[0] = insertAd(1);
            assertThat(repository.adExists(id[0])).isTrue();
            status.setRollbackOnly();
        });
        assertThat(repository.adExists(id[0])).isFalse();
    }

    private static long insertAd(long owner) {
        return repository.insertAd(owner, 10, "title", "/landing", 1.5, "active", "approved", "OCPC", 4.0, 5L);
    }

    private static JdbcTemplate physical(long key) { return key % 2 == 0 ? zero : one; }

    private static HikariDataSource source(PostgreSQLContainer<?> pg) {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(pg.getJdbcUrl()); ds.setUsername(pg.getUsername()); ds.setPassword(pg.getPassword());
        ds.setMaximumPoolSize(3); return ds;
    }

    /** 测试表同样带注释，DDL只在独占容器执行。 */
    private static void create(JdbcTemplate jdbc, String table, String columns) {
        jdbc.execute("CREATE TABLE " + table + "(" + columns + ")");
        jdbc.execute("COMMENT ON TABLE " + table + " IS '广告主迁移隔离测试'");
        for (String column : columns.split(",")) jdbc.execute("COMMENT ON COLUMN " + table + "." + column.strip().split(" ")[0] + " IS '测试字段'");
    }
}
