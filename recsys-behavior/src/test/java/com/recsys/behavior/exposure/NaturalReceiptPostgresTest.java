package com.recsys.behavior.exposure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recsys.common.exposure.*;
import com.recsys.common.exposure.NaturalReceipt.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** 隔离真实 PG 验证行为 owner 的并发、原子事实/Outbox 和未知结果恢复；不使用共享业务库。 */
@Tag("natural-exposure")
@Testcontainers
class NaturalReceiptPostgresTest {
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");
    static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    static final NaturalQualification.Policy POLICY = new NaturalQualification.Policy("test-only", 6000, 1200, 120000);
    static final String KEY = Base64.getEncoder().encodeToString("natural-receipt-test-only-key-32bytes".getBytes(StandardCharsets.US_ASCII));
    static final NaturalQualificationToken TOKENS = new NaturalQualificationToken("test", Map.of("test", KEY));
    static DriverManagerDataSource ds;
    static JdbcTemplate jdbc;
    static NaturalReceiptRepository repository;
    @BeforeAll static void migrate() throws Exception {
        ds = new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()); jdbc = new JdbcTemplate(ds);
        try (var c = ds.getConnection(); var s = c.createStatement(); var in = new ClassPathResource(NaturalReceiptRepository.MIGRATION).getInputStream()) {
            s.execute("SET recsys.migration_sha256 = '" + NaturalReceiptRepository.migrationSha256() + "'");
            s.execute(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        repository = new NaturalReceiptRepository(jdbc, JSON); repository.verifySchema();
    }
    NaturalReceiptService service(NaturalReceiptRepository r) {
        return new NaturalReceiptService(r, TOKENS, POLICY, new DataSourceTransactionManager(ds), true);
    }
    NaturalQualification qualification(long remainingMs) {
        Instant expiry = repository.databaseNow().plusMillis(remainingMs).truncatedTo(ChronoUnit.MILLIS);
        return new NaturalQualification(UUID.randomUUID(), UUID.randomUUID(), "admin", Long.MAX_VALUE, Long.MAX_VALUE-1,
                2, NaturalQualification.Kind.SEARCH, NaturalQualification.Traffic.SANDBOX, "search", "rank:first", "b".repeat(64),
                POLICY, expiry.minusMillis(POLICY.ttlMs()), expiry);
    }
    Command command(NaturalQualification q) { return new Command(UUID.randomUUID(), "admin", TOKENS.sign(q), 1200, 6000, true); }
    int count(String table, UUID qid) {
        String key = table.equals("natural_impression_outbox") ? "event_id" : "qualification_id";
        // 表名仅由本测试固定调用点给定，不来自接口或产品代码。
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE " + key + "=?", Integer.class, qid);
    }
    void assertOneFact(NaturalQualification q) {
        assertEquals(1, count("natural_impression_fact", q.qualificationId()));
        assertEquals(1, count("natural_impression_outbox", q.qualificationId()));
    }
    @Test void approvedNaturalTextPolicyAcceptsExactThresholdAndRejectsLowerOrHiddenEvidence() {
        var policy=NaturalQualification.TEXT_POLICY_V1;
        var template=qualification(60000);
        var q=new NaturalQualification(template.operationId(),template.qualificationId(),template.subject(),template.userId(),template.itemId(),template.slot(),
                template.kind(),template.trafficClass(),template.scene(),template.bucket(),template.contentHash(),policy,template.expiresAt().minusMillis(policy.ttlMs()),template.expiresAt());
        var acceptedService=new NaturalReceiptService(repository,TOKENS,policy,new DataSourceTransactionManager(ds),true);
        for(var bad:List.of(new Command(UUID.randomUUID(),"admin",TOKENS.sign(q),999,5000,true),
                new Command(UUID.randomUUID(),"admin",TOKENS.sign(q),1000,4999,true),new Command(UUID.randomUUID(),"admin",TOKENS.sign(q),1000,5000,false))) {
            assertEquals(Status.POLICY_NOT_MET,acceptedService.accept(bad).status());
        }
        var command=new Command(UUID.randomUUID(),"admin",TOKENS.sign(q),1000,5000,true);
        assertEquals(Status.ACCEPTED,acceptedService.accept(command).status());assertOneFact(q);
        assertEquals(Status.ALREADY_ACCEPTED,acceptedService.accept(new Command(UUID.randomUUID(),"admin",TOKENS.sign(q),1000,5000,true)).status());
        assertEquals(300000,java.time.Duration.between(q.issuedAt(),q.expiresAt()).toMillis());
    }

    @Test void feedFinalPositionIsPreservedInFactAndOutboxWithoutMoney() throws Exception {
        var template=qualification(60000);
        var q=new NaturalQualification(template.operationId(),template.qualificationId(),template.subject(),template.userId(),template.itemId(),11,
                NaturalQualification.Kind.FEED,template.trafficClass(),"feed",template.bucket(),template.contentHash(),template.policy(),template.issuedAt(),template.expiresAt());
        assertEquals(Status.ACCEPTED,service(repository).accept(command(q)).status()); assertOneFact(q);
        var event=JSON.readTree(jdbc.queryForObject("SELECT payload_json FROM natural_impression_outbox WHERE event_id=?",String.class,q.qualificationId()));
        assertEquals("FEED",event.path("kind").textValue()); assertEquals(11,event.path("slot").intValue());
        assertEquals(q.operationId().toString(),event.path("operationId").textValue()); assertFalse(event.has("amount"));
    }

    @Test void sameReceiptConcurrentAndRebuiltServiceReturnExactOriginal() throws Exception {
        var q = qualification(60000); var c = command(q);
        List<Result> results = concurrent(Collections.nCopies(8, c));
        assertTrue(results.stream().allMatch(results.getFirst()::equals)); assertEquals(Status.ACCEPTED, results.getFirst().status());
        assertOneFact(q); assertEquals(results.getFirst(), service(new NaturalReceiptRepository(jdbc, JSON)).find(c.receiptId(), "admin").orElseThrow());
        var payload = JSON.readTree(jdbc.queryForObject("SELECT payload_json FROM natural_impression_outbox WHERE event_id=?", String.class, q.qualificationId()));
        assertEquals(Long.toString(Long.MAX_VALUE), payload.get("userId").asText());
        assertEquals(q.bucket(), payload.get("bucket").asText()); assertEquals(q.contentHash(), payload.get("contentHash").asText());
        assertEquals(Event.TYPE, payload.get("eventType").asText());
        assertFalse(payload.has("subject")); assertFalse(payload.has("qualificationToken")); assertFalse(payload.has("chargedAmount"));
    }
    @Test void differentReceiptsForSameQualificationHaveOneCanonicalFact() throws Exception {
        var q = qualification(60000); var commands = new ArrayList<Command>();
        for (int i=0;i<8;i++) commands.add(command(q));
        var results = concurrent(commands); assertOneFact(q);
        assertEquals(1, results.stream().filter(r -> r.status() == Status.ACCEPTED).count());
        assertEquals(1, results.stream().map(Result::canonicalReceiptId).distinct().count());
        assertEquals(1, results.stream().map(Result::acceptedAt).distinct().count());
    }
    List<Result> concurrent(List<Command> commands) throws Exception {
        var barrier = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(commands.size())) {
            var tasks = new ArrayList<Future<Result>>();
            for (var c : commands) tasks.add(pool.submit(() -> { barrier.await(); return service(repository).accept(c); }));
            barrier.countDown(); var results = new ArrayList<Result>();
            for (var f : tasks) results.add(f.get(10, TimeUnit.SECONDS)); return results;
        }
    }
    @Test void changedPayloadWrongSubjectBadSignatureAndSameQualificationChangedContextCannotWrite() {
        var q = qualification(60000); var c = command(q); service(repository).accept(c);
        assertThrows(Conflict.class, () -> service(repository).accept(new Command(c.receiptId(), "admin", c.qualificationToken(), 1300, 6000, true)));
        assertThrows(AccessDenied.class, () -> service(repository).find(c.receiptId(), "other"));
        assertThrows(AccessDenied.class, () -> service(repository).accept(new Command(UUID.randomUUID(), "other", c.qualificationToken(), 1200, 6000, true)));
        assertThrows(IllegalArgumentException.class, () -> service(repository).accept(new Command(UUID.randomUUID(), "admin", c.qualificationToken()+"x", 1200, 6000, true)));
        var changed = new NaturalQualification(q.operationId(), q.qualificationId(), q.subject(), q.userId(), 99, q.slot(), q.kind(), q.trafficClass(), q.scene(),
                q.bucket(), q.contentHash(), q.policy(), q.issuedAt(), q.expiresAt());
        var altered = command(changed);
        assertThrows(Conflict.class, () -> service(repository).accept(altered));
        assertTrue(service(repository).find(altered.receiptId(), "admin").isEmpty()); assertOneFact(q);
    }
    @Test void explicitPolicyImpossibleDurationHiddenAndExpiryProduceStableRejections() {
        var q = qualification(60000); var c = command(q);
        for (var rejected : List.of(new Command(UUID.randomUUID(), "admin", c.qualificationToken(), 1199, 6000, true),
                new Command(UUID.randomUUID(), "admin", c.qualificationToken(), 1200, 5999, true),
                new Command(UUID.randomUUID(), "admin", c.qualificationToken(), 1200, 6000, false),
                new Command(UUID.randomUUID(), "admin", c.qualificationToken(), 100000, 6000, true))) {
            assertEquals(Status.POLICY_NOT_MET, service(repository).accept(rejected).status());
            assertEquals(service(repository).accept(rejected), service(repository).find(rejected.receiptId(), "admin").orElseThrow());
        }
        assertEquals(0, count("natural_impression_fact", q.qualificationId()));
        assertEquals(Status.EXPIRED, service(repository).accept(command(qualification(-1))).status());
        var alternate = new NaturalReceiptService(repository, TOKENS, new NaturalQualification.Policy("different-test",6000,1200,120000), new DataSourceTransactionManager(ds),true);
        assertThrows(IllegalArgumentException.class, () -> alternate.accept(c));
    }
    @Test void waitingForQualificationLockPastExpiryCannotAccept() throws Exception {
        var q = qualification(800); var c = command(q);
        service(repository).accept(new Command(UUID.randomUUID(), "admin", c.qualificationToken(), 0, 0, false));
        var attempting = new CountDownLatch(1);
        var guarded = new NaturalReceiptRepository(jdbc, JSON) {
            @Override public void lockQualification(NaturalQualification value) { attempting.countDown(); super.lockQualification(value); }
        };
        try (var conn = ds.getConnection(); var pool = Executors.newSingleThreadExecutor()) {
            conn.setAutoCommit(false);
            try (var s = conn.prepareStatement("SELECT qualification_id FROM natural_qualification_guard WHERE qualification_id=? FOR UPDATE")) {
                s.setObject(1,q.qualificationId()); s.executeQuery().close();
            }
            var result = pool.submit(() -> service(guarded).accept(c)); assertTrue(attempting.await(2,TimeUnit.SECONDS));
            while (repository.databaseNow().isBefore(q.expiresAt())) Thread.sleep(20);
            assertFalse(result.isDone()); conn.commit();
            assertEquals(Status.EXPIRED, result.get(3,TimeUnit.SECONDS).status());
        }
        assertEquals(0,count("natural_impression_fact",q.qualificationId()));
    }
    @ParameterizedTest @ValueSource(strings={"claim","fact","outbox","result"})
    void everyWriteFailureRollsBackReceiptGuardFactAndOutbox(String stage) {
        var q = qualification(60000); var c = command(q);
        var failing = new NaturalReceiptRepository(jdbc, JSON) {
            @Override public boolean claim(Command value, UUID id) { boolean b=super.claim(value,id); if(stage.equals("claim")) throw new IllegalStateException("test failure"); return b; }
            @Override public void appendFact(NaturalQualification value, UUID id, Instant now) { super.appendFact(value,id,now); if(stage.equals("fact")) throw new IllegalStateException("test failure"); }
            @Override public void appendOutbox(Event e) { super.appendOutbox(e); if(stage.equals("outbox")) throw new IllegalStateException("test failure"); }
            @Override public Result complete(Result r) { super.complete(r); if(stage.equals("result")) throw new IllegalStateException("test failure"); return r; }
        };
        assertThrows(IllegalStateException.class, () -> service(failing).accept(c));
        for(String table : List.of("natural_impression_receipt","natural_qualification_guard","natural_impression_fact","natural_impression_outbox")) assertEquals(0,count(table,q.qualificationId()));
        assertEquals(Status.ACCEPTED,service(repository).accept(c).status()); assertOneFact(q);
    }
    @Test void databaseRejectsUnfinishedReceiptMissingOutboxAndHistoryMutation() {
        var tx = new TransactionTemplate(new DataSourceTransactionManager(ds)); var q=qualification(60000); var c=command(q);
        assertThrows(RuntimeException.class, () -> tx.execute(t -> { repository.claim(c,q.qualificationId()); repository.lockQualification(q); return null; }));
        assertTrue(repository.find(c.receiptId(),"admin").isEmpty());
        assertThrows(RuntimeException.class, () -> tx.execute(t -> {
            repository.claim(c,q.qualificationId()); repository.lockQualification(q); var now=repository.databaseNow(); repository.appendFact(q,c.receiptId(),now);
            return repository.complete(new Result(c.receiptId(),q.qualificationId(),Status.ACCEPTED,q.qualificationId(),c.receiptId(),now,now));
        }));
        assertEquals(0,count("natural_impression_fact",q.qualificationId()));
        service(repository).accept(c);
        for(String table:List.of("natural_qualification_guard","natural_impression_fact","natural_impression_outbox","natural_impression_receipt")) {
            String key=table.equals("natural_impression_outbox")?"event_id":"qualification_id";
            assertThrows(RuntimeException.class, () -> jdbc.update("DELETE FROM "+table+" WHERE "+key+"=?",q.qualificationId()));
        }
        assertThrows(RuntimeException.class, () -> jdbc.update("UPDATE natural_impression_receipt SET status='EXPIRED' WHERE receipt_id=?",c.receiptId()));
        assertOneFact(q);
    }
    @Test void originalsSurviveAdmissionPauseKeyRemovalAndPolicyChange() {
        var q=qualification(60000); var c=command(q); var result=service(repository).accept(c);
        var otherKeys=new NaturalQualificationToken(null,Map.of("other",KEY));
        var paused=new NaturalReceiptService(repository,otherKeys,new NaturalQualification.Policy("later-test",5000,1000,60000),new DataSourceTransactionManager(ds),false);
        assertEquals(result,paused.accept(c)); assertEquals(result,paused.find(c.receiptId(),"admin").orElseThrow());
        assertThrows(IllegalStateException.class, () -> paused.accept(command(q)));
        assertOneFact(q);
    }
    @Test void requiresNewCommitsBeforeAckEvenWhenOuterTransactionRollsBackAndRejectsOtherPool() {
        var c=command(qualification(60000));
        var outer=new TransactionTemplate(new DataSourceTransactionManager(ds));
        outer.execute(t -> { assertEquals(Status.ACCEPTED,service(repository).accept(c).status()); t.setRollbackOnly(); return null; });
        assertTrue(repository.find(c.receiptId(),"admin").isPresent());
        var other=new DriverManagerDataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword());
        assertThrows(IllegalArgumentException.class, () -> new NaturalReceiptService(repository,TOKENS,POLICY,new DataSourceTransactionManager(other),true));
    }
    @Test void everyTableAndColumnIsDocumentedAndMigrationDigestIsChecked() {
        for(String table:List.of("natural_exposure_schema_history","natural_qualification_guard","natural_impression_receipt","natural_impression_fact","natural_impression_outbox")) {
            assertNotNull(jdbc.queryForObject("SELECT obj_description(?::regclass)",String.class,table));
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM pg_attribute WHERE attrelid=?::regclass AND attnum>0 AND NOT attisdropped AND col_description(attrelid,attnum) IS NULL",Integer.class,table));
        }
        assertEquals(NaturalReceiptRepository.migrationSha256(),jdbc.queryForObject("SELECT script_sha256 FROM natural_exposure_schema_history WHERE version=1",String.class));
    }
}
