package com.recsys.advertiser.catalog;

import com.recsys.advertiser.authz.AdvertiserAuthz;
import com.recsys.advertiser.dto.AdUpsert;
import com.recsys.advertiser.persistence.mapper.primary.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.*;
import java.util.*;
import static com.recsys.advertiser.catalog.CatalogPhysicalTransactionTest.*;
import static com.recsys.advertiser.catalog.CatalogProtocol.*;
import static org.assertj.core.api.Assertions.*;

/** 真实业务最终SQL失败耗尽后，以新审计ID恢复原步骤；重复审计不重置第二轮预算。 */
@Testcontainers @Tag("catalog-reliability")
class CatalogRecoveryTest {
    @Container static final PostgreSQLContainer<?> PG0=ZERO,PG1=ONE;
    @Container static final KafkaFixture BROKER=KAFKA;
    @Container static final GenericContainer<?> REDIS=CatalogInitializationTest.REDIS;
    CatalogInitializationTest fixture;
    CatalogCommandRepository commands;CatalogCommandService businessService;CatalogRecoveryService recovery;
    CatalogJson json;
    @BeforeAll static void startFixture() throws Exception {CatalogInitializationTest.startFixture();}
    @BeforeEach void prepare() throws Exception {
        fixture=new CatalogInitializationTest();fixture.prepare();fixture.service.initialize(fixture.id,fixture.input(),"owner","恢复测试基线");
        for(int i=0;i<50;i++)if(fixture.service.advance(fixture.id).completed())break;
        fixture.binding.verify(true);
        var local=fixture.local;var tx=new CatalogLocalTransactions(sharded,local);json=new CatalogJson(fixture.object);var codec=new CatalogSnapshotCodec(fixture.object);
        commands=new CatalogCommandRepository(local,tx);
        var steps=new CatalogBusinessSteps(fixture.business,local,new CatalogStepRepository(local,tx,fixture.object),commands,fixture.outbox,json,codec);
        var authz=new AdvertiserAuthz("disabled",new StaticListableBeanFactory().getBeanProvider(com.lrj.authz.protocol.AuthzEngine.class));
        businessService=new CatalogCommandService(commands,steps,fixture.enrichment,json,authz,null,null,null);
        recovery=new CatalogRecoveryService(local,CatalogInitializationTest.session.getMapper(CatalogRecoveryMapper.class),tx,fixture.binding,json,codec,SOURCE,KAFKA.bootstrap());
    }
    @AfterEach void closeReader(){fixture.closeReader();}
    @AfterAll static void closeFixture() throws Exception {CatalogInitializationTest.closeFixture();}

