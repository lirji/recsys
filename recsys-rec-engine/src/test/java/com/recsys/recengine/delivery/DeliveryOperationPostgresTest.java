package com.recsys.recengine.delivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recsys.ad.AdProperties;
import com.recsys.common.ad.*;
import com.recsys.common.ad.billing.Reservation;
import com.recsys.common.ad.delivery.SandboxDelivery.*;
import com.recsys.common.query.*;
import com.recsys.recengine.ad.AdServingGateway;
import com.recsys.recengine.experiment.ExperimentService;
import com.recsys.recengine.delivery.DeliveryOperation.*;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.mybatis.spring.SqlSessionTemplate;
import com.recsys.persistence.spring.SpringSqlSessions;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 真实 engine PG 检查不可变整页、领取竞争、fencing 和丢响应后原子进度恢复。 */
@Tag("money-chain")
@Testcontainers
class DeliveryOperationPostgresTest {
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");
    static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    static JdbcTemplate jdbc;
    static SqlSessionTemplate sessions;
    static DeliveryOperationRepository repository;
    AdServingGateway gateway;
    QueryUnderstandingService queries;
    ExperimentService experiments;
    @BeforeAll static void migrate() throws Exception {
        var ds = new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()); jdbc = new JdbcTemplate(ds);
        sessions = new SqlSessionTemplate(SpringSqlSessions.create(ds, "classpath*:mapper/recengine/primary/**/*.xml"));
        try (var c = ds.getConnection(); var stmt = c.createStatement()) {
            stmt.execute("SET recsys.migration_sha256 = '" + DeliveryOperationRepository.migrationSha256() + "'");
            ScriptUtils.executeSqlScript(c, new ClassPathResource(DeliveryOperationRepository.MIGRATION));
        }
        repository = new DeliveryOperationRepository(sessions, JSON); repository.verifySchema();
    }
    @BeforeEach void mocks() {
        gateway = mock(AdServingGateway.class); queries = mock(QueryUnderstandingService.class); experiments = mock(ExperimentService.class);
        when(queries.parse(anyString(), anyLong())).thenAnswer(i -> new StructuredQuery(i.getArgument(0), i.getArgument(0), null, null, null, null));
    }
    DeliveryOperationService fresh(DeliveryOperationRepository repo) { return new DeliveryOperationService(repo, queries, experiments, new AdProperties(), gateway); }
    Request request() { return new Request(UUID.randomUUID(), "admin", 1, "手机", 2, "search"); }
    Candidate candidate(long id) {
        return new Candidate(UUID.randomUUID(), new SponsoredAd(id, id, 1, 0, "title", AdChannel.KW_EXACT, 1, 1, 1, 1, 1, 1, 1, 1, 0, "CPM"), "opaque-test-proof");
    }
    Prepared result(Candidate candidate) { return new Prepared(candidate.childId(), Status.QUALIFIED, "1.000000", "qualification-test", Instant.now().plusSeconds(300)); }

    @Test void responseLostAfterOwnerCommitRestoresExactWholePageWithoutNewCandidates() {
        var request = request(); var first = candidate(1); var second = candidate(2);
        var firstResult = result(first); var secondResult = result(second);
        when(gateway.sandboxCandidates(any())).thenReturn(List.of(first, second));
        var ownerResults = new ConcurrentHashMap<UUID, Prepared>();
        when(gateway.findSandboxPreparation(any(), anyString())).thenAnswer(i -> Optional.ofNullable(ownerResults.get(i.getArgument(0))));
        when(gateway.prepareSandbox(any())).thenAnswer(i -> {
            Prepare p = i.getArgument(0);
            var r = p.childId().equals(first.childId()) ? firstResult : secondResult;
            ownerResults.put(p.childId(), r);
            if (p.childId().equals(second.childId())) throw new IllegalStateException("lost reply after commit");
            return r;
        });
        assertThrows(IllegalStateException.class, () -> fresh(repository).deliver(request));
        var partial = repository.find(request.operationId(), "admin").orElseThrow();
        assertEquals(firstResult, partial.plan().get(0).result()); assertNull(partial.plan().get(1).result());
        when(gateway.sandboxCandidates(any())).thenReturn(List.of(candidate(99)));
        var restored = fresh(new DeliveryOperationRepository(sessions, JSON)).deliver(request);
        assertEquals(State.COMPLETE, restored.state());
        assertEquals(List.of(new Entry(1, first, firstResult), new Entry(2, second, secondResult)), restored.plan());
        verify(gateway, times(1)).sandboxCandidates(any()); verify(gateway, times(2)).prepareSandbox(any());
        assertEquals(restored, fresh(repository).deliver(request));
    }
    @Test void freezeFailureCannotPrepareUnpersistedPlan() {
        var request = request(); when(gateway.sandboxCandidates(any())).thenReturn(List.of(candidate(1)));
        var failure = new DeliveryOperationRepository(sessions, JSON) {
            @Override public boolean freeze(DeliveryOperation operation, List<Entry> plan) { throw new IllegalStateException("database failure"); }
        };
        assertThrows(IllegalStateException.class, () -> fresh(failure).deliver(request));
        verify(gateway, never()).prepareSandbox(any());
        assertNull(repository.find(request.operationId(), "admin").orElseThrow().plan());
    }
    /** RETURNING领取必须服从调用方事务回滚，不能留下已消耗的fence或租约。 */
    @Test void rolledBackAcquireDoesNotConsumeFenceOrLease() {
        var request=request();repository.create(request,"original",.1);
        var tx=new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()));
        tx.setTimeout(5);
        tx.executeWithoutResult(status->{
            assertEquals(1,repository.acquire(request.operationId(),"admin").orElseThrow().fence());
            assertTrue(repository.acquire(request.operationId(),"admin").isEmpty());
            status.setRollbackOnly();
        });
        assertEquals(0,repository.find(request.operationId(),"admin").orElseThrow().fence());
        assertEquals(1,repository.acquire(request.operationId(),"admin").orElseThrow().fence());
        assertTrue(repository.acquire(request.operationId(),"admin").isEmpty());
    }
    @Test void staleLeaseCannotSavePlanProgressOrReleaseNewLease() {
        var request = request(); repository.create(request, "original", .1);
        var stale = repository.acquire(request.operationId(), "admin").orElseThrow();
        jdbc.update("UPDATE engine_ad_delivery SET lease_until=clock_timestamp()-interval '1 second' WHERE operation_id=?", request.operationId());
        var active = repository.acquire(request.operationId(), "admin").orElseThrow();
        var plan = List.of(new Entry(1, candidate(1), null));
        assertFalse(repository.freeze(stale, plan)); assertTrue(repository.freeze(active, plan));
        repository.relinquish(request.operationId(), stale.fence());
        assertTrue(repository.acquire(request.operationId(), "admin").isEmpty());
        var staleWithPlan = new DeliveryOperation(request, "original", .1, State.PREPARING, stale.fence(), plan);
        assertFalse(repository.progress(staleWithPlan, List.of(new Entry(1, plan.getFirst().candidate(), result(plan.getFirst().candidate())))));
    }
    @Test void concurrentAcquireHasOneWinnerAndChangesNeverOverwriteOriginal() throws Exception {
        var request = request(); repository.create(request, "first", .1);
        assertEquals("first", repository.create(request, "second", .9).bucket());
        try (var pool = Executors.newFixedThreadPool(8)) {
            var calls = new ArrayList<Future<Boolean>>();
            for (int i = 0; i < 8; i++) calls.add(pool.submit(() -> repository.acquire(request.operationId(), "admin").isPresent()));
            int won = 0; for (var f : calls) if (f.get(5, TimeUnit.SECONDS)) won++;
            assertEquals(1, won);
        }
        assertThrows(Conflict.class, () -> fresh(repository).deliver(new Request(request.operationId(), "admin", 1, "different", 2, "search")));
        assertThrows(Reservation.AccessDenied.class, () -> repository.find(request.operationId(), "other"));
    }
    @Test void rejectedItemKeepsItsSlotAndDoesNotTriggerReplacement() {
        var request = request(); var first = candidate(1); var second = candidate(2);
        when(gateway.sandboxCandidates(any())).thenReturn(List.of(first, second));
        var rejection = new Prepared(first.childId(), Status.INSUFFICIENT_BUDGET, "1.000000", "", Instant.now().plusSeconds(300));
        when(gateway.prepareSandbox(any())).thenAnswer(i -> ((Prepare)i.getArgument(0)).childId().equals(first.childId()) ? rejection : result(second));
        var finished = fresh(repository).deliver(request);
        assertEquals(State.COMPLETE, finished.state()); assertEquals(1, finished.plan().getFirst().slot());
        assertEquals(rejection, finished.plan().getFirst().result()); assertEquals(2, finished.plan().getLast().slot());
        verify(gateway, times(1)).sandboxCandidates(any());
    }
    @Test void normalEmptyPlanCompletesButDependencyFailureRemainsPreparing() {
        var empty = request(); when(gateway.sandboxCandidates(any())).thenReturn(List.of());
        assertEquals(State.COMPLETE, fresh(repository).deliver(empty).state());
        var failed = request(); when(gateway.sandboxCandidates(any())).thenThrow(new IllegalStateException("down"));
        assertThrows(IllegalStateException.class, () -> fresh(repository).deliver(failed));
        assertEquals(State.PREPARING, repository.find(failed.operationId(), "admin").orElseThrow().state());
    }
    @Test void databaseChecksCommentsAndImmutableProgressAreEnforced() {
        var request = request(); repository.create(request, "b", .1); var claimed = repository.acquire(request.operationId(), "admin").orElseThrow();
        repository.freeze(claimed, List.of(new Entry(1, candidate(1), null)));
        var frozen = repository.find(request.operationId(), "admin").orElseThrow();
        assertThrows(IllegalArgumentException.class, () -> repository.progress(frozen, List.of(new Entry(1, candidate(2), null))));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> jdbc.update("UPDATE engine_ad_delivery SET fence=-1 WHERE operation_id=?", request.operationId()));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM pg_attribute WHERE attrelid='engine_ad_delivery'::regclass AND attnum>0 AND NOT attisdropped AND col_description(attrelid,attnum) IS NULL", Integer.class));
    }
}
