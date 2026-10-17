package com.recsys.advertiser.catalog;

import com.recsys.advertiser.dto.AdvertiserUpsert;
import com.recsys.common.ad.CatalogEventCodec;
import com.recsys.common.ad.VersionedAdCatalogEvent;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.util.*;
import static com.recsys.advertiser.catalog.CatalogPhysicalTransactionTest.*;
import static com.recsys.advertiser.catalog.CatalogProtocol.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 实际Controller/Mapper/两物理PG的fanout与批次语义；独立JVM和停机另行验收，不以此类替代。 */
@Testcontainers @Tag("catalog-reliability")
class CatalogStatusFlowTest {
    @Container static final PostgreSQLContainer<?> PG0=ZERO,PG1=ONE;
    @Container static final KafkaFixture BROKER=KAFKA;
    @Container static final GenericContainer<?> REDIS=CatalogBusinessFlowTest.REDIS;
    CatalogBusinessFlowTest fixture;
    CatalogStatusBatchService batches;
    @BeforeAll static void startFixture() throws Exception{CatalogBusinessFlowTest.startBusiness();}
    @BeforeEach void prepare(){
        fixture=new CatalogBusinessFlowTest();fixture.prepare();
        batches=new CatalogStatusBatchService(CatalogBusinessFlowTest.local,CatalogBusinessFlowTest.tasks,
                new CatalogLocalTransactions(sharded,CatalogBusinessFlowTest.local),fixture.commands,fixture.json,SOURCE,true,true);
    }
    @AfterEach void finish(){fixture.stopEnrichment();}
    @AfterAll static void closeFixture() throws Exception{CatalogBusinessFlowTest.closeBusiness();}