    @Test void realFinalSqlFailurePausesAndAuditedRetryPreservesHistoryThenCompletesOriginal() throws Exception {
        var key=CommandKey.advertiser(SOURCE,1,UUID.randomUUID());
        var mutation=new CatalogMutation(Kind.AD_UPDATE,102,new AdUpsert(null,"blocked",null,null,null,null,null,null,null),null,null,null,null,null,null);
        var submission=new Submission(key,Kind.AD_UPDATE,json.encode(new CatalogFragments.Request(mutation,102L)),"owner","真实最终SQL失败");
        commands.admit(submission);businessService.advance(key);businessService.advance(key);businessService.advance(key);
        one.execute("ALTER TABLE ad ADD CONSTRAINT catalog_test_failure CHECK(title<>'blocked')");
        try {
            for(int attempt=0;attempt<3;attempt++) {
                // 等实际数据库退避，不伪造时钟或把Mock异常当数据库失败。
                if(attempt>0)Thread.sleep(attempt==1?1300:2300);
                assertThatThrownBy(()->businessService.advance(key)).isInstanceOf(RuntimeException.class);
            }
        } finally {one.execute("ALTER TABLE ad DROP CONSTRAINT catalog_test_failure");}
        var paused=commands.find(key);assertThat(paused.state()).isEqualTo("PAUSED");assertThat(paused.attempts()).isEqualTo(3);
        var step=fixture.local.step(1,key.stepKey("FINAL"));assertThat(step.state()).isEqualTo("PAUSED");assertThat(step.attempts()).isEqualTo(3);
        assertThat(fixture.business.ad(1,102).title()).isEqualTo("before");
        UUID id=UUID.randomUUID();var request=new CatalogRecoveryService.Request(Kind.RETRY_STEP,key,1,step.stepKey(),1,1,null,null,paused.requestSha256());
        recovery.submit(id,request,"maintenance-owner","修复依赖后原输入新轮");
        for(int i=0;i<3;i++)recovery.advance(id);
        assertThat(recovery.query(id).completed()).isTrue();assertThat(commands.find(key).round()).isEqualTo(2);
        assertThat(fixture.local.step(1,step.stepKey()).round()).isEqualTo(2);
        assertThat(one.queryForObject("SELECT count(*) FROM catalog_outbox WHERE command_scope='RECOVERY:RECOVERY' AND command_id=?",Integer.class,id)).isEqualTo(2);
        recovery.submit(id,request,"maintenance-owner","同ID重试");recovery.advance(id);
        assertThat(commands.find(key).round()).isEqualTo(2);
        assertThat(businessService.advance(key).completed()).isTrue();assertThat(fixture.business.ad(1,102).title()).isEqualTo("blocked");
        assertThat(one.queryForObject("SELECT active_command_id FROM catalog_advertiser_guard WHERE advertiser_id=1",UUID.class)).isNull();
        var audit=one.queryForObject("SELECT payload_bytes FROM catalog_outbox WHERE command_id=? AND step_id='RETRY_COMMAND'",byte[].class,id);
        assertThat(json.decode(audit,CatalogRecoveryService.Plan.class).commandAttempts()).isEqualTo(3);
    }
    @Test void replayPublishedEventUsesOriginalBytesAndStableAuditResultWithoutNewVersion() {
        var event=fixture.local.event(1,com.recsys.common.ad.CatalogEventCodec.eventId(SOURCE,102,1));
        try(var producer=new CatalogKafkaPublisher(KAFKA.bootstrap(),kafkaIdentity.clusterId(),kafkaIdentity.topicId())){new CatalogRelay(fixture.outbox,producer,SOURCE).advance(1);}
        UUID id=UUID.randomUUID();var request=new CatalogRecoveryService.Request(Kind.REPLAY_EVENT,null,null,null,0,0,1,event.eventId(),event.payloadSha256());
        recovery.submit(id,request,"owner","原字节受控续发");recovery.advance(id);var result=recovery.advance(id);assertThat(result.completed()).isTrue();
        var records=new CatalogPhysicalTransactionTest().consume(event.eventId(),2);
        for(var record:records){assertThat(record.key()).isEqualTo(event.keyBytes());assertThat(record.value()).isEqualTo(event.payloadBytes());}
        assertThat(recovery.advance(id).resultBytes()).isEqualTo(result.resultBytes());
        assertThat(fixture.local.event(1,event.eventId()).payloadBytes()).isEqualTo(event.payloadBytes());
        assertThat(one.queryForObject("SELECT reserved_version FROM catalog_aggregate WHERE ad_id=102",Long.class)).isEqualTo(1);
    }
    @Test void scopedRecheckDoesNotClearOtherShardStopOrRewriteOriginalBytes() {
        var event=fixture.local.event(1,com.recsys.common.ad.CatalogEventCodec.eventId(SOURCE,102,1));
        zero.update("UPDATE catalog_shard_control SET stop_reason='INCONSISTENT'");
        UUID id=UUID.randomUUID();var request=new CatalogRecoveryService.Request(Kind.RECHECK,null,null,null,0,0,1,event.eventId(),event.payloadSha256());
        recovery.submit(id,request,"owner","只核验指定原文");recovery.advance(id);assertThat(recovery.advance(id).completed()).isTrue();
        assertThat(fixture.local.binding(0).stopReason()).isEqualTo("INCONSISTENT");assertThat(fixture.local.event(1,event.eventId()).payloadBytes()).isEqualTo(event.payloadBytes());
    }

