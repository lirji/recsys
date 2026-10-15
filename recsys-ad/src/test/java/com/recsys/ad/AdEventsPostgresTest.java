package com.recsys.ad;

import com.recsys.common.ad.AdCandidate;
import com.recsys.common.ad.AdChannel;
import com.recsys.common.ad.SponsoredAd;
import com.recsys.ad.persistence.config.AdPersistenceConfiguration;
import com.recsys.ad.persistence.mapper.addb.AdEventMapper;
import com.recsys.ad.persistence.mapper.addb.AdConversionFactMapper;
import com.recsys.ad.persistence.mapper.addb.UpliftAssignmentMapper;
import com.recsys.ad.persistence.mapper.sharded.AdCatalogMapper;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 真实PG保护异步批量写入、事件唯一性和采样条件；Redis仅模拟外部边界。 */
@Testcontainers
class AdEventsPostgresTest {
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");
    private static JdbcTemplate events, catalog;
    private static AnnotationConfigApplicationContext context;
    private AdEventLogger logger;
    private AdOutcomeService outcomes;
    private UpliftAssignmentService uplift;
    private AdProperties properties;

    @BeforeAll
    static void prepare() {
        events = new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()));
        events.execute("CREATE SCHEMA catalog");
        catalog = new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl() + "&currentSchema=catalog", PG.getUsername(), PG.getPassword()));
        create(catalog, "ad", "ad_id BIGINT PRIMARY KEY,advertiser_id BIGINT,optimization_type TEXT");
        create(events, "ad_event", "id BIGSERIAL PRIMARY KEY,request_id TEXT,query TEXT,user_id BIGINT,ad_id BIGINT,bidword_id BIGINT,position INT CHECK(position>0),event_type TEXT,pctr DOUBLE PRECISION,pctr_calib DOUBLE PRECISION,ecpm DOUBLE PRECISION,charged_price DOUBLE PRECISION,relevance DOUBLE PRECISION,ad_bucket TEXT,creative_id BIGINT");
        create(events, "ad_conversion_fact", "event_id TEXT PRIMARY KEY,advertiser_id BIGINT NOT NULL,user_id BIGINT NOT NULL,objective TEXT NOT NULL,conversion_value DOUBLE PRECISION NOT NULL,occurred_at TIMESTAMP NOT NULL,created_at TIMESTAMP DEFAULT now()");
        context = new AnnotationConfigApplicationContext();
        context.getBeanFactory().registerSingleton("adDbDataSource", events.getDataSource());
        context.getBeanFactory().registerSingleton("adShardingDataSource", catalog.getDataSource());
        context.getBeanFactory().registerSingleton("derivedDbDataSource", events.getDataSource());
        context.register(AdPersistenceConfiguration.class); context.refresh();
    }

    @AfterAll
    static void closeContext() { if (context != null) context.close(); }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void resetExclusiveFixture() {
        events.execute("TRUNCATE ad_event,ad_conversion_fact");
        catalog.execute("TRUNCATE ad");
        catalog.update("INSERT INTO ad VALUES(101,1001,'OCPC'),(102,1002,NULL)");
        var redis = mock(StringRedisTemplate.class);
        var values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenReturn(null);
        logger = new AdEventLogger(context.getBean(AdEventMapper.class), context.getBean(AdCatalogMapper.class), redis);
        outcomes = new AdOutcomeService(context.getBean(AdConversionFactMapper.class));
        properties = new AdProperties();
        properties.getUplift().setCollectionEnabled(true);
        properties.getUplift().setControlRate(0.5);
        uplift = new UpliftAssignmentService(context.getBean(UpliftAssignmentMapper.class), events.getDataSource(), properties);
        uplift.ensureTables();
        events.execute("TRUNCATE ad_uplift_assignment");
    }

    @AfterEach
    void stopLogger() throws Exception { drainLogger(); }

    @Test
    void asynchronousImpressionsKeepEveryValueAndCreativeNull() throws Exception {
        logger.logImpressions("req'1", "quoted'query", 7, List.of(ad(101, 1, 0), ad(102, 2, 88)), "bucket");
        drainLogger();
        var rows = events.queryForList("SELECT * FROM ad_event ORDER BY position");
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).containsEntry("request_id", "req'1").containsEntry("query", "quoted'query")
                .containsEntry("pctr", 0.2).containsEntry("pctr_calib", 0.25).containsEntry("charged_price", 0.7)
                .containsEntry("event_type", "IMPRESSION").containsEntry("creative_id", null);
        assertThat(rows.get(1)).containsEntry("creative_id", 88L).containsEntry("ad_bucket", "bucket");
        assertThat(logger.readAttribution("req'1", 101)).isEqualTo(new AdEventLogger.ClickAttribution(1001, 0.7, "OCPC"));
        assertThat(logger.readAttribution("req'1", 102)).isEqualTo(new AdEventLogger.ClickAttribution(1002, 0.7, "CPC"));
    }

    @Test
    void failedBatchDoesNotLeavePartialImpressions() throws Exception {
        logger.logImpressions("batch-failure", "query", 7, List.of(ad(101, 1, 0), ad(102, -1, 0)), "bucket");
        drainLogger();
        assertThat(events.queryForObject("SELECT count(*) FROM ad_event", Integer.class)).isZero();
    }

    @Test
    void feedbackAndAttributionKeepMissingAndNullContracts() {
        logger.logFeedback("feedback", 101, 7, "CLICK");
        logger.logFeedback("feedback", 101, 7, "CONVERSION");
        assertThat(events.queryForList("SELECT event_type FROM ad_event ORDER BY id", String.class)).containsExactly("CLICK", "CONVERSION");
        assertThat(logger.readAttribution("feedback", 101)).isNull();
        events.update("INSERT INTO ad_event(request_id,ad_id,event_type,charged_price) VALUES('null',101,'IMPRESSION',NULL),('orphan',999,'IMPRESSION',2)");
        assertThat(logger.readAttribution("null", 101)).isNull();
        assertThat(logger.readAttribution("orphan", 999)).isNull();
    }

    @Test
    void outcomeConflictIsIdempotentAndKeepsOriginalPayload() throws Exception {
        Instant occurred = Instant.parse("2026-09-01T01:02:03.123456Z");
        try (var pool = Executors.newFixedThreadPool(4)) {
            List<Callable<Boolean>> calls = java.util.stream.IntStream.range(0, 8)
                    .mapToObj(i -> (Callable<Boolean>) () -> outcomes.record("same-event", 1001, 7, "purchase", 9.5, occurred)).toList();
            int inserted = 0;
            for (var future : pool.invokeAll(calls)) if (future.get()) inserted++;
            assertThat(inserted).isOne();
        }
        assertThat(outcomes.record("same-event", 1002, 8, "changed", 99, occurred)).isFalse();
        assertThat(events.queryForMap("SELECT * FROM ad_conversion_fact WHERE event_id='same-event'"))
                .containsEntry("advertiser_id", 1001L).containsEntry("conversion_value", 9.5).containsEntry("objective", "purchase");
        assertThat(events.queryForObject("SELECT occurred_at FROM ad_conversion_fact", java.sql.Timestamp.class).toInstant()).isEqualTo(occurred);
    }

    @Test
    void controlRemovesOnlyPersistedLastSlotAndPendingWindowSuppressesRepeat() {
        long user = userFor(false);
        var first = collect("assignment'1", user);
        assertThat(first.assigned()).isTrue(); assertThat(first.treatment()).isFalse(); assertThat(first.ads()).isEmpty();
        assertThat(events.queryForMap("SELECT * FROM ad_uplift_assignment"))
                .containsEntry("assignment_id", "assignment'1:101").containsEntry("propensity", 0.5).containsEntry("pcvr", 0.04).containsEntry("model_version", "test-v1");
        assertThat(collect("assignment'1", user).assigned()).isFalse();
        assertThat(collect("another-request", user).assigned()).isFalse();
        events.update("UPDATE ad_uplift_assignment SET outcome_due_at=now()-interval '1 second'");
        assertThat(collect("after-window", user).assigned()).isTrue();
        assertThat(events.queryForObject("SELECT count(*) FROM ad_uplift_assignment", Integer.class)).isEqualTo(2);
    }

    @Test
    void treatmentAndDatabaseFailureKeepDisplayBehavior() {
        var treatment = collect("treatment", userFor(true));
        assertThat(treatment.assigned()).isTrue(); assertThat(treatment.treatment()).isTrue(); assertThat(treatment.ads()).hasSize(1);
        events.execute("ALTER TABLE ad_uplift_assignment RENAME TO unavailable_assignment");
        try {
            var failed = collect("failure", userFor(false));
            assertThat(failed.assigned()).isFalse(); assertThat(failed.ads()).hasSize(1);
        } finally { events.execute("ALTER TABLE unavailable_assignment RENAME TO ad_uplift_assignment"); }
    }

    @Test
    void upliftBootstrapIsRepeatableAndDocumentsAllColumns() {
        uplift.ensureTables();
        assertThat(events.queryForObject("SELECT count(*) FROM pg_attribute WHERE attrelid='ad_uplift_assignment'::regclass "
                + "AND attnum>0 AND NOT attisdropped AND col_description(attrelid,attnum) IS NULL", Integer.class)).isZero();
        assertThat(events.queryForObject("SELECT obj_description('ad_uplift_assignment'::regclass,'pg_class')", String.class)).isNotBlank();
    }

    private UpliftAssignmentService.Result collect(String request, long user) {
        var candidate = new AdCandidate(101, 11, 1001, 88, 1, AdChannel.HOT_AD, 2, 0.9);
        return uplift.collect(request, user, List.of(ad(101, 1, 0)), List.of(candidate), Map.of(11L, 0.04),
                Map.of(101L, new UpliftEstimate(0.1, 0.2, 0.1, "test-v1")), "bucket");
    }

    private long userFor(boolean treatment) {
        long user = 1;
        while ((UpliftAssignmentService.uniform(user, properties.getUplift().getSalt()) < 0.5) != treatment) user++;
        return user;
    }

    private static SponsoredAd ad(long id, int position, long creative) {
        return new SponsoredAd(id, 11, 1001, 88, "title", AdChannel.HOT_AD, 2, 0.9, 0.8, 0.2, 0.25, 0.5, 0.7, position, creative, "CPC");
    }

    /** 等异步队列真实退出再查询PG，失败批次不能只靠固定sleep判断。 */
    private void drainLogger() throws Exception {
        if (logger == null) return;
        logger.shutdown();
        assertThat(((ExecutorService) ReflectionTestUtils.getField(logger, "executor")).awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }

    private static void create(JdbcTemplate jdbc, String table, String columns) {
        jdbc.execute("CREATE TABLE " + table + "(" + columns + ")");
        jdbc.execute("COMMENT ON TABLE " + table + " IS '事件迁移隔离测试'");
        for (String column : columns.split(",")) jdbc.execute("COMMENT ON COLUMN " + table + "." + column.strip().split(" ")[0] + " IS '测试字段'");
    }
}
