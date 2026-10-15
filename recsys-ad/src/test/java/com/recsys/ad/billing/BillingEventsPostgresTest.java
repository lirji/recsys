package com.recsys.ad.billing;

import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** 真数据库验证租约、幂等、投影事务；模拟 broker 窗口另由 Kafka 集成测试覆盖。 */
@Tag("money-chain") @Testcontainers
class BillingEventsPostgresTest {
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>("postgres:16-alpine");
    BillingEventsFixture f;
    @BeforeEach void setup() throws Exception {f=new BillingEventsFixture(PG);}
    @Test void concurrentClaimFencingAndCrashedLastAttemptBecomeDead() throws Exception {
        var id=f.capture(1,"0.100001");
        try(var pool=Executors.newFixedThreadPool(8)) {
            var calls=new ArrayList<Callable<Optional<BillingOutboxRepository.Pending>>>();
            for(int i=0;i<8;i++)calls.add(()->f.outbox.claim());
            var claimed=new ArrayList<BillingOutboxRepository.Pending>();
            for(var result:pool.invokeAll(calls))result.get().ifPresent(claimed::add);
            assertEquals(1,claimed.size());var old=claimed.getFirst();
            f.due(id);var next=f.outbox.claim().orElseThrow();assertTrue(next.fence()>old.fence());
            assertFalse(f.outbox.published(old));assertFalse(f.outbox.failed(old,0,"TEST"));
            assertTrue(f.outbox.failed(next,0,"TEST"));assertEquals("READY",f.state(id));
        }
        f.jdbc.update("UPDATE ad_billing_outbox SET attempts=19 WHERE event_id=?",id);
        var last=f.outbox.claim().orElseThrow();assertEquals(20,last.attempts());
        f.due(id);assertEquals(1,f.outbox.exhaust());assertEquals("DEAD",f.state(id));assertTrue(f.outbox.claim().isEmpty());
        assertTrue(f.outbox.replay(UUID.randomUUID(),id,"operator","恢复 broker 后重放"));
        assertFalse(f.outbox.replay(UUID.randomUUID(),id,"operator","重复操作"));
        assertEquals(1,f.jdbc.queryForObject("SELECT count(*) FROM ad_billing_outbox_replay",Integer.class));
        assertEquals(20,f.jdbc.queryForObject("SELECT previous_attempts FROM ad_billing_outbox_replay",Integer.class));
        assertEquals(1,f.outbox.claim().orElseThrow().attempts());
    }
    @RepeatedTest(5) void concurrentDuplicateAndReverseAccountVersionsCountEveryIndependentFact() throws Exception {
        var first=f.event(f.capture(1,"0.123456"));var later=f.event(f.capture(1,"0.000001"));
        assertTrue(f.projection.apply(later.key(),later.payload()));
        try(var pool=Executors.newFixedThreadPool(8)) {
            var calls=new ArrayList<Callable<Boolean>>();
            for(int i=0;i<8;i++)calls.add(()->f.projection.apply(first.key(),first.payload()));
            int applied=0;for(var value:pool.invokeAll(calls))if(value.get())applied++;
            assertEquals(1,applied);
        }
        assertEquals(2,f.count());assertEquals("0.123457",f.total().toPlainString());
        assertEquals(2,f.jdbc.queryForObject("SELECT impression_count FROM ad_billing_projection_daily",Integer.class));
    }
    @Test void failureAfterInboxOrAggregateRollsBackBothAndCanResume() {
        var e=f.event(f.capture(1,"0.345678"));
        for(boolean afterAggregate:List.of(false,true)) {
            var broken=new BillingProjectionRepository(f.jdbc) {
                @Override public void accumulate(BillingEventCodec.Validated value) {
                    if(afterAggregate)super.accumulate(value);
                    throw new IllegalStateException("injected");
                }
            };
            var service=new BillingProjectionService(broken,f.codec,BudgetConfiguration.transactions(f.ds,5));
            assertThrows(IllegalStateException.class,()->service.apply(e.key(),e.payload()));
            assertEquals(0,f.count());assertEquals(0,f.total().signum());
        }
        assertTrue(f.projection.apply(e.key(),e.payload()));assertEquals("0.345678",f.total().toPlainString());
        var restart=new BillingProjectionService(new BillingProjectionRepository(f.jdbc),f.codec,BudgetConfiguration.transactions(f.ds,5));
        assertFalse(restart.apply(e.key(),e.payload()));
    }
    @Test void changedPayloadAndAlternativeEventIdentityFailWithoutChangingTotals() throws Exception {
        var e=f.event(f.capture(1,"0.100000"));f.projection.apply(e.key(),e.payload());
        var changed=BillingEventsFixture.JSON.readTree(e.payload());
        ((com.fasterxml.jackson.databind.node.ObjectNode)changed).put("amount","0.200000");
        assertThrows(IllegalStateException.class,()->f.projection.apply(e.key(),changed.toString()));
        ((com.fasterxml.jackson.databind.node.ObjectNode)changed).put("eventId",UUID.randomUUID().toString());
        assertThrows(IllegalStateException.class,()->f.projection.apply(e.key(),changed.toString()));
        assertEquals(1,f.count());assertEquals("0.100000",f.total().toPlainString());
        var additive=(com.fasterxml.jackson.databind.node.ObjectNode)BillingEventsFixture.JSON.readTree(e.payload());
        additive.put("futureOptionalField","accepted");assertFalse(f.projection.apply(e.key(),additive.toString()));
    }
    @Test void invalidContractDoesNotProduceInboxAndFactsCannotBeMutated() throws Exception {
        var e=f.event(f.capture(1,"0.000001"));
        for(String field:List.of("schemaVersion","amount","advertiserId","trafficClass","acceptedAt")) {
            var invalid=(com.fasterxml.jackson.databind.node.ObjectNode)BillingEventsFixture.JSON.readTree(e.payload());
            if(field.equals("schemaVersion"))invalid.put(field,2);else invalid.put(field,100);
            assertThrows(BillingEventCodec.InvalidEvent.class,()->f.projection.apply(e.key(),invalid.toString()));
        }
        assertThrows(BillingEventCodec.InvalidEvent.class,()->f.projection.apply("wrong-key",e.payload()));
        assertEquals(0,f.count());
        f.projection.apply(e.key(),e.payload());
        assertThrows(org.springframework.dao.DataAccessException.class,()->f.jdbc.update("DELETE FROM ad_billing_projection_fact"));
        assertThrows(org.springframework.dao.DataAccessException.class,()->f.jdbc.update("UPDATE ad_billing_outbox SET payload_json='{}'"));
        assertThrows(org.springframework.dao.DataAccessException.class,()->f.jdbc.update("DELETE FROM ad_billing_outbox"));
        assertEquals(1,f.outbox.backlog().pending());assertEquals(0,f.outbox.backlog().dead());
        assertTrue(f.outbox.backlog().oldestSeconds()>=0);
        assertEquals(0,f.jdbc.queryForObject("""
                SELECT count(*) FROM pg_attribute a JOIN pg_class c ON c.oid=a.attrelid JOIN pg_namespace n ON n.oid=c.relnamespace
                WHERE n.nspname=current_schema() AND c.relkind='r' AND a.attnum>0 AND NOT a.attisdropped
                    AND col_description(c.oid,a.attnum) IS NULL
                """,Integer.class));
    }
}