    @Test void missingExplicitCommandStepAndEventAreNotFoundWhileKnownDigestMismatchIsInconsistent() {
        String digest="0".repeat(64);var missing=CommandKey.advertiser(SOURCE,1,UUID.randomUUID());
        assertFailure(new CatalogRecoveryService.Request(Kind.RECHECK,missing,null,null,1,0,null,null,digest),Failure.NOT_FOUND);
        var mutation=new CatalogMutation(Kind.AD_UPDATE,102,new AdUpsert(null,"known",null,null,null,null,null,null,null),null,null,null,null,null,null);
        var key=CommandKey.advertiser(SOURCE,1,UUID.randomUUID());
        var command=commands.admit(new Submission(key,Kind.AD_UPDATE,json.encode(new CatalogFragments.Request(mutation,102L)),"owner","缺记录分类"));
        assertFailure(new CatalogRecoveryService.Request(Kind.RECHECK,key,1,key.stepKey("NO_SUCH_STEP"),1,1,null,null,command.requestSha256()),Failure.NOT_FOUND);
        assertFailure(new CatalogRecoveryService.Request(Kind.RECHECK,key,null,null,1,0,null,null,digest),Failure.INCONSISTENT);
        var event=fixture.local.event(1,com.recsys.common.ad.CatalogEventCodec.eventId(SOURCE,102,1));
        assertFailure(new CatalogRecoveryService.Request(Kind.REPLAY_EVENT,null,null,null,0,0,1,event.eventId(),digest),Failure.INCONSISTENT);
        assertThat(commands.find(key).fence()).isZero();assertThat(commands.find(key).attempts()).isZero();
        assertThat(fixture.local.event(1,event.eventId()).payloadBytes()).isEqualTo(event.payloadBytes());
    }

    private void assertFailure(CatalogRecoveryService.Request request,Failure expected) {
        UUID id=UUID.randomUUID();recovery.submit(id,request,"owner","明确目标与原文损坏分类");
        assertThatThrownBy(()->recovery.advance(id)).isInstanceOfSatisfying(CatalogException.class,e->assertThat(e.failure()).isEqualTo(expected));
        assertThat(recovery.query(id).errorType()).isEqualTo(expected.name());assertThat(recovery.query(id).completed()).isFalse();
    }

