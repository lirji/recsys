package com.recsys.ad;

import com.recsys.common.ad.AdCandidate;
import com.recsys.common.ad.AdChannel;
import com.recsys.ad.persistence.config.AdPersistenceConfiguration;
import com.recsys.ad.persistence.mapper.sharded.AdCatalogMapper;
import com.recsys.ad.persistence.mapper.addb.AdEmbeddingMapper;
import com.recsys.ad.persistence.mapper.derived.AdUserEmbeddingMapper;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 广告向量与用户派生向量分别建schema，目录替身仅用于隔离本批向量读取契约。 */
@Testcontainers
class AdVectorSourcesPostgresTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    private AdRepository repository;
    private AnnotationConfigApplicationContext context;

    @BeforeEach
    void prepare() {
        JdbcTemplate admin = new JdbcTemplate(source("public"));
        admin.execute("CREATE EXTENSION IF NOT EXISTS vector; CREATE SCHEMA IF NOT EXISTS ad_owner; CREATE SCHEMA IF NOT EXISTS derived");
        JdbcTemplate adDb = new JdbcTemplate(source("ad_owner,public"));
        JdbcTemplate derived = new JdbcTemplate(source("derived,public"));
        adDb.execute("""
                CREATE TABLE IF NOT EXISTS ad_embedding(ad_id BIGINT PRIMARY KEY, embedding vector(3));
                COMMENT ON TABLE ad_embedding IS '广告向量owner隔离契约测试表';
                COMMENT ON COLUMN ad_embedding.ad_id IS '广告标识';
                COMMENT ON COLUMN ad_embedding.embedding IS '广告向量';
                TRUNCATE ad_embedding;
                INSERT INTO ad_embedding VALUES (101,'[1,0,0]'),(102,'[0,1,0]'),(103,NULL);
                """);
        derived.execute("""
                CREATE TABLE IF NOT EXISTS user_embedding(user_id BIGINT PRIMARY KEY, embedding vector(3));
                COMMENT ON TABLE user_embedding IS '用户派生向量隔离契约测试表';
                COMMENT ON COLUMN user_embedding.user_id IS '用户标识';
                COMMENT ON COLUMN user_embedding.embedding IS '用户向量';
                TRUNCATE user_embedding;
                INSERT INTO user_embedding VALUES (7,'[1,0,0]'),(8,NULL);
                """);
        AdCatalogReader catalog = mock(AdCatalogReader.class);
        when(catalog.activeAdDetails(any())).thenReturn(Map.of(101L, new AdCatalogReader.AdDetail(11, 1, 1),
                102L, new AdCatalogReader.AdDetail(12, 2, 2), 103L, new AdCatalogReader.AdDetail(13, 3, 1)));
        when(catalog.bidMap(any())).thenReturn(Map.of(101L, 2.0));
        context = new AnnotationConfigApplicationContext();
        context.getBeanFactory().registerSingleton("adShardingDataSource", admin.getDataSource());
        context.getBeanFactory().registerSingleton("adDbDataSource", adDb.getDataSource());
        context.getBeanFactory().registerSingleton("derivedDbDataSource", derived.getDataSource());
        context.register(AdPersistenceConfiguration.class);
        context.refresh();
        repository = new AdRepository(context.getBean(AdUserEmbeddingMapper.class), context.getBean(AdEmbeddingMapper.class),
                context.getBean(AdCatalogMapper.class), catalog);
    }

    @AfterEach
    void close() {
        if (context != null) context.close();
    }

    @Test
    void factoriesKeepDataSourceIdentityAndMapperIsolation() {
        SqlSessionFactory adDb = context.getBean("adDbSqlSessionFactory", SqlSessionFactory.class);
        SqlSessionFactory derived = context.getBean("derivedSqlSessionFactory", SqlSessionFactory.class);
        assertThat(adDb.getConfiguration().getEnvironment().getDataSource()).isSameAs(context.getBean("adDbDataSource"));
        assertThat(derived.getConfiguration().getEnvironment().getDataSource()).isSameAs(context.getBean("derivedDbDataSource"));
        assertThat(adDb.getConfiguration().hasMapper(AdEmbeddingMapper.class)).isTrue();
        assertThat(adDb.getConfiguration().hasMapper(AdUserEmbeddingMapper.class)).isFalse();
        assertThat(derived.getConfiguration().hasMapper(AdEmbeddingMapper.class)).isFalse();
    }

    @Test
    void semanticAnnRetainsOrderNullScoresAndMissingBidDefaults() {
        var rows = repository.semantic(new float[]{1, 0, 0}, 3);
        assertThat(rows).extracting(AdCandidate::adId).containsExactly(101L, 102L, 103L);
        assertThat(rows).extracting(AdCandidate::recallScore).containsExactly(1.0, 0.0, 0.0);
        assertThat(rows).extracting(AdCandidate::bid).containsExactly(2.0, 0.0, 0.0);
        assertThat(rows).allMatch(row -> row.channel() == AdChannel.SEMANTIC_AD);
        assertThat(repository.semantic(new float[]{1, 0, 0}, 1)).hasSize(1);
    }

    @Test
    void u2aReadsUserVectorFromDerivedThenAdsFromOwner() {
        var rows = repository.u2a(7, 1);
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().adId()).isEqualTo(101);
        assertThat(rows.getFirst().channel()).isEqualTo(AdChannel.U2A);
        assertThat(repository.u2a(8, 10)).isEmpty();
        assertThat(repository.u2a(999, 10)).isEmpty();
    }

    private static DriverManagerDataSource source(String schema) {
        return new DriverManagerDataSource(POSTGRES.getJdbcUrl() + "&currentSchema=" + schema,
                POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
