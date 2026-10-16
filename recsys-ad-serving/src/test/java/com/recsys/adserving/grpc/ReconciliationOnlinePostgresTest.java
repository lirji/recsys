package com.recsys.adserving.grpc;

import com.recsys.common.ad.billing.*;
import com.recsys.common.ad.delivery.SandboxDelivery.ChargeMode;
import com.recsys.proto.ReconciliationProtoMapper;
import com.recsys.proto.reconciliation.v1.*;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 真实TCP和PostgreSQL验证可信调用、稳定ID、任务日检查点及恢复查询，不模拟资金owner。 */
@Tag("money-chain") @Testcontainers
class ReconciliationOnlinePostgresTest {
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");
    ReconciliationOnlineFixture f;
    @BeforeEach void setup() throws Exception { f = new ReconciliationOnlineFixture(PG); }
    @AfterEach void close() throws Exception { f.close(); }
    ReconciliationScope scope() { return ReconciliationProtoMapper.toProto(new BillingReconciliation.Scope(Long.MAX_VALUE, f.day)); }
    ReconciliationTask.Create command() { return new ReconciliationTask.Create(Long.MAX_VALUE, UUID.randomUUID(), f.day.minusDays(1), f.day, "事故核查", "offline:operator"); }
    void status(Status.Code expected, org.junit.jupiter.api.function.Executable call) {
        assertEquals(expected, assertThrows(StatusRuntimeException.class, call).getStatus().getCode());
    }

