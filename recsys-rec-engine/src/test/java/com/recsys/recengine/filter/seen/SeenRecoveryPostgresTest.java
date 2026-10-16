package com.recsys.recengine.filter.seen;

import com.recsys.recengine.persistence.mapper.primary.SeenRecoveryMapper.*;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 真PG验证约束/原文不可变/命令竞争；Kafka/Redis由另组真实产品测试负责。 */
@Tag("behavior-reliability") @Testcontainers
class SeenRecoveryPostgresTest {
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>("postgres:16-alpine");
    SeenRecoveryFixture f;
    @BeforeEach void setup() throws Exception {f=new SeenRecoveryFixture(PG);bind();}
    void bind() {
        var id=UUID.randomUUID();var command=SeenRecoveryService.command(id,"INITIALIZE","LEGACY",null,"{}","test","隔离绑定");
        f.repo.initialize(new Control("LEGACY","cluster","topic-id","behavior-events","group",null,null),command,List.of());
        var claim=f.repo.claimCommand(id);f.repo.initialized(claim.command(),List.of(new Partition("LEGACY",0,"cluster","topic-id","behavior-events","group",0,id,0)));
    }
    SeenRecord positive(long offset) {byte[] value=SeenRecoveryFixture.positive(1,2);return f.repo.register("LEGACY",0,offset,null,value,f.decoder.decode(null,value));}
    @Test void migrationCommentsAndImmutableEvidenceAndNoOtherOwnerTables() {
        assertEquals(0,f.jdbc.queryForObject("SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname=current_schema() AND c.relkind='r' AND obj_description(c.oid) IS NULL",Integer.class));
        assertEquals(0,f.jdbc.queryForObject("SELECT count(*) FROM pg_attribute a JOIN pg_class c ON c.oid=a.attrelid JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname=current_schema() AND c.relkind='r' AND a.attnum>0 AND NOT a.attisdropped AND col_description(c.oid,a.attnum) IS NULL",Integer.class));
        assertEquals(5,f.jdbc.queryForObject("SELECT count(*) FROM pg_tables WHERE schemaname=current_schema()",Integer.class));
        var r=positive(0);
        assertThrows(Exception.class,()->f.jdbc.update("UPDATE seen_record SET value_bytes=? WHERE record_id=?",new byte[]{1},r.recordId()));
        assertThrows(Exception.class,()->f.jdbc.update("DELETE FROM seen_record WHERE record_id=?",r.recordId()));
        assertThrows(Exception.class,()->f.jdbc.update("UPDATE seen_stream_partition SET bootstrap_offset=7"));
        assertThrows(Exception.class,()->f.jdbc.update("UPDATE seen_recovery_command SET actor='other'"));
        assertThrows(Exception.class,()->f.jdbc.update("UPDATE seen_recovery_command SET status='READY'"));
        assertThrows(Exception.class,()->f.jdbc.update("UPDATE engine_seen_schema_history SET script_sha256=repeat('a',64)"));
        f.repo.verifySchema();
    }
    @Test void coordinateConflictAndDuplicateAreNotBusinessHashDeduplication() {
        var a=positive(0);var b=positive(0);var c=positive(1);
        assertEquals(a.recordId(),b.recordId());assertNotEquals(a.recordId(),c.recordId());
        byte[] other=SeenRecoveryFixture.positive(1,3);
        assertEquals(SeenRecord.Code.COORDINATE_CONFLICT,assertThrows(SeenRecord.Failure.class,()->f.repo.register("LEGACY",0,0,null,other,f.decoder.decode(null,other))).code());
        assertEquals(2,f.jdbc.queryForObject("SELECT count(*) FROM seen_record",Integer.class));
    }
    @Test void claimRollbackAndFencesAndBoundedPersistentBudget() throws Exception {
        var r=positive(0);var claimed=f.repo.claim(r);assertEquals(1,claimed.attempts());assertNull(f.repo.claim(r));
        assertTrue(f.repo.failed(claimed,0));assertFalse(f.repo.applied(claimed));
        var second=f.repo.claim(r);assertEquals(2,second.attempts());assertTrue(f.repo.failed(second,0));
        var third=f.repo.claim(r);assertEquals(3,third.attempts());assertTrue(f.repo.failed(third,0));
        var restarted=new SeenRecoveryFixture(f.ds);assertEquals("PAUSED",restarted.repo.rawRecord(r.recordId()).status());assertTrue(restarted.repo.blocked("LEGACY"));assertNull(restarted.repo.claim(r));
        assertFalse(restarted.repo.applied(second));
    }
    @Test void recoveryCommandImmutabilityConflictAndActiveOwnership() {
        var r=positive(0);for(int i=0;i<3;i++){var c=f.repo.claim(r);assertTrue(f.repo.failed(c,0));}
        var id=UUID.randomUUID();var command=SeenRecoveryService.command(id,"RETRY","LEGACY",r.recordId(),"original","owner","原因");
        var registered=f.repo.request(command);assertEquals(3,registered.beforeAttempts());assertEquals(0,registered.beforeEpoch());
        assertEquals(id,f.repo.request(command).commandId());
        assertThrows(SeenRecord.Failure.class,()->f.repo.request(SeenRecoveryService.command(id,"RETRY","LEGACY",r.recordId(),"changed","owner","原因")));
        assertThrows(SeenRecord.Failure.class,()->f.repo.request(SeenRecoveryService.command(UUID.randomUUID(),"RETRY","LEGACY",r.recordId(),"original","owner","原因")));
        var claim=f.repo.claimCommand(id);assertTrue(claim.acquired());assertFalse(f.repo.claimCommand(id).acquired());assertNull(f.repo.claim(r));
        f.repo.recovered(claim,"APPLIED","APPLIED",true);assertEquals("SUCCEEDED",f.repo.rawCommand(id).status());assertNull(f.repo.rawRecord(r.recordId()).activeCommandId());
        assertFalse(f.repo.claimCommand(id).acquired());assertEquals(1,f.repo.rawRecord(r.recordId()).epoch());
    }
    @Test void absentMigrationChecksumDriftAndWrongTransactionSourceFailClosed() throws Exception {
        var other=new SeenRecoveryFixture(SeenRecoveryFixture.schema(PG));
        assertThrows(SeenRecord.Failure.class,other.repo::verifySchema);
        try(var input=new org.springframework.core.io.ClassPathResource(SeenRecoveryRepository.MIGRATION).getInputStream();var connection=other.ds.getConnection();var statement=connection.createStatement()) {
            statement.execute("SET recsys.migration_sha256='"+"a".repeat(64)+"'");statement.execute(new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
        }
        assertEquals(SeenRecord.Code.MIGRATION_INVALID,assertThrows(SeenRecord.Failure.class,other.repo::verifySchema).code());
        var sessions=new org.mybatis.spring.SqlSessionTemplate(com.recsys.persistence.spring.SpringSqlSessions.create(f.ds,"classpath*:mapper/recengine/primary/SeenRecoveryMapper.xml"));
        assertEquals(SeenRecord.Code.INVALID_COMMAND,assertThrows(SeenRecord.Failure.class,()->new SeenRecoveryRepository(sessions,new org.springframework.jdbc.datasource.DataSourceTransactionManager(other.ds))).code());
    }
    @Test void sameInitializationIdKeepsWinnersFreezeWhenConcurrentReadSnapshotsDiffer() throws Exception {
        var race=new SeenRecoveryFixture(PG);
        var id=UUID.randomUUID();
        var binding=new com.recsys.recengine.persistence.mapper.primary.SeenRecoveryMapper.Control("LEGACY","isolated-cluster","isolated-topic","behavior-events","isolated-legacy",null,null);
        String original="{\"mode\":\"EARLIEST_RETAINED\",\"stream\":\"LEGACY\",\"clusterId\":\"isolated-cluster\",\"topicId\":\"isolated-topic\",\"topic\":\"behavior-events\",\"groupId\":\"isolated-legacy\",\"positions\":[{\"partition\":0,\"beginning\":0,\"end\":1,\"committed\":null,\"enroll\":true}]}";
        String laterObservation=original.replace("\"beginning\":0","\"beginning\":1").replace("\"end\":1","\"end\":2");
        // 模拟两个初始化调用在PG登记前已各读到不同快照的交错；不模拟Kafka副作用。
        var first=SeenRecoveryService.command(id,"INITIALIZE","LEGACY",null,original,"owner","同一受控请求");
        var second=SeenRecoveryService.command(id,"INITIALIZE","LEGACY",null,laterObservation,"owner","同一受控请求");
        race.repo.initialize(binding,first,List.of());
        assertEquals(original,race.repo.initialize(binding,second,List.of()).requestJson());
        assertEquals(first.requestDigest(),race.repo.rawCommand(id).requestDigest());
        assertEquals(id,race.repo.control("LEGACY").initializingCommandId());
        var changed=SeenRecoveryService.command(id,"INITIALIZE","LEGACY",null,original.replace("EARLIEST_RETAINED","ADOPT_COMMITTED"),"owner","同一受控请求");
        assertEquals(SeenRecord.Code.COMMAND_CONFLICT,assertThrows(SeenRecord.Failure.class,()->race.repo.initialize(binding,changed,List.of())).code());
    }
    @Test void unknownRecordReportReturnsFixedNotFoundThroughRealRepository() {
        var service=new SeenRecoveryService(f.repo,null,null,f.decoder,false);
        var failure=assertThrows(SeenRecord.Failure.class,()->service.record(UUID.randomUUID()));
        assertEquals(SeenRecord.Code.NOT_FOUND,failure.code());
        assertNull(failure.getCause());
        assertEquals("Seen操作未完成: NOT_FOUND",failure.getMessage());
    }
}
