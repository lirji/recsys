package com.recsys.ad.billing;

import com.recsys.common.ad.BidType;
import com.recsys.common.ad.billing.*;
import com.recsys.common.ad.billing.BillingRecovery.Action;
import com.recsys.common.ad.billing.BillingRecovery.Command;
import com.recsys.common.ad.billing.BillingRecovery.Status;
import com.recsys.common.ad.delivery.SandboxDelivery.ChargeMode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.*;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.mock.env.MockEnvironment;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** 独占PG/Kafka验证原事件续发、到期义务原子恢复及未知结果查询，不写共享环境。 */
@Tag("money-chain") @Testcontainers
class BillingRecoveryPostgresKafkaTest {
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");
    @Container static final BillingEventsKafkaTest.KafkaFixture KAFKA = new BillingEventsKafkaTest.KafkaFixture();
    ConversionEventsFixture f;
    BillingRecoveryRepository repository;
    BillingRecoveryService service;
    ReservationService reservations;
    ReconciliationService snapshots;

    @BeforeEach void setup() throws Exception {
        f = new ConversionEventsFixture(PG);
        for (var path : List.of(PreparationRepository.CONVERSION_MIGRATION, ReconciliationRepository.MIGRATION, BillingRecoveryRepository.MIGRATION)) {
            try (var in = new ClassPathResource(path).getInputStream(); var c = f.ds.getConnection(); var st = c.createStatement()) {
                st.execute("SET recsys.migration_sha256='" + BudgetRepository.migrationSha256(path) + "'");
                st.execute(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        repository = new BillingRecoveryRepository(f.cpm.sessions, ConversionEventsFixture.JSON); repository.verifySchema();
        reservations = new ReservationService(f.cpm.reservations, f.cpm.quotes, BudgetConfiguration.transactions(f.ds, 5), false);
        service = fresh(repository);
        snapshots = new ReconciliationService(new ReconciliationRepository(f.cpm.sessions), ReconciliationConfiguration.transactions(f.ds));
    }
    BillingRecoveryService fresh(BillingRecoveryRepository repo) {
        return new BillingRecoveryService(repo, reservations, f.clicks.settlement, f.settlement, BudgetConfiguration.transactions(f.ds, 5));
    }
    String table(ChargeMode family) { return switch (family) { case IMPRESSION -> "ad_billing_outbox"; case CLICK -> "ad_click_outbox"; case CONVERSION -> "ad_conversion_outbox"; }; }
    UUID capture(ChargeMode family) { return switch (family) { case IMPRESSION -> f.cpm.capture(1, "0.100001"); case CLICK -> f.clicks.capture(1, "0.200002"); case CONVERSION -> f.capture(1, "0.300003"); }; }
    Command command(ChargeMode family, Action action, UUID target) { return new Command(UUID.randomUUID(), 1, f.cpm.today(), family, action, target, "故障续办", "offline-operator"); }
    BillingOutboxRepository outbox(ChargeMode family) { return new BillingOutboxRepository(f.cpm.sessions, BillingOutboxRepository.EventFamily.valueOf(family.name())); }
    BillingOutboxRepository.Pending event(ChargeMode family, UUID id) { return switch (family) { case IMPRESSION -> f.cpm.event(id); case CLICK -> f.clicks.event(id); case CONVERSION -> f.event(id); }; }
    void apply(ChargeMode family, String key, String payload) { switch (family) { case IMPRESSION -> f.cpm.projection.apply(key, payload); case CLICK -> f.clicks.projection.apply(key, payload); case CONVERSION -> f.projection.apply(key, payload); } }
    BillingOutboxRelay relay(ChargeMode family, BillingKafkaPublisher publisher) {
        if (family == ChargeMode.IMPRESSION) return new BillingOutboxRelay(outbox(family), publisher, f.cpm.codec, new SimpleMeterRegistry());
        return new BillingOutboxRelay(outbox(family), publisher,
                family == ChargeMode.CLICK ? f.clicks.codec::validatePending : f.codec::validatePending,
                new SimpleMeterRegistry(), BillingOutboxRepository.EventFamily.valueOf(family.name()));
    }

    @Test void allFamiliesRecoverRealBrokerFailureAndReconcileAfterDuplicateDelivery() throws Exception {
        for (var family : ChargeMode.values()) {
            var id = capture(family); var original = event(family, id);
            String topic = "recovery-" + UUID.randomUUID();
            var env = new MockEnvironment().withProperty("spring.kafka.bootstrap-servers", KAFKA.bootstrap())
                    .withProperty("recsys.ad.billing.events.group", "recovery-" + UUID.randomUUID());
            try (var admin = Admin.create(Map.of("bootstrap.servers", KAFKA.bootstrap()))) {
                admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1))).all().get(15, TimeUnit.SECONDS);
            }
            // 真实不可达broker耗尽本次原传输预算；恢复命令不重新创建收费事实。
            f.jdbc.update("UPDATE " + table(family) + " SET attempts=19 WHERE event_id=?", id);
            var unavailable = BillingEventConfiguration.producerProperties(env);
            unavailable.put("bootstrap.servers", "127.0.0.1:1"); unavailable.put("max.block.ms", 250);
            try (var publisher = new BillingKafkaPublisher(new KafkaProducer<>(unavailable), topic)) { assertTrue(relay(family, publisher).relayOne()); }
            assertEquals("DEAD", f.jdbc.queryForObject("SELECT delivery_state FROM " + table(family) + " WHERE event_id=?", String.class, id));
            var cmd = command(family, Action.REPUBLISH, id); var result = service.recover(cmd);
            assertEquals(Status.REQUEUED, result.status()); assertEquals(result, fresh(repository).recover(cmd));
            assertEquals(original.payload(), event(family, id).payload()); assertEquals(original.key(), event(family, id).key());
            try (var publisher = new BillingKafkaPublisher(new KafkaProducer<>(BillingEventConfiguration.producerProperties(env)), topic);
                 var consumer = new KafkaConsumer<String,String>(BillingEventConfiguration.consumerProperties(env))) {
                consumer.subscribe(List.of(topic)); assertTrue(relay(family, publisher).relayOne());
                var until = System.nanoTime() + Duration.ofSeconds(15).toNanos(); boolean observed = false;
                while (!observed && System.nanoTime() < until) {
                    for (var record : consumer.poll(Duration.ofMillis(200))) {
                        apply(family, record.key(), record.value()); apply(family, record.key(), record.value()); observed = true;
                    }
                }
                assertTrue(observed);
            }
            assertEquals(Status.ALREADY_APPLIED, service.recover(command(family, Action.REPUBLISH, id)).status());
        }
        var snapshot = snapshots.inspect(new BillingReconciliation.Scope(1, f.cpm.today()));
        assertEquals(BillingReconciliation.FundsStatus.BALANCED, snapshot.fundsStatus());
        assertEquals(new BigDecimal("0.600006"), snapshot.account().chargedAmount());
        for (var family : snapshot.families()) {
            assertEquals(1, family.ledgerCount()); assertEquals(1, family.factCount());
            assertEquals(BillingReconciliation.ProjectionStatus.CAUGHT_UP, family.projectionStatus());
        }
    }

