package com.recsys.ad;

import com.recsys.ad.persistence.config.AdPersistenceConfiguration;
import com.recsys.ad.persistence.mapper.primary.AdContractMapper;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
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

/** 真实PG保留合约时间和CPM的bigint舍入，模型选择仍由原GdPacing实现。 */
@Testcontainers
class GuaranteedDeliveryPostgresTest {
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");
    private static JdbcTemplate jdbc;
    private static AnnotationConfigApplicationContext context;
    private AdProperties props;
    private AdCatalogReader catalog;
    private GuaranteedDeliveryService service;

    @BeforeAll
    static void prepare() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()));
        jdbc.execute("CREATE TABLE ad_contract(contract_id BIGINT PRIMARY KEY,ad_id BIGINT,advertiser_id BIGINT,guaranteed BIGINT,start_ts TIMESTAMP,end_ts TIMESTAMP,price_cpm DOUBLE PRECISION,status TEXT)");
        jdbc.execute("COMMENT ON TABLE ad_contract IS 'GD迁移隔离测试合约'");
        for (String c : new String[]{"contract_id","ad_id","advertiser_id","guaranteed","start_ts","end_ts","price_cpm","status"})
            jdbc.execute("COMMENT ON COLUMN ad_contract." + c + " IS '合约测试字段'");
        context = new AnnotationConfigApplicationContext();
        for (String name : new String[]{"dataSource", "adDbDataSource", "adShardingDataSource", "derivedDbDataSource"})
            context.getBeanFactory().registerSingleton(name, jdbc.getDataSource());
        context.register(AdPersistenceConfiguration.class); context.refresh();
    }

    @AfterAll
    static void close() { if (context != null) context.close(); }

    @BeforeEach
    void fixture() {
        jdbc.execute("TRUNCATE ad_contract");
        jdbc.update("INSERT INTO ad_contract VALUES(1,101,1001,100,now()-interval '1 day',now()+interval '1 day',1.23456,'active'),(2,102,1002,100,now()-interval '1 day',now()+interval '1 day',99,'paused')");
        props = new AdProperties(); props.getGd().setEnabled(true);
        catalog = mock(AdCatalogReader.class);
        when(catalog.adItemAdvertiser(any())).thenReturn(Map.of(101L, new long[]{11, 1001}));
        service = new GuaranteedDeliveryService(context.getBean(AdContractMapper.class), catalog,
                new StaticListableBeanFactory().getBeanProvider(StringRedisTemplate.class), props);
    }

    @Test
    void activeContractKeepsPgPriceRoundingAndSelection() {
        var chosen = service.select(7, 2).orElseThrow();
        assertThat(chosen.contractId()).isEqualTo(1);
        assertThat(chosen.ad().adId()).isEqualTo(101);
        assertThat(chosen.ad().chargedPrice()).isCloseTo(0.001235, org.assertj.core.data.Offset.offset(1e-12));
        assertThat(chosen.ad().position()).isEqualTo(2);
        assertThat(chosen.ad().bidType()).isEqualTo("CPM");
        jdbc.update("UPDATE ad_contract SET price_cpm=NULL WHERE contract_id=1");
        assertThat(service.select(7, 1).orElseThrow().ad().chargedPrice()).isZero();
    }

    @Test
    void invalidWindowOrMissingCatalogReturnsNoContract() {
        jdbc.update("UPDATE ad_contract SET start_ts=NULL,end_ts=NULL,guaranteed=NULL WHERE contract_id=1");
        assertThat(service.select(7, 1)).isEmpty();
        jdbc.update("UPDATE ad_contract SET start_ts=now()-interval '1 day',end_ts=now()+interval '1 day',guaranteed=100 WHERE contract_id=1");
        when(catalog.adItemAdvertiser(any())).thenReturn(Map.of());
        assertThat(service.select(7, 1)).isEmpty();
    }

    @Test
    void disabledOrReadFailureKeepsEmptySelection() {
        props.getGd().setEnabled(false);
        assertThat(service.select(7, 1)).isEmpty();
        props.getGd().setEnabled(true);
        jdbc.execute("ALTER TABLE ad_contract RENAME TO unavailable_contract");
        try { assertThat(service.select(7, 1)).isEmpty(); }
        finally { jdbc.execute("ALTER TABLE unavailable_contract RENAME TO ad_contract"); }
    }
}