    @Test void independentCallerMatrixRejectsBeforeAnyOwnerWrite() throws Exception {
        f.start(true);
        for (var caller : Arrays.asList(null, "recsys", "recsys-rec-engine", "other")) {
            var expected = caller == null ? Status.Code.UNAUTHENTICATED : Status.Code.PERMISSION_DENIED;
            status(expected, () -> f.stub(caller).inspect(scope()));
            status(expected, () -> f.stub(caller).createTask(ReconciliationProtoMapper.toProto(command())));
        }
        var advertiser = f.stub("recsys-advertiser");
        var snapshot = ReconciliationProtoMapper.fromProto(advertiser.inspect(scope()));
        assertEquals(new BigDecimal("123.123456"), snapshot.account().limitAmount());
        var identity = ReconciliationProtoMapper.task(Long.MAX_VALUE, UUID.randomUUID());
        var denied = List.<org.junit.jupiter.api.function.Executable>of(
                () -> advertiser.createTask(ReconciliationProtoMapper.toProto(command())), () -> advertiser.getTask(identity),
                () -> advertiser.advanceTask(identity), () -> advertiser.cancelTask(CancelReconciliationTask.getDefaultInstance()),
                () -> advertiser.getDays(identity), () -> advertiser.recover(BillingRecoveryCommand.getDefaultInstance()),
                () -> advertiser.getRecovery(BillingRecoveryIdentity.getDefaultInstance()));
        for (var action : denied) status(Status.Code.PERMISSION_DENIED, action);
        assertEquals(0, f.jdbc.queryForObject("SELECT count(*) FROM ad_reconciliation_task", Integer.class));
        assertEquals(0, f.jdbc.queryForObject("SELECT count(*) FROM ad_billing_recovery_command", Integer.class));
        var badSignature = SandboxReconciliationServiceGrpc.newBlockingStub(f.channel).withDeadlineAfter(5, java.util.concurrent.TimeUnit.SECONDS)
                .withInterceptors(new com.recsys.proto.security.InternalAuthGrpcClientInterceptor("x-internal-token", "recsys-offline", "SERVICE", 60, "wrong-signature"));
        status(Status.Code.UNAUTHENTICATED, () -> badSignature.inspect(scope()));
        assertThrows(IllegalArgumentException.class, () -> new SandboxReconciliationGrpcService(f.snapshots,
                ConversionOnlineFixture.provider(f.tasks), ConversionOnlineFixture.provider(f.recovery), "same", "same"));
        assertThrows(IllegalArgumentException.class, () -> new SandboxReconciliationGrpcService(f.snapshots,
                ConversionOnlineFixture.provider(f.tasks), ConversionOnlineFixture.provider(f.recovery), "recsys-advertiser", "recsys"));
    }
    @Test void twoDaysPersistAndRoundTripWithDistinctSnapshotTimes() throws Exception {
        f.start(true); var client = f.stub("recsys-offline"); var input = command(); var request = ReconciliationProtoMapper.toProto(input);
        var first = client.createTask(request); assertEquals(first, client.createTask(request));
        var id = ReconciliationProtoMapper.task(input.advertiserId(), input.taskId());
        var one = ReconciliationProtoMapper.fromProto(client.advanceTask(id)); assertEquals(1, one.completedDays());
        var last = ReconciliationProtoMapper.fromProto(client.advanceTask(id)); assertEquals(ReconciliationTask.State.COMPLETED, last.state());
        assertEquals(last, ReconciliationProtoMapper.fromProto(client.getTask(id)));
        var days = ReconciliationProtoMapper.fromProto(client.getDays(id)); assertEquals(2, days.size());
        assertNull(days.getFirst().snapshot().account()); assertEquals(BillingReconciliation.FundsStatus.ACCOUNT_MISSING, days.getFirst().snapshot().fundsStatus());
        assertEquals("0.000000", days.getLast().snapshot().chargedDelta()); assertNotEquals(days.getFirst().snapshot().observedAt(), days.getLast().snapshot().observedAt());
        status(Status.Code.ALREADY_EXISTS, () -> client.createTask(request.toBuilder().setReason("different").build()));
        status(Status.Code.NOT_FOUND, () -> client.getTask(id.toBuilder().setAdvertiserId(1).build()));
    }
    @Test void cancelAndRecoveryOriginalResultsSurviveRepeatedRequests() throws Exception {
        f.start(true); var client = f.stub("recsys-offline"); var input = command(); client.createTask(ReconciliationProtoMapper.toProto(input));
        var cancel = ReconciliationProtoMapper.toProto(new ReconciliationTask.Cancel(input.advertiserId(), input.taskId(), UUID.randomUUID(), "停止核查", "offline:operator"));
        var cancelled = client.cancelTask(cancel); assertEquals(cancelled, client.cancelTask(cancel));
        assertEquals("CANCELLED", cancelled.getState());
        var recovery = new BillingRecovery.Command(UUID.randomUUID(), Long.MAX_VALUE, f.day, ChargeMode.CONVERSION, BillingRecovery.Action.EXPIRE,
                UUID.randomUUID(), "原目标恢复", "offline:operator");
        var wire = ReconciliationProtoMapper.toProto(recovery); var result = client.recover(wire);
        assertEquals(BillingRecovery.Status.NOT_FOUND, ReconciliationProtoMapper.fromProto(result).status());
        assertEquals(result, client.recover(wire));
        var query = BillingRecoveryIdentity.newBuilder().setAdvertiserId(Long.MAX_VALUE).setCommandId(recovery.commandId().toString()).build();
        assertEquals(result, client.getRecovery(query));
        status(Status.Code.NOT_FOUND, () -> client.getRecovery(query.toBuilder().setAdvertiserId(1).build()));
        status(Status.Code.ALREADY_EXISTS, () -> client.recover(wire.toBuilder().setReason("changed").build()));
        assertEquals(1, f.jdbc.queryForObject("SELECT count(*) FROM ad_billing_recovery_command", Integer.class));
    }
    @Test void disabledManagementStillAllowsReadOnlyInspection() throws Exception {
        f.start(false); var client = f.stub("recsys-offline"); assertEquals(3, client.inspect(scope()).getFamiliesCount());
        status(Status.Code.FAILED_PRECONDITION, () -> client.createTask(ReconciliationProtoMapper.toProto(command())));
        status(Status.Code.FAILED_PRECONDITION, () -> client.recover(BillingRecoveryCommand.getDefaultInstance()));
    }
    @Test void malformedRequestsAndDatabaseFailureAreExplicitSafeFailures() throws Exception {
        f.start(true); var client = f.stub("recsys-offline");
        status(Status.Code.INVALID_ARGUMENT, () -> client.inspect(scope().toBuilder().setBudgetDate("2026-2-31").build()));
        status(Status.Code.INVALID_ARGUMENT, () -> client.getTask(ReconciliationTaskIdentity.newBuilder().setAdvertiserId(1).setTaskId("bad").build()));
        status(Status.Code.INVALID_ARGUMENT, () -> client.recover(BillingRecoveryCommand.getDefaultInstance()));
        f.jdbc.execute("ALTER TABLE ad_budget_account RENAME TO unavailable_account_test_only");
        var failure = assertThrows(StatusRuntimeException.class, () -> client.inspect(scope()));
        assertEquals(Status.Code.UNAVAILABLE, failure.getStatus().getCode());
        assertFalse(failure.getMessage().contains("ad_budget_account")); assertTrue(failure.getMessage().contains("结果未知"));
    }
}