    @Test void publishedWaitAndLiveSendingAreNotPreemptedAndOriginalDecisionIsStable() {
        for (var family : ChargeMode.values()) {
            var id = capture(family); var first = command(family, Action.REPUBLISH, id);
            assertEquals(Status.NOT_ELIGIBLE, service.recover(first).status());
            f.jdbc.update("UPDATE " + table(family) + " SET delivery_state='SENDING',available_at=clock_timestamp()+interval '30 seconds' WHERE event_id=?", id);
            assertEquals(Status.NOT_ELIGIBLE, service.recover(command(family, Action.REPUBLISH, id)).status());
            f.jdbc.update("UPDATE " + table(family) + " SET delivery_state='PUBLISHED',published_at=clock_timestamp() WHERE event_id=?", id);
            assertEquals(Status.NOT_ELIGIBLE, service.recover(command(family, Action.REPUBLISH, id)).status());
            f.jdbc.update("UPDATE " + table(family) + " SET published_at=clock_timestamp()-interval '61 seconds' WHERE event_id=?", id);
            assertEquals(Status.REQUEUED, service.recover(command(family, Action.REPUBLISH, id)).status());
            assertNull(f.jdbc.queryForObject("SELECT published_at FROM " + table(family) + " WHERE event_id=?", java.sql.Timestamp.class, id));
            assertEquals(Status.NOT_ELIGIBLE, service.recover(first).status());
        }
    }

    @Test void badExistingFactsRequireReviewAndNeverRewriteOrRecharge() throws Exception {
        for (var family : ChargeMode.values()) {
            var id = capture(family); var e = event(family, id);
            var bad = (com.fasterxml.jackson.databind.node.ObjectNode) ConversionEventsFixture.JSON.readTree(e.payload());
            bad.put("amount", "0.500000"); apply(family, e.key(), bad.toString());
            assertEquals(Status.NEEDS_REVIEW, service.recover(command(family, Action.REPUBLISH, id)).status());
        }
        var snapshot = snapshots.inspect(new BillingReconciliation.Scope(1, f.cpm.today()));
        assertEquals(new BigDecimal("0.600006"), snapshot.account().chargedAmount());
        for (var family : snapshot.families()) assertEquals(BillingReconciliation.ProjectionStatus.MISMATCH, family.projectionStatus());
    }

