package com.recsys.advertiser.catalog;

import com.recsys.advertiser.persistence.mapper.primary.*;
import com.recsys.persistence.spring.SpringSqlSessions;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.util.*;
import static com.recsys.advertiser.catalog.CatalogPhysicalTransactionTest.*;
import static com.recsys.advertiser.catalog.CatalogProtocol.*;
import static org.assertj.core.api.Assertions.*;

/** 先以两台独占PG和产品ShardingSphere验证V001升级、任务路由、原清单和最终化SQL。 */
@Testcontainers @Tag("catalog-reliability")
class CatalogStatusPersistenceTest {
    @Container static final PostgreSQLContainer<?> PG0=ZERO,PG1=ONE;
    @Container static final KafkaFixture BROKER=KAFKA;
    static CatalogTaskMapper tasks;
    @BeforeAll static void setup() throws Exception {
        CatalogPhysicalTransactionTest.start(true);
        tasks=new SqlSessionTemplate(SpringSqlSessions.create(sharded,"classpath*:mapper/advertiser/primary/Catalog*.xml")).getMapper(CatalogTaskMapper.class);
    }
    @BeforeEach void resetFixture(){new CatalogPhysicalTransactionTest().arrangeExclusiveDatabase();zero.execute("TRUNCATE catalog_task_item");one.execute("TRUNCATE catalog_task_item");}
    @AfterAll static void finish() throws Exception{CatalogPhysicalTransactionTest.close();}

    @Test void publishedV1AndAppendV2RetainSeparateChecksumsAndInstallerIsIdempotent() throws Exception {
        for(int shard=0;shard<2;shard++) {
            assertThat(tasks.schemaChecksum(shard,1)).isEqualTo("8e11dec460441405a0b1b1982f50fa70a169788fccc2c506563811f14300787d");
            assertThat(tasks.schemaChecksum(shard,2)).isEqualTo(CatalogSchemaInstaller.checksum(2));
            CatalogSchemaInstaller.install(shard==0?ds0:ds1,shard);
        }
        assertThatThrownBy(()->CatalogSchemaInstaller.install(ds0,1)).hasMessageContaining("IDENTITY_MISMATCH");
        new CatalogPhysicalTransactionTest().explicitInstallerOnlyVerifiesExistingIdentityAndNeverReassignsPhysicalShard();
    }
    @Test void routedFrozenPagesAndCountsCannotCancelDispatchedOrRewriteTerminalResult() {
        var key=CommandKey.advertiser(SOURCE,1,UUID.randomUUID());
        var first=target(0,102,1);var second=target(1,103,1);
        assertThat(tasks.insert(key,first)).isOne();assertThat(tasks.insert(key,second)).isOne();
        assertThat(zero.queryForObject("SELECT count(*) FROM catalog_task_item",Integer.class)).isZero();
        assertThat(one.queryForObject("SELECT count(*) FROM catalog_task_item",Integer.class)).isEqualTo(2);
        assertThat(tasks.adPage(1,0)).containsExactly(102L);
        assertThat(tasks.nextFanout(key).targetId()).isEqualTo(102);
        assertThat(tasks.dispatchFanout(key,102)).isOne();assertThat(tasks.completeFanout(key,102,utf8("original"))).isOne();
        assertThat(tasks.counts(key).completed()).isOne();assertThat(tasks.counts(key).pending()).isOne();
        assertThat(tasks.page(key,-1)).hasSize(2);
        assertThatThrownBy(()->one.update("UPDATE catalog_task_item SET expected_version=9 WHERE target_id=103")).hasMessageContaining("IMMUTABLE_TARGET");
        assertThatThrownBy(()->one.update("UPDATE catalog_task_item SET result_bytes=? WHERE target_id=102",utf8("new"))).hasMessageContaining("IMMUTABLE_TARGET_RESULT");
    }
    @Test void batchBudgetsPersistBeforeUnknownAndFreshAuditRoundOnlyRestartsOriginalTarget() {
        var key=new CommandKey(0,SOURCE,ScopeKind.STATUS_BATCH,"STATUS_BATCH",UUID.randomUUID());
        for(long id=1;id<=2;id++)tasks.insert(key,new CatalogTaskMapper.Target(id-1,"STATUS_OWNER",id,id,1L,UUID.randomUUID(),"READY",null,null,"a".repeat(64),"ADMIT",1,0,null));
        assertThat(tasks.nextBatch(key).targetId()).isOne();
        for(int attempt=0;attempt<3;attempt++){assertThat(tasks.beginObservation(key,1)).isOne();assertThat(tasks.unknown(key,1,"RESULT_UNKNOWN")).isOne();}
        assertThat(tasks.target(key,"STATUS_OWNER",1).state()).isEqualTo("PAUSED");assertThat(tasks.beginObservation(key,1)).isZero();
        assertThat(tasks.nextBatch(key).targetId()).isEqualTo(2);
        assertThat(tasks.cancelUndispatched(key,utf8("audit"))).isOne();
        assertThat(tasks.retryTarget(key,1,1)).isOne();assertThat(tasks.retryTarget(key,1,1)).isZero();
        assertThat(tasks.target(key,"STATUS_OWNER",1).round()).isEqualTo(2);
        assertThat(tasks.counts(key).cancelled()).isOne();assertThat(tasks.counts(key).pending()).isOne();
        assertThat(tasks.observed(key,1,"COMPLETED","WAIT",utf8("done"),null)).isOne();
        assertThat(tasks.counts(key).complete()).isFalse();
    }

