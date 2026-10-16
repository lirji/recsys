package com.recsys.advertiser.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recsys.advertiser.persistence.mapper.primary.*;
import com.recsys.advertiser.persistence.mapper.derived.AdvertiserItemEmbeddingMapper;
import com.recsys.persistence.spring.SpringSqlSessions;
import com.recsys.common.ad.*;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.*;
import java.util.*;
import static com.recsys.advertiser.catalog.CatalogPhysicalTransactionTest.*;
import static org.assertj.core.api.Assertions.*;

/** 存活目录基线必须从真实两库冻结并发布，半初始化绝不生成空墓碑或改用新ID。 */
@Testcontainers @Tag("catalog-reliability")
class CatalogInitializationTest {
    @Container static final PostgreSQLContainer<?> PG0=ZERO,PG1=ONE;
    @Container static final KafkaFixture BROKER=KAFKA;
    @Container static final GenericContainer<?> REDIS=new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    static SqlSessionTemplate session;static LettuceConnectionFactory connection;
    CatalogInitializationService service;CatalogRuntimeBinding binding;CatalogEnrichment enrichment;CatalogOutboxRepository outbox;
    CatalogLocalMapper local;CatalogBusinessMapper business;CatalogInitializationMapper init;
    final ObjectMapper object=new ObjectMapper().findAndRegisterModules();UUID id;
    @BeforeAll static void startFixture() throws Exception {
        CatalogPhysicalTransactionTest.start();session=new SqlSessionTemplate(SpringSqlSessions.create(sharded,"classpath*:mapper/advertiser/primary/Catalog*.xml"));
        connection=new LettuceConnectionFactory(REDIS.getHost(),REDIS.getMappedPort(6379));connection.afterPropertiesSet();connection.start();
    }
    @BeforeEach void prepare() throws Exception {
        new CatalogPhysicalTransactionTest().arrangeExclusiveDatabase();id=UUID.randomUUID();
        for(var db:List.of(zero,one)) {
            db.update("UPDATE catalog_shard_control SET mode='LEGACY',source_id=NULL,topology_version=NULL,initializing_command_id=NULL,initialization_ready=false,writer_manifest='{}',cluster_id=NULL,topic_id=NULL,topic_name=NULL,stop_reason=NULL");
            db.execute("TRUNCATE catalog_command,catalog_step,catalog_outbox,catalog_advertiser_guard,catalog_aggregate,catalog_task_item");
        }
        zero.update("INSERT INTO advertiser VALUES(2,'even',100,'paused')");
        zero.update("INSERT INTO ad(ad_id,advertiser_id,item_id,title,landing_url,quality_score,status,review_status,optimization_type) VALUES(103,2,10,'even ad','/even',1,'active','approved','CPC')");
        zero.update("INSERT INTO bidword VALUES(100,102,'真实词','EXACT',1,'CPC')");
        one.update("INSERT INTO ad_creative VALUES(101,103,'待审创意','/creative','active','pending')");
        local=session.getMapper(CatalogLocalMapper.class);business=session.getMapper(CatalogBusinessMapper.class);init=session.getMapper(CatalogInitializationMapper.class);
        var tx=new CatalogLocalTransactions(sharded,local);var json=new CatalogJson(object);var codec=new CatalogSnapshotCodec(object);
        var commands=new CatalogCommandRepository(local,tx);outbox=new CatalogOutboxRepository(local,tx,codec);
        var steps=new CatalogBusinessSteps(business,local,new CatalogStepRepository(local,tx,object),commands,outbox,json,codec);
        var vectors=new SqlSessionTemplate(SpringSqlSessions.create(ds0,"classpath*:mapper/advertiser/derived/AdvertiserItemEmbeddingMapper.xml")).getMapper(AdvertiserItemEmbeddingMapper.class);
        enrichment=new CatalogEnrichment(vectors,new StringRedisTemplate(connection));
        binding=new CatalogRuntimeBinding(new CatalogProperties(true,false,false,false,true,SOURCE,"init-test",7),local,object);
        service=new CatalogInitializationService(local,init,business,tx,steps,outbox,enrichment,binding,json,codec,SOURCE,KAFKA.bootstrap());
    }
    @AfterEach void closeReader(){enrichment.close();}
    @AfterAll static void closeFixture() throws Exception {connection.destroy();CatalogPhysicalTransactionTest.close();}
    CatalogInitializationService.Input input(){return new CatalogInitializationService.Input(SOURCE,CatalogProtocol.TOPOLOGY,VersionedAdCatalogEvent.TOPIC,List.of(0,1),Map.of("init-test",7));}
    @Test void completeBaselineFreezesBothPhysicalOwnersChildrenAndRealKafkaOriginals() {
        service.initialize(id,input(),"maintenance-owner","隔离基线初始化");
        for(int i=0;i<50;i++){var row=service.advance(id);if(row.completed())break;if(i==49)fail("初始化未有界推进完成："+row.phase());}
        binding.verify(true);
        for(int shard=0;shard<2;shard++) {
            var db=shard==0?zero:one;long ad=shard==0?103:102;
            assertThat(db.queryForObject("SELECT last_materialized_version FROM catalog_aggregate WHERE ad_id=?",Long.class,ad)).isEqualTo(1);
            byte[] bytes=db.queryForObject("SELECT payload_bytes FROM catalog_outbox WHERE purpose='KAFKA' AND ad_id=?",byte[].class,ad);
            var event=new CatalogSnapshotCodec(object).decode(CatalogEventCodec.key(ad),bytes);
            assertThat(event.commandId()).isEqualTo(id);assertThat(event.lifecycle()).isEqualTo(VersionedAdCatalogEvent.Lifecycle.PRESENT);
            if(shard==0){assertThat(event.servable()).isFalse();assertThat(event.creatives().getFirst().reviewStatus()).isEqualTo("pending");}
            else assertThat(event.bidwords().getFirst().keyword()).isEqualTo("真实词");
            assertThat(db.queryForObject("SELECT count(*) FROM catalog_step s JOIN catalog_outbox o ON s.shard_id=o.shard_id AND s.outbox_id=o.outbox_id WHERE s.write_txid<>o.write_txid",Integer.class)).isZero();
            var sent=local.event(shard,event.eventId());assertThat(sent.state()).isEqualTo("PUBLISHED");assertThat(sent.brokerOffset()).isNotNull();
            var record=new CatalogPhysicalTransactionTest().consume(event.eventId(),1).getFirst();assertThat(record.key()).isEqualTo(CatalogEventCodec.key(ad));assertThat(record.value()).isEqualTo(bytes);
            try(var publisher=new CatalogKafkaPublisher(KAFKA.bootstrap(),kafkaIdentity.clusterId(),kafkaIdentity.topicId())) {assertThat(new CatalogRelay(outbox,publisher,SOURCE).advance(shard)).isZero();}
        }
        assertThat(service.initialize(id,input(),"maintenance-owner","重复同ID").completed()).isTrue();
        assertThatThrownBy(()->service.initialize(UUID.randomUUID(),input(),"owner","不能重新采样")).isInstanceOf(CatalogProtocol.CatalogException.class);
    }
    @Test void partialQuiesceKeepsOriginalOwnershipAndRejectsOldWriterUntilSameIdContinues() {
        service.initialize(id,input(),"owner","原ID");service.advance(id);service.advance(id);
        assertThat(local.binding(0).mode()).isEqualTo("QUIESCED");assertThat(local.binding(1).mode()).isEqualTo("LEGACY");
        assertThatThrownBy(()->binding.verify(true)).isInstanceOf(CatalogProtocol.CatalogException.class);
        assertThatThrownBy(()->service.initialize(UUID.randomUUID(),input(),"other","拒绝抢占")).isInstanceOf(CatalogProtocol.CatalogException.class);
        assertThatThrownBy(()->zero.update("UPDATE ad SET title='旧writer' WHERE ad_id=103")).hasMessageContaining("CATALOG_WRITER_CLOSED");
        service.advance(id);assertThat(local.binding(1).mode()).isEqualTo("QUIESCED");
    }
    @Test void wrongPhysicalModulusIsRejectedWithoutMovingOrOpeningAdmission() {
        zero.update("INSERT INTO advertiser VALUES(9,'错库',1,'active')");service.initialize(id,input(),"owner","错库负对照");service.advance(id);
        assertThatThrownBy(()->service.advance(id)).isInstanceOf(CatalogProtocol.CatalogException.class);
        assertThat(zero.queryForObject("SELECT count(*) FROM advertiser WHERE advertiser_id=9",Integer.class)).isOne();
        assertThat(local.binding(0).initializationReady()).isFalse();assertThat(local.binding(0).initializingCommandId()).isEqualTo(id);
    }
    @Test void concurrentDifferentInitializationIdsOccupyHomeBeforeAnyOtherShardEffect() throws Exception {
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);var start=new java.util.concurrent.CountDownLatch(1);
        var ids=List.of(UUID.randomUUID(),UUID.randomUUID());
        try {
            var calls=ids.stream().map(command->pool.submit(()->{start.await();try{service.initialize(command,input(),"owner","并发初始化");return true;}catch(RuntimeException rejected){return false;}})).toList();
            start.countDown();int successes=0;for(var call:calls)if(call.get(10,java.util.concurrent.TimeUnit.SECONDS))successes++;
            assertThat(successes).isOne();UUID original=local.binding(0).initializingCommandId();assertThat(ids).contains(original);
            assertThat(local.binding(1).initializingCommandId()).isNull();assertThat(local.binding(1).sourceId()).isNull();assertThat(local.binding(1).mode()).isEqualTo("LEGACY");
            assertThat(zero.queryForObject("SELECT count(*) FROM catalog_command WHERE scope_kind='INITIALIZE'",Integer.class)).isOne();
            assertThat(service.initialize(original,input(),"owner","查原结果").commandId()).isEqualTo(original);
            UUID loser=ids.get(0).equals(original)?ids.get(1):ids.get(0);
            assertThatThrownBy(()->service.initialize(loser,input(),"owner","不得另起采样")).isInstanceOf(CatalogProtocol.CatalogException.class);
        } finally {pool.shutdownNow();}
    }
    @Test void invalidBaselinePausesWithOriginalOwnershipAndNeverCreatesEmptySnapshotOrTombstone() throws Exception {
        zero.update("UPDATE bidword SET match_type='invalid' WHERE id=100");service.initialize(id,input(),"owner","非法存量必须阻断");
        boolean invalid=false;
        for(int i=0;i<50;i++)try{service.advance(id);}catch(IllegalArgumentException expected){invalid=true;break;}
        assertThat(invalid).isTrue();var key=new CatalogProtocol.CommandKey(0,SOURCE,CatalogProtocol.ScopeKind.INITIALIZE,"INITIALIZE",id);
        for(int retry=0;retry<2;retry++){Thread.sleep(retry==0?1300:2300);assertThatThrownBy(()->service.advance(id)).isInstanceOf(IllegalArgumentException.class);}
        assertThat(local.command(key).state()).isEqualTo("PAUSED");assertThat(local.command(key).attempts()).isEqualTo(3);
        assertThat(one.queryForObject("SELECT count(*) FROM catalog_outbox WHERE purpose='KAFKA' AND ad_id=102",Integer.class)).isZero();
        assertThat(one.queryForObject("SELECT deleted FROM catalog_aggregate WHERE ad_id=102",Boolean.class)).isFalse();
        for(int shard=0;shard<2;shard++){assertThat(local.binding(shard).initializingCommandId()).isEqualTo(id);assertThat(local.binding(shard).initializationReady()).isFalse();}
        assertThatThrownBy(()->service.initialize(UUID.randomUUID(),input(),"owner","不能换ID跳过坏数据")).isInstanceOf(CatalogProtocol.CatalogException.class);
    }
    @Test void frozenInventoryUsesBoundedPhysicalPagesAndRestartsFromPersistedCursor() {
        for(int i=0;i<101;i++)one.update("INSERT INTO ad(ad_id,advertiser_id,item_id,title,landing_url,quality_score,status,review_status,optimization_type) VALUES(?,1,10,'page','/page',1,'active','approved','CPC')",1000+i);
        service.initialize(id,input(),"owner","多页封闭清单");var key=new CatalogProtocol.CommandKey(0,SOURCE,CatalogProtocol.ScopeKind.INITIALIZE,"INITIALIZE",id);
        for(int i=0;i<20;i++) {
            var row=service.advance(id);var progress=row.frozenBytes()==null?null:new CatalogJson(object).decode(row.frozenBytes(),CatalogInitializationService.Progress.class);
            if("ADS".equals(row.phase()) && progress.shard()==1 && progress.after()>0)break;
        }
        assertThat(one.queryForObject("SELECT count(*) FROM catalog_task_item WHERE parent_command_id=?",Integer.class,id)).isEqualTo(100);
        byte[] checkpoint=local.command(key).frozenBytes();assertThat(new CatalogJson(object).decode(checkpoint,CatalogInitializationService.Progress.class).after()).isEqualTo(1098);
        // 再次从数据库加载原命令推进下一页，没有新清单或基于当前MAX(id)跳过存量。
        service.advance(id);
        assertThat(one.queryForObject("SELECT count(*) FROM catalog_task_item WHERE parent_command_id=?",Integer.class,id)).isEqualTo(102);
        assertThat(one.queryForObject("SELECT count(DISTINCT target_id) FROM catalog_task_item WHERE parent_command_id=?",Integer.class,id)).isEqualTo(102);
        assertThat(local.command(key).phase()).isEqualTo("CHILDREN");
    }
    @Test void oversizedStoredChildrenRemainQuiescedWithoutTruncatingBaseline() {
        for(int n=0;n<1024;n++)zero.update("INSERT INTO bidword VALUES(?,102,'bounded','EXACT',1,'CPC')",1000+n);
        service.initialize(id,input(),"owner","存量超限不得截断");
        boolean limited=false;
        for(int step=0;step<60;step++)try{service.advance(id);}catch(CatalogProtocol.CatalogException failure){assertThat(failure.failure()).isEqualTo(CatalogProtocol.Failure.SNAPSHOT_LIMIT);limited=true;break;}
        assertThat(limited).isTrue();assertThat(zero.queryForObject("SELECT count(*) FROM bidword WHERE ad_id=102",Integer.class)).isEqualTo(1025);
        assertThat(one.queryForObject("SELECT count(*) FROM catalog_outbox WHERE purpose='KAFKA' AND ad_id=102",Integer.class)).isZero();
        for(int shard=0;shard<2;shard++)assertThat(local.binding(shard).initializationReady()).isFalse();
    }

    @Test void recoveryOnlyQuiescedInitializationSettlesExpiredFinalStepAndResumesOriginalBaseline() throws Exception {
        service.initialize(id,input(),"owner","恢复专用末次领取");
        CatalogProtocol.CommandRow row=null;
        for(int n=0;n<30;n++){row=service.advance(id);if(row.phase().equals("BASE_OWNER"))break;}
        assertThat(row.phase()).isEqualTo("BASE_OWNER");var key=row.key();
        var json=new CatalogJson(object);var progress=json.decode(row.frozenBytes(),CatalogInitializationService.Progress.class);
        var target=progress.target();var tx=new CatalogLocalTransactions(sharded,local);
        var steps=new CatalogStepRepository(local,tx,object);
        var request=new CatalogProtocol.StepRequest(progress.shard(),key,"BASE_OWNER_"+target.targetId(),target.targetOwnerId(),target.targetId(),1L,row.requestBytes(),"[]");
        var db=progress.shard()==0?zero:one;
        db.execute("ALTER TABLE catalog_aggregate ADD CONSTRAINT catalog_test_baseline_failure CHECK(ad_id<>"+target.targetId()+")");
        try {
            assertThatThrownBy(()->service.advance(id)).isInstanceOf(RuntimeException.class);Thread.sleep(1300);
            assertThatThrownBy(()->service.advance(id)).isInstanceOf(RuntimeException.class);
        } finally {db.execute("ALTER TABLE catalog_aggregate DROP CONSTRAINT catalog_test_baseline_failure");}
        Thread.sleep(2400);
        assertThat(tx.on(0,()->local.claimCommand(key))).isOne();assertThat(steps.claim(request).attempts()).isEqualTo(3);
        var original=local.command(key);assertThat(original.attempts()).isEqualTo(3);
        var recovery=new CatalogRecoveryService(local,session.getMapper(com.recsys.advertiser.persistence.mapper.primary.CatalogRecoveryMapper.class),tx,binding,json,new CatalogSnapshotCodec(object),SOURCE,KAFKA.bootstrap());
        var retry=new CatalogRecoveryService.Request(CatalogProtocol.Kind.RETRY_STEP,key,progress.shard(),request.stepKey(),1,1,null,null,original.requestSha256());
        UUID early=UUID.randomUUID();recovery.submit(early,retry,"owner","有效租约不得抢占");
        assertThatThrownBy(()->recovery.advance(early)).isInstanceOf(CatalogProtocol.CatalogException.class);
        assertThat(steps.find(request).state()).isEqualTo("RUNNING");assertThat(local.command(key).state()).isEqualTo("RUNNING");
        long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(35);
        while(steps.find(request).leaseUntil().isAfter(java.time.Instant.now()) && System.nanoTime()<until)Thread.sleep(100);
        UUID audit=UUID.randomUUID();recovery.submit(audit,retry,"owner","QUIESCED无worker审计清算");
        for(int n=0;n<3;n++)recovery.advance(audit);assertThat(recovery.query(audit).completed()).isTrue();
        assertThat(local.binding(0).mode()).isEqualTo("QUIESCED");assertThat(local.binding(1).mode()).isEqualTo("QUIESCED");
        assertThat(local.binding(0).initializingCommandId()).isEqualTo(id);
        for(int n=0;n<60;n++)if(service.advance(id).completed())break;
        binding.verify(true);assertThat(local.command(key).completed()).isTrue();assertThat(local.command(key).round()).isEqualTo(2);
        assertThat(steps.find(request).applied()).isTrue();assertThat(steps.find(request).round()).isEqualTo(2);
    }

}
