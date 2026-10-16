package com.recsys.recengine.filter.seen;

import com.recsys.common.behavior.LegacyBehaviorAccepted;
import com.recsys.common.behavior.LegacyBehaviorAccepted.*;
import com.recsys.common.constant.ActionType;
import com.recsys.platform.behavior.LegacyBehaviorCodec;
import com.recsys.recengine.filter.SeenItemsConsumer;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.*;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.recsys.recengine.filter.seen.SeenRecoveryFixture.*;
import static org.junit.jupiter.api.Assertions.*;

/** 新流真实产品入口、PG/Kafka/Redis及进程窗口；与旧流共用机制但逐流核对状态。 */
@Tag("behavior-reliability") @Testcontainers
class VersionedSeenKafkaRedisTest {
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>("postgres:16-alpine");
    @Container static final KafkaFixture KAFKA=new KafkaFixture();
    @Container static final GenericContainer<?> REDIS=new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    SeenRecoveryFixture f;
    final Map<SeenStream,String> topics=new EnumMap<>(SeenStream.class),groups=new EnumMap<>(SeenStream.class);
    @AfterAll static void closeClient(){closeObserver();}
    @BeforeEach void setup() throws Exception {
        f=new SeenRecoveryFixture(PG);
        for(var stream:List.of(SeenStream.LIVE,SeenStream.HISTORY)) {
            String topic=(stream==SeenStream.LIVE?LegacyBehaviorAccepted.LIVE_TOPIC:LegacyBehaviorAccepted.HISTORY_TOPIC)+".c2-"+UUID.randomUUID();
            topics.put(stream,topic);groups.put(stream,group());topic(KAFKA.bootstrap(),topic,2);
            long deadline=System.nanoTime()+java.time.Duration.ofSeconds(20).toNanos();
            while(true)try(var a=admin(KAFKA.bootstrap())) {
                a.describeConsumerGroups(List.of(groups.get(stream))).all().get(5,java.util.concurrent.TimeUnit.SECONDS);break;
            }catch(java.util.concurrent.ExecutionException failure) {
                if(failure.getCause() instanceof org.apache.kafka.common.errors.GroupIdNotFoundException)break;
                assertTrue(System.nanoTime()<deadline,"独占组协调器未就绪");Thread.sleep(300);
            }
        }
    }
    SeenRecoveryFixture.Runtime runtime(SeenStream stream){return f.runtime(stream,KAFKA.bootstrap(),topics.get(stream),groups.get(stream),REDIS.getHost(),REDIS.getMappedPort(6379));}
    static byte[] wire(SeenStream stream,long user,long item,ActionType action) {
        String source="f142c1c0-4167-4910-a1b7-40de023c8473";
        var event=new LegacyBehaviorAccepted(LegacyBehaviorAccepted.TYPE,1,source,"1",LegacyBehaviorAccepted.identity(source,"1"),
                stream==SeenStream.LIVE?Origin.BEHAVIOR_POST:Origin.LEGACY_ROW,stream==SeenStream.LIVE?PriorPublication.NOT_ATTEMPTED:PriorPublication.UNKNOWN,
                "2026-10-01T01:02:03.123456Z",new Behavior(Long.toString(user),Long.toString(item),action,0,null,null,null,stream==SeenStream.LIVE?1700000000000L:null),
                stream==SeenStream.LIVE?null:"2001-02-03T04:05:06.123456");
        return new LegacyBehaviorCodec().encode(event).getBytes(StandardCharsets.UTF_8);
    }
    void publish(SeenStream stream,int partition,long user,byte[] value) throws Exception {send(KAFKA.bootstrap(),topics.get(stream),partition,Long.toString(user).getBytes(StandardCharsets.UTF_8),value);}
    long committed(SeenStream stream,int partition){return offset(KAFKA.bootstrap(),groups.get(stream),topics.get(stream),partition);}
    void waitOffset(SeenStream stream,int partition,long offset) throws Exception {await(()->committed(stream,partition)==offset,30);}

