package com.recsys.proto;

import com.google.protobuf.Timestamp;
import com.recsys.common.ad.billing.*;
import com.recsys.common.ad.billing.BillingReconciliation.*;
import com.recsys.common.ad.delivery.SandboxDelivery.ChargeMode;
import com.recsys.proto.reconciliation.v1.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 协议往返验证精确小数、缺失语义及故意构造的坏状态，不能用protobuf默认值伪装成功。 */
@Tag("money-chain")
class ReconciliationProtoMapperTest {
    static final LocalDate DAY = LocalDate.of(2026, 10, 16);
    static final Instant NOW = Instant.parse("2026-10-16T00:01:02.123456Z");
    Snapshot snapshot(boolean account) {
        var families = Arrays.stream(ChargeMode.values()).map(f -> new FamilySummary(f, 0, "0.000000", 0,
                1, "0.000001", 0, 0, 1, 0, 0, NOW, 0, "0.000000", 1, 0, 0, null, false, 0, "0.000000")).toList();
        return new Snapshot(new Scope(Long.MAX_VALUE, DAY), NOW, account ? new BudgetAccount(Long.MAX_VALUE, DAY,
                new BigDecimal("999999999.123456"), BigDecimal.ZERO, new BigDecimal("0.000003"), 9) : null, families);
    }
    @Test void exactMoneyLargeIdentityAndMissingAccountRoundTrip() {
        for (boolean present : List.of(false, true)) {
            var snapshot = snapshot(present); var wire = ReconciliationProtoMapper.toProto(snapshot);
            assertEquals(snapshot, ReconciliationProtoMapper.fromProto(wire));
            assertEquals(present, wire.hasAccount()); assertEquals(present, wire.hasHeldDelta());
            assertEquals(Long.MAX_VALUE, wire.getScope().getAdvertiserId());
            assertEquals("LAGGING", wire.getFamilies(0).getProjectionStatus());
            assertFalse(wire.getFamilies(0).hasLastAppliedAt());
        }
    }
    @Test void badSnapshotNeverBecomesBalancedOrZero() {
        var good = ReconciliationProtoMapper.toProto(snapshot(true));
        var bad = List.of(good.toBuilder().setSchemaVersion(2).build(), good.toBuilder().setTrafficClass("PRODUCTION").build(),
                good.toBuilder().setHeldDelta("1.000000").build(), good.toBuilder().setFundsStatus("MISMATCH").build(),
                good.toBuilder().clearChargedDelta().build(), good.toBuilder().clearObservedAt().build(),
                good.toBuilder().setObservedAt(Timestamp.newBuilder().setNanos(-1)).build(),
                good.toBuilder().setAccount(good.getAccount().toBuilder().setLimitAmount("999999999.1234560")).build(),
                good.toBuilder().setFamilies(0, good.getFamilies(0).toBuilder().setProjectionStatus("CAUGHT_UP")).build(),
                good.toBuilder().setFamilies(0, good.getFamilies(0).toBuilder().setLedgerCount(-1)).build(),
                good.toBuilder().removeFamilies(2).build(), good.toBuilder().setScope(good.getScope().toBuilder().setAdvertiserId(7)).build());
        bad.forEach(value -> assertThrows(IllegalArgumentException.class, () -> ReconciliationProtoMapper.fromProto(value)));
        assertThrows(IllegalArgumentException.class, () -> ReconciliationProtoMapper.fromProto(ReconciliationProtoMapper.toProto(snapshot(false)).toBuilder().setChargedDelta("0.000000").build()));
    }
    ReconciliationTask.Task task() {
        return new ReconciliationTask.Task(UUID.randomUUID(), Long.MAX_VALUE, DAY, DAY.plusDays(1), "日常核查", "offline:alice", NOW,
                ReconciliationTask.State.RUNNING, DAY.plusDays(1), 1, 2, 3, NOW, NOW.plusSeconds(30), null, ReconciliationTask.WORKER, NOW, null, null);
    }
    @Test void taskRoundTripAndInvalidCheckpointLeaseCancellationFailClosed() {
        var task = task(); var wire = ReconciliationProtoMapper.toProto(task);
        assertEquals(task, ReconciliationProtoMapper.fromProto(wire));
        for (var bad : List.of(wire.toBuilder().setCompletedDays(2).build(), wire.toBuilder().setAttempts(21).build(),
                wire.toBuilder().setVersion(-1).build(), wire.toBuilder().clearLeaseUntil().build(), wire.toBuilder().setState("FUTURE").build(),
                wire.toBuilder().setCancelReason("reason-without-command").build(), wire.toBuilder().setLastFailure("FUTURE").build(),
                wire.toBuilder().clearCreatedAt().build())) {
            assertThrows(IllegalArgumentException.class, () -> ReconciliationProtoMapper.fromProto(bad));
        }
    }
    @Test void dayResultsRequireScopeFenceAndContiguousOrder() {
        var task = task(); var snapshot = snapshot(true); var day = new ReconciliationTask.Day(DAY, 2, snapshot);
        var reply = ReconciliationProtoMapper.days(Long.MAX_VALUE, task.taskId(), List.of(day));
        assertEquals(List.of(day), ReconciliationProtoMapper.fromProto(reply));
        assertThrows(IllegalArgumentException.class, () -> ReconciliationProtoMapper.fromProto(reply.toBuilder().addDays(reply.getDays(0)).build()));
        assertThrows(IllegalArgumentException.class, () -> ReconciliationProtoMapper.fromProto(reply.toBuilder().setTask(reply.getTask().toBuilder().setAdvertiserId(3)).build()));
        assertThrows(IllegalArgumentException.class, () -> ReconciliationProtoMapper.fromProto(reply.toBuilder().setDays(0, reply.getDays(0).toBuilder().setFence(0)).build()));
    }
    @Test void recoveryKeepsOriginalCommandAndRejectsPendingOrWrongActionResult() {
        var c = new BillingRecovery.Command(UUID.randomUUID(), Long.MAX_VALUE, DAY, ChargeMode.CONVERSION, BillingRecovery.Action.REPUBLISH,
                UUID.randomUUID(), "原事件续发", "offline:alice");
        var result = new BillingRecovery.Result(c, BillingRecovery.Status.REQUEUED, NOW, NOW.plusSeconds(1));
        var wire = ReconciliationProtoMapper.toProto(result); assertEquals(result, ReconciliationProtoMapper.fromProto(wire));
        for (var bad : List.of(wire.toBuilder().setStatus("PENDING").build(), wire.toBuilder().setStatus("EXPIRED").build(),
                wire.toBuilder().clearCompletedAt().build(), wire.toBuilder().setCommand(wire.getCommand().toBuilder().setFamily("OTHER")).build())) {
            assertThrows(IllegalArgumentException.class, () -> ReconciliationProtoMapper.fromProto(bad));
        }
    }
}