    @Test void concurrentFreshInstallAndLockedV2UpgradeRemainBoundedAndPreservePublishedFacts() throws Exception {
        var fresh=freshDatabase();var pool=java.util.concurrent.Executors.newFixedThreadPool(2);var start=new java.util.concurrent.CountDownLatch(1);
        try {
            var a=pool.submit(()->{start.await();CatalogSchemaInstaller.install(fresh,0);return true;});
            var b=pool.submit(()->{start.await();CatalogSchemaInstaller.install(fresh,0);return true;});start.countDown();
            assertThat(a.get(12,java.util.concurrent.TimeUnit.SECONDS)).isTrue();assertThat(b.get(12,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(new org.springframework.jdbc.core.JdbcTemplate(fresh).queryForObject("SELECT count(*) FROM advertiser_catalog_schema_history",Integer.class)).isEqualTo(2);
        } finally {pool.shutdownNow();}
        var old=freshDatabase();installPublishedV1(old);var db=new org.springframework.jdbc.core.JdbcTemplate(old);
        UUID id=UUID.randomUUID();db.update("INSERT INTO catalog_identity(shard_id,source_id,command_id,entity_id) VALUES(0,?,?,123456)",SOURCE,id);
        db.update("INSERT INTO catalog_task_item(shard_id,parent_scope,parent_command_id,ordinal,target_kind,target_id,target_owner_id,expected_version,child_command_id,page_sha256) VALUES(0,'INITIALIZE:INITIALIZE',?,0,'AD',102,1,1,?,?)",id,UUID.randomUUID(),"c".repeat(64));
        String original=db.queryForObject("SELECT row_to_json(t)::text FROM catalog_identity t",String.class);
        try(var lock=old.getConnection()) {
            lock.setAutoCommit(false);try(var statement=lock.createStatement()){statement.execute("LOCK TABLE catalog_task_item IN ACCESS EXCLUSIVE MODE");}
            long began=System.nanoTime();assertThatThrownBy(()->CatalogSchemaInstaller.install(old,0)).hasStackTraceContaining("lock timeout");
            assertThat(java.time.Duration.ofNanos(System.nanoTime()-began)).isLessThan(java.time.Duration.ofSeconds(7));
            assertThat(db.queryForObject("SELECT count(*) FROM advertiser_catalog_schema_history",Integer.class)).isOne();lock.rollback();
        }
        one.update("UPDATE catalog_shard_control SET writer_manifest=CAST(? AS jsonb)","{\"init-test\":7}");
        String yaml=resource("/sharding-catalog-reliable.yaml");
        var logical=org.apache.shardingsphere.driver.api.yaml.YamlShardingSphereDataSourceFactory.createDataSource(Map.of("ds_0",old,"ds_1",source(ONE)),
                bytes(yaml.substring(yaml.indexOf("\nrules:")+1).replace("$${CATALOG_WRITER_ID::0}","16")));
        try(var close=(AutoCloseable)logical) {
            var mapper=new SqlSessionTemplate(SpringSqlSessions.create(logical,"classpath*:mapper/advertiser/primary/CatalogLocalMapper.xml")).getMapper(CatalogLocalMapper.class);
            var properties=new CatalogProperties(true,false,false,false,true,SOURCE,"init-test",7);
            assertThatThrownBy(()->new CatalogRuntimeBinding(properties,mapper,new com.fasterxml.jackson.databind.ObjectMapper()))
                    .isInstanceOfSatisfying(CatalogException.class,e->assertThat(e.failure()).isEqualTo(Failure.NOT_READY));
            CatalogSchemaInstaller.install(old,0);
            assertThat(new CatalogRuntimeBinding(properties,mapper,new com.fasterxml.jackson.databind.ObjectMapper()).verify(false)).hasSize(2);
        }
        assertThat(db.queryForObject("SELECT row_to_json(t)::text FROM catalog_identity t",String.class)).isEqualTo(original);
        assertThat(db.queryForObject("SELECT phase FROM catalog_task_item",String.class)).isEqualTo("ADMIT");
        assertThat(db.queryForObject("SELECT script_sha256 FROM advertiser_catalog_schema_history WHERE version=1",String.class)).isEqualTo(CatalogSchemaInstaller.checksum());
    }
    @Test void routedTaskQueriesHaveRealLockWaitBudgetOutsideTransaction() throws Exception {
        var key=CommandKey.advertiser(SOURCE,1,UUID.randomUUID());
        try(var lock=ds1.getConnection()) {
            lock.setAutoCommit(false);try(var statement=lock.createStatement()){statement.execute("LOCK TABLE catalog_task_item IN ACCESS EXCLUSIVE MODE");}
            long began=System.nanoTime();assertThatThrownBy(()->tasks.counts(key)).hasStackTraceContaining("canceling statement");
            assertThat(java.time.Duration.ofNanos(System.nanoTime()-began)).isLessThan(java.time.Duration.ofSeconds(8));lock.rollback();
        }
        assertThat(tasks.counts(key).total()).isZero();
    }
    private javax.sql.DataSource freshDatabase() {
        String name="status_upgrade_"+UUID.randomUUID().toString().replace("-","");zero.execute("CREATE DATABASE "+name);
        var ds=new org.springframework.jdbc.datasource.DriverManagerDataSource(ZERO.getJdbcUrl().replace("/"+ZERO.getDatabaseName(),"/"+name),ZERO.getUsername(),ZERO.getPassword());
        createBusinessTables(new org.springframework.jdbc.core.JdbcTemplate(ds));return ds;
    }
    private void installPublishedV1(javax.sql.DataSource source) throws Exception {
        try(var connection=source.getConnection()) {
            connection.setAutoCommit(false);
            try(var statement=connection.prepareStatement("SELECT set_config('recsys.catalog.install_shard','0',true),set_config('recsys.catalog.install_sha256',?,true)")){statement.setString(1,CatalogSchemaInstaller.checksum());statement.execute();}
            var runner=new org.apache.ibatis.jdbc.ScriptRunner(connection);runner.setAutoCommit(false);runner.setSendFullScript(true);runner.setStopOnError(true);runner.setLogWriter(null);runner.setErrorLogWriter(null);
            runner.runScript(new java.io.StringReader(resource("/db/ad-catalog/V001__catalog_recovery.sql")));
        }
    }
    private CatalogTaskMapper.Target target(long ordinal,long ad,long owner){return new CatalogTaskMapper.Target(ordinal,"FANOUT_AD",ad,owner,1L,UUID.randomUUID(),"READY",null,null,"b".repeat(64),"ADMIT",1,0,null);}
}
