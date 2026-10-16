package com.recsys.advertiser.catalog;

import com.recsys.advertiser.persistence.mapper.primary.CatalogLocalMapper;
import com.recsys.advertiser.persistence.model.GeneratedId;
import com.recsys.persistence.spring.SpringSqlSessions;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.shardingsphere.driver.api.yaml.YamlShardingSphereDataSourceFactory;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/** 首先验证实际5.5.0解析和物理PG事务；这组探针不冒充完整Controller/Kafka验收。 */
@Testcontainers
@Tag("catalog-reliability")
class CatalogPhysicalTransactionTest {
    @Container static final PostgreSQLContainer<?> ZERO = new PostgreSQLContainer<>("postgres:16-alpine");
    @Container static final PostgreSQLContainer<?> ONE = new PostgreSQLContainer<>("postgres:16-alpine");
    @Container static final KafkaFixture KAFKA = new KafkaFixture();
    static {pinRestartPort(ZERO,5432);pinRestartPort(ONE,5432);pinRestartPort(KAFKA,19092);}
    /** 为本次独占容器选择空闲loopback端口；显式HostPort使真实stop/start不会变成端点迁移。 */
    private static void pinRestartPort(org.testcontainers.containers.GenericContainer<?> container,int internalPort) {
        container.withCreateContainerCmdModifier(command->{
            try(var socket=new java.net.ServerSocket(0,1,java.net.InetAddress.getLoopbackAddress())) {
                command.getHostConfig().withPortBindings(new com.github.dockerjava.api.model.PortBinding(
                        com.github.dockerjava.api.model.Ports.Binding.bindIpAndPort("127.0.0.1",socket.getLocalPort()),
                        com.github.dockerjava.api.model.ExposedPort.tcp(internalPort)));
            } catch(java.io.IOException unavailable){throw new IllegalStateException("无法分配独占故障验收端口",unavailable);}
        });
    }
    static HikariDataSource ds0, ds1;
    static JdbcTemplate zero, one;
    static DataSource sharded;
    static CatalogLocalMapper mapper;
    static TransactionTemplate transaction;
    static UUID SOURCE;
    static CatalogKafkaPublisher.Identity kafkaIdentity;
    UUID command; String step;

