package com.recsys.offline;

import com.recsys.common.ad.billing.*;
import com.recsys.common.ad.delivery.SandboxDelivery.ChargeMode;
import com.recsys.common.error.*;
import com.recsys.platform.security.AuthProperties;
import com.recsys.proto.ReconciliationProtoMapper;
import com.recsys.proto.reconciliation.v1.*;
import com.recsys.proto.security.*;
import io.grpc.*;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.*;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

/** CLI通过真实TCP调用受控协议替身，验证参数/身份/截止与不重试；数据库原子性由owner专项证明。 */
@Tag("money-chain")
class BillingReconciliationJobTest {
    static final LocalDate DAY = LocalDate.of(2026, 10, 16);
    static final Instant NOW = Instant.parse("2026-10-16T00:00:00Z");
    static final long ADVERTISER = Long.MAX_VALUE;
    static final String SECRET = "isolated-offline-reconciliation-test-secret";
    final AtomicInteger calls = new AtomicInteger();
    final AtomicBoolean unavailable = new AtomicBoolean(), invalidScope = new AtomicBoolean(), deadlineSeen = new AtomicBoolean();
    final AtomicReference<BillingRecoveryCommand> observedRecovery = new AtomicReference<>();
    final AtomicReference<CreateReconciliationTask> observedCreate = new AtomicReference<>();
    Server server; ManagedChannel channel; BillingReconciliationJob job;
    @BeforeEach void setup() throws Exception {
        server = ServerBuilder.forPort(0).addService(ServerInterceptors.intercept(new Endpoint(),
                new InternalAuthGrpcServerInterceptor("x-internal-token", SECRET, true))).build().start();
        channel = ManagedChannelBuilder.forAddress("localhost", server.getPort()).usePlaintext().build();
        job = new BillingReconciliationJob("configured-operator", 1000);
        ReflectionTestUtils.setField(job, "stub", SandboxReconciliationServiceGrpc.newBlockingStub(channel)
                .withInterceptors(new InternalAuthGrpcClientInterceptor("x-internal-token", "recsys-offline", "SERVICE", 60, SECRET)));
    }
    @AfterEach void close() throws Exception {
        channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS); server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }
    DefaultApplicationArguments args(String action, String... extra) {
        var all = new ArrayList<>(List.of("--action=" + action, "--advertiser-id=" + ADVERTISER)); all.addAll(List.of(extra));
        return new DefaultApplicationArguments(all.toArray(String[]::new));
    }
    ReconciliationTask.Task task(CreateReconciliationTask c, ReconciliationTask.State state, UUID cancel, String reason) {
        var input = ReconciliationProtoMapper.fromProto(c);
        int count = state == ReconciliationTask.State.COMPLETED ? 1 : 0;
        return new ReconciliationTask.Task(input.taskId(), invalidScope.get() ? 2 : input.advertiserId(), input.startDate(), input.endDate(), input.reason(), input.actor(), NOW,
                state, DAY.plusDays(count), count, 0, 1, NOW, null, null, "configured-operator", NOW, cancel, reason);
    }
    class Endpoint extends SandboxReconciliationServiceGrpc.SandboxReconciliationServiceImplBase {
        <T> void reply(StreamObserver<T> observer, T value) {
            calls.incrementAndGet(); deadlineSeen.set(Context.current().getDeadline() != null);
            assertEquals("recsys-offline", InternalAuthGrpcServerInterceptor.CALLER_SUBJECT.get());
            if (unavailable.get()) observer.onError(Status.UNAVAILABLE.withDescription("SECRET-DB-PASSWORD").asRuntimeException());
            else { observer.onNext(value); observer.onCompleted(); }
        }
        @Override public void inspect(ReconciliationScope r, StreamObserver<ReconciliationSnapshot> o) {
            var families = Arrays.stream(ChargeMode.values()).map(f -> new BillingReconciliation.FamilySummary(f, 0, "0.000000", 0, 0, "0.000000", 0, 0,
                    0, 0, 0, null, 0, "0.000000", 0, 0, 0, null, false, 0, "0.000000")).toList();
            reply(o, ReconciliationProtoMapper.toProto(new BillingReconciliation.Snapshot(ReconciliationProtoMapper.fromProto(r), NOW, null, families)));
        }
        @Override public void createTask(CreateReconciliationTask r, StreamObserver<ReconciliationTaskReply> o) {
            observedCreate.set(r); reply(o, ReconciliationProtoMapper.toProto(task(r, ReconciliationTask.State.READY, null, null)));
        }
        @Override public void getTask(ReconciliationTaskIdentity r, StreamObserver<ReconciliationTaskReply> o) {
            reply(o, ReconciliationProtoMapper.toProto(task(observedCreate.get(), ReconciliationTask.State.READY, null, null)));
        }
        @Override public void advanceTask(ReconciliationTaskIdentity r, StreamObserver<ReconciliationTaskReply> o) {
            reply(o, ReconciliationProtoMapper.toProto(task(observedCreate.get(), ReconciliationTask.State.COMPLETED, null, null)));
        }
        @Override public void cancelTask(CancelReconciliationTask r, StreamObserver<ReconciliationTaskReply> o) {
            reply(o, ReconciliationProtoMapper.toProto(task(observedCreate.get(), ReconciliationTask.State.CANCELLED, BudgetCommand.parseId(r.getCommandId()), r.getReason())));
        }
        @Override public void getDays(ReconciliationTaskIdentity r, StreamObserver<ReconciliationDaysReply> o) { reply(o, ReconciliationDaysReply.newBuilder().setTask(r).build()); }
        @Override public void recover(BillingRecoveryCommand r, StreamObserver<BillingRecoveryReply> o) {
            observedRecovery.set(r); reply(o, ReconciliationProtoMapper.toProto(new BillingRecovery.Result(ReconciliationProtoMapper.fromProto(r), BillingRecovery.Status.EXPIRED, NOW, NOW)));
        }
        @Override public void getRecovery(BillingRecoveryIdentity r, StreamObserver<BillingRecoveryReply> o) {
            reply(o, ReconciliationProtoMapper.toProto(new BillingRecovery.Result(ReconciliationProtoMapper.fromProto(observedRecovery.get()), BillingRecovery.Status.EXPIRED, NOW, NOW)));
        }
    }

    @Test void allEightActionsUseOneRpcAndTrustedConfiguredActor() {
        var taskId = UUID.randomUUID(); var commandId = UUID.randomUUID(); var target = UUID.randomUUID();
        var actions = List.of(args("inspect", "--budget-date=" + DAY), args("create", "--task-id=" + taskId, "--start-date=" + DAY, "--end-date=" + DAY, "--reason=核查"),
                args("get", "--task-id=" + taskId), args("advance", "--task-id=" + taskId), args("days", "--task-id=" + taskId),
                args("cancel", "--task-id=" + taskId, "--command-id=" + UUID.randomUUID(), "--reason=取消"),
                args("recover", "--budget-date=" + DAY, "--command-id=" + commandId, "--family=CLICK", "--recovery-action=EXPIRE", "--target-id=" + target, "--reason=恢复"),
                args("get-recovery", "--command-id=" + commandId));
        for (int i = 0; i < actions.size(); i++) { assertNotNull(job.execute(actions.get(i))); assertEquals(i + 1, calls.get()); assertTrue(deadlineSeen.get()); }
        assertEquals("configured-operator", observedCreate.get().getActor()); assertEquals("configured-operator", observedRecovery.get().getActor());
        assertEquals(commandId.toString(), observedRecovery.get().getCommandId()); assertEquals(target.toString(), observedRecovery.get().getTargetId());
    }
    @Test void missingIdsMalformedDatesAndCliActorNeverCallOwner() {
        for (var bad : List.of(args("recover", "--budget-date=" + DAY), args("inspect", "--budget-date=2026-2-31"),
                args("inspect", "--budget-date=" + DAY, "--actor=forged"), args("unknown"), args("get", "--task-id=1-1-1-1-1"))) {
            assertEquals(ErrorCode.JOB_INVALID_ARGUMENT, assertThrows(PublicErrorException.class, () -> job.execute(bad)).errorCode());
        }
        assertEquals(0, calls.get());
    }
    @Test void unavailableIsUnknownAndNeverAutomaticallyRetriesOrReplacesId() {
        unavailable.set(true); var commandId = UUID.randomUUID();
        var failure = assertThrows(PublicErrorException.class, () -> job.execute(args("recover", "--budget-date=" + DAY,
                "--command-id=" + commandId, "--family=CONVERSION", "--recovery-action=EXPIRE", "--target-id=" + UUID.randomUUID(), "--reason=恢复")));
        assertEquals(ErrorCode.JOB_RECONCILIATION_RESULT_UNKNOWN, failure.errorCode()); assertFalse(failure.getMessage().contains("SECRET"));
        assertFalse(failure.getCause().getMessage().contains("SECRET"));
        assertEquals(1, calls.get()); assertEquals(commandId.toString(), observedRecovery.get().getCommandId());
        assertEquals(failure, JobFailures.publicFailure(failure));
    }
    @Test void ownerReplyOutsideRequestedAccountIsRejected() {
        invalidScope.set(true);
        assertEquals(ErrorCode.JOB_RECONCILIATION_REPLY_INVALID, assertThrows(PublicErrorException.class, () -> job.execute(
                args("create", "--task-id=" + UUID.randomUUID(), "--start-date=" + DAY, "--end-date=" + DAY, "--reason=核查"))).errorCode());
        assertEquals(1, calls.get());
    }
    @Test void defaultDisabledAndUnsafeIdentityOrMissingActorCannotStart() {
        new ApplicationContextRunner().withUserConfiguration(BillingReconciliationJob.class, BillingReconciliationConfiguration.class)
                .run(context -> { assertNull(context.getStartupFailure()); assertTrue(context.getBeansOfType(BillingReconciliationJob.class).isEmpty()); });
        var config = new BillingReconciliationConfiguration(); var auth = new AuthProperties();
        assertThrows(IllegalArgumentException.class, () -> config.reconciliationIdentity(auth, "recsys-offline", "recsys-advertiser"));
        auth.setServiceName("recsys-offline"); assertNotNull(config.reconciliationIdentity(auth, "recsys-offline", "recsys-advertiser"));
        auth.setEnabled(false); assertThrows(IllegalArgumentException.class, () -> config.reconciliationIdentity(auth, "recsys-offline", "recsys-advertiser"));
        assertThrows(IllegalArgumentException.class, () -> new BillingReconciliationJob("", 1000));
        assertThrows(IllegalArgumentException.class, () -> new BillingReconciliationJob("operator", 30001));
    }

    @Test void actualCliBootstrapNeedsOnlyOwnerRpcAndClosesAfterOneCall() {
        OfflineApplication.main(new String[]{"--job=billing-reconciliation", "--action=inspect", "--advertiser-id=" + ADVERTISER,
                "--budget-date=" + DAY, "--recsys.offline.reconciliation.enabled=true", "--recsys.offline.reconciliation.actor=configured-operator",
                "--recsys.security.enabled=true", "--recsys.security.service-name=recsys-offline", "--recsys.security.internal-header=x-internal-token",
                "--recsys.security.internal-secret=" + SECRET, "--grpc.client.ad-serving.address=static://localhost:" + server.getPort(),
                "--grpc.client.ad-serving.negotiation-type=PLAINTEXT", "--spring.datasource.url=jdbc:postgresql://127.0.0.1:1/unavailable",
                "--spring.data.redis.port=1", "--spring.main.banner-mode=off"});
        assertEquals(1, calls.get()); assertTrue(deadlineSeen.get());
    }
}
