package com.recsys.advertiser.catalog;

import com.recsys.advertiser.authz.AdvertiserAuthz;
import com.recsys.advertiser.persistence.mapper.primary.CatalogTaskMapper;
import com.recsys.common.ad.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static com.recsys.advertiser.catalog.CatalogPhysicalTransactionTest.*;
import static com.recsys.advertiser.catalog.CatalogProtocol.*;
import static org.assertj.core.api.Assertions.*;

/** 多页清单在独立产品JVM退出及真实单物理库停机后续办；没有测试Sink替代产品步骤/发布。 */
@Testcontainers @Tag("catalog-reliability")
class CatalogStatusApplicationTest {
    @Container static final PostgreSQLContainer<?> PG0=ZERO,PG1=ONE;
    @Container static final KafkaFixture BROKER=KAFKA;
    @Container static final GenericContainer<?> REDIS=CatalogInitializationTest.REDIS;
    @TempDir(cleanup=org.junit.jupiter.api.io.CleanupMode.NEVER) Path directory;
    CatalogApplicationTest app;CatalogInitializationTest fixture;CatalogStatusBatchService batches;
    CatalogCommandRepository commands;CatalogCommandService service;CatalogStepRepository steps;CatalogTaskMapper tasks;
    @BeforeAll static void startFixture() throws Exception{CatalogApplicationTest.startFixture();}
    @BeforeEach void prepare() throws Exception {
        app=new CatalogApplicationTest();app.directory=directory;fixture=new CatalogInitializationTest();fixture.prepare();app.fixture=fixture;
    }
    @AfterEach void stop() throws Exception{app.stopChildren();}
    @AfterAll static void closeFixture() throws Exception{CatalogApplicationTest.closeFixture();}
    void initialize(boolean pages) {
        if(pages)for(long ad=1000;ad<1103;ad++)one.update("INSERT INTO ad(ad_id,advertiser_id,item_id,title,landing_url,quality_score,status,review_status,optimization_type) VALUES(?,1,10,'多页恢复','/landing',1,?,?,'CPC')",ad,ad%10==0?"paused":"active",ad%11==0?"rejected":"approved");
        one.update("INSERT INTO advertiser VALUES(3,'healthy',100,'active')");one.update("INSERT INTO ad(ad_id,advertiser_id,item_id,title,landing_url,quality_score,status,review_status,optimization_type) VALUES(303,3,10,'healthy','/healthy',1,'active','approved','CPC')");
        fixture.service.initialize(fixture.id,new CatalogInitializationService.Input(SOURCE,TOPOLOGY,VersionedAdCatalogEvent.TOPIC,List.of(0,1),Map.of("init-test",7,"http-a",8,"http-b",9)),"maintenance-owner","进程与停机验收基线");
        for(int i=0;i<1000;i++)if(fixture.service.advance(fixture.id).completed())break;fixture.binding.verify(true);
        var tx=new CatalogLocalTransactions(sharded,fixture.local);var json=new CatalogJson(fixture.object);var codec=new CatalogSnapshotCodec(fixture.object);
        commands=new CatalogCommandRepository(fixture.local,tx);steps=new CatalogStepRepository(fixture.local,tx,fixture.object);
        tasks=CatalogInitializationTest.session.getMapper(CatalogTaskMapper.class);
        var businessSteps=new CatalogBusinessSteps(fixture.business,fixture.local,steps,commands,fixture.outbox,json,codec);
        var fanout=new CatalogFanoutService(fixture.local,tasks,fixture.business,tx,businessSteps,commands,fixture.outbox,fixture.enrichment,json,codec);
        batches=new CatalogStatusBatchService(fixture.local,tasks,tx,commands,json,SOURCE,true,true);
        var authz=new AdvertiserAuthz("disabled",new StaticListableBeanFactory().getBeanProvider(com.lrj.authz.protocol.AuthzEngine.class));
        service=new CatalogCommandService(commands,businessSteps,fixture.enrichment,json,authz,null,fanout,batches);
    }
    @Test void killedJvmRetainsMultiPageManifestAndResumesOriginalFactsAndKafkaBytes() throws Exception {
        initialize(true);UUID id=UUID.randomUUID();var key=CommandKey.advertiser(SOURCE,1,id);String body="{\"status\":\"paused\"}";
        var first=app.start("http-a",false,false,true,true);
        assertThat(app.call(first,"PUT","/api/advertiser/1",body,null,null,id).statusCode()).isEqualTo(401);
        assertThat(app.call(first,"PUT","/api/advertiser/1",body,"owner","ADVERTISER",id).statusCode()).isEqualTo(503);
        assertThat(app.call(first,"PUT","/api/advertiser/1",body,"owner","ADVERTISER",id).statusCode()).isEqualTo(503);
        assertThat(tasks.counts(key).completed()).isOne();
        byte[] beforeExit=one.queryForObject("SELECT payload_bytes FROM catalog_outbox WHERE command_id=? AND purpose='KAFKA' AND ad_id=102",byte[].class,id);
        var pending=java.util.concurrent.Executors.newSingleThreadExecutor();
        try(var blocker=ds0.getConnection()) {
            blocker.setAutoCommit(false);try(var statement=blocker.createStatement()){statement.execute("LOCK TABLE bidword IN ACCESS EXCLUSIVE MODE");}
            var response=pending.submit(()->app.call(first,"PUT","/api/advertiser/1",body,"owner","ADVERTISER",id));
            await(()->zero.queryForObject("SELECT count(*) FROM catalog_step WHERE command_id=? AND step_id='F_1000_CHILDREN' AND state='RUNNING'",Integer.class,id)==1,8);
            first.process().destroyForcibly();assertThat(first.process().waitFor(10,TimeUnit.SECONDS)).isTrue();blocker.rollback();
            try{assertThat(response.get(12,TimeUnit.SECONDS).statusCode()).isEqualTo(503);}catch(java.util.concurrent.ExecutionException lost){assertThat(lost.getCause()).isInstanceOf(java.io.IOException.class);}
        } finally {pending.shutdownNow();}
        assertThat(tasks.counts(key).total()).isEqualTo(104);assertThat(tasks.counts(key).completed()).isOne();
        assertThat(fixture.business.root(1).status()).isEqualTo("paused");assertThat(one.queryForObject("SELECT active_command_id FROM catalog_advertiser_guard WHERE advertiser_id=1",UUID.class)).isEqualTo(id);
        var original=manifest(key);assertThat(original).hasSize(104);
        var next=app.start("http-b",false,true,false,true,Map.of("CATALOG_FIXTURE_ADVANCE_ID",id.toString(),"CATALOG_FIXTURE_ADVANCE_OWNER","1"));
        await(()->commands.find(key).completed(),110);assertThat(manifest(key)).isEqualTo(original);
        assertThat(tasks.counts(key).completed()).isEqualTo(104);
        var response=app.call(next,"PUT","/api/advertiser/1",body,"owner","ADVERTISER",id);assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(fixture.object.readTree(response.body())).isEqualTo(fixture.object.readTree(commands.find(key).resultBytes()));
        assertThat(one.queryForObject("SELECT payload_bytes FROM catalog_outbox WHERE command_id=? AND purpose='KAFKA' AND ad_id=102",byte[].class,id)).isEqualTo(beforeExit);
        assertThat(app.call(next,"PUT","/api/advertiser/1","{\"status\":\"active\"}","owner","ADVERTISER",UUID.randomUUID()).statusCode()).isEqualTo(503);
        var query=app.call(next,"GET","/api/advertiser/1/catalog-commands/"+id,null,"owner","ADVERTISER",null);assertThat(query.statusCode()).isEqualTo(200);
        assertThat(fixture.object.readTree(query.body()).at("/summary/targets/completed").asInt()).isEqualTo(104);
        await(()->one.queryForObject("SELECT count(*) FROM catalog_outbox WHERE command_id=? AND purpose='KAFKA' AND state='PUBLISHED'",Integer.class,id)==104,30);
        verifyWire(id,1,104);assertThat(one.queryForObject("SELECT active_command_id FROM catalog_advertiser_guard WHERE advertiser_id=1",UUID.class)).isNull();
        assertThat(one.queryForObject("SELECT count(*) FROM catalog_aggregate WHERE advertiser_id=1 AND reserved_version<>2",Integer.class)).isZero();
        assertThat(zero.queryForObject("SELECT attempts FROM catalog_step WHERE command_id=? AND step_id='F_1000_CHILDREN'",Integer.class,id)).isEqualTo(2);
    }
    @Test void realSinglePgOutageLeavesOriginalBatchPartialAndHealthyOwnerCompletesBeforeRecovery() throws Exception {
        initialize(false);UUID id=new UUID(UUID.randomUUID().getMostSignificantBits(),1);var key=batches.key(id);
        batches.submitStatusBatch(id,List.of(target(2,"active"),target(3,"paused")),"internal-owner","一库停机公平推进");var original=manifest(key);
        var docker=org.testcontainers.DockerClientFactory.instance().client();String container=ZERO.getContainerId();int port=ZERO.getMappedPort(5432);
        docker.stopContainerCmd(container).withTimeout(1).exec();
        try(var worker=new CatalogWorker(fixture.local,service,steps,null,SOURCE)) {
            worker.start();await(()->{var c=batches.query(id).counts();return c.completed()==1 && c.paused()==1;},65);
            assertThat(batches.query(id).state()).isEqualTo("PARTIAL");assertThat(fixture.business.root(3).status()).isEqualTo("paused");
            assertThat(manifest(key)).isEqualTo(original);assertThat(commands.find(CommandKey.advertiser(SOURCE,3,batches.targets(id,-1).get(1).childCommandId())).completed()).isTrue();
        } finally {docker.startContainerCmd(container).exec();}
        assertThat(Integer.parseInt(docker.inspectContainerCmd(container).exec().getNetworkSettings().getPorts().getBindings().get(com.github.dockerjava.api.model.ExposedPort.tcp(5432))[0].getHostPortSpec())).isEqualTo(port);await(()->{try{return zero.queryForObject("SELECT 1",Integer.class)==1;}catch(RuntimeException down){return false;}},30);
        // 原输入/子command不变；恢复需要新的稳定审计，不把重启当预算重置。
        var target=batches.targets(id,-1).getFirst();assertThat(target.attempts()).isEqualTo(3);
        batches.retryTarget(UUID.randomUUID(),id,2,1,"internal-owner","原库恢复后重开原目标");
        var next=app.start("http-b",true,true,true,true);await(()->commands.find(key).completed(),45);
        assertThat(manifest(key)).isEqualTo(original);assertThat(batches.query(id).counts().completed()).isEqualTo(2);
        assertThat(batches.targets(id,-1).getFirst().childCommandId()).isEqualTo(target.childCommandId());
        for(var item:batches.targets(id,-1)){int shard=(int)(item.targetId()%2);var db=shard==0?zero:one;await(()->db.queryForObject("SELECT count(*) FROM catalog_outbox WHERE command_id=? AND purpose='KAFKA' AND state='PUBLISHED'",Integer.class,item.childCommandId())==1,20);verifyWire(item.childCommandId(),shard,1);}
        assertThat(next.state().get("workers").intValue()).isOne();
    }
    @Test void lastBatchObservationClaimKilledJvmIsSettledWithoutUnboundedRetryOrBlockingOtherOwner() throws Exception {
        initialize(false);UUID id=UUID.randomUUID();var key=batches.key(id);
        batches.submitStatusBatch(id,List.of(target(1,"paused"),target(2,"active"),target(3,"paused")),"internal-owner","最后领取退出");batches.advance(key);
        for(int i=1;i<=2;i++){final int attempt=i;await(()->{var claim=commands.claim(key);if(claim==null)return false;assertThat(claim.attempts()).isEqualTo(attempt);assertThat(commands.failed(claim,Failure.RESULT_UNKNOWN)).isTrue();return true;},5);}
        var first=app.start("http-a",false,false,false,true,Map.of("CATALOG_FIXTURE_BATCH_LAST_CLAIM",id.toString()));
        assertThat(commands.find(key).attempts()).isEqualTo(3);first.process().destroyForcibly();assertThat(first.process().waitFor(10,TimeUnit.SECONDS)).isTrue();
        var next=app.start("http-b",true,true,false,true);
        await(()->{var c=batches.query(id).counts();return c.paused()==1 && c.completed()==2;},65);
        assertThat(batches.targets(id,-1).getFirst().errorType()).isEqualTo("OBSERVATION_LEASE_EXHAUSTED");
        assertThat(batches.query(id).state()).isEqualTo("PARTIAL");var db=key.shard()==0?zero:one;
        assertThat(db.queryForObject("SELECT count(*) FROM catalog_outbox WHERE command_id=? AND step_id LIKE 'EXHAUSTED_%'",Integer.class,id)).isOne();
        assertThat(fixture.business.root(1).status()).isEqualTo("active");assertThat(next.state().get("workers").intValue()).isOne();
    }
    @Test void childPgOutagePausesOriginalFanoutAndAuditedRetryKeepsOwnerFragmentAndVersion() throws Exception {
        initialize(false);var first=app.start("http-a",false,false,true,true);UUID id=UUID.randomUUID();var key=CommandKey.advertiser(SOURCE,1,id);String body="{\"status\":\"paused\"}";
        assertThat(app.call(first,"PUT","/api/advertiser/1",body,"owner","ADVERTISER",id).statusCode()).isEqualTo(503);
        assertThat(commands.find(key).phase()).isEqualTo("FANOUT_CHILDREN");var original=manifest(key);
        byte[] ownerFragment=fixture.local.step(1,key.stepKey("F_102_OWNER")).resultBytes();
        var docker=org.testcontainers.DockerClientFactory.instance().client();String container=ZERO.getContainerId();int port=ZERO.getMappedPort(5432);
        docker.stopContainerCmd(container).withTimeout(1).exec();
        try(var worker=new CatalogWorker(fixture.local,service,steps,null,SOURCE)) {
            worker.start();await(()->State.PAUSED.name().equals(commands.find(key).state()),60);
            assertThat(commands.find(key).attempts()).isEqualTo(3);assertThat(manifest(key)).isEqualTo(original);
            assertThat(one.queryForObject("SELECT active_command_id FROM catalog_advertiser_guard WHERE advertiser_id=1",UUID.class)).isEqualTo(id);
            assertThat(one.queryForObject("SELECT count(*) FROM catalog_outbox WHERE command_id=? AND purpose='KAFKA'",Integer.class,id)).isZero();
            assertThat(tasks.counts(key).completed()).isZero();assertThat(fixture.business.root(1).status()).isEqualTo("paused");
        } finally {docker.startContainerCmd(container).exec();}
        assertThat(Integer.parseInt(docker.inspectContainerCmd(container).exec().getNetworkSettings().getPorts().getBindings().get(com.github.dockerjava.api.model.ExposedPort.tcp(5432))[0].getHostPortSpec())).isEqualTo(port);
        await(()->{try{return zero.queryForObject("SELECT 1",Integer.class)==1;}catch(RuntimeException down){return false;}},30);
        assertThat(app.call(first,"PUT","/api/advertiser/1",body,"owner","ADVERTISER",id).statusCode()).isEqualTo(503);
        var recovery=new CatalogRecoveryService(fixture.local,CatalogInitializationTest.session.getMapper(com.recsys.advertiser.persistence.mapper.primary.CatalogRecoveryMapper.class),
                new CatalogLocalTransactions(sharded,fixture.local),fixture.binding,new CatalogJson(fixture.object),new CatalogSnapshotCodec(fixture.object),SOURCE,KAFKA.bootstrap());
        UUID audit=UUID.randomUUID();var request=new CatalogRecoveryService.Request(Kind.RETRY_STEP,key,null,null,1,0,null,null,commands.find(key).requestSha256());
        recovery.submit(audit,request,"internal-owner","原children库恢复后新审计轮");
        for(int i=0;i<5 && !recovery.query(audit).completed();i++)recovery.advance(audit);
        assertThat(recovery.query(audit).completed()).isTrue();assertThat(recovery.submit(audit,request,"internal-owner","原审计查回").resultBytes()).isEqualTo(recovery.query(audit).resultBytes());
        first.process().destroy();assertThat(first.process().waitFor(10,TimeUnit.SECONDS)).isTrue();
        var next=app.start("http-b",true,true,false,true);await(()->commands.find(key).completed(),40);
        assertThat(commands.find(key).round()).isEqualTo(2);assertThat(manifest(key)).isEqualTo(original);
        assertThat(fixture.local.step(1,key.stepKey("F_102_OWNER")).resultBytes()).isEqualTo(ownerFragment);
        assertThat(one.queryForObject("SELECT reserved_version FROM catalog_aggregate WHERE ad_id=102",Long.class)).isEqualTo(2);
        assertThat(tasks.counts(key).completed()).isOne();assertThat(one.queryForObject("SELECT active_command_id FROM catalog_advertiser_guard WHERE advertiser_id=1",UUID.class)).isNull();
        await(()->one.queryForObject("SELECT count(*) FROM catalog_outbox WHERE command_id=? AND purpose='KAFKA' AND state='PUBLISHED'",Integer.class,id)==1,20);verifyWire(id,1,1);
        assertThat(app.call(next,"PUT","/api/advertiser/1",body,"owner","ADVERTISER",id).statusCode()).isEqualTo(200);
        var plan=new CatalogJson(fixture.object).decode(recovery.query(audit).frozenBytes(),CatalogRecoveryService.Plan.class);assertThat(plan.commandAttempts()).isEqualTo(3);
    }
    List<Map<String,Object>> manifest(CommandKey key){var db=key.shard()==0?zero:one;return db.queryForList("SELECT ordinal,target_kind,target_id,target_owner_id,expected_version,child_command_id,page_sha256 FROM catalog_task_item WHERE parent_command_id=? ORDER BY ordinal",key.command());}
    private CatalogStatusBatchService.StatusTarget target(long owner,String status){return new CatalogStatusBatchService.StatusTarget(owner,1,status);}
    private void verifyWire(UUID id,int shard,int count) throws Exception {
        var db=shard==0?zero:one;var original=new HashMap<String,byte[]>();db.query("SELECT event_id,payload_bytes FROM catalog_outbox WHERE command_id=? AND purpose='KAFKA'",rs->{original.put(rs.getString(1),rs.getBytes(2));},id);assertThat(original).hasSize(count);
        var properties=Map.<String,Object>of("bootstrap.servers",KAFKA.bootstrap(),"group.id","status-recovery-"+UUID.randomUUID(),"auto.offset.reset","earliest","enable.auto.commit",false);
        try(var consumer=new org.apache.kafka.clients.consumer.KafkaConsumer<byte[],byte[]>(properties,new org.apache.kafka.common.serialization.ByteArrayDeserializer(),new org.apache.kafka.common.serialization.ByteArrayDeserializer())) {
            consumer.subscribe(List.of(VersionedAdCatalogEvent.TOPIC));var seen=new HashSet<String>();long end=System.nanoTime()+Duration.ofSeconds(20).toNanos();
            while(seen.size()<count && System.nanoTime()<end)for(var record:consumer.poll(Duration.ofMillis(200))){var event=new CatalogSnapshotCodec(fixture.object).decode(record.key(),record.value());if(id.equals(event.commandId())){assertThat(record.value()).isEqualTo(original.get(event.eventId()));assertThat(record.key()).isEqualTo(CatalogEventCodec.key(event.adId()));seen.add(event.eventId());}}
            assertThat(seen).containsExactlyInAnyOrderElementsOf(original.keySet());
        }
    }
    private static void await(java.util.function.BooleanSupplier condition,int seconds) throws Exception {long until=System.nanoTime()+Duration.ofSeconds(seconds).toNanos();while(System.nanoTime()<until){if(condition.getAsBoolean())return;Thread.sleep(100);}fail("真实独占故障验收超出有界等待");}
}