    @Test void concurrentDuplicateCommandAndConflictingIdentityNeverRepeatSideEffect() throws Exception {
        var id = capture(ChargeMode.CONVERSION); f.jdbc.update("UPDATE ad_conversion_outbox SET delivery_state='DEAD' WHERE event_id=?", id);
        var cmd = command(ChargeMode.CONVERSION, Action.REPUBLISH, id);
        try (var pool = Executors.newFixedThreadPool(4)) {
            var pending = new ArrayList<Future<BillingRecovery.Result>>();
            for (int i = 0; i < 4; i++) pending.add(pool.submit(() -> service.recover(cmd)));
            var first = pending.getFirst().get(10, TimeUnit.SECONDS);
            for (var result : pending) assertEquals(first, result.get(10, TimeUnit.SECONDS));
        }
        assertEquals(1L, f.jdbc.queryForObject("SELECT fence FROM ad_conversion_outbox WHERE event_id=?", Long.class, id));
        assertEquals(1L, f.jdbc.queryForObject("SELECT count(*) FROM ad_billing_recovery_command", Long.class));
        assertThrows(ReconciliationTask.Conflict.class, () -> service.recover(new Command(cmd.commandId(), 2, cmd.budgetDate(), cmd.family(), cmd.action(), id, cmd.reason(), cmd.actor())));
        assertThrows(ReconciliationTask.Conflict.class, () -> service.recover(new Command(cmd.commandId(), 1, cmd.budgetDate(), cmd.family(), cmd.action(), id, "changed", cmd.actor())));
        assertThrows(ReconciliationTask.NotFound.class, () -> service.find(2, cmd.commandId()));
        assertEquals(Status.NOT_FOUND, service.recover(new Command(UUID.randomUUID(), 2, cmd.budgetDate(), cmd.family(), cmd.action(), id, cmd.reason(), cmd.actor())).status());
        assertEquals(Status.NOT_FOUND, service.recover(new Command(UUID.randomUUID(), 1, cmd.budgetDate().minusDays(1), cmd.family(), cmd.action(), id, cmd.reason(), cmd.actor())).status());
        assertThrows(DataAccessException.class, () -> f.jdbc.update("DELETE FROM ad_billing_recovery_command"));
    }

    @Test void failedResultCommitRollsBackRequeueAndCommandTogether() {
        var id = capture(ChargeMode.CLICK); f.jdbc.update("UPDATE ad_click_outbox SET delivery_state='DEAD' WHERE event_id=?", id);
        var command = command(ChargeMode.CLICK, Action.REPUBLISH, id);
        var broken = fresh(new BillingRecoveryRepository(f.cpm.sessions, ConversionEventsFixture.JSON) {
            @Override void complete(Command c, Status status) { super.complete(c, status); throw new IllegalStateException("提交前崩溃"); }
        });
        assertThrows(IllegalStateException.class, () -> broken.recover(command));
        assertEquals("DEAD", f.clicks.state(id)); assertThrows(ReconciliationTask.NotFound.class, () -> service.find(1, command.commandId()));
        assertEquals(Status.REQUEUED, service.recover(command).status());
        assertEquals(service.recover(command), service.find(1, command.commandId()));
    }

    UUID hold(ChargeMode family) {
        if (f.cpm.budgets.findAccount(1, f.cpm.today()).isEmpty()) f.cpm.budgets.setLimit(BudgetCommand.parse(1, UUID.randomUUID().toString(), f.cpm.today().toString(), "CNY", "100", 0, "operator"));
        var id = UUID.randomUUID(); var amount = new BigDecimal("0.100000");
        if (family == ChargeMode.IMPRESSION) {
            f.cpm.quotes.freeze(id, UUID.randomUUID(), 1, 1, 1, new CpmPrice(amount, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO, amount));
            new ReservationService(f.cpm.reservations, f.cpm.quotes, BudgetConfiguration.transactions(f.ds, 5), true).prepare(id, id, "admin");
        } else if (family == ChargeMode.CLICK) {
            f.clicks.quotes.freezeBefore(new ClickQuoteRepository.Draft(id, UUID.randomUUID(), "admin", 1, 1, 2, 3, 4, 1, "点击",
                    new ClickPrice(BidType.CPC, new GspPrice(amount, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO, amount))), Instant.now().plusSeconds(30));
            f.clicks.settlement.prepare(id, "admin");
        } else {
            f.quotes.freezeBefore(new ConversionQuoteRepository.Draft(id, UUID.randomUUID(), "admin", 1, 1, 2, 3, 4, 1, "转化",
                    new ConversionPrice(new GspPrice(amount, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO, amount))), Instant.now().plusSeconds(30));
            f.settlement.prepare(id, "admin");
        }
        return id;
    }