    @Test void prepublishedVersionedEventsDuplicateOffsetsNoEffectAndStrictPoisonKeepOriginalBytes() throws Exception {
        for(var stream:List.of(SeenStream.LIVE,SeenStream.HISTORY))try(var r=runtime(stream)) {
            long user=2001+stream.ordinal();byte[] original=wire(stream,user,2009,ActionType.CLICK);
            publish(stream,0,user,original);publish(stream,0,user,original);
            publish(stream,0,user,wire(stream,user,2010,ActionType.IMPRESSION));
            publish(stream,0,user+1,original); // 错key必须隔离，不能当无效果。
            publish(stream,0,user,wire(stream==SeenStream.LIVE?SeenStream.HISTORY:SeenStream.LIVE,user,2011,ActionType.LIKE));
            publish(stream,0,user,new String(original,StandardCharsets.UTF_8).replace("\"schemaVersion\":1","\"schemaVersion\":2").getBytes(StandardCharsets.UTF_8));
            publish(stream,0,user,new byte[]{(byte)0xc0,(byte)0xaf});publish(stream,0,user,null);
            send(KAFKA.bootstrap(),topics.get(stream),0,new byte[]{(byte)0xff},original);
            publish(stream,0,user,new byte[32769]);
            r.initialize();r.listener.start();waitOffset(stream,0,10);
            assertEquals(Set.of("2009"),r.redis.opsForSet().members("seen:"+user));
            assertEquals(2,f.repo.statusCount(stream.name(),"APPLIED"));assertEquals(1,f.repo.statusCount(stream.name(),"NO_EFFECT"));assertEquals(7,f.repo.statusCount(stream.name(),"QUARANTINED"));assertEquals(7,r.recovery.quarantine(null,null,100).size());
            assertNotEquals(f.at(stream,0,0).recordId(),f.at(stream,0,1).recordId());assertArrayEquals(original,f.at(stream,0,0).valueBytes());
            var decoded=new LegacyBehaviorCodec().decode(Long.toString(user),new String(f.at(stream,0,0).valueBytes(),StandardCharsets.UTF_8));
            if(stream==SeenStream.HISTORY){assertNull(decoded.behavior().ts());assertEquals("2001-02-03T04:05:06.123456",decoded.sourceTsLocal());}
            r.listener.stop();
            try(var admin=admin(KAFKA.bootstrap())) {admin.deleteRecords(Map.of(new org.apache.kafka.common.TopicPartition(topics.get(stream),0),org.apache.kafka.clients.admin.RecordsToDelete.beforeOffset(10))).all().get();}
            var poison=f.at(stream,0,3);var recheck=UUID.randomUUID();r.recovery.requestRecovery(recheck,poison.recordId(),SeenRecoveryService.Mode.RECHECK,poison.digest(),"owner","原文重判");assertEquals("STILL_INVALID",r.recovery.advance(recheck).result());
            var missing=f.at(stream,0,9);assertFalse(missing.payloadComplete());var expired=UUID.randomUUID();r.recovery.requestRecovery(expired,missing.recordId(),SeenRecoveryService.Mode.RECHECK,missing.digest(),"owner","过期原坐标");assertEquals("SOURCE_UNAVAILABLE",r.recovery.advance(expired).result());
            System.out.println("C2_WIRE stream="+stream+" committed=10 applied=2 no_effect=1 quarantined=7 distinct_coordinates=2");
        }
    }
    @Test void actualSpringGroupsPauseAndStopIndependentlyWithScopedRecoveryReportsAndMetrics() throws Exception {
        var redisFactory=redisFactory(REDIS.getHost(),REDIS.getMappedPort(6379));
        try {
            var redis=new org.springframework.data.redis.core.StringRedisTemplate(redisFactory);
            var sessions=new org.mybatis.spring.SqlSessionTemplate(com.recsys.persistence.spring.SpringSqlSessions.create(f.ds,"classpath*:mapper/recengine/primary/SeenRecoveryMapper.xml"));
            new org.springframework.boot.test.context.runner.ApplicationContextRunner().withUserConfiguration(SeenItemsConsumer.class,SeenConsumerConfiguration.class)
                .withBean("sqlSessionTemplate",org.mybatis.spring.SqlSessionTemplate.class,()->sessions).withBean(org.springframework.data.redis.core.StringRedisTemplate.class,()->redis)
                .withPropertyValues("spring.kafka.bootstrap-servers="+KAFKA.bootstrap(),"recsys.behavior.seen.recovery-enabled=true",
                    "recsys.behavior.seen.live-topic="+topics.get(SeenStream.LIVE),"recsys.behavior.seen.history-topic="+topics.get(SeenStream.HISTORY),
                    "recsys.behavior.seen.live-group="+groups.get(SeenStream.LIVE),"recsys.behavior.seen.history-group="+groups.get(SeenStream.HISTORY))
                .run(c->{
                    assertNull(c.getStartupFailure());var live=c.getBean("liveSeenListenerControl",SeenListenerControl.class);var history=c.getBean("historySeenListenerControl",SeenListenerControl.class);
                    var lr=c.getBean("liveSeenRecoveryService",SeenRecoveryService.class);var hr=c.getBean("historySeenRecoveryService",SeenRecoveryService.class);
                    for(var service:List.of(lr,hr)){var id=UUID.randomUUID();service.initialize(id,SeenOffsetInitializer.Mode.EARLIEST_RETAINED,"owner","预发布起点");assertEquals("SUCCEEDED",service.advance(id).status());}
                    for(var failed:List.of(SeenStream.LIVE,SeenStream.HISTORY)) {
                        var other=failed==SeenStream.LIVE?SeenStream.HISTORY:SeenStream.LIVE;long user=2100+failed.ordinal();
                        redis.opsForValue().set("seen:"+user,"WRONGTYPE");publish(failed,0,user,wire(failed,user,2109,ActionType.PLAY));publish(other,1,2190+other.ordinal(),wire(other,2190+other.ordinal(),2199,ActionType.RATING));
                        if(!live.isRunning()){live.start();history.start();}
                        await(()->f.at(failed,0,0)!=null && f.at(failed,0,0).status().equals("PAUSED"),25);assertEquals(0,committed(failed,0));waitOffset(other,1,1);
                        var record=f.at(failed,0,0);var own=failed==SeenStream.LIVE?lr:hr;var foreign=failed==SeenStream.LIVE?hr:lr;
                        assertEquals(SeenRecord.Code.NOT_ELIGIBLE,assertThrows(SeenRecord.Failure.class,()->foreign.record(record.recordId())).code());
                        var id=UUID.randomUUID();own.requestRecovery(id,record.recordId(),SeenRecoveryService.Mode.RETRY,record.digest(),"owner","恢复本流");
                        assertEquals(SeenRecord.Code.NOT_ELIGIBLE,assertThrows(SeenRecord.Failure.class,()->foreign.advance(id)).code());assertEquals(0,own.report(id).attempts());
                        redis.delete("seen:"+user);assertEquals("SUCCEEDED",own.advance(id).status());(failed==SeenStream.LIVE?live:history).resume();waitOffset(failed,0,1);
                        assertEquals(Set.of("2109"),redis.opsForSet().members("seen:"+user));
                    }
                    // 真实PG拒绝只触发LIVE停止，验证共享错误处理器按group归属，不写LEGACY/HISTORY。
                    f.jdbc.execute("CREATE FUNCTION c2_reject_live() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.stream='LIVE' THEN RAISE EXCEPTION 'isolated rejection'; END IF; RETURN NEW; END $$");
                    f.jdbc.execute("CREATE TRIGGER c2_reject_live BEFORE INSERT ON seen_record FOR EACH ROW EXECUTE FUNCTION c2_reject_live()");
                    publish(SeenStream.LIVE,0,2201,wire(SeenStream.LIVE,2201,2202,ActionType.CLICK));await(()->f.repo.control("LIVE").stopReason()!=null,25);await(()->!live.isRunning(),15);
                    publish(SeenStream.HISTORY,0,2203,wire(SeenStream.HISTORY,2203,2204,ActionType.LIKE));waitOffset(SeenStream.HISTORY,0,2);assertNull(f.repo.control("HISTORY").stopReason());assertNull(f.repo.control("LEGACY"));
                    f.jdbc.execute("DROP TRIGGER c2_reject_live ON seen_record");lr.authorizeResume();live.resume();waitOffset(SeenStream.LIVE,0,2);
                    var metrics=new io.micrometer.core.instrument.simple.SimpleMeterRegistry();try {
                        c.getBean("seenMetrics",io.micrometer.core.instrument.binder.MeterBinder.class).bindTo(metrics);
                        assertEquals(3,metrics.get("recsys.seen.records").tags("stream","LIVE","status","APPLIED").gauge().value());
                        assertEquals(3,metrics.get("recsys.seen.records").tags("stream","HISTORY","status","APPLIED").gauge().value());
                        assertEquals(0,metrics.get("recsys.seen.records").tags("stream","LEGACY","status","APPLIED").gauge().value());
                    }finally{metrics.close();}
                    live.stop();history.stop();System.out.println("C2_INDEPENDENCE live_committed=2 history_committed=2 separate_stop_pause_recovery=PASS");
                });
        }finally{redisFactory.destroy();}
    }
    @Test void actualRedisPauseExhaustsBothStreamsWithoutAckAndAuditedRetryRecovers() throws Exception {
        try(var live=runtime(SeenStream.LIVE);var history=runtime(SeenStream.HISTORY)) {
            var runtimes=Map.of(SeenStream.LIVE,live,SeenStream.HISTORY,history);
            for(var stream:runtimes.keySet()) {
                runtimes.get(stream).initialize();long user=2500+stream.ordinal();
                publish(stream,0,user,wire(stream,user,2509,ActionType.CLICK));
                try(var connection=runtimes.get(stream).redis.getConnectionFactory().getConnection()){assertEquals("PONG",connection.ping());}
            }
            // 仅暂停此测试自建Redis进程；超时后仍必须保持真实broker位点0。
            REDIS.getDockerClient().pauseContainerCmd(REDIS.getContainerId()).exec();
            try {
                live.listener.start();history.listener.start();
                for(var stream:runtimes.keySet()) {
                    await(()->f.at(stream,0,0)!=null && f.at(stream,0,0).status().equals("PAUSED"),30);
                    assertEquals(3,f.at(stream,0,0).attempts());assertEquals(0,committed(stream,0));
                }
            }finally{REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec();}
            for(var stream:runtimes.keySet()) {
                var runtime=runtimes.get(stream);
                await(()->{try(var connection=runtime.redis.getConnectionFactory().getConnection()){return "PONG".equals(connection.ping());}catch(RuntimeException e){return false;}},10);
                var record=f.at(stream,0,0);var id=UUID.randomUUID();runtime.recovery.requestRecovery(id,record.recordId(),SeenRecoveryService.Mode.RETRY,record.digest(),"owner","独占Redis超时恢复");
                assertEquals("SUCCEEDED",runtime.recovery.advance(id).status());runtime.listener.resume();waitOffset(stream,0,1);
                assertEquals(Set.of("2509"),runtime.redis.opsForSet().members("seen:"+(2500+stream.ordinal())));
                System.out.println("C2_REAL_REDIS stream="+stream+" attempts=3 committed_before_retry=0 committed_after_retry=1");
            }
        }
    }
    @Test void individualConsumeFlagStartsOnlyRegisteredStreamAndConsumesPrepublishedEvent() throws Exception {
        for(var stream:List.of(SeenStream.LIVE,SeenStream.HISTORY)) {
            long user=2600+stream.ordinal();publish(stream,0,user,wire(stream,user,2609,ActionType.CLICK));
            try(var initialized=runtime(stream)) {initialized.initialize();}
            var redisFactory=redisFactory(REDIS.getHost(),REDIS.getMappedPort(6379));
            try {
                var redis=new org.springframework.data.redis.core.StringRedisTemplate(redisFactory);
                var sessions=new org.mybatis.spring.SqlSessionTemplate(com.recsys.persistence.spring.SpringSqlSessions.create(f.ds,"classpath*:mapper/recengine/primary/SeenRecoveryMapper.xml"));
                String name=stream.name().toLowerCase(Locale.ROOT);
                new org.springframework.boot.test.context.runner.ApplicationContextRunner().withUserConfiguration(SeenItemsConsumer.class,SeenConsumerConfiguration.class)
                    .withBean("sqlSessionTemplate",org.mybatis.spring.SqlSessionTemplate.class,()->sessions).withBean(org.springframework.data.redis.core.StringRedisTemplate.class,()->redis)
                    .withPropertyValues("spring.kafka.bootstrap-servers="+KAFKA.bootstrap(),"recsys.behavior.seen."+name+"-consume=true",
                        "recsys.behavior.seen.live-topic="+topics.get(SeenStream.LIVE),"recsys.behavior.seen.history-topic="+topics.get(SeenStream.HISTORY),
                        "recsys.behavior.seen.live-group="+groups.get(SeenStream.LIVE),"recsys.behavior.seen.history-group="+groups.get(SeenStream.HISTORY))
                    .run(c->{
                        assertNull(c.getStartupFailure());assertEquals(1,c.getBeansOfType(SeenListenerControl.class).size());
                        var control=c.getBean(name+"SeenListenerControl",SeenListenerControl.class);assertTrue(control.isRunning());assertEquals(stream,control.stream());
                        waitOffset(stream,0,1);assertEquals(Set.of("2609"),redis.opsForSet().members("seen:"+user));
                        assertEquals(SeenRecord.Code.NOT_ELIGIBLE,assertThrows(SeenRecord.Failure.class,()->c.getBean(name+"SeenRecoveryService",SeenRecoveryService.class).initialize(UUID.randomUUID(),SeenOffsetInitializer.Mode.EARLIEST_RETAINED,"owner","未启恢复")).code());
                        control.stop();System.out.println("C2_AUTO_START stream="+stream+" controls=1 committed=1");
                    });
            }finally{redisFactory.destroy();}
        }
    }
    @Test void versionedRebalanceAndRedisLossRestoreRetainBothFlowBindings() throws Exception {
        for(var stream:List.of(SeenStream.LIVE,SeenStream.HISTORY))try(var one=runtime(stream);var two=runtime(stream)) {
            one.initialize();one.listener.start();two.listener.start();await(()->one.listener.assigned()!=null && two.listener.assigned()!=null && one.listener.assigned().size()==1 && two.listener.assigned().size()==1,30);
            long user=2300+stream.ordinal();publish(stream,0,user,wire(stream,user,2309,ActionType.CLICK));publish(stream,1,user,wire(stream,user,2310,ActionType.LIKE));waitOffset(stream,0,1);waitOffset(stream,1,1);
            one.listener.stop();await(()->two.listener.assigned()!=null && two.listener.assigned().size()==2,25);two.listener.stop();
            one.redis.delete("seen:"+user);two.listener.start();Thread.sleep(800);assertFalse(one.redis.hasKey("seen:"+user));assertEquals(1,committed(stream,0));
            var record=f.at(stream,0,0);var id=UUID.randomUUID();one.recovery.requestRecovery(id,record.recordId(),SeenRecoveryService.Mode.RESTORE,record.digest(),"owner","只恢复已知记录");assertEquals("SUCCEEDED",one.recovery.advance(id).status());assertEquals(Set.of("2309"),one.redis.opsForSet().members("seen:"+user));assertEquals(1,committed(stream,0));
        }
    }
    @Test void newStreamsIndependentJvmSaddAndAppliedBeforeAckWindowsUseOriginalCoordinates() throws Exception {
        for(var stream:List.of(SeenStream.LIVE,SeenStream.HISTORY))for(String phase:List.of("REDIS_APPLIED","PG_APPLIED")) {
            if(!(stream==SeenStream.LIVE && phase.equals("REDIS_APPLIED")))setup();
            long user=2400+stream.ordinal();publish(stream,0,user,wire(stream,user,2409,ActionType.CLICK));
            try(var r=runtime(stream)) {
                r.initialize();crash(stream,phase);var record=f.at(stream,0,0);assertNotNull(record);assertEquals(0,committed(stream,0));assertEquals(Set.of("2409"),r.redis.opsForSet().members("seen:"+user));
                r.listener.start();waitOffsetWithLease(stream);assertEquals(record.recordId(),f.at(stream,0,0).recordId());assertArrayEquals(record.valueBytes(),f.at(stream,0,0).valueBytes());assertEquals(phase.equals("PG_APPLIED")?1:2,f.at(stream,0,0).attempts());
                System.out.println("C2_PROCESS stream="+stream+" phase="+phase+" committed=1 member=1");
            }
        }
    }
    void waitOffsetWithLease(SeenStream stream) throws Exception {await(()->committed(stream,0)==1,50);}
    void crash(SeenStream stream,String phase) throws Exception {
        var log=java.nio.file.Path.of("target","seen-process","C2-"+stream+"-"+phase+"-"+UUID.randomUUID()+".log");java.nio.file.Files.createDirectories(log.getParent());
        var builder=new ProcessBuilder(java.nio.file.Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),SeenRecoveryProcessMain.class.getName(),phase);
        builder.environment().putAll(Map.of("SEEN_TEST_JDBC",f.ds.getUrl(),"SEEN_TEST_USER",f.ds.getUsername(),"SEEN_TEST_PASSWORD",f.ds.getPassword(),"SEEN_TEST_BOOTSTRAP",KAFKA.bootstrap(),"SEEN_TEST_TOPIC",topics.get(stream),"SEEN_TEST_GROUP",groups.get(stream),"SEEN_TEST_STREAM",stream.name(),"SEEN_TEST_REDIS_HOST",REDIS.getHost(),"SEEN_TEST_REDIS_PORT",REDIS.getMappedPort(6379).toString()));
        var process=builder.redirectErrorStream(true).redirectOutput(log.toFile()).start();try {assertTrue(process.waitFor(60,java.util.concurrent.TimeUnit.SECONDS));assertEquals(73,process.exitValue());}finally{if(process.isAlive()){process.destroyForcibly();process.waitFor(5,java.util.concurrent.TimeUnit.SECONDS);}}
    }
}