    @Test void realControllerPauseAndResumeKeepAdStatusAndOriginalResponseWithGuardUntilEveryFact() throws Exception {
        UUID id=UUID.randomUUID();var key=CommandKey.advertiser(SOURCE,1,id);String input="{\"status\":\"paused\",\"name\":\"状态与名称\"}";
        fixture.http.perform(put("/api/advertiser/1").contentType("application/json").content(input)
                .header(CatalogWriteRouter.COMMAND_HEADER,id).header(CatalogWriteRouter.OWNER_HEADER,"1")).andExpect(status().isServiceUnavailable());
        assertThat(CatalogBusinessFlowTest.business.root(1).status()).isEqualTo("paused");
        assertThat(one.queryForObject("SELECT active_command_id FROM catalog_advertiser_guard WHERE advertiser_id=1",UUID.class)).isEqualTo(id);
        finish(key,30);byte[] original=fixture.commands.find(key).resultBytes();
        fixture.http.perform(put("/api/advertiser/1").contentType("application/json").content(input)
                .header(CatalogWriteRouter.COMMAND_HEADER,id).header(CatalogWriteRouter.OWNER_HEADER,"1")).andExpect(status().isOk())
                .andExpect(content().json(new String(original,java.nio.charset.StandardCharsets.UTF_8)));
        var paused=last(1,102);assertThat(paused.advertiserVersion()).isEqualTo(2);assertThat(paused.aggregateVersion()).isEqualTo(2);
        assertThat(paused.servable()).isFalse();assertThat(paused.ad().status()).isEqualTo("active");
        assertThat(CatalogBusinessFlowTest.tasks.counts(key).completed()).isOne();
        assertThat(one.queryForObject("SELECT active_command_id FROM catalog_advertiser_guard WHERE advertiser_id=1",UUID.class)).isNull();
        var resume=admit(1,"active",null);finish(resume.key(),30);assertThat(last(1,102).servable()).isTrue();
        assertThat(last(1,102).aggregateVersion()).isEqualTo(3);
        var same=admit(1,"active",null);finish(same.key(),10);assertThat(last(1,102).aggregateVersion()).isEqualTo(3);
        assertThat(CatalogBusinessFlowTest.tasks.counts(same.key()).total()).isZero();
    }
    @Test void explicitTwoOwnerBatchPublishesChildFactsAndReportsOnlyAllCompletedAsSuccess() {
        seedOwner(2,203);UUID id=UUID.randomUUID();var input=List.of(target(2,1,"paused"),target(1,1,"paused"));
        var batch=batches.submitStatusBatch(id,input,"internal-operator","双owner暂停");
        assertThat(batches.submitStatusBatch(id,input.reversed(),"internal-operator","重复").requestSha256()).isEqualTo(batch.requestSha256());
        finishBatch(id,100);
        assertThat(batches.query(id).state()).isEqualTo("COMPLETED");assertThat(batches.query(id).counts().completed()).isEqualTo(2);
        assertThat(last(1,102).servable()).isFalse();assertThat(last(2,203).servable()).isFalse();
        assertThat(one.queryForObject("SELECT active_command_id FROM catalog_advertiser_guard WHERE advertiser_id=1",UUID.class)).isNull();
        assertThat(zero.queryForObject("SELECT active_command_id FROM catalog_advertiser_guard WHERE advertiser_id=2",UUID.class)).isNull();
    }
    @Test void lateBatchConflictsAndAuditCancelsOnlyUndispatchedOriginalTargets() {
        seedOwner(2,203);UUID id=UUID.randomUUID();
        var batch=batches.submitStatusBatch(id,List.of(target(1,1,"paused"),target(2,1,"paused")),"operator","原清单");
        var newer=admit(1,"paused",null);finish(newer.key(),30);newer=admit(1,"active",null);finish(newer.key(),30);
        batches.advance(batch.key()); // 目标1已DISPATCHED，取消不能动它，即使尚未访问owner。
        UUID audit=UUID.randomUUID();var cancelled=batches.cancelUndispatched(audit,id,"operator","仅取消未派发");
        assertThat(batches.cancelUndispatched(audit,id,"operator","重试").resultBytes()).isEqualTo(cancelled.resultBytes());
        finishBatch(id,20);
        var counts=batches.query(id).counts();assertThat(counts.conflicts()).isOne();assertThat(counts.cancelled()).isOne();assertThat(counts.completed()).isZero();
        assertThat(batches.query(id).state()).isEqualTo("PARTIAL");assertThat(CatalogBusinessFlowTest.business.root(1).status()).isEqualTo("active");
        assertThat(CatalogBusinessFlowTest.business.root(2).status()).isEqualTo("active");
        assertThat(last(1,102).advertiserVersion()).isEqualTo(3);
    }
    @Test void multipagePauseResumeFreezesAllVersionsAndDoesNotReviveIndividuallyPausedRejectedOrDeleted() throws Exception {
        for(long ad=1000;ad<1105;ad++)seedAd(1,ad,ad%10==0?"paused":"active",ad%11==0?"rejected":"approved");
        var deleteKey=CommandKey.advertiser(SOURCE,1,UUID.randomUUID());
        var delete=new CatalogMutation(Kind.AD_DELETE,102,null,null,null,null,null,null,null);
        fixture.commands.admit(new Submission(deleteKey,Kind.AD_DELETE,fixture.json.encode(new CatalogFragments.Request(delete,102L)),"owner","原广告明确删除"));
        finish(deleteKey,10);byte[] tombstone=one.queryForObject("SELECT payload_bytes FROM catalog_outbox WHERE purpose='KAFKA' AND ad_id=102",byte[].class);
        var pause=admit(1,"paused",null);fixture.service.advance(pause.key());fixture.service.advance(pause.key());
        assertThat(CatalogBusinessFlowTest.tasks.counts(pause.key()).total()).isEqualTo(100);
        fixture.service.advance(pause.key());assertThat(CatalogBusinessFlowTest.tasks.counts(pause.key()).total()).isEqualTo(105);
        assertThat(one.queryForObject("SELECT count(DISTINCT page_sha256) FROM catalog_task_item WHERE parent_command_id=?",Integer.class,pause.commandId())).isEqualTo(2);
        for(int i=0;i<10;i++)fixture.service.advance(pause.key());
        assertThat(CatalogBusinessFlowTest.tasks.counts(pause.key()).completed()).isPositive().isLessThan(105);
        assertThat(one.queryForObject("SELECT active_command_id FROM catalog_advertiser_guard WHERE advertiser_id=1",UUID.class)).isEqualTo(pause.commandId());
        finish(pause.key(),700);publishAndVerify(pause.commandId(),105);
        var resume=admit(1,"active",null);finish(resume.key(),700);publishAndVerify(resume.commandId(),105);
        for(long ad=1000;ad<1105;ad++) {
            var event=last(1,ad);assertThat(event.aggregateVersion()).isEqualTo(3);assertThat(event.advertiserVersion()).isEqualTo(3);
            assertThat(event.servable()).isEqualTo(ad%10!=0 && ad%11!=0);
            assertThat(event.ad().status()).isEqualTo(ad%10==0?"paused":"active");assertThat(event.ad().reviewStatus()).isEqualTo(ad%11==0?"rejected":"approved");
        }
        assertThat(one.queryForObject("SELECT payload_bytes FROM catalog_outbox WHERE purpose='KAFKA' AND ad_id=102",byte[].class)).isEqualTo(tombstone);
        assertThat(CatalogBusinessFlowTest.business.ad(1,102)).isNull();
    }
    @Test void lowIdCreateIsInClosedSetOrRejectedByOwnerGuardAndLaterSeesNewRootStatus() {
        seedAd(1,8_000_000_000_000_000_000L,"active","approved");
        var create=new CatalogMutation(Kind.AD_CREATE,1,new com.recsys.advertiser.dto.AdUpsert(10L,"低ID创建",null,1.0,"active","CPC",null,null,null),null,null,null,null,null,null);
        var firstKey=CommandKey.advertiser(SOURCE,1,UUID.randomUUID());
        fixture.commands.admit(new Submission(firstKey,Kind.AD_CREATE,fixture.json.encode(new CatalogFragments.Request(create,null)),"owner","先取得占有"));
        assertThatThrownBy(()->admit(1,"paused",null)).isInstanceOf(CatalogException.class);
        finish(firstKey,10);long low=fixture.json.decode(fixture.commands.find(firstKey).resultBytes(),com.recsys.advertiser.dto.AdView.class).adId();
        assertThat(low).isLessThan(8_000_000_000_000_000_000L);
        var pause=admit(1,"paused",null);fixture.service.advance(pause.key());fixture.service.advance(pause.key());
        assertThat(CatalogBusinessFlowTest.tasks.page(pause.key(),-1).stream().map(t->t.targetId())).contains(low);
        var lateKey=CommandKey.advertiser(SOURCE,1,UUID.randomUUID());var late=new Submission(lateKey,Kind.AD_CREATE,fixture.json.encode(new CatalogFragments.Request(create,null)),"owner","持有期间不能晚提交");
        assertThatThrownBy(()->fixture.commands.admit(late)).isInstanceOf(CatalogException.class);assertThat(fixture.commands.find(lateKey)).isNull();
        finish(pause.key(),40);fixture.commands.admit(late);finish(lateKey,10);
        long after=fixture.json.decode(fixture.commands.find(lateKey).resultBytes(),com.recsys.advertiser.dto.AdView.class).adId();
        assertThat(last(1,after).advertiserStatus()).isEqualTo("paused");assertThat(last(1,after).servable()).isFalse();
    }
    @Test void oneBusyOwnerPausesAfterFiniteBudgetWhileOtherCompletesAndAuditResumesOriginalChild() {
        seedOwner(2,203);
        var blocked=CommandKey.advertiser(SOURCE,1,UUID.randomUUID());
        var mutation=new CatalogMutation(Kind.AD_STATUS,102,null,null,null,null,"active",null,null);
        fixture.commands.admit(new Submission(blocked,Kind.AD_STATUS,fixture.json.encode(new CatalogFragments.Request(mutation,102L)),"owner","占有屏障"));
        UUID id=UUID.randomUUID();batches.submitStatusBatch(id,List.of(target(1,1,"paused"),target(2,1,"paused")),"operator","局部公平");
        UUID child=batches.targets(id,-1).getFirst().childCommandId();finishBatch(id,180);
        var counts=batches.query(id).counts();assertThat(counts.completed()).isOne();assertThat(counts.paused()).isOne();assertThat(batches.query(id).state()).isEqualTo("PARTIAL");
        var target=batches.targets(id,-1).getFirst();assertThat(target.attempts()).isEqualTo(3);
        assertThat(one.queryForObject("SELECT active_command_id FROM catalog_advertiser_guard WHERE advertiser_id=1",UUID.class)).isEqualTo(blocked.command());
        finish(blocked,10);UUID audit=UUID.randomUUID();var resumed=batches.retryTarget(audit,id,1,1,"operator","恢复原目标");
        assertThat(batches.retryTarget(audit,id,1,1,"operator","响应未知重试").resultBytes()).isEqualTo(resumed.resultBytes());
        finishBatch(id,100);assertThat(batches.query(id).state()).isEqualTo("COMPLETED");assertThat(batches.targets(id,-1).getFirst().childCommandId()).isEqualTo(child);
        assertThat(batches.targets(id,-1).getFirst().round()).isEqualTo(2);
    }

