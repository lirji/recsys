package com.recsys.advertiser.catalog;

import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.*;
import java.util.*;
import static com.recsys.advertiser.catalog.CatalogPhysicalTransactionTest.*;
import static org.assertj.core.api.Assertions.*;

/** 实际双物理库准入负对照；配置纯校验补充已经真实Boot的无迁移关闭验收。 */
@Testcontainers @Tag("catalog-reliability")
class CatalogAdmissionTest {
    @Container static final PostgreSQLContainer<?> PG0=ZERO,PG1=ONE;
    @Container static final KafkaFixture BROKER=KAFKA;
    @Container static final GenericContainer<?> REDIS=CatalogInitializationTest.REDIS;
    CatalogInitializationTest fixture;
    @BeforeAll static void startFixture() throws Exception {CatalogInitializationTest.startFixture();}
    @BeforeEach void prepare() throws Exception {fixture=new CatalogInitializationTest();fixture.prepare();}
    @AfterEach void finish(){fixture.closeReader();}
    @AfterAll static void closeFixture() throws Exception {CatalogInitializationTest.closeFixture();}

    @Test void actualPhysicalSchemaChecksumAndMismatchedSourceAndWriterManifestRejectAdmission() {
        String hash=CatalogSchemaInstaller.checksum();
        try {
            one.update("UPDATE advertiser_catalog_schema_history SET script_sha256=? WHERE version=1","0".repeat(64));
            assertProtocolFailure(()->fixture.binding.verify(false),CatalogProtocol.Failure.NOT_READY);
        } finally {one.update("UPDATE advertiser_catalog_schema_history SET script_sha256=? WHERE version=1",hash);}
        fixture.service.initialize(fixture.id,fixture.input(),"owner","准入反例");fixture.service.advance(fixture.id);fixture.service.advance(fixture.id);fixture.service.advance(fixture.id);
        assertProtocolFailure(()->fixture.binding.verify(true),CatalogProtocol.Failure.NOT_READY);
        zero.update("UPDATE catalog_shard_control SET source_id=?",UUID.randomUUID());
        assertProtocolFailure(()->fixture.binding.verify(false),CatalogProtocol.Failure.INCONSISTENT);
        zero.update("UPDATE catalog_shard_control SET source_id=?",SOURCE);
        one.update("UPDATE catalog_shard_control SET writer_manifest='{\"init-test\":7,\"duplicate\":7}'");
        assertProtocolFailure(()->fixture.binding.verify(false),CatalogProtocol.Failure.INCONSISTENT);
        one.update("UPDATE catalog_shard_control SET writer_manifest='{\"wrong-instance\":8}'");
        assertProtocolFailure(()->fixture.binding.verify(false),CatalogProtocol.Failure.INCONSISTENT);
        assertThat(one.queryForObject("SELECT count(*) FROM catalog_outbox WHERE purpose='KAFKA'",Integer.class)).isZero();
    }
    @Test void switchesRejectLegacyResourceMissingWriterAndOldPublisherBeforeOrdinaryAdmission() throws Exception {
        var config=new CatalogRuntimeConfiguration();var env=new org.springframework.mock.env.MockEnvironment();
        var valid=new CatalogProperties(true,true,false,false,false,SOURCE,"init-test",7);
        assertThatThrownBy(()->config.catalogSwitchGuard(valid,env).afterPropertiesSet()).hasMessageContaining("可靠分片资源");
        env.setProperty("spring.datasource.url","jdbc:shardingsphere:classpath:sharding-catalog-reliable.yaml?placeholder-type=environment");
        assertThatThrownBy(()->config.catalogSwitchGuard(valid,env).afterPropertiesSet()).hasMessageContaining("唯一writer");
        env.setProperty("CATALOG_WRITER_ID","7");env.setProperty("recsys.ad.catalog.publish-events","true");
        assertThatThrownBy(()->config.catalogSwitchGuard(valid,env).afterPropertiesSet()).hasMessageContaining("旧目录事件");
        assertThatThrownBy(()->config.catalogSwitchGuard(new CatalogProperties(false,false,false,true,false,null,null,null),env).afterPropertiesSet()).hasMessageContaining("子开关");
    }
}