    @Test void brokerOriginalExpiredButKnownDatabaseBytesReplayAndMissingOriginalFailsClosed() throws Exception {
        var event=fixture.local.event(1,com.recsys.common.ad.CatalogEventCodec.eventId(SOURCE,102,1));
        var partition=new org.apache.kafka.common.TopicPartition(event.topic(),event.brokerPartition());
        // 在独占broker推进真实日志起点，模拟保留过期；不换topicId，也不触碰共享主题。
        var properties=new java.util.Properties();properties.put("bootstrap.servers",KAFKA.bootstrap());
        properties.put("default.api.timeout.ms","5000");properties.put("request.timeout.ms","3000");
        try(var admin=org.apache.kafka.clients.admin.Admin.create(properties)) {
            var deleted=admin.deleteRecords(Map.of(partition,org.apache.kafka.clients.admin.RecordsToDelete.beforeOffset(event.brokerOffset()+1)))
                    .lowWatermarks().get(partition).get(5,java.util.concurrent.TimeUnit.SECONDS);
            assertThat(deleted.lowWatermark()).isGreaterThan(event.brokerOffset());
        }
        assertThat(CatalogKafkaPublisher.identity(KAFKA.bootstrap())).isEqualTo(kafkaIdentity);
        UUID id=UUID.randomUUID();recovery.submit(id,new CatalogRecoveryService.Request(Kind.REPLAY_EVENT,null,null,null,0,0,1,event.eventId(),event.payloadSha256()),"owner","已知原文在保留过期后续发");
        recovery.advance(id);assertThat(recovery.advance(id).completed()).isTrue();
        var actual=new CatalogPhysicalTransactionTest().consume(event.eventId(),1).getFirst();
        assertThat(actual.offset()).isGreaterThan(event.brokerOffset());assertThat(actual.key()).isEqualTo(event.keyBytes());assertThat(actual.value()).isEqualTo(event.payloadBytes());
        UUID absent=UUID.randomUUID();String missing=com.recsys.common.ad.CatalogEventCodec.eventId(SOURCE,999999,1);
        recovery.submit(absent,new CatalogRecoveryService.Request(Kind.REPLAY_EVENT,null,null,null,0,0,1,missing,event.payloadSha256()),"owner","无原文不得补造");
        assertThatThrownBy(()->recovery.advance(absent)).isInstanceOfSatisfying(CatalogException.class,e->assertThat(e.failure()).isEqualTo(Failure.NOT_FOUND));
        assertThat(recovery.query(absent).completed()).isFalse();assertThat(recovery.query(absent).errorType()).isEqualTo("NOT_FOUND");
        assertThat(fixture.local.event(1,missing)).isNull();assertThat(fixture.local.event(1,event.eventId()).payloadBytes()).isEqualTo(event.payloadBytes());
    }
    @Test void fullShardRecheckVerifiesOriginalReceiptsBeforeResumingOnlyThatShard() {
        var target=CommandKey.advertiser(SOURCE,1,UUID.randomUUID());
        commands.admit(new Submission(target,Kind.ROOT_UPDATE,json.encode(Map.of("retained","owner")),"owner","未决占有保持"));
        fixture.local.stopShard(0,"INCONSISTENT");fixture.local.stopShard(1,"INCONSISTENT");
        var checks=new CatalogShardRecheckService(fixture.local,CatalogInitializationTest.session.getMapper(CatalogRecheckMapper.class),
                new CatalogLocalTransactions(sharded,fixture.local),fixture.binding,json,new CatalogSnapshotCodec(fixture.object),SOURCE,KAFKA.bootstrap());
        UUID id=UUID.randomUUID();checks.submit(1,id,"owner","全原事实逐页复核");
        for(int i=0;i<10;i++){var result=checks.advance(1,id);if(result.completed())break;if(i==9)fail("全分片核验未完成");}
        assertThat(fixture.local.binding(1).stopReason()).isNull();assertThat(fixture.local.binding(0).stopReason()).isEqualTo("INCONSISTENT");
        assertThat(one.queryForObject("SELECT active_command_id FROM catalog_advertiser_guard WHERE advertiser_id=1",UUID.class)).isEqualTo(target.command());
        var registry=new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        try(var metrics=new CatalogMetrics(fixture.local,json,registry)) {
            metrics.observe();assertThat(registry.get("catalog_records").tags("shard","0","kind","CONTROL","state","STOPPED").gauge().value()).isEqualTo(1);
            assertThat(registry.get("catalog_stale").tags("shard","1","kind","CONTROL","state","OBSERVATION").gauge().value()).isZero();
            assertThat(registry.getMeters()).allMatch(m->m.getId().getTags().stream().allMatch(t->Set.of("shard","kind","state").contains(t.getKey())));
        }
    }
    @Test void committedReplayAckFinishesOriginalHomeEvenWhenBrokerThenStops() throws Exception {
        var event=fixture.local.event(1,com.recsys.common.ad.CatalogEventCodec.eventId(SOURCE,102,1));
        UUID id=new UUID(UUID.randomUUID().getMostSignificantBits(),2);var home=zero;
        recovery.submit(id,new CatalogRecoveryService.Request(Kind.REPLAY_EVENT,null,null,null,0,0,1,event.eventId(),event.payloadSha256()),"owner","ACK提交后home未知");recovery.advance(id);
        home.execute("CREATE FUNCTION catalog_test_replay_finish_fail() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'catalog_test_finish'; END $$");
        home.execute("CREATE TRIGGER catalog_test_replay_finish BEFORE UPDATE ON catalog_command FOR EACH ROW WHEN (NEW.state='COMPLETED') EXECUTE FUNCTION catalog_test_replay_finish_fail()");
        CatalogRecoveryService.ReplayAck ack;
        try {
            assertThatThrownBy(()->recovery.advance(id)).isInstanceOf(RuntimeException.class);
            var receipt=one.queryForObject("SELECT payload_bytes FROM catalog_outbox WHERE command_id=? AND step_id='REPLAY_ACK'",byte[].class,id);
            ack=json.decode(receipt,CatalogRecoveryService.ReplayAck.class);
            assertThat(recovery.query(id).completed()).isFalse();
        } finally {home.execute("DROP TRIGGER catalog_test_replay_finish ON catalog_command");home.execute("DROP FUNCTION catalog_test_replay_finish_fail()");}
        var docker=KAFKA.getDockerClient();String container=KAFKA.getContainerId();docker.stopContainerCmd(container).withTimeout(1).exec();
        try {
            var result=recovery.advance(id);assertThat(result.completed()).isTrue();
            var value=fixture.object.readTree(result.resultBytes());assertThat(value.get("offset").longValue()).isEqualTo(ack.offset());
            assertThat(result.attempts()).isEqualTo(1);assertThat(recovery.advance(id).resultBytes()).isEqualTo(result.resultBytes());
        } finally {docker.startContainerCmd(container).exec();}
        long deadline=System.nanoTime()+java.time.Duration.ofSeconds(30).toNanos();boolean restored=false;
        while(System.nanoTime()<deadline)try{restored=CatalogKafkaPublisher.identity(KAFKA.bootstrap()).equals(kafkaIdentity);if(restored)break;}catch(RuntimeException pending){Thread.sleep(200);}
        assertThat(restored).isTrue();
        assertThat(new CatalogPhysicalTransactionTest().consume(event.eventId(),2)).hasSize(2);
    }