    @Test void twoExhaustedObservationsKeepDistinctReceiptsAndThirdOwnerStillCompletes() throws Exception {
        seedOwner(2,203);seedOwner(3,305);UUID id=UUID.randomUUID();
        batches.submitStatusBatch(id,List.of(target(1,1,"paused"),target(2,1,"paused"),target(3,1,"paused")),"operator","重复耗尽不能阻断公平推进");
        var key=batches.key(id);
        for(long owner=1;owner<=2;owner++) {
            if(!fixture.commands.find(key).phase().equals("BATCH_OBSERVE"))batches.advance(key);
            assertThat(fixture.json.decode(fixture.commands.find(key).frozenBytes(),CatalogStatusBatchService.Progress.class).target()).isEqualTo(owner);
            for(int attempt=1;attempt<=3;attempt++) {
                CommandRow claim=null;long until=System.nanoTime()+java.time.Duration.ofSeconds(5).toNanos();
                while(claim==null && System.nanoTime()<until){claim=fixture.commands.claim(key);if(claim==null)Thread.sleep(50);}
                assertThat(claim).isNotNull();assertThat(claim.attempts()).isEqualTo(attempt);assertThat(fixture.commands.failed(claim,Failure.RESULT_UNKNOWN)).isTrue();
            }
            batches.advance(key);
            assertThat(batches.targets(id,-1).get((int)owner-1).state()).isEqualTo("PAUSED");
        }
        finishBatch(id,100);var counts=batches.query(id).counts();assertThat(counts.paused()).isEqualTo(2);assertThat(counts.completed()).isOne();
        var db=key.shard()==0?zero:one;
        assertThat(db.queryForObject("SELECT count(DISTINCT step_id) FROM catalog_outbox WHERE command_id=? AND step_id LIKE 'EXHAUSTED_%'",Integer.class,id)).isEqualTo(2);
        assertThat(batches.query(id).state()).isEqualTo("PARTIAL");
    }
    @Test void disabledInternalAdmissionAndRecoveryKeepOriginalResultsAndAllowExistingProgress() {
        UUID id=UUID.randomUUID();var input=List.of(target(1,1,"paused"));var original=batches.submitStatusBatch(id,input,"operator","原批次");
        var disabled=new CatalogStatusBatchService(CatalogBusinessFlowTest.local,CatalogBusinessFlowTest.tasks,new CatalogLocalTransactions(sharded,CatalogBusinessFlowTest.local),fixture.commands,fixture.json,SOURCE,false,false);
        assertThat(disabled.submitStatusBatch(id,input,"operator","原ID查回").requestBytes()).isEqualTo(original.requestBytes());
        assertThatThrownBy(()->disabled.submitStatusBatch(UUID.randomUUID(),input,"operator","关闭新准入")).isInstanceOfSatisfying(CatalogException.class,e->assertThat(e.failure()).isEqualTo(Failure.NOT_READY));
        UUID audit=UUID.randomUUID();assertThatThrownBy(()->disabled.cancelUndispatched(audit,id,"operator","关闭恢复")).isInstanceOfSatisfying(CatalogException.class,e->assertThat(e.failure()).isEqualTo(Failure.NOT_READY));
        var result=batches.cancelUndispatched(audit,id,"operator","显式开启恢复");
        assertThat(disabled.cancelUndispatched(audit,id,"operator","原审计响应").resultBytes()).isEqualTo(result.resultBytes());
        disabled.advance(original.key());assertThat(disabled.query(id).counts().cancelled()).isOne();
    }
    @Test void simultaneousSameBatchAndOppositeOwnerCommandsOnlyPersistOneClosedOwnerSet() throws Exception {
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);var start=new java.util.concurrent.CountDownLatch(1);UUID id=UUID.randomUUID();
        try {
            var a=pool.submit(()->{start.await();return batches.submitStatusBatch(id,List.of(target(1,1,"paused")),"operator","并发同ID");});
            var b=pool.submit(()->{start.await();return batches.submitStatusBatch(id,List.of(target(1,1,"paused")),"operator","并发同ID");});start.countDown();
            assertThat(a.get(8,java.util.concurrent.TimeUnit.SECONDS).requestBytes()).isEqualTo(b.get(8,java.util.concurrent.TimeUnit.SECONDS).requestBytes());
            assertThat(batches.targets(id,-1)).hasSize(1);
            seedAd(1,8_000_000_000_000_000_000L,"active","approved");
            var create=new CatalogMutation(Kind.AD_CREATE,1,new com.recsys.advertiser.dto.AdUpsert(10L,"并发低ID",null,1.0,"active","CPC",null,null,null),null,null,null,null,null,null);
            var createKey=CommandKey.advertiser(SOURCE,1,UUID.randomUUID());var gate=new java.util.concurrent.CountDownLatch(1);
            var first=pool.submit(()->{gate.await();try{return fixture.commands.admit(new Submission(createKey,Kind.AD_CREATE,fixture.json.encode(new CatalogFragments.Request(create,null)),"owner","并发创建")).key();}catch(CatalogException e){assertThat(e.failure()).isEqualTo(Failure.CONFLICT);return null;}});
            var second=pool.submit(()->{gate.await();try{return admit(1,"paused",null).key();}catch(CatalogException e){assertThat(e.failure()).isEqualTo(Failure.CONFLICT);return null;}});gate.countDown();
            var winners=new ArrayList<CommandKey>();var x=first.get(8,java.util.concurrent.TimeUnit.SECONDS);var y=second.get(8,java.util.concurrent.TimeUnit.SECONDS);if(x!=null)winners.add(x);if(y!=null)winners.add(y);
            assertThat(winners).hasSize(1);finish(winners.getFirst(),40);
            if(x!=null){long low=fixture.json.decode(fixture.commands.find(x).resultBytes(),com.recsys.advertiser.dto.AdView.class).adId();assertThat(low).isLessThan(8_000_000_000_000_000_000L);var pause=admit(1,"paused",null);finish(pause.key(),40);assertThat(CatalogBusinessFlowTest.tasks.page(pause.key(),-1).stream().map(t->t.targetId())).contains(low);}
            else assertThat(fixture.commands.find(createKey)).isNull();
        } finally {pool.shutdownNow();}
    }

    @Test void pauseAndResumeRaceUsesOneGuardAndLosingHttpGetsConflictWithoutVersionOrManifest() throws Exception {
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);var gate=new java.util.concurrent.CountDownLatch(1);
        var requests=new ArrayList<Submission>();
        for(String status:List.of("paused","active")) {
            var mutation=new CatalogMutation(Kind.ROOT_UPDATE,1,null,null,null,new AdvertiserUpsert(null,null,status),null,null,null);
            requests.add(new Submission(CommandKey.advertiser(SOURCE,1,UUID.randomUUID()),Kind.ROOT_STATUS,fixture.json.encode(new CatalogFragments.Request(mutation,null)),"operator","相反状态并发"));
        }
        try {
            var futures=requests.stream().map(request->pool.submit(()->{gate.await();try{fixture.commands.admit(request);return true;}catch(CatalogException e){assertThat(e.failure()).isEqualTo(Failure.CONFLICT);return false;}})).toList();gate.countDown();
            boolean first=futures.get(0).get(8,java.util.concurrent.TimeUnit.SECONDS),second=futures.get(1).get(8,java.util.concurrent.TimeUnit.SECONDS);assertThat(first^second).isTrue();
            var winner=requests.get(first?0:1);var loser=requests.get(first?1:0);String desired=first?"active":"paused";
            assertThat(one.queryForObject("SELECT active_command_id FROM catalog_advertiser_guard WHERE advertiser_id=1",UUID.class)).isEqualTo(winner.key().command());
            fixture.http.perform(put("/api/advertiser/1").contentType("application/json").content("{\"status\":\""+desired+"\"}")
                    .header(CatalogWriteRouter.COMMAND_HEADER,loser.key().command()).header(CatalogWriteRouter.OWNER_HEADER,"1")).andExpect(status().isConflict());
            assertThat(fixture.commands.find(loser.key())).isNull();assertThat(CatalogBusinessFlowTest.tasks.counts(loser.key()).total()).isZero();
            assertThat(one.queryForObject("SELECT advertiser_version FROM catalog_advertiser_guard WHERE advertiser_id=1",Long.class)).isOne();
            finish(winner.key(),30);fixture.commands.admit(loser);finish(loser.key(),30);
            assertThat(CatalogBusinessFlowTest.business.root(1).status()).isEqualTo(desired);
            assertThat(last(1,102).aggregateVersion()).isEqualTo(first?3:2);assertThat(last(1,102).advertiserStatus()).isEqualTo(desired);
            assertThat(one.queryForObject("SELECT active_command_id FROM catalog_advertiser_guard WHERE advertiser_id=1",UUID.class)).isNull();
        } finally {pool.shutdownNow();}
    }
    @Test void explicitBatchLimitRejectsBeforeAnyCommandAndPersistsExactlyOneHundredOriginalTargets() {
        UUID id=UUID.randomUUID();var tooMany=java.util.stream.LongStream.rangeClosed(1,101).mapToObj(owner->target(owner,1,"paused")).toList();
        assertThatThrownBy(()->batches.submitStatusBatch(id,tooMany,"operator","超过上限"))
                .isInstanceOfSatisfying(CatalogException.class,e->assertThat(e.failure()).isEqualTo(Failure.INVALID_INPUT));
        assertThat(fixture.commands.find(batches.key(id))).isNull();
        assertThatThrownBy(()->batches.submitStatusBatch(id,List.of(target(1,1,"paused"),target(1,1,"active")),"operator","重复目标"))
                .isInstanceOfSatisfying(CatalogException.class,e->assertThat(e.failure()).isEqualTo(Failure.INVALID_INPUT));
        var original=batches.submitStatusBatch(id,tooMany.subList(0,100),"operator","精确上限");
        assertThat(batches.targets(id,-1)).hasSize(100);assertThat(batches.targets(id,99)).isEmpty();assertThat(batches.query(id).counts().total()).isEqualTo(100);
        assertThat(batches.submitStatusBatch(id,tooMany.subList(0,100).reversed(),"operator","原批次查回").requestBytes()).isEqualTo(original.requestBytes());
        assertThat(CatalogBusinessFlowTest.business.root(1).status()).isEqualTo("active");
    }
    CommandRow admit(long owner,String status,Long expected) {
        var mutation=new CatalogMutation(Kind.ROOT_UPDATE,owner,null,null,null,new AdvertiserUpsert(null,null,status),null,null,null);
        var key=CommandKey.advertiser(SOURCE,owner,UUID.randomUUID());
        return fixture.commands.admitExpected(new Submission(key,Kind.ROOT_STATUS,fixture.json.encode(new CatalogFragments.Request(mutation,null,expected)),"operator","状态专项"),expected);
    }
    void finish(CommandKey key,int max){for(int i=0;i<max && !fixture.commands.find(key).completed();i++)fixture.service.advance(key);assertThat(fixture.commands.find(key).state()).isEqualTo("COMPLETED");}
    void finishBatch(UUID id,int max) {
        var key=batches.key(id);
        for(int i=0;i<max;i++) {
            var row=fixture.commands.find(key);if(row.completed() || State.PAUSED.name().equals(row.state()))return;
            batches.advance(key);
            for(var item:batches.targets(id,-1)) {
                var child=CommandKey.advertiser(SOURCE,item.targetId(),item.childCommandId());var command=fixture.commands.find(child);
                if(command!=null && !command.completed() && !State.PAUSED.name().equals(command.state()))fixture.service.advance(child);
            }
            try{Thread.sleep(55);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}
        }
        throw new AssertionError("批次未在有界步数内收敛");
    }
    void seedOwner(long owner,long ad) {
        var db=owner%2==0?zero:one;db.update("UPDATE catalog_shard_control SET mode='LEGACY'");
        try{db.update("INSERT INTO advertiser VALUES(?,?,100,'active')",owner,"owner"+owner);
            db.update("INSERT INTO ad(ad_id,advertiser_id,item_id,title,landing_url,quality_score,status,review_status,optimization_type) VALUES(?,?,10,'广告','/landing',1,'active','approved','CPC')",ad,owner);
        }finally{db.update("UPDATE catalog_shard_control SET mode='ACTIVE'");}
        db.update("INSERT INTO catalog_advertiser_guard(shard_id,advertiser_id) VALUES(?,?)",owner%2,owner);
        db.update("INSERT INTO catalog_aggregate(shard_id,ad_id,advertiser_id,reserved_version,last_materialized_version) VALUES(?,?,?,1,1)",owner%2,ad,owner);
    }
    void seedAd(long owner,long ad,String status,String review) {
        var db=owner%2==0?zero:one;db.update("UPDATE catalog_shard_control SET mode='LEGACY'");
        try{db.update("INSERT INTO ad(ad_id,advertiser_id,item_id,title,landing_url,quality_score,status,review_status,optimization_type) VALUES(?,?,10,'广告','/landing',1,?,?,'CPC')",ad,owner,status,review);}
        finally{db.update("UPDATE catalog_shard_control SET mode='ACTIVE'");}
        db.update("INSERT INTO catalog_aggregate(shard_id,ad_id,advertiser_id,reserved_version,last_materialized_version) VALUES(?,?,?,1,1)",owner%2,ad,owner);
    }
    void publishAndVerify(UUID command,int expected) throws Exception {
        var originals=new HashMap<String,byte[]>();
        one.query("SELECT event_id,payload_bytes FROM catalog_outbox WHERE command_id=? AND purpose='KAFKA'",rs->{originals.put(rs.getString(1),rs.getBytes(2));},command);
        assertThat(originals).hasSize(expected);
        try(var publisher=new CatalogKafkaPublisher(KAFKA.bootstrap(),kafkaIdentity.clusterId(),kafkaIdentity.topicId())) {
            var relay=new CatalogRelay(fixture.events,publisher,SOURCE);
            for(int i=0;i<20;i++)if(relay.advance(1)==0)break;
        }
        assertThat(one.queryForObject("SELECT count(*) FROM catalog_outbox WHERE command_id=? AND purpose='KAFKA' AND state='PUBLISHED'",Integer.class,command)).isEqualTo(expected);
        var properties=new Properties();properties.put("bootstrap.servers",KAFKA.bootstrap());properties.put("group.id","status-wire-"+UUID.randomUUID());properties.put("auto.offset.reset","earliest");properties.put("enable.auto.commit","false");
        try(var consumer=new org.apache.kafka.clients.consumer.KafkaConsumer<byte[],byte[]>(properties,new org.apache.kafka.common.serialization.ByteArrayDeserializer(),new org.apache.kafka.common.serialization.ByteArrayDeserializer())) {
            consumer.subscribe(List.of(VersionedAdCatalogEvent.TOPIC));var seen=new HashSet<String>();long deadline=System.nanoTime()+java.time.Duration.ofSeconds(20).toNanos();
            while(seen.size()<expected && System.nanoTime()<deadline)for(var record:consumer.poll(java.time.Duration.ofMillis(200))) {
                var event=new CatalogSnapshotCodec(fixture.object).decode(record.key(),record.value());
                if(command.equals(event.commandId())){assertThat(record.value()).isEqualTo(originals.get(event.eventId()));assertThat(record.key()).isEqualTo(CatalogEventCodec.key(event.adId()));seen.add(event.eventId());}
            }
            assertThat(seen).containsExactlyInAnyOrderElementsOf(originals.keySet());
        }
    }
    VersionedAdCatalogEvent last(long owner,long ad){var db=owner%2==0?zero:one;byte[] value=db.queryForObject("SELECT payload_bytes FROM catalog_outbox WHERE purpose='KAFKA' AND ad_id=? ORDER BY aggregate_version DESC LIMIT 1",byte[].class,ad);return new CatalogSnapshotCodec(fixture.object).decode(CatalogEventCodec.key(ad),value);}
    private CatalogStatusBatchService.StatusTarget target(long owner,long version,String state){return new CatalogStatusBatchService.StatusTarget(owner,version,state);}
}
