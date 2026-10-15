package com.recsys.adserving.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recsys.ad.AdRepository;
import com.recsys.adserving.report.AdReportStatsRepository;
import com.recsys.common.ad.AdCatalogEvent;
import com.recsys.adserving.persistence.config.AdServingPersistenceConfiguration;
import com.recsys.adserving.persistence.mapper.addb.AdServableMapper;
import com.recsys.adserving.persistence.mapper.addb.AdReportStatsMapper;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** 隔离PG验证目录副本、事件覆盖及报表口径，保护吞错降级与NULL的既有语义。 */
@Testcontainers
class AdServablePostgresTest {
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
    private static JdbcTemplate jdbc;
    private static AnnotationConfigApplicationContext context;
    private static AdServableRepository repository;
    private static AdReportStatsRepository reports;

    @BeforeAll
    static void prepare() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()));
        context = new AnnotationConfigApplicationContext();
        context.getBeanFactory().registerSingleton("adDbDataSource", jdbc.getDataSource());
        context.register(AdServingPersistenceConfiguration.class);
        context.refresh();
        repository = new AdServableRepository(context.getBean(AdServableMapper.class), jdbc.getDataSource());
        reports = new AdReportStatsRepository(context.getBean(AdReportStatsMapper.class));
        repository.ensureTable();
        jdbc.execute("CREATE TABLE ad_event(ad_id BIGINT,event_type TEXT,charged_price DOUBLE PRECISION)");
        jdbc.execute("COMMENT ON TABLE ad_event IS '报表隔离测试事件'");
        for (String c : List.of("ad_id", "event_type", "charged_price")) jdbc.execute("COMMENT ON COLUMN ad_event." + c + " IS '测试字段'");
    }

    @AfterAll
    static void close() { if (context != null) context.close(); }

    @BeforeEach
    void resetExclusiveFixture() { jdbc.execute("TRUNCATE ad_servable,ad_embedding,ad_event"); }

    @Test
    void upsertPreservesJsonMaximumBidAndAllCatalogReads() {
        var event = event("first", 4.0, 9L, 20);
        repository.upsert(event); repository.upsert(event);
        assertThat(repository.count()).isOne();
        assertThat(repository.titles(List.of(101L, 999L))).containsExactlyInAnyOrderEntriesOf(Map.of(101L, "first"));
        assertThat(repository.ocpcParams(List.of(101L))).containsEntry(101L, new AdRepository.OcpcParams("OCPC", 4));
        assertThat(repository.activeAdDetails(null)).containsOnlyKeys(101L);
        assertThat(repository.activeAdDetails(List.of(101L)).get(101L).itemId()).isEqualTo(10);
        assertThat(repository.bidMap(null)).containsEntry(101L, 3.5);
        assertThat(repository.bidMap(List.of(101L))).containsEntry(101L, 3.5);
        assertThat(repository.audiencesByAd(List.of(101L))).containsEntry(101L, 9L);
        assertThat(repository.bidwordsOf(101)).isEqualTo(event.bidwords());
        assertThat(repository.titles(List.of())).isEmpty();
        assertThat(repository.ocpcParams(List.of())).isEmpty();
        assertThat(repository.activeAdDetails(List.of())).isEmpty();
        assertThat(repository.bidMap(List.of())).isEmpty();
        assertThat(repository.audiencesByAd(null)).isEmpty();
        assertThat(repository.bidwordsOf(999)).isEmpty();
    }

    @Test
    void eventConsumerKeepsArrivalOrderAndTombstoneDeletion() throws Exception {
        var consumer = new AdCatalogEventConsumer(repository, mock(BidwordInvMaintainer.class));
        var json = new ObjectMapper();
        consumer.onEvent(json.writeValueAsString(event("newer-ts", 4.0, 9L, 20)));
        // 当前事件协议按Kafka分区到达顺序覆盖，ts只审计；迁移不私自引入版本拦截。
        consumer.onEvent(json.writeValueAsString(event("last-arrival", null, null, 10)));
        assertThat(repository.titles(List.of(101L))).containsEntry(101L, "last-arrival");
        assertThat(repository.ocpcParams(List.of(101L)).get(101L).targetCpa()).isZero();
        assertThat(repository.audiencesByAd(List.of(101L))).isEmpty();
        consumer.onEvent(json.writeValueAsString(AdCatalogEvent.tombstone(101, 30)));
        assertThat(repository.count()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ad_embedding", Integer.class)).isZero();
    }

    @Test
    void nullColumnsAndMalformedJsonKeepFallbackValues() {
        repository.upsert(event(null, null, null, 10));
        jdbc.update("UPDATE ad_servable SET quality_score=NULL,max_bid=NULL,optimization_type=NULL,bidwords_json='bad-json' WHERE ad_id=101");
        assertThat(repository.titles(List.of(101L))).containsEntry(101L, null);
        assertThat(repository.activeAdDetails(null).get(101L).quality()).isZero();
        assertThat(repository.bidMap(null)).containsEntry(101L, 0.0);
        assertThat(repository.ocpcParams(List.of(101L))).containsEntry(101L, new AdRepository.OcpcParams("CPC", 0));
        assertThat(repository.bidwordsOf(101)).isEmpty();
    }

    @Test
    void vectorUpsertAndDeletePreserveEmptyInputAndFailureFallback() {
        repository.upsertEmbedding(101, vector(0));
        repository.upsertEmbedding(101, " ");
        assertThat(jdbc.queryForObject("SELECT embedding::text FROM ad_embedding WHERE ad_id=101", String.class)).startsWith("[1,0,");
        repository.upsertEmbedding(101, vector(1));
        repository.upsertEmbedding(101, "invalid-vector");
        assertThat(jdbc.queryForObject("SELECT embedding::text FROM ad_embedding WHERE ad_id=101", String.class)).startsWith("[0,1,");
        assertThat(jdbc.queryForObject("SELECT model FROM ad_embedding WHERE ad_id=101", String.class)).isEqualTo("catalog-event");
        repository.deleteEmbedding(101); repository.deleteEmbedding(101);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ad_embedding", Integer.class)).isZero();
    }

    @Test
    void missingCatalogKeepsReadAndWriteFailureContracts() {
        jdbc.execute("ALTER TABLE ad_servable RENAME TO unavailable_catalog");
        try {
            repository.upsert(event("fail", null, null, 0)); repository.delete(101);
            assertThat(repository.count()).isEqualTo(-1);
            assertThat(repository.titles(List.of(101L))).isEmpty();
            assertThat(repository.activeAdDetails(null)).isEmpty();
            assertThat(repository.bidMap(null)).isEmpty();
        } finally { jdbc.execute("ALTER TABLE unavailable_catalog RENAME TO ad_servable"); }
    }

    @Test
    void reportAggregationCountsEveryEventButOnlyClickCharges() {
        jdbc.update("INSERT INTO ad_event VALUES(101,'IMPRESSION',99),(101,'CLICK',2.5),(101,'CLICK',NULL),(101,'CONVERSION',8),(102,'IMPRESSION',NULL)");
        assertThat(reports.statsByAds(List.of(101L, 102L, 999L))).containsExactlyInAnyOrder(
                new AdReportStatsRepository.Stat(101, 1, 2, 1, 2.5), new AdReportStatsRepository.Stat(102, 1, 0, 0, 0));
        assertThat(reports.statsByAds(null)).isEmpty(); assertThat(reports.statsByAds(List.of())).isEmpty();
    }

    @Test
    void bootstrapSupportsLegacyColumnsAndHasCompleteComments() {
        jdbc.execute("ALTER TABLE ad_servable DROP COLUMN audience_id, DROP COLUMN max_bid");
        repository.ensureTable(); repository.ensureTable();
        repository.upsert(event("expanded", 1.0, 9L, 10));
        assertThat(repository.audiencesByAd(List.of(101L))).containsEntry(101L, 9L);
        assertThat(repository.bidMap(null)).containsEntry(101L, 3.5);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_attribute a JOIN pg_class c ON c.oid=a.attrelid "
                + "WHERE c.relname IN ('ad_servable','ad_embedding') AND c.relkind='r' AND a.attnum>0 AND NOT a.attisdropped "
                + "AND col_description(c.oid,a.attnum) IS NULL", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_class WHERE relname IN ('ad_servable','ad_embedding') "
                + "AND obj_description(oid,'pg_class') IS NOT NULL", Integer.class)).isEqualTo(2);
    }

    @Test
    void mappersUseTheSameAdOwnerSourceAsBootstrap() {
        var configuration = context.getBean("adDbSqlSessionFactory", SqlSessionFactory.class).getConfiguration();
        assertThat(configuration.getEnvironment().getDataSource()).isSameAs(jdbc.getDataSource());
        assertThat(configuration.hasMapper(AdServableMapper.class)).isTrue();
        assertThat(configuration.hasMapper(AdReportStatsMapper.class)).isTrue();
    }

    private static AdCatalogEvent event(String title, Double cpa, Long audience, long ts) {
        return new AdCatalogEvent(101, true, 1, 10, title, "/landing", 1.2, "active", "approved", "OCPC", cpa, audience,
                List.of(new AdCatalogEvent.Bidword(1, "space", "exact", 2), new AdCatalogEvent.Bidword(2, "a'quoted", "broad", 3.5)),
                List.of(new AdCatalogEvent.Creative(4, "creative", "/creative", "active")), vector(0), ts);
    }

    private static String vector(int axis) {
        String[] values = new String[768]; java.util.Arrays.fill(values, "0"); values[axis] = "1";
        return "[" + String.join(",", values) + "]";
    }
}