    @Test void boundedWorkerRotatesPastFailedRootWithoutUnlockingItsOwner() throws Exception {
        for(var db:List.of(zero,one))db.execute("ALTER TABLE advertiser ADD CONSTRAINT catalog_test_root_fail CHECK(name<>'poison')");
        var keys=new ArrayList<CommandKey>();
        try(var worker=new CatalogWorker(fixture.local,businessService,new CatalogStepRepository(fixture.local,new CatalogLocalTransactions(sharded,fixture.local),fixture.object),null,SOURCE)) {
            for(int n=0;n<12;n++) {
                var key=CommandKey.root(SOURCE,new UUID(UUID.randomUUID().getMostSignificantBits(),2L*(n+1)));keys.add(key);
                var root=new com.recsys.advertiser.dto.AdvertiserUpsert(n==0?"poison":"healthy-"+n,10.0,null);
                var mutation=new CatalogMutation(Kind.ROOT_CREATE,0,null,null,null,root,null,null,null);
                commands.admitRoot(new Submission(key,Kind.ROOT_CREATE,json.encode(new CatalogFragments.Request(mutation,null)),"owner","有界公平轮转"));
            }
            worker.tick(0);
            assertThat(zero.queryForObject("SELECT count(*) FROM catalog_command WHERE scope_kind='ROOT_CREATE' AND (phase<>'PREPARE' OR attempts>0)",Integer.class)).isEqualTo(10);
            // 下一轮先轮到尚未领取者；失败命令自身退避，不会占据无界重试循环。
            worker.start();long until=System.nanoTime()+java.time.Duration.ofSeconds(20).toNanos();
            while(System.nanoTime()<until && keys.stream().skip(1).anyMatch(k->!commands.find(k).completed()))Thread.sleep(100);
            for(var key:keys.subList(1,keys.size()))assertThat(commands.find(key).completed()).isTrue();
            var bad=commands.find(keys.getFirst());assertThat(bad.completed()).isFalse();
            assertThat(fixture.local.lockGuard((int)(bad.advertiserId()%2),bad.advertiserId()).activeCommandId()).isEqualTo(bad.commandId());
        } finally {for(var db:List.of(zero,one))db.execute("ALTER TABLE advertiser DROP CONSTRAINT catalog_test_root_fail");}
    }

}