    @Test void expiryNeverReleasesCapturedMoney() throws Exception {
        for (var family : ChargeMode.values()) {
            var id = capture(family);
            var payload = ConversionEventsFixture.JSON.readTree(event(family, id).payload());
            var qualification = UUID.fromString(payload.get("qualificationId").asText());
            assertEquals(Status.ALREADY_TERMINAL, service.recover(command(family, Action.EXPIRE, qualification)).status());
        }
        var snapshot = snapshots.inspect(new BillingReconciliation.Scope(1, f.cpm.today()));
        assertEquals(new BigDecimal("0.600006"), snapshot.account().chargedAmount());
        assertEquals(BillingReconciliation.FundsStatus.BALANCED, snapshot.fundsStatus());
    }

    @Test void allFamiliesExpireOnlyTheirOriginalAccountDayAndNeverReleaseEarlyOrTwice() throws Exception {
        // 只改变独占fixture新插入报价的签发时间，保持300秒协议且不用篡改不可变历史。
        f.jdbc.execute("CREATE FUNCTION recovery_short_quote() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN NEW.issued_at=clock_timestamp()-interval '297 seconds'; NEW.expires_at=NEW.issued_at+interval '300 seconds'; RETURN NEW; END; $$");
        for (var name : List.of("ad_cpm_quote", "ad_click_quote", "ad_conversion_quote")) {
            f.jdbc.execute("CREATE TRIGGER zz_recovery_short_quote BEFORE INSERT ON " + name + " FOR EACH ROW EXECUTE FUNCTION recovery_short_quote()");
        }
        var targets = new EnumMap<ChargeMode,UUID>(ChargeMode.class);
        for (var family : ChargeMode.values()) {
            var id = hold(family); targets.put(family, id);
            assertEquals(Status.NOT_ELIGIBLE, service.recover(command(family, Action.EXPIRE, id)).status());
            assertEquals(Status.NOT_FOUND, service.recover(new Command(UUID.randomUUID(), 2, f.cpm.today(), family, Action.EXPIRE, id, "隔离", "operator")).status());
        }
        assertEquals(new BigDecimal("0.300000"), snapshots.inspect(new BillingReconciliation.Scope(1, f.cpm.today())).account().heldAmount());
        long until = System.nanoTime() + Duration.ofSeconds(8).toNanos();
        while (System.nanoTime() < until && snapshots.inspect(new BillingReconciliation.Scope(1, f.cpm.today())).families().stream().mapToLong(BillingReconciliation.FamilySummary::expiredHeldCount).sum() < 3) Thread.sleep(100);
        for (var family : ChargeMode.values()) {
            var cmd = command(family, Action.EXPIRE, targets.get(family));
            var before = snapshots.inspect(new BillingReconciliation.Scope(1, f.cpm.today()));
            var broken = fresh(new BillingRecoveryRepository(f.cpm.sessions, ConversionEventsFixture.JSON) {
                @Override void complete(Command c, Status status) { super.complete(c, status); throw new IllegalStateException("到期结果提交前崩溃"); }
            });
            assertThrows(IllegalStateException.class, () -> broken.recover(cmd));
            assertThrows(ReconciliationTask.NotFound.class, () -> service.find(1, cmd.commandId()));
            assertEquals(before.account(), snapshots.inspect(new BillingReconciliation.Scope(1, f.cpm.today())).account());
            assertEquals(Status.EXPIRED, service.recover(cmd).status());
            assertEquals(Status.EXPIRED, service.recover(cmd).status());
            assertEquals(Status.ALREADY_TERMINAL, service.recover(command(family, Action.EXPIRE, targets.get(family))).status());
        }
        var snapshot = snapshots.inspect(new BillingReconciliation.Scope(1, f.cpm.today()));
        assertEquals(BigDecimal.ZERO.setScale(6), snapshot.account().heldAmount());
        assertEquals(BigDecimal.ZERO.setScale(6), snapshot.account().chargedAmount());
        assertEquals(BillingReconciliation.FundsStatus.BALANCED, snapshot.fundsStatus());
    }
}