    @BeforeAll
    static void start() throws Exception {
        ds0 = source(ZERO); ds1 = source(ONE);
        zero = new JdbcTemplate(ds0); one = new JdbcTemplate(ds1);
        for (JdbcTemplate db : List.of(zero, one)) {
            create(db,"advertiser","advertiser_id bigint PRIMARY KEY,name text,daily_budget double precision,status text");
            create(db,"ad","ad_id bigint PRIMARY KEY,advertiser_id bigint,item_id bigint,title text,landing_url text,quality_score double precision,status text,review_status text,optimization_type text,target_cpa double precision,audience_id bigint,review_reason text");
            create(db,"bidword","id bigint PRIMARY KEY,ad_id bigint,keyword text,match_type text,bid double precision,bid_mode text");
            create(db,"ad_creative","creative_id bigint PRIMARY KEY,ad_id bigint,title text,landing_url text,status text,review_status text DEFAULT 'approved'");
        }
        // 单表元数据必须在真实ShardingSphere启动前存在；业务用例从真实PG读item与派生向量。
        create(zero,"item","item_id bigint PRIMARY KEY");zero.update("INSERT INTO item VALUES(10)");
        create(zero,"item_embedding","item_id bigint PRIMARY KEY,embedding text,model text");
        // 无新迁移先实际加载旧profile，缺治理表不能破坏default-off的原数据源启动。
        try (var legacy = (AutoCloseable) sharding("/sharding.yaml")) {
            var old = new JdbcTemplate((DataSource) legacy);
            old.update("INSERT INTO advertiser(advertiser_id,name,daily_budget,status) VALUES(1,'legacy',100,'active')");
            assertThat(one.queryForObject("SELECT name FROM advertiser WHERE advertiser_id=1",String.class)).isEqualTo("legacy");
            assertThat(zero.queryForObject("SELECT count(*) FROM advertiser",Integer.class)).isZero();
            old.update("DELETE FROM advertiser WHERE advertiser_id=1");
        }
        // 旧逻辑源关闭可能关闭其传入物理池；重新建独立测试池，两个容器本身不重启。
        ds0 = source(ZERO); ds1 = source(ONE); zero = new JdbcTemplate(ds0); one = new JdbcTemplate(ds1);
        for (int shard=0; shard<2; shard++) CatalogSchemaInstaller.install(shard==0?ds0:ds1,shard);
        sharded = sharding("/sharding-catalog-reliable.yaml");
        var factory = SpringSqlSessions.create(sharded,"classpath*:mapper/advertiser/primary/CatalogLocalMapper.xml");
        mapper = new SqlSessionTemplate(factory).getMapper(CatalogLocalMapper.class);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(sharded));
        transaction.setTimeout(5);
        try(var admin=org.apache.kafka.clients.admin.AdminClient.create(Map.of("bootstrap.servers",KAFKA.bootstrap(),"request.timeout.ms",3000,"default.api.timeout.ms",5000))) {
            admin.createTopics(List.of(new org.apache.kafka.clients.admin.NewTopic(com.recsys.common.ad.VersionedAdCatalogEvent.TOPIC,1,(short)1))).all().get(10,java.util.concurrent.TimeUnit.SECONDS);
        }
        kafkaIdentity=CatalogKafkaPublisher.identity(KAFKA.bootstrap());
    }

    @BeforeEach
    void arrangeExclusiveDatabase() {
        SOURCE=UUID.randomUUID();command = UUID.randomUUID(); step="ADVERTISER:1:"+command+":OWNER";
        for (int shard=0; shard<2; shard++) {
            JdbcTemplate db=shard==0?zero:one;
            db.update("UPDATE catalog_shard_control SET mode='LEGACY'");
            db.execute("TRUNCATE advertiser,ad,bidword,ad_creative,catalog_advertiser_guard,catalog_step,catalog_outbox,catalog_identity,catalog_command");
            db.update("UPDATE catalog_shard_control SET source_id=?::uuid,mode='ACTIVE',initialization_ready=true,topology_version=?",SOURCE,CatalogProtocol.TOPOLOGY);
        }
        one.update("UPDATE catalog_shard_control SET mode='LEGACY'");
        one.update("INSERT INTO advertiser VALUES(1,'owner',100,'active')");
        one.update("INSERT INTO ad(ad_id,advertiser_id,item_id,title,landing_url,quality_score,status,review_status,optimization_type) VALUES(102,1,10,'before','/landing',1,'active','approved','CPC')");
        one.update("UPDATE catalog_shard_control SET mode='ACTIVE'");
        one.update("INSERT INTO catalog_advertiser_guard(shard_id,advertiser_id,active_command_id,active_command_home,active_command_scope) VALUES(1,1,?::uuid,1,'ADVERTISER:1')",command);
        localStep(one,1,step,"[{\"table\":\"ad\",\"operation\":\"UPDATE\",\"id\":102}]");
    }

    @AfterAll static void close() throws Exception {
        if(sharded instanceof AutoCloseable c)c.close();
        if(ds0!=null)ds0.close(); if(ds1!=null)ds1.close();
    }

    @Test void actualProfileRoutesControlAndSnowflakeReservationToBothPhysicalDatabases() {
        assertThat(mapper.control(0).shardId()).isZero(); assertThat(mapper.control(1).shardId()).isOne();
        for(int shard=0;shard<2;shard++) {
            int target=shard;
            GeneratedId key=new GeneratedId();
            transaction.executeWithoutResult(s->mapper.reserveIdentity(target,SOURCE,command,key));
            assertThat(key.getValue()).isPositive();
            assertThat(mapper.identity(shard,SOURCE,command)).isEqualTo(key.getValue());
            assertThat((shard==0?zero:one).queryForObject("SELECT entity_id FROM catalog_identity",Long.class)).isEqualTo(key.getValue());
        }
    }

    @Test void sourceReceiptAndFactShareActualPgTransactionOnOddOwner() {
        String txid=transaction.execute(s->{
            String tx=mapper.enterStep(1,step,1);
            assertThat(mapper.updateAdTitle(1,102,"after")).isOne();
            finish(1,step,"fact",effects()); return tx;
        });
        assertThat(one.queryForObject("SELECT title FROM ad WHERE ad_id=102",String.class)).isEqualTo("after");
        assertThat(one.queryForObject("SELECT write_txid FROM catalog_step WHERE step_key=?",String.class,step)).isEqualTo(txid);
        assertThat(one.queryForObject("SELECT write_txid FROM catalog_outbox",String.class)).isEqualTo(txid);
        assertThat(one.queryForObject("SELECT xmin::text FROM ad WHERE ad_id=102",String.class))
                .isEqualTo(new java.math.BigInteger(txid).and(new java.math.BigInteger("ffffffff",16)).toString());
        assertThat(zero.queryForObject("SELECT count(*) FROM catalog_outbox",Integer.class)).isZero();
        assertThat(zero.queryForObject("SELECT count(*) FROM ad",Integer.class)).isZero();
    }

    @Test void missingReceiptAndMissingLocalFactRollbackInsteadOfTrustingLogicalConnection() {
        assertCatalogFailure(()->transaction.executeWithoutResult(s->{mapper.enterStep(1,step,1);mapper.updateAdTitle(1,102,"leak");}),"CATALOG_MISSING_LOCAL_RECEIPT");
        assertOriginal();
        assertCatalogFailure(()->transaction.executeWithoutResult(s->{
            mapper.enterStep(1,step,1);mapper.updateAdTitle(1,102,"leak");
            mapper.completeStep(1,step,1,bytes("{}"),hash("{}"),effects(),"wrong-shard");
        }),"CATALOG_MISSING_LOCAL_OUTBOX");
        assertOriginal();
    }

    @Test void factInOtherPhysicalDatabaseCannotSatisfyOwnerDeferredConstraint() {
        // 故意在另一物理库预置事实，模拟公共SINGLE Outbox：owner提交仍必须失败。
        mapper.insertFact(0,"wrong-shard",SOURCE,"ADVERTISER:1",command,"OWNER",bytes("{}"),hash("{}"));
        assertCatalogFailure(()->transaction.executeWithoutResult(s->{
            mapper.enterStep(1,step,1);mapper.updateAdTitle(1,102,"leak");
            mapper.completeStep(1,step,1,bytes("{}"),hash("{}"),effects(),"wrong-shard");
        }),"CATALOG_MISSING_LOCAL_OUTBOX");
        assertOriginal(); assertThat(zero.queryForObject("SELECT count(*) FROM catalog_outbox",Integer.class)).isOne();
    }

    @Test void sourceWithoutContextWrongFenceAndWrongEffectAreRejected() {
        assertThatThrownBy(()->mapper.updateAdTitle(1,102,"legacy")).hasStackTraceContaining("CATALOG_WRITE_WITHOUT_STEP");
        assertCatalogFailure(()->transaction.executeWithoutResult(s->mapper.enterStep(1,step,0)),"CATALOG_STEP_FENCED");
        assertCatalogFailure(()->transaction.executeWithoutResult(s->{
            mapper.enterStep(1,step,1);mapper.updateAdTitle(1,102,"leak");
            assertThat(mapper.completeStep(1,step,1,bytes("{}"),hash("{}"),"[]","fact")).isZero();
        }),"CATALOG_MISSING_LOCAL_RECEIPT");
        assertOriginal();
    }

    @Test void committedOriginalBytesAndAppliedReceiptCannotBeRewritten() {
        transaction.executeWithoutResult(s->{mapper.enterStep(1,step,1);mapper.updateAdTitle(1,102,"after");finish(1,step,"fact",effects());});
        assertThatThrownBy(()->one.update("UPDATE catalog_outbox SET payload_bytes='new'::bytea")).hasStackTraceContaining("CATALOG_IMMUTABLE_OUTBOX");
        assertThatThrownBy(()->one.update("UPDATE catalog_step SET state='READY'")).hasStackTraceContaining("CATALOG_IMMUTABLE_RECEIPT");
        assertThatThrownBy(()->one.update("DELETE FROM catalog_outbox")).hasStackTraceContaining("CATALOG_FACT_DELETE_FORBIDDEN");
        assertThatThrownBy(()->one.execute("TRUNCATE ad")).hasStackTraceContaining("CATALOG_TRUNCATE_FORBIDDEN");
    }

    @Test void allNewTablesAndColumnsHaveChineseComments() {
        for(JdbcTemplate db:List.of(zero,one)) {
            assertThat(db.queryForObject("SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public' AND c.relkind='r' AND (c.relname LIKE 'catalog_%' OR c.relname='advertiser_catalog_schema_history') AND obj_description(c.oid) IS NULL",Integer.class)).isZero();
            assertThat(db.queryForObject("SELECT count(*) FROM pg_attribute a JOIN pg_class c ON c.oid=a.attrelid JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public' AND c.relkind='r' AND (c.relname LIKE 'catalog_%' OR c.relname='advertiser_catalog_schema_history') AND a.attnum>0 AND NOT a.attisdropped AND col_description(c.oid,a.attnum) IS NULL",Integer.class)).isZero();
        }
    }

    @Test void advertiserOwnershipPrecedesOtherCommandAndOriginalRequestRemainsImmutable() {
        releaseFixtureGuard();
        var repository=commands();
        var key=CatalogProtocol.CommandKey.advertiser(SOURCE,1,command);
        var request=new CatalogProtocol.Submission(key,CatalogProtocol.Kind.AD_UPDATE,bytes("{\"title\":\"a\"}"),"actor-a","测试原命令");
        var first=repository.admit(request);
        assertThat(first.state()).isEqualTo("ADMITTED");
        assertThat(repository.admit(request).commandId()).isEqualTo(command);
        assertThat(one.queryForObject("SELECT active_command_id FROM catalog_advertiser_guard",UUID.class)).isEqualTo(command);
        assertProtocolFailure(()->repository.admit(new CatalogProtocol.Submission(key,CatalogProtocol.Kind.AD_UPDATE,bytes("different"),"actor-a","测试原命令")),CatalogProtocol.Failure.CONFLICT);
        var other=CatalogProtocol.CommandKey.advertiser(SOURCE,1,UUID.randomUUID());
        assertProtocolFailure(()->repository.admit(new CatalogProtocol.Submission(other,CatalogProtocol.Kind.AD_DELETE,bytes("delete"),"actor-a","另一个命令")),CatalogProtocol.Failure.CONFLICT);
        assertThat(one.queryForObject("SELECT count(*) FROM catalog_command",Integer.class)).isOne();
    }

    @Test void concurrentRootCommandsReserveExactlyOneOriginalSnowflakeIdentity() throws Exception {
        var repository=commands();var key=CatalogProtocol.CommandKey.root(SOURCE,command);
        var request=new CatalogProtocol.Submission(key,CatalogProtocol.Kind.ROOT_CREATE,bytes("{\"name\":\"new\"}"),"original-actor","开户");
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var ready=new java.util.concurrent.CountDownLatch(2);var start=new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Callable<CatalogProtocol.CommandRow> task=()->{ready.countDown();start.await();return repository.admitRoot(request);};
            var a=pool.submit(task);var b=pool.submit(task);assertThat(ready.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();start.countDown();
            var first=a.get(10,java.util.concurrent.TimeUnit.SECONDS);var second=b.get(10,java.util.concurrent.TimeUnit.SECONDS);
            assertThat(first.advertiserId()).isPositive().isEqualTo(second.advertiserId());
            assertThat(repository.find(key).actor()).isEqualTo("original-actor");
            assertThat(mapper.identity(key.shard(),SOURCE,command)).isEqualTo(first.advertiserId());
            assertThat((key.shard()==0?zero:one).queryForObject("SELECT count(*) FROM catalog_identity",Integer.class)).isOne();
            assertThat((key.shard()==0?one:zero).queryForObject("SELECT count(*) FROM catalog_identity",Integer.class)).isZero();
        }
    }

    @Test void expiredThirdOwnerClaimPausesWithoutReleasingAnotherCommand() {
        releaseFixtureGuard();var repository=commands();var key=CatalogProtocol.CommandKey.advertiser(SOURCE,1,command);
        repository.admit(new CatalogProtocol.Submission(key,CatalogProtocol.Kind.AD_UPDATE,bytes("original"),"actor","退出预算"));
        var first=repository.claim(key);assertThat(first.attempts()).isOne();
        one.update("UPDATE catalog_command SET lease_until=clock_timestamp()-interval '1 second'");
        var second=repository.claim(key);assertThat(second.attempts()).isEqualTo(2);assertThat(second.fence()).isGreaterThan(first.fence());
        assertProtocolFailure(()->repository.advance(first,"FINAL",null,bytes("{}")),CatalogProtocol.Failure.FENCED);
        one.update("UPDATE catalog_command SET lease_until=clock_timestamp()-interval '1 second'");
        assertThat(repository.claim(key).attempts()).isEqualTo(3);
        one.update("UPDATE catalog_command SET lease_until=clock_timestamp()-interval '1 second'");
        assertThat(repository.claim(key)).isNull();assertThat(repository.find(key).state()).isEqualTo("PAUSED");
        assertThat(one.queryForObject("SELECT active_command_id FROM catalog_advertiser_guard",UUID.class)).isEqualTo(command);
        assertThat(repository.claim(key)).isNull();assertThat(repository.find(key).attempts()).isEqualTo(3);
    }

    @Test void localReceiptIsReusedWithoutInvokingMutationAgainAndOldLocalFenceCannotWrite() {
        var key=CatalogProtocol.CommandKey.advertiser(SOURCE,1,command);
        var request=new CatalogProtocol.StepRequest(1,key,"FINAL",1,102L,1L,bytes("frozen"),"[{\"table\":\"ad\",\"operation\":\"UPDATE\",\"id\":102}]");
        var repository=new CatalogStepRepository(mapper,new CatalogLocalTransactions(sharded,mapper),new com.fasterxml.jackson.databind.ObjectMapper());
        repository.register(request);var first=repository.claim(request);assertThat(first.attempts()).isOne();
        one.update("UPDATE catalog_step SET lease_until=clock_timestamp()-interval '1 second' WHERE step_key=?",request.stepKey());
        var second=repository.claim(request);assertThat(second.attempts()).isEqualTo(2);
        assertCatalogFailure(()->repository.apply(request,first,()->{throw new AssertionError("迟到本地步骤不应执行业务");}),"CATALOG_STEP_FENCED");
        byte[] result=bytes("{\"effects\":"+effects()+",\"title\":\"after\"}");
        var applied=repository.apply(request,second,()->{mapper.updateAdTitle(1,102,"after");return new CatalogStepRepository.LocalResult(result,effects());});
        assertThat(applied.applied()).isTrue();assertThat(applied.resultBytes()).isEqualTo(result);
        var again=repository.apply(request,second,()->{throw new AssertionError("APPLIED不得重复执行");});
        assertThat(again.resultBytes()).isEqualTo(result);assertThat(again.writeTxid()).isEqualTo(applied.writeTxid());
        // 命令完成释放后，另一命令可占有owner；迟到旧执行器只读旧receipt，不能重新夺锁或写业务。
        one.update("UPDATE catalog_advertiser_guard SET active_command_id=?",UUID.randomUUID());
        assertThat(repository.register(request).resultBytes()).isEqualTo(result);
        assertThat(repository.claim(request).resultBytes()).isEqualTo(result);
        assertThat(repository.apply(request,second,()->{throw new AssertionError("新命令占有后不得重写旧效果");}).resultBytes()).isEqualTo(result);
    }

    @Test void localTransactionRefusesLegacyOuterTransaction() {
        var local=new CatalogLocalTransactions(sharded,mapper);
        assertThatThrownBy(()->transaction.execute(s->local.on(1,()->mapper.control(1)))).hasMessageContaining("不允许嵌套");
    }

    @Test void generatedAdIdAndDefaultLandingMustAppearInOriginalPhysicalReceipt() {
        var key=CatalogProtocol.CommandKey.advertiser(SOURCE,1,command);
        var request=new CatalogProtocol.StepRequest(1,key,"CREATE",1,null,1L,bytes("create frozen"),
                "[{\"table\":\"ad\",\"operation\":\"INSERT\",\"generated\":true},{\"table\":\"ad\",\"operation\":\"UPDATE\",\"generated\":true}]");
        var repository=steps();repository.register(request);var claim=repository.claim(request);var generated=new GeneratedId();
        var result=repository.apply(request,claim,()->{
            assertThat(mapper.insertAd(generated,1,10,"new",null,1,"active","pending_review",null,"CPC",null,null)).isOne();
            long id=generated.valueOrZero();assertThat(id).isPositive();
            assertThat(mapper.setAdLanding(1,id,"https://example.com/ad/"+id)).isOne();
            String effects="[{\"table\":\"ad\",\"operation\":\"INSERT\",\"id\":"+id+"},{\"table\":\"ad\",\"operation\":\"UPDATE\",\"id\":"+id+"}]";
            return localResult(effects);
        });
        assertThat(result.applied()).isTrue();
        assertThat(one.queryForObject("SELECT landing_url FROM ad WHERE ad_id=?",String.class,generated.valueOrZero())).isEqualTo("https://example.com/ad/"+generated.valueOrZero());
        assertThat(zero.queryForObject("SELECT count(*) FROM ad",Integer.class)).isZero();
        assertThat(result.resultBytes()).containsExactly(one.queryForObject("SELECT payload_bytes FROM catalog_outbox WHERE outbox_id=?",byte[].class,result.outboxId()));
    }

    @Test void oppositeChildShardKeepsExistingPendingAndOriginalNewCreativeDefault() {
        zero.update("UPDATE catalog_shard_control SET mode='LEGACY'");
        zero.update("INSERT INTO ad_creative(creative_id,ad_id,title,status,review_status) VALUES(10,102,'old','active','pending_review')");
        zero.update("UPDATE catalog_shard_control SET mode='ACTIVE'");
        var key=CatalogProtocol.CommandKey.advertiser(SOURCE,1,command);
        var request=new CatalogProtocol.StepRequest(0,key,"CHILDREN",1,102L,1L,bytes("children frozen"),
                "[{\"table\":\"bidword\",\"operation\":\"INSERT\",\"generated\":true},{\"table\":\"ad_creative\",\"operation\":\"INSERT\",\"generated\":true}]");
        var repository=steps();repository.register(request);var claim=repository.claim(request);
        var bid=new GeneratedId();var creative=new GeneratedId();
        var receipt=repository.apply(request,claim,()->{
            mapper.insertBidword(bid,102,"word","EXACT",1,"CPC");
            mapper.insertCreative(creative,102,"creative","/landing","active");
            String effects="[{\"table\":\"bidword\",\"operation\":\"INSERT\",\"id\":"+bid.valueOrZero()+"},{\"table\":\"ad_creative\",\"operation\":\"INSERT\",\"id\":"+creative.valueOrZero()+"}]";
            return localResult(effects);
        });
        assertThat(zero.queryForObject("SELECT review_status FROM ad_creative WHERE creative_id=?",String.class,creative.valueOrZero())).isEqualTo("approved");
        assertThat(zero.queryForObject("SELECT review_status FROM ad_creative WHERE creative_id=10",String.class)).isEqualTo("pending_review");
        assertThat(one.queryForObject("SELECT count(*) FROM bidword",Integer.class)).isZero();
        assertThat(one.queryForObject("SELECT count(*) FROM ad_creative",Integer.class)).isZero();
        assertThat(zero.queryForObject("SELECT write_txid FROM catalog_outbox",String.class)).isEqualTo(receipt.writeTxid());
        assertThat(one.queryForObject("SELECT count(*) FROM catalog_outbox",Integer.class)).isZero();
    }

    @Test void generatedPermissionCannotUpdatePreExistingAdAndSourceFailureRollsBackRecordedEffects() {
        var key=CatalogProtocol.CommandKey.advertiser(SOURCE,1,command);
        var request=new CatalogProtocol.StepRequest(1,key,"CREATE",1,null,1L,bytes("create frozen"),
                "[{\"table\":\"ad\",\"operation\":\"INSERT\",\"generated\":true},{\"table\":\"ad\",\"operation\":\"UPDATE\",\"generated\":true}]");
        var repository=steps();repository.register(request);var claim=repository.claim(request);
        assertCatalogFailure(()->repository.apply(request,claim,()->{
            mapper.setAdLanding(1,102,"/bad");return localResult("[]");
        }),"CATALOG_WRITE_NOT_PLANNED");
        assertThat(repository.find(request).effects()).isNull();
        assertThat(one.queryForObject("SELECT landing_url FROM ad WHERE ad_id=102",String.class)).isEqualTo("/landing");
        assertThat(one.queryForObject("SELECT count(*) FROM catalog_outbox",Integer.class)).isZero();
    }

    @Test void explicitInstallerOnlyVerifiesExistingIdentityAndNeverReassignsPhysicalShard() throws Exception {
        CatalogSchemaInstaller.install(ds0,0);CatalogSchemaInstaller.install(ds1,1);
        assertThatThrownBy(()->CatalogSchemaInstaller.install(ds0,1)).hasMessageContaining("CATALOG_SCHEMA_IDENTITY_MISMATCH");
        assertThat(mapper.control(0).shardId()).isZero();
        assertThat(zero.queryForObject("SELECT script_sha256 FROM advertiser_catalog_schema_history",String.class)).isEqualTo(CatalogSchemaInstaller.checksum());
        assertThat(zero.queryForObject("SELECT count(*) FROM advertiser_catalog_schema_history",Integer.class)).isOne();
    }

    @Test void previousTransactionFactInSameDatabaseCannotProveCurrentBusinessCommit() {
        String result="{\"effects\":"+effects()+"}";
        mapper.insertFact(1,"previous",SOURCE,"ADVERTISER:1",command,"OWNER",bytes(result),hash(result));
        assertCatalogFailure(()->transaction.executeWithoutResult(s->{
            mapper.enterStep(1,step,1);mapper.updateAdTitle(1,102,"leak");
            assertThat(mapper.completeStep(1,step,1,bytes(result),hash(result),effects(),"previous")).isOne();
        }),"CATALOG_MISSING_LOCAL_OUTBOX");
        assertOriginal();assertThat(one.queryForObject("SELECT count(*) FROM catalog_outbox",Integer.class)).isOne();
    }

    @Test void realKafkaRelayPublishesExactLocalOriginalAndConfirmsBrokerCoordinates() throws Exception {
        var event=appendEvent();var outbox=outbox();var before=outbox.find(1,event.eventId());
        try(var publisher=publisher()) {
            assertThat(new CatalogRelay(outbox,publisher,SOURCE).advance(1)).isOne();
        }
        var after=outbox.find(1,event.eventId());
        assertThat(after.state()).isEqualTo("PUBLISHED");assertThat(after.attempts()).isOne();
        assertThat(after.payloadBytes()).isEqualTo(before.payloadBytes());
        var actual=consume(event.eventId(),1).getFirst();
        assertThat(actual.key()).isEqualTo(before.keyBytes());assertThat(actual.value()).isEqualTo(before.payloadBytes());
        assertThat(actual.partition()).isEqualTo(after.brokerPartition());assertThat(actual.offset()).isEqualTo(after.brokerOffset());
        assertThat(one.queryForObject("SELECT write_txid FROM catalog_step WHERE step_key=?",String.class,step)).isEqualTo(before.writeTxid());
        assertThat(zero.queryForObject("SELECT count(*) FROM catalog_outbox",Integer.class)).isZero();
        assertThat(one.queryForObject("SELECT count(*) FROM catalog_outbox WHERE purpose='COORDINATOR' AND state='READY'",Integer.class)).isOne();
    }

    @Test void realBrokerAckBeforeDatabaseMarkerAllowsOriginalDuplicateAndFencesLateConfirmation() throws Exception {
        var event=appendEvent();var outbox=outbox();var first=outbox.claim(1,SOURCE).getFirst();
        try(var publisher=publisher()) {
            var firstAck=publisher.send(first);
            // 明确的ACK后未标记窗口注入，不冒充杀JVM或网络丢包。
            one.update("UPDATE catalog_outbox SET lease_until=clock_timestamp()-interval '1 second' WHERE purpose='KAFKA'");
            var second=outbox.claim(1,SOURCE).getFirst();var secondAck=publisher.send(second);
            assertThat(second.fence()).isGreaterThan(first.fence());
            assertThat(outbox.published(first,firstAck.partition(),firstAck.offset())).isFalse();
            assertThat(outbox.published(second,secondAck.partition(),secondAck.offset())).isTrue();
            assertThat(secondAck.offset()).isGreaterThan(firstAck.offset());
        }
        var actual=consume(event.eventId(),2);assertThat(actual).hasSize(2);
        assertThat(actual.get(0).value()).isEqualTo(first.payloadBytes());assertThat(actual.get(1).value()).isEqualTo(first.payloadBytes());
        assertThat(actual.get(0).key()).isEqualTo(actual.get(1).key());
    }

    @Test void fifthSendingLeaseExpirationIsDurablyPausedAndWrongClusterIdentityIsRejected() {
        var event=appendEvent();var outbox=outbox();
        for(int attempt=1;attempt<=5;attempt++) {
            var claim=outbox.claim(1,SOURCE).getFirst();assertThat(claim.attempts()).isEqualTo(attempt);
            one.update("UPDATE catalog_outbox SET lease_until=clock_timestamp()-interval '1 second' WHERE purpose='KAFKA'");
        }
        assertThat(outbox.claim(1,SOURCE)).isEmpty();assertThat(outbox.find(1,event.eventId()).state()).isEqualTo("PAUSED");
        assertThat(outbox().claim(1,SOURCE)).isEmpty();assertThat(outbox.find(1,event.eventId()).attempts()).isEqualTo(5);
        assertProtocolFailure(()->new CatalogKafkaPublisher(KAFKA.bootstrap(),"wrong-cluster",kafkaIdentity.topicId()),CatalogProtocol.Failure.INCONSISTENT);
    }

    @Test void routedNativeSkipLockedDoesNotWaitForOtherRelayTransaction() throws Exception {
        var event=appendEvent();var locked=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        try(var pool=java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var owner=pool.submit(()->new CatalogLocalTransactions(sharded,mapper).on(1,()->{
                assertThat(mapper.lockReadyEventIds(1)).containsExactly(event.eventId());locked.countDown();
                try{assertThat(release.await(4,java.util.concurrent.TimeUnit.SECONDS)).isTrue();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
                return null;
            }));
            try {
                assertThat(locked.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                assertThat(outbox().claim(1,SOURCE)).isEmpty();
            } finally {release.countDown();}
            owner.get(5,java.util.concurrent.TimeUnit.SECONDS);
        }
        assertThat(outbox().claim(1,SOURCE)).hasSize(1);
    }

    @Test void legacyWriteControlLockBlocksQuiesceUntilItsRealTransactionCompletes() throws Exception {
        one.update("UPDATE catalog_shard_control SET mode='LEGACY'");
        var written=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        try(var pool=java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var writer=pool.submit(()->new CatalogLocalTransactions(sharded,mapper).on(1,()->{
                mapper.updateAdTitle(1,102,"legacy-committed");written.countDown();
                try{assertThat(release.await(4,java.util.concurrent.TimeUnit.SECONDS)).isTrue();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
                return null;
            }));
            try {
                assertThat(written.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                try(var connection=ds1.getConnection();var sql=connection.createStatement()) {
                    connection.setAutoCommit(false);sql.execute("SET LOCAL lock_timeout='200ms'");
                    assertThatThrownBy(()->sql.executeUpdate("UPDATE catalog_shard_control SET mode='QUIESCED'"))
                            .isInstanceOfSatisfying(java.sql.SQLException.class,e->assertThat(e.getSQLState()).isEqualTo("55P03"));
                    connection.rollback();
                }
            } finally {release.countDown();}
            writer.get(5,java.util.concurrent.TimeUnit.SECONDS);
        }
        one.update("UPDATE catalog_shard_control SET mode='QUIESCED'");
        assertThatThrownBy(()->mapper.updateAdTitle(1,102,"late-legacy")).hasStackTraceContaining("CATALOG_WRITER_CLOSED");
        assertThat(one.queryForObject("SELECT title FROM ad WHERE ad_id=102",String.class)).isEqualTo("legacy-committed");
    }

    @Test void sameCommandUuidInDifferentAdvertiserScopesDoesNotMergeTheirOwnership() {
        releaseFixtureGuard();
        zero.update("INSERT INTO catalog_advertiser_guard(shard_id,advertiser_id) VALUES(0,2)");
        var first=CatalogProtocol.CommandKey.advertiser(SOURCE,1,command);var second=CatalogProtocol.CommandKey.advertiser(SOURCE,2,command);
        commands().admit(new CatalogProtocol.Submission(first,CatalogProtocol.Kind.AD_UPDATE,bytes("owner-one"),"actor","原作用域一"));
        commands().admit(new CatalogProtocol.Submission(second,CatalogProtocol.Kind.AD_UPDATE,bytes("owner-two"),"actor","原作用域二"));
        assertThat(commands().find(first).requestBytes()).isEqualTo(bytes("owner-one"));
        assertThat(commands().find(second).requestBytes()).isEqualTo(bytes("owner-two"));
        assertThat(one.queryForObject("SELECT count(*) FROM catalog_command",Integer.class)).isOne();
        assertThat(zero.queryForObject("SELECT count(*) FROM catalog_command",Integer.class)).isOne();
    }

    @Test void rolledBackStepFailureHasDurableBudgetAndOriginalBytesWithoutTransactionReceipt() {
        var key=CatalogProtocol.CommandKey.advertiser(SOURCE,1,command);
        var request=new CatalogProtocol.StepRequest(1,key,"RETRY_FAILURE",1,102L,1L,bytes("original"),
                "[{\"table\":\"ad\",\"operation\":\"UPDATE\",\"id\":102}]");
        var repository=steps();repository.register(request);
        for(int attempt=1;attempt<=3;attempt++) {
            var claim=repository.claim(request);assertThat(claim.attempts()).isEqualTo(attempt);
            assertThatThrownBy(()->repository.apply(request,claim,()->{
                mapper.updateAdTitle(1,102,"must rollback");throw CatalogProtocol.failure(CatalogProtocol.Failure.RESULT_UNKNOWN);
            })).isInstanceOf(CatalogProtocol.CatalogException.class);
            assertThat(repository.failed(claim,CatalogProtocol.Failure.RESULT_UNKNOWN)).isTrue();
            assertThat(repository.failed(claim,CatalogProtocol.Failure.RESULT_UNKNOWN)).isFalse();
            var saved=repository.find(request);
            assertThat(saved.state()).isEqualTo(attempt==3?"PAUSED":"READY");
            assertThat(saved.requestBytes()).isEqualTo(bytes("original"));
            assertThat(saved.resultBytes()).isNull();assertThat(saved.writeTxid()).isNull();
            assertThat(one.queryForObject("SELECT title FROM ad WHERE ad_id=102",String.class)).isEqualTo("before");
            assertThat(one.queryForObject("SELECT count(*) FROM catalog_outbox",Integer.class)).isZero();
            // 仅前移可领取时间，保留尝试次数和原命令；不冒称真实等待或杀进程。
            one.update("UPDATE catalog_step SET available_at=clock_timestamp()-interval '1 second' WHERE step_key=?",request.stepKey());
        }
        assertThat(repository.claim(request)).isNull();
        assertThat(one.queryForObject("SELECT active_command_id FROM catalog_advertiser_guard",UUID.class)).isEqualTo(command);
    }

    @Test void failedCommandAndEventRetainFiniteBudgetsWithStableBackoff() {
        releaseFixtureGuard();var key=CatalogProtocol.CommandKey.advertiser(SOURCE,1,command);var repository=commands();
        repository.admit(new CatalogProtocol.Submission(key,CatalogProtocol.Kind.AD_UPDATE,bytes("original"),"actor","失败预算"));
        var first=repository.claim(key);
        assertThat(repository.failed(first,CatalogProtocol.Failure.RESULT_UNKNOWN)).isTrue();
        assertThat(repository.failed(first,CatalogProtocol.Failure.RESULT_UNKNOWN)).isFalse();
        assertThat(repository.find(key).attempts()).isOne();assertThat(repository.claim(key)).isNull();
        var event=appendEvent();var outbox=outbox();var claimed=outbox.claim(1,SOURCE).getFirst();
        assertThat(outbox.failed(claimed,CatalogProtocol.Failure.RESULT_UNKNOWN)).isTrue();
        assertThat(outbox.failed(claimed,CatalogProtocol.Failure.RESULT_UNKNOWN)).isFalse();
        assertThat(outbox.find(1,event.eventId()).state()).isEqualTo("READY");
        assertThat(outbox.find(1,event.eventId()).payloadBytes()).isEqualTo(claimed.payloadBytes());
        assertThat(outbox.claim(1,SOURCE)).isEmpty();
        for(int attempt=1;attempt<=5;attempt++) {
            double delay=one.queryForObject("SELECT extract(epoch FROM catalog_backoff(?,?))",Double.class,attempt,command.toString());
            assertThat(delay).isBetween(Math.pow(2,attempt-1),Math.pow(2,attempt-1)+0.199);
            assertThat(zero.queryForObject("SELECT extract(epoch FROM catalog_backoff(?,?))",Double.class,attempt,command.toString())).isEqualTo(delay);
        }
    }

    @Test void physicalBudgetsAreSetBeforeControlWaitAndDoNotLeakToNextTransaction() throws Exception {
        var local=new CatalogLocalTransactions(sharded,mapper);var jdbc=new JdbcTemplate(sharded);
        for(int shard=0;shard<2;shard++) {
            int target=shard;
            assertThat(local.on(target,()->jdbc.queryForObject("SELECT current_setting('lock_timeout') || '/' || current_setting('statement_timeout') FROM catalog_shard_control WHERE shard_id=?",String.class,target))).isEqualTo("4s/5s");
        }
        // 在另一个真实PG连接独占control；被测入口必须先设置预算，不能卡在第一把行锁。
        try(var connection=ds1.getConnection();var sql=connection.createStatement()) {
            connection.setAutoCommit(false);sql.execute("SELECT shard_id FROM catalog_shard_control FOR UPDATE");
            long started=System.nanoTime();
            assertCatalogFailure(()->local.on(1,()->mapper.control(1)),"lock timeout");
            assertThat(java.time.Duration.ofNanos(System.nanoTime()-started)).isBetween(java.time.Duration.ofSeconds(3),java.time.Duration.ofSeconds(7));
            connection.rollback();
        }
        // 同一逻辑数据源开启下一事务，SET LOCAL不能泄漏到连接复用者。
        String nextSettings=transaction.execute(s->jdbc.queryForObject("SELECT current_setting('lock_timeout') || '/' || current_setting('statement_timeout') FROM catalog_shard_control WHERE shard_id=1",String.class));
        assertThat(nextSettings).isEqualTo("0/0");
    }

    @Test void survivingRelayRejectsRecreatedSameNameTopicAndPersistsStoppedShard() throws Exception {
        var event=appendEvent();var events=outbox();
        try(var publisher=publisher();var admin=org.apache.kafka.clients.admin.AdminClient.create(Map.of("bootstrap.servers",KAFKA.bootstrap(),"default.api.timeout.ms",5000,"request.timeout.ms",3000))) {
            admin.deleteTopics(List.of(com.recsys.common.ad.VersionedAdCatalogEvent.TOPIC)).all().get(5,java.util.concurrent.TimeUnit.SECONDS);
            long deletedBy=System.nanoTime()+java.time.Duration.ofSeconds(10).toNanos();
            while(admin.listTopics().names().get(5,java.util.concurrent.TimeUnit.SECONDS).contains(com.recsys.common.ad.VersionedAdCatalogEvent.TOPIC) && System.nanoTime()<deletedBy)Thread.sleep(100);
            assertThat(admin.listTopics().names().get(5,java.util.concurrent.TimeUnit.SECONDS)).doesNotContain(com.recsys.common.ad.VersionedAdCatalogEvent.TOPIC);
            admin.createTopics(List.of(new org.apache.kafka.clients.admin.NewTopic(com.recsys.common.ad.VersionedAdCatalogEvent.TOPIC,1,(short)1))).all().get(5,java.util.concurrent.TimeUnit.SECONDS);
            var replaced=CatalogKafkaPublisher.identity(KAFKA.bootstrap());assertThat(replaced.topicId()).isNotEqualTo(kafkaIdentity.topicId());
            assertThat(new CatalogRelay(events,publisher,SOURCE).advance(1)).isZero();
            assertThat(events.find(1,event.eventId()).state()).isNotEqualTo("PUBLISHED");
            assertThat(mapper.binding(1).stopReason()).isEqualTo("INCONSISTENT");
            var partition=new org.apache.kafka.common.TopicPartition(com.recsys.common.ad.VersionedAdCatalogEvent.TOPIC,0);
            var end=admin.listOffsets(Map.of(partition,org.apache.kafka.clients.admin.OffsetSpec.latest())).all().get(5,java.util.concurrent.TimeUnit.SECONDS);
            assertThat(end.get(partition).offset()).isZero();
        } finally {
            kafkaIdentity=CatalogKafkaPublisher.identity(KAFKA.bootstrap());
            one.update("UPDATE catalog_shard_control SET stop_reason=NULL");
        }
    }

    CatalogOutboxRepository outbox(){return new CatalogOutboxRepository(mapper,new CatalogLocalTransactions(sharded,mapper),new CatalogSnapshotCodec(new com.fasterxml.jackson.databind.ObjectMapper()));}
    CatalogKafkaPublisher publisher(){return new CatalogKafkaPublisher(KAFKA.bootstrap(),kafkaIdentity.clusterId(),kafkaIdentity.topicId());}
    com.recsys.common.ad.VersionedAdCatalogEvent appendEvent() {
        var time=java.time.Instant.now();
        var event=new com.recsys.common.ad.VersionedAdCatalogEvent(1,com.recsys.common.ad.VersionedAdCatalogEvent.CatalogEventType.AD_SNAPSHOT,
                SOURCE,1,CatalogProtocol.TOPOLOGY,com.recsys.common.ad.CatalogEventCodec.eventId(SOURCE,102,2),102,1,2,1,command,time,time,
                com.recsys.common.ad.VersionedAdCatalogEvent.Lifecycle.PRESENT,true,
                new com.recsys.common.ad.VersionedAdCatalogEvent.Ad(10,"after","/landing",1,"active","approved",null,"CPC",null,null),
                "active",List.of(),List.of(),new com.recsys.common.ad.VersionedAdCatalogEvent.Embedding(
                com.recsys.common.ad.VersionedAdCatalogEvent.EmbeddingState.UNKNOWN,10L,null,null,time,
                com.recsys.common.ad.VersionedAdCatalogEvent.EmbeddingReason.UNAVAILABLE),true,null);
        byte[] bytes=new CatalogSnapshotCodec(new com.fasterxml.jackson.databind.ObjectMapper()).encode(event);
        transaction.executeWithoutResult(s->{mapper.enterStep(1,step,1);mapper.updateAdTitle(1,102,"after");
            outbox().append(CatalogProtocol.CommandKey.advertiser(SOURCE,1,command),"OWNER",event,bytes);finish(1,step,"fact",effects());});
        return event;
    }
    List<org.apache.kafka.clients.consumer.ConsumerRecord<byte[],byte[]>> consume(String eventId,int expected) {
        var properties=Map.<String,Object>of("bootstrap.servers",KAFKA.bootstrap(),"group.id","catalog-probe-"+UUID.randomUUID(),
                "enable.auto.commit",false,"auto.offset.reset","earliest","key.deserializer","org.apache.kafka.common.serialization.ByteArrayDeserializer",
                "value.deserializer","org.apache.kafka.common.serialization.ByteArrayDeserializer");
        var result=new java.util.ArrayList<org.apache.kafka.clients.consumer.ConsumerRecord<byte[],byte[]>>();
        try(var consumer=new org.apache.kafka.clients.consumer.KafkaConsumer<byte[],byte[]>(properties)) {
            var partition=new org.apache.kafka.common.TopicPartition(com.recsys.common.ad.VersionedAdCatalogEvent.TOPIC,0);
            consumer.assign(List.of(partition));consumer.seekToBeginning(List.of(partition));
            long deadline=System.nanoTime()+java.time.Duration.ofSeconds(10).toNanos();var codec=new CatalogSnapshotCodec(new com.fasterxml.jackson.databind.ObjectMapper());
            while(result.size()<expected && System.nanoTime()<deadline)for(var record:consumer.poll(java.time.Duration.ofMillis(200)))
                if(codec.decode(record.key(),record.value()).eventId().equals(eventId))result.add(record);
        }
        assertThat(result).hasSize(expected);return result;
    }

    CatalogStepRepository steps(){return new CatalogStepRepository(mapper,new CatalogLocalTransactions(sharded,mapper),new com.fasterxml.jackson.databind.ObjectMapper());}
    static CatalogStepRepository.LocalResult localResult(String effects){return new CatalogStepRepository.LocalResult(bytes("{\"effects\":"+effects+"}"),effects);}

    CatalogCommandRepository commands(){return new CatalogCommandRepository(mapper,new CatalogLocalTransactions(sharded,mapper));}
    void releaseFixtureGuard(){one.update("UPDATE catalog_advertiser_guard SET active_command_id=NULL,active_command_home=NULL,active_command_scope=NULL");}
    static void assertProtocolFailure(org.assertj.core.api.ThrowableAssert.ThrowingCallable action,CatalogProtocol.Failure code) {
        assertThat(catchThrowable(action)).isInstanceOfSatisfying(CatalogProtocol.CatalogException.class,e->assertThat(e.failure()).isEqualTo(code));
    }

    static void assertCatalogFailure(org.assertj.core.api.ThrowableAssert.ThrowingCallable action,String code) {
        Throwable failure=catchThrowable(action); assertThat(failure).isNotNull();
        var visited=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable,Boolean>());
        var pending=new java.util.ArrayDeque<Throwable>(); pending.add(failure); var details=new StringBuilder();
        while(!pending.isEmpty()) {
            Throwable t=pending.remove(); if(!visited.add(t))continue;
            details.append(t.getClass().getName()).append(':').append(t.getMessage()).append('\n');
            if(t.getCause()!=null)pending.add(t.getCause());
            if(t instanceof java.sql.SQLException sql && sql.getNextException()!=null)pending.add(sql.getNextException());
            for(Throwable suppressed:t.getSuppressed())pending.add(suppressed);
        }
        assertThat(details.toString()).contains(code);
    }

    void localStep(JdbcTemplate db,int shard,String key,String plan) {
        db.update("INSERT INTO catalog_step(shard_id,source_id,step_key,owner_shard,command_scope,command_id,step_id,advertiser_id,ad_id,request_bytes,request_sha256,write_plan,state,attempts,fence,lease_until) VALUES(?,?::uuid,?,1,'ADVERTISER:1',?::uuid,'OWNER',1,102,?, ?,?::jsonb,'RUNNING',1,1,clock_timestamp()+interval '30 seconds')",shard,SOURCE,key,command,bytes("{}"),hash("{}"),plan);
    }
    void finish(int shard,String key,String fact,String effects) {
        String result="{\"effects\":"+effects+"}";
        assertThat(mapper.insertFact(shard,fact,SOURCE,"ADVERTISER:1",command,"OWNER",bytes(result),hash(result))).isOne();
        assertThat(mapper.completeStep(shard,key,1,bytes(result),hash(result),effects,fact)).isOne();
    }
    void assertOriginal(){assertThat(one.queryForObject("SELECT title FROM ad WHERE ad_id=102",String.class)).isEqualTo("before");assertThat(one.queryForObject("SELECT state FROM catalog_step",String.class)).isEqualTo("RUNNING");}
    static String effects(){return "[{\"table\":\"ad\",\"operation\":\"UPDATE\",\"id\":102}]";}
    static byte[] bytes(String value){return value.getBytes(StandardCharsets.UTF_8);}
    static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes(value)));}catch(Exception e){throw new IllegalStateException(e);}}
    static HikariDataSource source(PostgreSQLContainer<?> pg){var ds=new HikariDataSource();ds.setJdbcUrl(pg.getJdbcUrl());ds.setUsername(pg.getUsername());ds.setPassword(pg.getPassword());ds.setMaximumPoolSize(4);ds.setConnectionTimeout(3000);return ds;}
    static DataSource sharding(String resource)throws Exception{
        String yaml=resource(resource);
        // 仅替换两物理连接与测试worker-id；业务及治理路由来自实际产品资源。
        return YamlShardingSphereDataSourceFactory.createDataSource(Map.of("ds_0",ds0,"ds_1",ds1),bytes(yaml.substring(yaml.indexOf("\nrules:")+1).replace("$${CATALOG_WRITER_ID::0}","16")));
    }
    static String resource(String path)throws Exception{try(var in=CatalogPhysicalTransactionTest.class.getResourceAsStream(path)){return new String(in.readAllBytes(),StandardCharsets.UTF_8);}}
    static void create(JdbcTemplate db,String table,String columns){
        db.execute("CREATE TABLE "+table+"("+columns+")");db.execute("COMMENT ON TABLE "+table+" IS '独占物理分片测试目录'");
        for(String column:columns.split(","))db.execute("COMMENT ON COLUMN "+table+"."+column.trim().split(" ")[0]+" IS '原目录业务字段'");
    }

    /** 只有此类拥有的独占broker，动态端点在容器启动前显式写入advertised listener。 */
    static class KafkaFixture extends org.testcontainers.containers.GenericContainer<KafkaFixture> {
        KafkaFixture() {
            super("apache/kafka:3.7.1");withExposedPorts(19092);
            withEnv(Map.of("KAFKA_NODE_ID","1","KAFKA_PROCESS_ROLES","broker,controller","KAFKA_CONTROLLER_QUORUM_VOTERS","1@localhost:9093",
                    "KAFKA_LISTENERS","INTERNAL://0.0.0.0:9092,CONTROLLER://0.0.0.0:9093,HOST://0.0.0.0:19092",
                    "KAFKA_LISTENER_SECURITY_PROTOCOL_MAP","INTERNAL:PLAINTEXT,CONTROLLER:PLAINTEXT,HOST:PLAINTEXT",
                    "KAFKA_INTER_BROKER_LISTENER_NAME","INTERNAL","KAFKA_CONTROLLER_LISTENER_NAMES","CONTROLLER",
                    "KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR","1","KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS","0","KAFKA_AUTO_CREATE_TOPICS_ENABLE","false"));
            withCommand("/bin/bash","-c","while [ ! -f /tmp/catalog-kafka-ready ]; do sleep 0.1; done; . /tmp/catalog-kafka-start; exec /etc/kafka/docker/run");
            waitingFor(org.testcontainers.containers.wait.strategy.Wait.forLogMessage(".*Kafka Server started.*",1).withStartupTimeout(java.time.Duration.ofSeconds(90)));
        }
        @Override protected void containerIsStarting(com.github.dockerjava.api.command.InspectContainerResponse info) {
            // tar创建文件早于写完；先完整复制配置，再发布独立ready标志，避免读到半个声明。
            copyFileToContainer(org.testcontainers.images.builder.Transferable.of("export KAFKA_ADVERTISED_LISTENERS='INTERNAL://localhost:9092,HOST://"+bootstrap()+"'\n",0644),"/tmp/catalog-kafka-start");
            copyFileToContainer(org.testcontainers.images.builder.Transferable.of("ready\n",0644),"/tmp/catalog-kafka-ready");
        }
        String bootstrap(){return getHost()+":"+getMappedPort(19092);}
    }
}
