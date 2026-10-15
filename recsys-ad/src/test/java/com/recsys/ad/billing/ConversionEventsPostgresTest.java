package com.recsys.ad.billing;

import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** 真数据库验证租约、幂等、投影事务；模拟 broker 窗口另由 Kafka 集成测试覆盖。 */
@Tag("money-chain") @Testcontainers
class ConversionEventsPostgresTest {
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>("postgres:16-alpine");
    ConversionEventsFixture f;
    @BeforeEach void setup() throws Exception {f=new ConversionEventsFixture(PG);}
    /** 相同URL不等于同一事务资源，三族投影都必须拒绝另一个DataSource实例。 */
    @Test void everyProjectionUsesActualDataSourceIdentityForItsTransaction() {
        var other=new org.springframework.jdbc.datasource.DriverManagerDataSource(f.ds.getUrl(),PG.getUsername(),PG.getPassword());
        var wrong=BudgetConfiguration.transactions(other,5);
        var cpm=new BillingProjectionRepository(f.cpm.sessions);var click=new ClickProjectionRepository(f.cpm.sessions);
        var conversion=new ConversionProjectionRepository(f.cpm.sessions);
        assertSame(f.ds,cpm.dataSource());assertSame(f.ds,click.dataSource());assertSame(f.ds,conversion.dataSource());
        assertThrows(IllegalArgumentException.class,()->new BillingProjectionService(cpm,f.cpm.codec,wrong));
        assertThrows(IllegalArgumentException.class,()->new ClickProjectionService(click,f.clicks.codec,wrong));
        assertThrows(IllegalArgumentException.class,()->new ConversionProjectionService(conversion,f.codec,wrong));
    }
    @Test void threeEventFamiliesShareAccountButKeepIndependentProjectionAndOutbox() {
        var cpm=f.cpm.event(f.cpm.capture(1,"0.200000")); var conversion=f.event(f.capture(1,"0.123456"));
        var oldClick=f.clicks.event(f.clicks.capture(1,"0.050000"));
        assertEquals(oldClick.eventId(),f.clicks.outbox.claim().orElseThrow().eventId());
        assertTrue(f.clicks.projection.apply(oldClick.key(),oldClick.payload()));
        assertThrows(BillingEventCodec.InvalidEvent.class,()->f.projection.apply(oldClick.key(),oldClick.payload()));
        assertEquals(cpm.eventId(),f.cpm.outbox.claim().orElseThrow().eventId());
        assertEquals(conversion.eventId(),f.outbox.claim().orElseThrow().eventId());
        assertTrue(f.cpm.projection.apply(cpm.key(),cpm.payload())); assertTrue(f.projection.apply(conversion.key(),conversion.payload()));
        assertThrows(BillingEventCodec.InvalidEvent.class,()->f.projection.apply(cpm.key(),cpm.payload()));
        assertThrows(BillingEventCodec.InvalidEvent.class,()->f.cpm.projection.apply(conversion.key(),conversion.payload()));
        assertEquals("0.373456",f.cpm.budgets.findAccount(1,f.cpm.today()).orElseThrow().chargedAmount().toPlainString());
        assertEquals("0.200000",f.cpm.total().toPlainString()); assertEquals("0.123456",f.total().toPlainString());
        assertEquals(1,f.jdbc.queryForObject("SELECT impression_count FROM ad_billing_projection_daily",Integer.class));
        assertEquals(1,f.jdbc.queryForObject("SELECT conversion_count FROM ad_conversion_projection_daily",Integer.class));
    }
    @Test void businessIdentityIsUniqueWithinAdvertiserButIndependentAcrossAdvertisers() throws Exception {
        UUID event=UUID.randomUUID();var a=f.event(f.capture(1,"0.1",event));var b=f.event(f.capture(2,"0.2",event));
        assertTrue(f.projection.apply(a.key(),a.payload()));assertTrue(f.projection.apply(b.key(),b.payload()));
        var c=f.event(f.capture(1,"0.3"));var changed=(com.fasterxml.jackson.databind.node.ObjectNode)ConversionEventsFixture.JSON.readTree(c.payload());
        changed.put("conversionEventId",event.toString());
        assertThrows(IllegalStateException.class,()->f.projection.apply(c.key(),changed.toString()));
        assertEquals(2,f.count());assertTrue(f.projection.apply(c.key(),c.payload()));assertEquals("0.600000",f.total().toPlainString());
    }
    @Test void visibleAndInternalOutcomeUseSeparateNamespacesButEachMustBeUnique() throws Exception {
        var first=f.event(f.capture(1,"0.1"));var second=f.event(f.capture(1,"0.2"));
        var firstTree=(com.fasterxml.jackson.databind.node.ObjectNode)ConversionEventsFixture.JSON.readTree(first.payload());
        firstTree.put("visibleReceiptId",firstTree.get("outcomeId").textValue());
        assertTrue(f.projection.apply(first.key(),firstTree.toString()));
        for(String field:List.of("outcomeId","visibleReceiptId")) {
            var changed=(com.fasterxml.jackson.databind.node.ObjectNode)ConversionEventsFixture.JSON.readTree(second.payload());
            changed.put(field,firstTree.get(field).textValue());
            assertThrows(IllegalStateException.class,()->f.projection.apply(second.key(),changed.toString()));
        }
        assertEquals(1,f.count());assertTrue(f.projection.apply(second.key(),second.payload()));assertEquals("0.300000",f.total().toPlainString());
    }
    @Test void conversionFieldsDuplicateJsonAndOversizePayloadFailBeforeInbox() throws Exception {
        var e=f.event(f.capture(1,"0.1")); var tree=ConversionEventsFixture.JSON.readTree(e.payload());
        for(var change:Map.of("bidType","CPM","priceUnit","PER_IMPRESSION","conversionEventId","not-a-uuid","outcomeId","not-a-uuid","occurredAt","2999-01-01T00:00:00Z",
                "itemId","9007199254740993.0","amount","0.1").entrySet()) {
            var invalid=(com.fasterxml.jackson.databind.node.ObjectNode)tree.deepCopy(); invalid.put(change.getKey(),change.getValue());
            assertThrows(BillingEventCodec.InvalidEvent.class,()->f.projection.apply(e.key(),invalid.toString()));
        }
        for(String invalid:List.of(e.payload()+" {}",e.payload().replaceFirst("\\{","{\"amount\":\"0.100000\",")," ".repeat(16385))) {
            assertThrows(BillingEventCodec.InvalidEvent.class,()->f.projection.apply(e.key(),invalid));
        }
        assertEquals(0,f.count());
    }
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
        f.jdbc.update("UPDATE ad_conversion_outbox SET attempts=19 WHERE event_id=?",id);
        var last=f.outbox.claim().orElseThrow();assertEquals(20,last.attempts());
        f.due(id);assertEquals(1,f.outbox.exhaust());assertEquals("DEAD",f.state(id));assertTrue(f.outbox.claim().isEmpty());
        assertTrue(f.outbox.replay(UUID.randomUUID(),id,"operator","恢复 broker 后重放"));
        assertFalse(f.outbox.replay(UUID.randomUUID(),id,"operator","重复操作"));
        assertEquals(1,f.jdbc.queryForObject("SELECT count(*) FROM ad_conversion_outbox_replay",Integer.class));
        assertEquals(20,f.jdbc.queryForObject("SELECT previous_attempts FROM ad_conversion_outbox_replay",Integer.class));
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
        assertEquals(2,f.jdbc.queryForObject("SELECT conversion_count FROM ad_conversion_projection_daily",Integer.class));
    }
    @Test void failureAfterInboxOrAggregateRollsBackBothAndCanResume() {
        var e=f.event(f.capture(1,"0.345678"));
        for(boolean afterAggregate:List.of(false,true)) {
            var broken=new ConversionProjectionRepository(f.cpm.sessions) {
                @Override public void accumulate(ConversionEventCodec.Validated value) {
                    if(afterAggregate)super.accumulate(value);
                    throw new IllegalStateException("injected");
                }
            };
            var service=new ConversionProjectionService(broken,f.codec,BudgetConfiguration.transactions(f.ds,5));
            assertThrows(IllegalStateException.class,()->service.apply(e.key(),e.payload()));
            assertEquals(0,f.count());assertEquals(0,f.total().signum());
        }
        assertTrue(f.projection.apply(e.key(),e.payload()));assertEquals("0.345678",f.total().toPlainString());
        var restart=new ConversionProjectionService(new ConversionProjectionRepository(f.cpm.sessions),f.codec,BudgetConfiguration.transactions(f.ds,5));
        assertFalse(restart.apply(e.key(),e.payload()));
    }
    @Test void changedPayloadAndAlternativeEventIdentityFailWithoutChangingTotals() throws Exception {
        var e=f.event(f.capture(1,"0.100000"));f.projection.apply(e.key(),e.payload());
        var changed=ConversionEventsFixture.JSON.readTree(e.payload());
        ((com.fasterxml.jackson.databind.node.ObjectNode)changed).put("amount","0.200000");
        assertThrows(IllegalStateException.class,()->f.projection.apply(e.key(),changed.toString()));
        ((com.fasterxml.jackson.databind.node.ObjectNode)changed).put("eventId",UUID.randomUUID().toString());
        assertThrows(IllegalStateException.class,()->f.projection.apply(e.key(),changed.toString()));
        assertEquals(1,f.count());assertEquals("0.100000",f.total().toPlainString());
        var additive=(com.fasterxml.jackson.databind.node.ObjectNode)ConversionEventsFixture.JSON.readTree(e.payload());
        additive.put("futureOptionalField","accepted");assertFalse(f.projection.apply(e.key(),additive.toString()));
    }
    @Test void invalidContractDoesNotProduceInboxAndFactsCannotBeMutated() throws Exception {
        var e=f.event(f.capture(1,"0.000001"));
        for(String field:List.of("schemaVersion","amount","advertiserId","trafficClass","acceptedAt")) {
            var invalid=(com.fasterxml.jackson.databind.node.ObjectNode)ConversionEventsFixture.JSON.readTree(e.payload());
            if(field.equals("schemaVersion"))invalid.put(field,2);else invalid.put(field,100);
            assertThrows(BillingEventCodec.InvalidEvent.class,()->f.projection.apply(e.key(),invalid.toString()));
        }
        assertThrows(BillingEventCodec.InvalidEvent.class,()->f.projection.apply("wrong-key",e.payload()));
        assertEquals(0,f.count());
        f.projection.apply(e.key(),e.payload());
        assertThrows(org.springframework.dao.DataAccessException.class,()->f.jdbc.update("DELETE FROM ad_conversion_projection_fact"));
        assertThrows(org.springframework.dao.DataAccessException.class,()->f.jdbc.update("UPDATE ad_conversion_outbox SET payload_json='{}'"));
        assertThrows(org.springframework.dao.DataAccessException.class,()->f.jdbc.update("DELETE FROM ad_conversion_outbox"));
        assertEquals(1,f.outbox.backlog().pending());assertEquals(0,f.outbox.backlog().dead());
        assertTrue(f.outbox.backlog().oldestSeconds()>=0);
        assertEquals(0,f.jdbc.queryForObject("""
                SELECT count(*) FROM pg_attribute a JOIN pg_class c ON c.oid=a.attrelid JOIN pg_namespace n ON n.oid=c.relnamespace
                WHERE n.nspname=current_schema() AND c.relkind='r' AND a.attnum>0 AND NOT a.attisdropped
                    AND col_description(c.oid,a.attnum) IS NULL
                """,Integer.class));
    }
}
