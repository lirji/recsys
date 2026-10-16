package com.recsys.recengine.filter.seen;

import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.recsys.recengine.filter.seen.SeenRecoveryFixture.*;
import static org.junit.jupiter.api.Assertions.*;

/** 真实旧产品listener+PG/Kafka/Redis；所有位点断言来自Admin而不是ACK计数。 */
@Tag("behavior-reliability") @Testcontainers
class SeenRecoveryKafkaRedisTest {
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>("postgres:16-alpine");
    @Container static final KafkaFixture KAFKA=new KafkaFixture();
    @Container static final GenericContainer<?> REDIS=new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    SeenRecoveryFixture f;String topic,group;
    @AfterAll static void closeObserverClient(){closeObserver();}
    @BeforeEach void setup() throws Exception {f=new SeenRecoveryFixture(PG);topic=topic();group=group();topic(KAFKA.bootstrap(),topic,2);awaitCoordinator();}
    SeenRecoveryFixture.Runtime runtime() {return f.runtime(KAFKA.bootstrap(),topic,group,REDIS.getHost(),REDIS.getMappedPort(6379));}
    @Test void legacyAllPositiveInvocationsLongIdsZeroAddAndNoEffectUseActualListener() throws Exception {
        try(var r=runtime()) {
            int count=0;
            for(String action:List.of("CLICK","LIKE","PLAY","RATING"))for(String exposure:List.of("",",\"exposureId\":\"old\"")) {
                byte[] value=("{\"userId\":9223372036854775807,\"itemId\":9223372036854775806,\"action\":\""+action+"\",\"value\":0,\"scene\":null,\"bucket\":null,\"ts\":-1"+exposure+"}").getBytes(StandardCharsets.UTF_8);
                send(KAFKA.bootstrap(),topic,0,null,value);send(KAFKA.bootstrap(),topic,0,"different-old-key".getBytes(StandardCharsets.UTF_8),value);count+=2;
            }
            send(KAFKA.bootstrap(),topic,0,null,"{\"userId\":1,\"itemId\":2,\"action\":\"IMPRESSION\"}".getBytes(StandardCharsets.UTF_8));
            r.initialize();r.listener.start();final long expected=count+1;
            await(()->Objects.equals(expected,offset(KAFKA.bootstrap(),group,topic,0)),30);
            assertEquals(Set.of("9223372036854775806"),r.redis.opsForSet().members("seen:9223372036854775807"));
            assertEquals(16,f.jdbc.queryForObject("SELECT count(*) FROM seen_record WHERE status='APPLIED' AND attempts=1",Integer.class));
            assertEquals("NO_EFFECT",f.at(0,16).status());assertEquals(0,f.at(0,16).attempts());assertFalse(r.redis.hasKey("seen:1"));
            System.out.println("C01 offset="+expected+" applied=16 redis_members=1 no_effect=1");
        }
    }
    @Test void wrongTypeExhaustsPersistentBudgetThenAuditedRetryResumesActualOffset() throws Exception {
        send(KAFKA.bootstrap(),topic,0,null,positive(201,301));send(KAFKA.bootstrap(),topic,0,null,positive(201,302));
        try(var r=runtime()) {
            r.redis.opsForValue().set("seen:201","wrong-type");r.initialize();r.listener.start();
            await(()->f.at(0,0)!=null && "PAUSED".equals(f.at(0,0).status()),25);
            assertEquals(0L,offset(KAFKA.bootstrap(),group,topic,0));assertNull(f.at(0,1));assertEquals(3,f.at(0,0).attempts());
            Thread.sleep(1500);assertEquals(3,f.at(0,0).attempts());r.listener.stop();
            try(var next=runtime()) {next.listener.start();assertTrue(next.listener.isPaused());assertEquals(3,f.at(0,0).attempts());}
            r.redis.delete("seen:201");var id=UUID.randomUUID();var record=f.at(0,0);
            r.recovery.requestRecovery(id,record.recordId(),SeenRecoveryService.Mode.RETRY,record.digest(),"owner","修复WRONGTYPE");
            assertEquals(id,r.recovery.requestRecovery(id,record.recordId(),SeenRecoveryService.Mode.RETRY,record.digest(),"owner","修复WRONGTYPE").commandId());
            assertThrows(SeenRecord.Failure.class,()->r.recovery.requestRecovery(id,record.recordId(),SeenRecoveryService.Mode.RETRY,record.digest(),"owner","修改原因"));
            assertEquals("SUCCEEDED",r.recovery.advance(id).status());assertEquals(1,r.recovery.advance(id).attempts());
            r.listener.resume();await(()->Objects.equals(2L,offset(KAFKA.bootstrap(),group,topic,0)),25);
            assertEquals(Set.of("301","302"),r.redis.opsForSet().members("seen:201"));
        }
    }
    @Test void poisonBytesPersistBeforeAckAndRecheckNeverSubstitutesAdjacentRecord() throws Exception {
        var values=new ArrayList<byte[]>();values.add(null);values.add(new byte[]{(byte)0xc0,(byte)0xaf});values.add("private-invalid".getBytes(StandardCharsets.UTF_8));
        values.add("{\"userId\":1,\"itemId\":2,\"action\":\"BAD\"}".getBytes(StandardCharsets.UTF_8));
        values.add("{\"userId\":1,\"itemId\":2,\"action\":\"CLICK\",\"version\":2}".getBytes(StandardCharsets.UTF_8));
        values.add(new byte[32769]);
        for(byte[] value:values)send(KAFKA.bootstrap(),topic,0,null,value);
        send(KAFKA.bootstrap(),topic,0,null,positive(401,402));
        try(var r=runtime()) {
            r.initialize();r.listener.start();await(()->Objects.equals(7L,offset(KAFKA.bootstrap(),group,topic,0)),25);r.listener.stop();
            assertEquals(6,f.jdbc.queryForObject("SELECT count(*) FROM seen_record WHERE status='QUARANTINED'",Integer.class));
            var invalid=f.at(0,2);assertArrayEquals(values.get(2),invalid.valueBytes());assertFalse(f.at(0,5).payloadComplete());
            try(var a=admin(KAFKA.bootstrap())) {a.deleteRecords(Map.of(new org.apache.kafka.common.TopicPartition(topic,0),org.apache.kafka.clients.admin.RecordsToDelete.beforeOffset(6))).all().get(10,java.util.concurrent.TimeUnit.SECONDS);}
            var id=UUID.randomUUID();r.recovery.requestRecovery(id,invalid.recordId(),SeenRecoveryService.Mode.RECHECK,invalid.digest(),"owner","原文仍在PG");
            assertEquals("STILL_INVALID",r.recovery.advance(id).result());assertArrayEquals(values.get(2),f.repo.rawRecord(invalid.recordId()).valueBytes());
            var incomplete=f.at(0,5);var missing=UUID.randomUUID();r.recovery.requestRecovery(missing,incomplete.recordId(),SeenRecoveryService.Mode.RECHECK,incomplete.digest(),"owner","已过期缺完整原文");
            assertEquals("SOURCE_UNAVAILABLE",r.recovery.advance(missing).result());assertEquals("QUARANTINED",f.at(0,5).status());
            assertEquals(Set.of("402"),r.redis.opsForSet().members("seen:401"));assertEquals(7L,offset(KAFKA.bootstrap(),group,topic,0));
        }
    }
    @Test void acknowledgedRedisLossDoesNotHealOnRestartButExplicitRestoreReallyAdds() throws Exception {
        send(KAFKA.bootstrap(),topic,0,null,positive(501,502));
        try(var r=runtime()) {
            r.initialize();r.listener.start();await(()->Objects.equals(1L,offset(KAFKA.bootstrap(),group,topic,0)),25);r.listener.stop();
            r.redis.delete("seen:501");r.listener.start();Thread.sleep(1200);assertFalse(r.redis.hasKey("seen:501"));assertEquals(1L,offset(KAFKA.bootstrap(),group,topic,0));
            var record=f.at(0,0);var id=UUID.randomUUID();r.recovery.requestRecovery(id,record.recordId(),SeenRecoveryService.Mode.RESTORE,record.digest(),"owner","已知集合丢失");
            assertEquals("APPLIED",r.recovery.advance(id).result());assertEquals(Set.of("502"),r.redis.opsForSet().members("seen:501"));assertEquals(1L,offset(KAFKA.bootstrap(),group,topic,0));
        }
    }
    @Test void initializationDifferentCommandsExclusiveAndEnrollmentPreservesOldOffsets() throws Exception {
        send(KAFKA.bootstrap(),topic,0,null,positive(601,602));
        try(var r=runtime()) {
            var first=UUID.randomUUID();var second=UUID.randomUUID();
            try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
                var gate=new java.util.concurrent.CyclicBarrier(2);
                var results=new ArrayList<java.util.concurrent.Future<String>>();
                for(var id:List.of(first,second))results.add(pool.submit(()->{gate.await();try{r.recovery.initialize(id,SeenOffsetInitializer.Mode.EARLIEST_RETAINED,"owner","并发初始化");return id.toString();}catch(SeenRecord.Failure failure){return failure.code().name();}}));
                var a=results.get(0).get();var b=results.get(1).get();assertTrue(a.equals("COMMAND_CONFLICT")^b.equals("COMMAND_CONFLICT"),"并发结果: "+a+" / "+b);
                first=UUID.fromString(a.equals("COMMAND_CONFLICT")?b:a);
            }
            assertNull(offset(KAFKA.bootstrap(),group,topic,0));assertEquals("SUCCEEDED",r.recovery.advance(first).status());
            r.listener.start();await(()->Objects.equals(1L,offset(KAFKA.bootstrap(),group,topic,0)),25);r.listener.stop();
            try(var a=admin(KAFKA.bootstrap())) {a.createPartitions(Map.of(topic,org.apache.kafka.clients.admin.NewPartitions.increaseTo(3))).all().get(10,java.util.concurrent.TimeUnit.SECONDS);}
            assertThrows(SeenRecord.Failure.class,r.offsets::validate);
            send(KAFKA.bootstrap(),topic,2,null,positive(601,603));var enroll=UUID.randomUUID();
            r.recovery.initialize(enroll,SeenOffsetInitializer.Mode.ENROLL_NEW_PARTITIONS,"owner","只准入新增分区");assertEquals("SUCCEEDED",r.recovery.advance(enroll).status());
            assertEquals(1L,offset(KAFKA.bootstrap(),group,topic,0));assertEquals(0L,offset(KAFKA.bootstrap(),group,topic,1));assertEquals(0L,offset(KAFKA.bootstrap(),group,topic,2));
            r.listener.resume();await(()->Objects.equals(1L,offset(KAFKA.bootstrap(),group,topic,2)),25);assertEquals(Set.of("602","603"),r.redis.opsForSet().members("seen:601"));
        }
    }
    @Test void adoptNonzeroAndActualDeleteRecordsGapAndTopicRecreationAreRejected() throws Exception {
        send(KAFKA.bootstrap(),topic,0,null,positive(701,702));send(KAFKA.bootstrap(),topic,0,null,positive(701,703));
        try(var a=admin(KAFKA.bootstrap())) {a.alterConsumerGroupOffsets(group,Map.of(new org.apache.kafka.common.TopicPartition(topic,0),new org.apache.kafka.clients.consumer.OffsetAndMetadata(1),new org.apache.kafka.common.TopicPartition(topic,1),new org.apache.kafka.clients.consumer.OffsetAndMetadata(0))).all().get(10,java.util.concurrent.TimeUnit.SECONDS);}
        try(var r=runtime()) {
            var id=UUID.randomUUID();r.recovery.initialize(id,SeenOffsetInitializer.Mode.ADOPT_COMMITTED,"owner","只读采用");assertEquals("SUCCEEDED",r.recovery.advance(id).status());assertEquals(1L,offset(KAFKA.bootstrap(),group,topic,0));
            var tp=new org.apache.kafka.common.TopicPartition(topic,0);
            try(var a=admin(KAFKA.bootstrap())) {
                a.deleteConsumerGroupOffsets(group,Set.of(tp)).all().get(10,java.util.concurrent.TimeUnit.SECONDS);
                assertEquals(SeenRecord.Code.OFFSET_GAP,assertThrows(SeenRecord.Failure.class,r.offsets::validate).code());
                a.alterConsumerGroupOffsets(group,Map.of(tp,new org.apache.kafka.clients.consumer.OffsetAndMetadata(3))).all().get(10,java.util.concurrent.TimeUnit.SECONDS);
                assertEquals(SeenRecord.Code.OFFSET_GAP,assertThrows(SeenRecord.Failure.class,r.offsets::validate).code());
                a.alterConsumerGroupOffsets(group,Map.of(tp,new org.apache.kafka.clients.consumer.OffsetAndMetadata(1))).all().get(10,java.util.concurrent.TimeUnit.SECONDS);
                r.offsets.validate();
            }
            try(var a=admin(KAFKA.bootstrap())) {a.deleteRecords(Map.of(new org.apache.kafka.common.TopicPartition(topic,0),org.apache.kafka.clients.admin.RecordsToDelete.beforeOffset(2))).all().get(10,java.util.concurrent.TimeUnit.SECONDS);}
            assertEquals(SeenRecord.Code.OFFSET_GAP,assertThrows(SeenRecord.Failure.class,r.offsets::validate).code());assertEquals(1L,offset(KAFKA.bootstrap(),group,topic,0));
            try(var a=admin(KAFKA.bootstrap())) {a.deleteTopics(List.of(topic)).all().get(10,java.util.concurrent.TimeUnit.SECONDS);}
            topic(KAFKA.bootstrap(),topic,2);
            assertEquals(SeenRecord.Code.IDENTITY_CHANGED,assertThrows(SeenRecord.Failure.class,r.offsets::validate).code());
        }
    }
    @Test void twoRealConsumersRebalanceAndActiveGroupCannotInitialize() throws Exception {
        try(var one=runtime();var two=runtime()) {
            one.initialize();one.listener.start();two.listener.start();
            await(()->one.listener.assigned()!=null && two.listener.assigned()!=null && one.listener.assigned().size()==1 && two.listener.assigned().size()==1,30);
            assertEquals(SeenRecord.Code.ACTIVE_LEASE,assertThrows(SeenRecord.Failure.class,()->one.recovery.initialize(UUID.randomUUID(),SeenOffsetInitializer.Mode.ENROLL_NEW_PARTITIONS,"owner","活跃组拒绝")).code());
            send(KAFKA.bootstrap(),topic,0,null,positive(801,802));send(KAFKA.bootstrap(),topic,1,null,positive(801,803));
            await(()->Objects.equals(1L,offset(KAFKA.bootstrap(),group,topic,0)) && Objects.equals(1L,offset(KAFKA.bootstrap(),group,topic,1)),25);
            one.listener.stop();await(()->two.listener.assigned()!=null && two.listener.assigned().size()==2,25);
            send(KAFKA.bootstrap(),topic,0,null,positive(801,804));await(()->Objects.equals(2L,offset(KAFKA.bootstrap(),group,topic,0)),25);
            assertEquals(Set.of("802","803","804"),two.redis.opsForSet().members("seen:801"));
        }
    }
    @Test void independentJvmClaimSaddAndPgBeforeAckWindowsRecoverOriginalCoordinates() throws Exception {
        for(String phase:List.of("CLAIMED","REDIS_APPLIED","PG_APPLIED")) {
            if(!phase.equals("CLAIMED"))setup();
            send(KAFKA.bootstrap(),topic,0,null,positive(901,902));
            try(var r=runtime()) {
                r.initialize();crash(phase,null);
                assertEquals(0L,offset(KAFKA.bootstrap(),group,topic,0));var original=f.at(0,0);assertNotNull(original);
                assertEquals(1,original.attempts());assertEquals(phase.equals("PG_APPLIED")?"APPLIED":"APPLYING",original.status());
                if(!phase.equals("CLAIMED"))assertEquals(Set.of("902"),r.redis.opsForSet().members("seen:901"));
                r.listener.start();await(()->Objects.equals(1L,offset(KAFKA.bootstrap(),group,topic,0)),50);
                assertEquals(original.recordId(),f.at(0,0).recordId());assertEquals(original.digest(),f.at(0,0).digest());
                assertEquals(Set.of("902"),r.redis.opsForSet().members("seen:901"));assertEquals(phase.equals("PG_APPLIED")?1:2,f.at(0,0).attempts());
                System.out.println("C03_C04_PROCESS phase="+phase+" committed=1 members=1 attempts="+f.at(0,0).attempts());
            }
        }
    }
    @Test void initializationAndRecoverySideEffectsBeforeResultSurviveIndependentExit() throws Exception {
        send(KAFKA.bootstrap(),topic,0,null,positive(1001,1002));
        try(var r=runtime()) {
            var init=UUID.randomUUID();r.recovery.initialize(init,SeenOffsetInitializer.Mode.EARLIEST_RETAINED,"owner","初始化退出窗口");
            String frozen=f.repo.rawCommand(init).requestJson();crash("INIT",init);
            assertEquals(0L,offset(KAFKA.bootstrap(),group,topic,0));assertEquals(init,f.repo.control("LEGACY").initializingCommandId());
            assertEquals(SeenRecord.Code.COMMAND_CONFLICT,assertThrows(SeenRecord.Failure.class,()->r.recovery.initialize(UUID.randomUUID(),SeenOffsetInitializer.Mode.EARLIEST_RETAINED,"owner","不得接管旧命令")).code());
            await(()->!f.repo.rawCommand(init).leaseActive(),35);assertEquals("SUCCEEDED",r.recovery.advance(init).status());assertEquals(frozen,f.repo.rawCommand(init).requestJson());
            r.listener.start();await(()->Objects.equals(1L,offset(KAFKA.bootstrap(),group,topic,0)),25);r.listener.stop();
            r.redis.delete("seen:1001");var record=f.at(0,0);var restore=UUID.randomUUID();
            r.recovery.requestRecovery(restore,record.recordId(),SeenRecoveryService.Mode.RESTORE,record.digest(),"owner","重放退出窗口");crash("RECOVERY",restore);
            assertEquals(Set.of("1002"),r.redis.opsForSet().members("seen:1001"));assertEquals("RUNNING",r.recovery.report(restore).status());
            await(()->!f.repo.rawCommand(restore).leaseActive(),35);assertEquals("SUCCEEDED",r.recovery.advance(restore).status());
            assertEquals(2,r.recovery.report(restore).attempts());assertNull(f.at(0,0).activeCommandId());assertEquals(1L,offset(KAFKA.bootstrap(),group,topic,0));
        }
    }
    @Test void thirdClaimExitPausesAcrossProcessAndFinalCommandExitReleasesOccupation() throws Exception {
        send(KAFKA.bootstrap(),topic,0,null,positive(1101,1102));
        try(var r=runtime()) {
            r.initialize();r.redis.opsForValue().set("seen:1101","wrong-type");
            for(int i=0;i<2;i++) {assertEquals(SeenProjectionService.Outcome.WAIT,r.projection.consume("LEGACY",0,0,null,positive(1101,1102)));await(()->f.at(0,0).due(),5);}
            var stale=f.at(0,0);crash("CLAIMED",null);assertEquals(3,f.at(0,0).attempts());assertFalse(f.repo.applied(stale));await(()->!f.at(0,0).leaseActive(),35);
            assertTrue(f.repo.blocked("LEGACY"));assertEquals("PAUSED",f.at(0,0).status());assertEquals(0L,offset(KAFKA.bootstrap(),group,topic,0));
            var record=f.at(0,0);var id=UUID.randomUUID();r.recovery.requestRecovery(id,record.recordId(),SeenRecoveryService.Mode.RETRY,record.digest(),"owner","恢复命令预算");
            for(int i=0;i<2;i++){assertEquals("READY",r.recovery.advance(id).status());await(()->f.repo.rawCommand(id).due(),5);}
            r.redis.delete("seen:1101");crash("RECOVERY",id);assertEquals(3,r.recovery.report(id).attempts());await(()->!f.repo.rawCommand(id).leaseActive(),35);
            assertEquals("FAILED",r.recovery.advance(id).status());assertNull(f.at(0,0).activeCommandId());assertEquals("PAUSED",f.at(0,0).status());
            assertEquals(3,r.recovery.advance(id).attempts());var next=UUID.randomUUID();r.recovery.requestRecovery(next,record.recordId(),SeenRecoveryService.Mode.RETRY,record.digest(),"owner","显式新一轮");
            assertEquals("SUCCEEDED",r.recovery.advance(next).status());r.listener.resume();await(()->Objects.equals(1L,offset(KAFKA.bootstrap(),group,topic,0)),25);
            assertEquals(Set.of("1102"),r.redis.opsForSet().members("seen:1101"));
        }
    }
    @Test void twoPausedPartitionsRequireBothRecoveriesBeforeResume() throws Exception {
        send(KAFKA.bootstrap(),topic,0,null,positive(1201,1202));send(KAFKA.bootstrap(),topic,1,null,positive(1203,1204));
        try(var r=runtime()) {
            r.initialize();r.redis.opsForValue().set("seen:1201","bad");r.redis.opsForValue().set("seen:1203","bad");
            for(int p=0;p<2;p++)for(int i=0;i<2;i++) {long user=p==0?1201:1203;int partition=p;r.projection.consume("LEGACY",p,0,null,positive(user,user+1));await(()->f.at(partition,0).due(),5);}
            var gate=new java.util.concurrent.CyclicBarrier(2);
            var projection=new SeenProjectionService(f.repo,f.decoder,r.redis,(phase,record)->{if(phase==SeenProjectionService.Phase.CLAIMED && record.attempts()==3)try{gate.await(5,java.util.concurrent.TimeUnit.SECONDS);}catch(Exception e){throw new AssertionError(e);}});
            try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
                var a=pool.submit(()->projection.consume("LEGACY",0,0,null,positive(1201,1202)));var b=pool.submit(()->projection.consume("LEGACY",1,0,null,positive(1203,1204)));
                assertEquals(SeenProjectionService.Outcome.PAUSED,a.get());assertEquals(SeenProjectionService.Outcome.PAUSED,b.get());
            }
            r.listener.start();assertTrue(r.listener.isPaused());r.redis.delete(List.of("seen:1201","seen:1203"));
            for(int p=0;p<2;p++) {
                var record=f.at(p,0);var id=UUID.randomUUID();r.recovery.requestRecovery(id,record.recordId(),SeenRecoveryService.Mode.RETRY,record.digest(),"owner","分区恢复");assertEquals("SUCCEEDED",r.recovery.advance(id).status());
                if(p==0){assertThrows(SeenRecord.Failure.class,r.listener::resume);assertTrue(r.listener.isPaused());assertEquals(0L,offset(KAFKA.bootstrap(),group,topic,0));}
            }
            r.listener.resume();await(()->Objects.equals(1L,offset(KAFKA.bootstrap(),group,topic,0))&&Objects.equals(1L,offset(KAFKA.bootstrap(),group,topic,1)),25);
        }
    }
    private void crash(String phase,UUID command) throws Exception {
        var log=java.nio.file.Path.of("target","seen-process",phase+"-"+UUID.randomUUID()+".log");java.nio.file.Files.createDirectories(log.getParent());
        var args=new ArrayList<>(List.of(java.nio.file.Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),SeenRecoveryProcessMain.class.getName(),phase));
        if(command!=null)args.add(command.toString());var builder=new ProcessBuilder(args);
        builder.environment().putAll(Map.of("SEEN_TEST_JDBC",f.ds.getUrl(),"SEEN_TEST_USER",f.ds.getUsername(),"SEEN_TEST_PASSWORD",f.ds.getPassword(),"SEEN_TEST_BOOTSTRAP",KAFKA.bootstrap(),"SEEN_TEST_TOPIC",topic,"SEEN_TEST_GROUP",group,"SEEN_TEST_REDIS_HOST",REDIS.getHost(),"SEEN_TEST_REDIS_PORT",REDIS.getMappedPort(6379).toString()));
        var process=builder.redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {assertTrue(process.waitFor(60,java.util.concurrent.TimeUnit.SECONDS),"独立JVM未退出 "+log);assertEquals(73,process.exitValue(),"断点未命中 "+log);}
        finally {if(process.isAlive()){process.destroyForcibly();process.waitFor(5,java.util.concurrent.TimeUnit.SECONDS);}}
    }
    private void awaitCoordinator() throws Exception {
        // Kafka进程started不等于首次组协调器已可用；只读等待测试组，不写offset或放宽产品超时。
        long deadline=System.nanoTime()+java.time.Duration.ofSeconds(20).toNanos();
        while(true) {
            try(var a=admin(KAFKA.bootstrap())) {a.describeConsumerGroups(List.of(group)).all().get(5,java.util.concurrent.TimeUnit.SECONDS);return;}
            catch(java.util.concurrent.ExecutionException failure) {
                if(failure.getCause() instanceof org.apache.kafka.common.errors.GroupIdNotFoundException)return;
                System.out.println("TEST_GROUP_READINESS "+failure.getCause().getClass().getSimpleName()+" "+failure.getCause().getMessage());
                assertTrue(System.nanoTime()<deadline,"测试Kafka协调器未就绪");Thread.sleep(300);
            }
        }
    }
    @Test void actualRedisPauseAndStopNeverCommitFailedPositive() throws Exception {
        for(String outage:List.of("pause")) {
            if(outage.equals("stop"))setup();
            send(KAFKA.bootstrap(),topic,0,null,positive(1301,1302));
            try(var r=runtime()) {
                r.initialize();r.redis.opsForSet().add("seen:1301","probe");
                if(outage.equals("pause"))REDIS.getDockerClient().pauseContainerCmd(REDIS.getContainerId()).exec();
                else REDIS.getDockerClient().stopContainerCmd(REDIS.getContainerId()).withTimeout(0).exec();
                try {r.listener.start();await(()->f.at(0,0)!=null && f.at(0,0).status().equals("PAUSED"),30);assertEquals(0L,offset(KAFKA.bootstrap(),group,topic,0));assertEquals(3,f.at(0,0).attempts());}
                finally {if(outage.equals("pause"))REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec();else REDIS.getDockerClient().startContainerCmd(REDIS.getContainerId()).exec();}
                await(()->{try(var connection=r.redis.getConnectionFactory().getConnection()){return "PONG".equals(connection.ping());}catch(RuntimeException e){return false;}},10);
                var record=f.at(0,0);var id=UUID.randomUUID();r.recovery.requestRecovery(id,record.recordId(),SeenRecoveryService.Mode.RETRY,record.digest(),"owner","独占Redis恢复");
                assertEquals("SUCCEEDED",r.recovery.advance(id).status());r.listener.resume();await(()->Objects.equals(1L,offset(KAFKA.bootstrap(),group,topic,0)),25);
                assertTrue(r.redis.opsForSet().isMember("seen:1301","1302"));System.out.println("C02_REAL_REDIS outage="+outage+" attempts=3 committed_after_restore=1");
            }
        }
    }
    @Test void realPgQuarantineCommitFailureStopsWithoutAcknowledgingOrLeakingPayload() throws Exception {
        send(KAFKA.bootstrap(),topic,0,null,"secret-marker-invalid-json".getBytes(StandardCharsets.UTF_8));
        try(var r=runtime()) {
            r.initialize();f.jdbc.execute("CREATE FUNCTION test_seen_reject() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'isolated pg rejection'; END $$");
            f.jdbc.execute("CREATE TRIGGER test_seen_reject BEFORE INSERT ON seen_record FOR EACH ROW EXECUTE FUNCTION test_seen_reject()");
            r.listener.start();await(()->f.repo.control("LEGACY").stopReason()!=null,25);await(()->!r.listener.isRunning(),20);
            assertEquals(0L,offset(KAFKA.bootstrap(),group,topic,0));assertNull(f.at(0,0));
            assertThrows(SeenRecord.Failure.class,r.listener::start);
            f.jdbc.execute("DROP TRIGGER test_seen_reject ON seen_record"); // 仅清除本测试临时故障注入，不删任何业务证据。
            r.recovery.authorizeResume();r.listener.resume();await(()->Objects.equals(1L,offset(KAFKA.bootstrap(),group,topic,0)),25);
            assertEquals("QUARANTINED",f.at(0,0).status());
        }
    }
    @Test void actualCommittedOffsetUnknownAndObservableCommitFailureUseTerminalReplay() throws Exception {
        send(KAFKA.bootstrap(),topic,0,null,positive(1401,1402));
        try(var r=runtime()) {
            r.initialize();
            // 真实consumer完成产品SADD/PG；代理在真实commit后丢响应，仅证明提交响应窗口，不冒称网络断连。
            var properties=SeenConsumerConfiguration.consumerProperties(kafka(KAFKA.bootstrap()));properties.put("group.id",group);
            try(var actual=new org.apache.kafka.clients.consumer.KafkaConsumer<byte[],byte[]>(properties)) {
                var tp=new org.apache.kafka.common.TopicPartition(topic,0);actual.assign(List.of(tp));actual.seek(tp,0);
                var records=actual.poll(java.time.Duration.ofSeconds(5));assertEquals(1,records.count());
                var beans=new org.springframework.beans.factory.support.DefaultListableBeanFactory();beans.registerSingleton("projection",r.projection);
                var listener=new com.recsys.recengine.filter.SeenItemsConsumer(beans.getBeanProvider(SeenProjectionService.class),topic);
                var ack=new org.springframework.kafka.support.Acknowledgment(){public void acknowledge(){actual.commitSync(Map.of(tp,new org.apache.kafka.clients.consumer.OffsetAndMetadata(1)));throw new org.apache.kafka.common.errors.TimeoutException("fixed-test-response-loss");}};
                assertThrows(SeenRecord.Failure.class,()->listener.onEvent(records.iterator().next(),ack,actual,org.mockito.Mockito.mock(org.springframework.kafka.listener.MessageListenerContainer.class)));
                assertEquals("APPLIED",f.at(0,0).status());assertEquals(1L,offset(KAFKA.bootstrap(),group,topic,0));
                assertEquals(Set.of("1402"),r.redis.opsForSet().members("seen:1401"));
            }
            r.listener.start();Thread.sleep(1000);assertEquals(1,f.at(0,0).attempts());
        }
    }
    @Test @SuppressWarnings("unchecked") void containerCommitFailuresAndFrameworkInternalWindowsOnlyCoverTerminalRecords() throws Exception {
        for(String mode:List.of("UNKNOWN","REBALANCE","WAKEUP")) {
            if(!mode.equals("UNKNOWN"))setup();
            send(KAFKA.bootstrap(),topic,0,null,positive(1501,1502));send(KAFKA.bootstrap(),topic,0,null,positive(1501,1503));
            try(var r=runtime()) {
                r.initialize();var props=SeenConsumerConfiguration.consumerProperties(kafka(KAFKA.bootstrap()));
                var factory=SeenConsumerConfiguration.factory(props,r.errors);var once=new java.util.concurrent.atomic.AtomicBoolean();
                ((org.springframework.kafka.core.DefaultKafkaConsumerFactory<byte[],byte[]>)factory.getConsumerFactory()).addPostProcessor(actual->
                    (org.apache.kafka.clients.consumer.Consumer<byte[],byte[]>)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{org.apache.kafka.clients.consumer.Consumer.class},(proxy,method,args)->{
                        try {
                            if(method.getName().equals("commitSync") && once.compareAndSet(false,true)) {
                                if(mode.equals("UNKNOWN")){method.invoke(actual,args);throw new org.apache.kafka.common.errors.TimeoutException("fixed-response-window");}
                                if(mode.equals("REBALANCE"))throw new org.apache.kafka.common.errors.RebalanceInProgressException("fixed-framework-window");
                                throw new org.apache.kafka.common.errors.WakeupException();
                            }
                            return method.invoke(actual,args);
                        }catch(java.lang.reflect.InvocationTargetException target){throw target.getCause();}
                    }));
                var beans=new org.springframework.beans.factory.support.DefaultListableBeanFactory();beans.registerSingleton("projection",r.projection);
                var listener=new com.recsys.recengine.filter.SeenItemsConsumer(beans.getBeanProvider(SeenProjectionService.class),topic);
                var control=new SeenListenerControl(factory,listener,f.repo,r.offsets,topic,group,false);
                try {
                    control.start();
                    if(mode.equals("UNKNOWN")) {
                        await(()->f.repo.control("LEGACY").stopReason()!=null,25);await(()->!control.isRunning(),20);
                        assertEquals(1L,offset(KAFKA.bootstrap(),group,topic,0));assertEquals("APPLIED",f.at(0,0).status());assertNull(f.at(0,1));
                        r.recovery.authorizeResume();control.resume();
                    }
                    await(()->Objects.equals(2L,offset(KAFKA.bootstrap(),group,topic,0)),25);
                    assertTrue(once.get());assertEquals("APPLIED",f.at(0,0).status());assertEquals("APPLIED",f.at(0,1).status());
                    assertEquals(Set.of("1502","1503"),r.redis.opsForSet().members("seen:1501"));
                    System.out.println("C09_CONTAINER injected_boundary="+mode+" real_committed=2 terminal_records=2");
                }finally{control.stop();}
            }
        }
    }
    @Test void realSaddResponseUnknownRetriesAndRetainsSingleMember() throws Exception {
        send(KAFKA.bootstrap(),topic,0,null,positive(1601,1602));
        try(var r=runtime()) {
            r.initialize();var once=new java.util.concurrent.atomic.AtomicBoolean();
            var projection=new SeenProjectionService(f.repo,f.decoder,r.redis) {
                @Override public void applyRedis(SeenMessageDecoder.Decoded decoded) {super.applyRedis(decoded);if(once.compareAndSet(false,true))throw new SeenRecord.Failure(SeenRecord.Code.DEPENDENCY_UNAVAILABLE);}
            };
            assertEquals(SeenProjectionService.Outcome.WAIT,projection.consume("LEGACY",0,0,null,positive(1601,1602)));
            assertEquals(Set.of("1602"),r.redis.opsForSet().members("seen:1601"));assertEquals(0L,offset(KAFKA.bootstrap(),group,topic,0));
            r.listener.start();await(()->Objects.equals(1L,offset(KAFKA.bootstrap(),group,topic,0)),25);assertEquals(2,f.at(0,0).attempts());
            assertEquals(Set.of("1602"),r.redis.opsForSet().members("seen:1601"));
        }
    }
    @Test void actualEnabledSpringConfigurationUsesPrimaryMigrationAndDedicatedListener() throws Exception {
        send(KAFKA.bootstrap(),topic,0,null,positive(1701,1702));
        try(var r=runtime()) {
            r.initialize();
            var sessions=new org.mybatis.spring.SqlSessionTemplate(com.recsys.persistence.spring.SpringSqlSessions.create(f.ds,"classpath*:mapper/recengine/primary/SeenRecoveryMapper.xml"));
            new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withUserConfiguration(com.recsys.recengine.filter.SeenItemsConsumer.class,SeenConsumerConfiguration.class)
                .withBean("sqlSessionTemplate",org.mybatis.spring.SqlSessionTemplate.class,()->sessions)
                .withBean(org.springframework.data.redis.core.StringRedisTemplate.class,()->r.redis)
                .withPropertyValues("recsys.behavior.seen-consume=true","recsys.behavior.topic="+topic,"recsys.behavior.seen-group="+group,"spring.kafka.bootstrap-servers="+KAFKA.bootstrap())
                .run(context->{assertNull(context.getStartupFailure());assertTrue(context.getBean(SeenListenerControl.class).isRunning());await(()->Objects.equals(1L,offset(KAFKA.bootstrap(),group,topic,0)),25);
                    assertEquals(Set.of("1702"),r.redis.opsForSet().members("seen:1701"));
                    var metrics=new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
                    try {
                        context.getBean("seenMetrics",io.micrometer.core.instrument.binder.MeterBinder.class).bindTo(metrics);
                        assertEquals(1,metrics.get("recsys.seen.records").tag("status","APPLIED").gauge().value());
                        assertEquals(0,metrics.get("recsys.seen.records").tag("status","QUARANTINED").gauge().value());
                    }finally{metrics.close();}
                    assertThrows(SeenRecord.Failure.class,()->context.getBean(SeenRecoveryService.class).initialize(UUID.randomUUID(),SeenOffsetInitializer.Mode.ADOPT_COMMITTED,"owner","默认恢复关闭"));});
        }
    }
    @Test void stoppedDedicatedRedisRetainsPgBudgetThenRecoversAtExplicitEndpoint() throws Exception {
        try(var dedicated=new GenericContainer<>("redis:7-alpine").withExposedPorts(6379)) {
            dedicated.start();int originalPort=dedicated.getMappedPort(6379);
            send(KAFKA.bootstrap(),topic,0,null,positive(1801,1802));
            try(var r=f.runtime(KAFKA.bootstrap(),topic,group,dedicated.getHost(),originalPort)) {
                r.initialize();r.redis.opsForSet().add("seen:1801","probe");
                dedicated.getDockerClient().stopContainerCmd(dedicated.getContainerId()).withTimeout(0).exec();
                r.listener.start();await(()->f.at(0,0)!=null && f.at(0,0).status().equals("PAUSED"),30);
                assertEquals(3,f.at(0,0).attempts());assertEquals(0L,offset(KAFKA.bootstrap(),group,topic,0));r.listener.stop();
                dedicated.getDockerClient().startContainerCmd(dedicated.getContainerId()).exec();
                var info=dedicated.getDockerClient().inspectContainerCmd(dedicated.getContainerId()).exec();
                int restoredPort=Integer.parseInt(info.getNetworkSettings().getPorts().getBindings().get(new com.github.dockerjava.api.model.ExposedPort(6379))[0].getHostPortSpec());
                System.out.println("C02_STOP_TEST_ENDPOINT changed="+(originalPort!=restoredPort));
                try(var recovered=f.runtime(KAFKA.bootstrap(),topic,group,dedicated.getHost(),restoredPort)) {
                    await(()->{try(var c=recovered.redis.getConnectionFactory().getConnection()){return "PONG".equals(c.ping());}catch(RuntimeException e){return false;}},10);
                    var record=f.at(0,0);var id=UUID.randomUUID();recovered.recovery.requestRecovery(id,record.recordId(),SeenRecoveryService.Mode.RETRY,record.digest(),"owner","独占Redis重启端点已显式核验");
                    assertEquals("SUCCEEDED",recovered.recovery.advance(id).status());recovered.listener.resume();await(()->Objects.equals(1L,offset(KAFKA.bootstrap(),group,topic,0)),25);
                    assertTrue(recovered.redis.opsForSet().isMember("seen:1801","1802"));
                }
            }
        }
    }
    @Test void independentJvmAfterQuarantineCommitBeforeAckReusesUniqueOriginalEvidence() throws Exception {
        byte[] original="isolated-invalid-original".getBytes(StandardCharsets.UTF_8);send(KAFKA.bootstrap(),topic,0,null,original);
        try(var r=runtime()) {
            r.initialize();crash("CLASSIFIED",null);var stored=f.at(0,0);
            assertEquals("QUARANTINED",stored.status());assertEquals(0,stored.attempts());assertEquals(0L,offset(KAFKA.bootstrap(),group,topic,0));
            r.listener.start();await(()->Objects.equals(1L,offset(KAFKA.bootstrap(),group,topic,0)),30);
            assertEquals(stored.recordId(),f.at(0,0).recordId());assertArrayEquals(original,f.at(0,0).valueBytes());
            assertEquals(1,f.jdbc.queryForObject("SELECT count(*) FROM seen_record",Integer.class));assertEquals(0,f.repo.statusCount("APPLIED"));
            System.out.println("C06_QUARANTINE_PROCESS committed=1 unique_record=1 attempts=0");
        }
    }
}
