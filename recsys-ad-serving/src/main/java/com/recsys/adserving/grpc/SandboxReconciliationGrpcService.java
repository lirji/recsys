package com.recsys.adserving.grpc;

import com.recsys.ad.billing.*;
import com.recsys.common.ad.billing.*;
import com.recsys.common.error.ErrorCode;
import com.recsys.proto.ReconciliationProtoMapper;
import com.recsys.proto.grpc.GrpcErrors;
import com.recsys.proto.reconciliation.v1.*;
import com.recsys.proto.security.InternalAuthGrpcServerInterceptor;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import java.util.UUID;
import java.util.function.Supplier;

/** 对账owner的可信入口；advertiser仅可Inspect，任务和恢复始终要求独立offline身份。 */
@GrpcService
@ConditionalOnProperty(prefix = "recsys.ad.billing", name = {"sandbox-enabled", "reconciliation-enabled"}, havingValue = "true")
public class SandboxReconciliationGrpcService extends SandboxReconciliationServiceGrpc.SandboxReconciliationServiceImplBase {
    private static final Logger log = LoggerFactory.getLogger(SandboxReconciliationGrpcService.class);
    private final ReconciliationService snapshots;
    private final ObjectProvider<ReconciliationTaskService> tasks;
    private final ObjectProvider<BillingRecoveryService> recovery;
    private final String advertiserCaller;
    private final String offlineCaller;

    /** 同名或通用身份不能隔离读写权限，启动时拒绝含糊配置。 */
    public SandboxReconciliationGrpcService(ReconciliationService snapshots, ObjectProvider<ReconciliationTaskService> tasks,
            ObjectProvider<BillingRecoveryService> recovery,
            @Value("${recsys.ad.billing.advertiser-caller:recsys-advertiser}") String advertiserCaller,
            @Value("${recsys.ad.billing.offline-caller:recsys-offline}") String offlineCaller) {
        caller(advertiserCaller); caller(offlineCaller);
        if (advertiserCaller.equals(offlineCaller)) throw new IllegalArgumentException("对账读写调用方必须独立");
        this.snapshots = snapshots; this.tasks = tasks; this.recovery = recovery;
        this.advertiserCaller = advertiserCaller; this.offlineCaller = offlineCaller;
    }

    /** 原预算日一次一致观察，不驱动任何恢复或收费。 */
    @Override public void inspect(ReconciliationScope request, StreamObserver<ReconciliationSnapshot> observer) {
        respond(observer, "inspect", false, () -> ReconciliationProtoMapper.toProto(snapshots.inspect(parse(() -> ReconciliationProtoMapper.fromProto(request)))));
    }
    /** 稳定任务ID约束首次范围，重试不能重开新的日期任务。 */
    @Override public void createTask(CreateReconciliationTask request, StreamObserver<ReconciliationTaskReply> observer) {
        respond(observer, "createTask", true, () -> ReconciliationProtoMapper.toProto(required(tasks).create(parse(() -> ReconciliationProtoMapper.fromProto(request)))));
    }
    /** 查询同样要求offline身份及广告主范围。 */
    @Override public void getTask(ReconciliationTaskIdentity request, StreamObserver<ReconciliationTaskReply> observer) {
        respond(observer, "getTask", true, () -> ReconciliationProtoMapper.toProto(required(tasks).find(request.getAdvertiserId(), identity(request))));
    }
    /** 每次最多核查一天，RPC截止不等于数据库命令被撤销。 */
    @Override public void advanceTask(ReconciliationTaskIdentity request, StreamObserver<ReconciliationTaskReply> observer) {
        respond(observer, "advanceTask", true, () -> ReconciliationProtoMapper.toProto(required(tasks).advance(request.getAdvertiserId(), identity(request))));
    }
    /** 取消保留已保存报告，原取消命令可重复查询。 */
    @Override public void cancelTask(CancelReconciliationTask request, StreamObserver<ReconciliationTaskReply> observer) {
        respond(observer, "cancelTask", true, () -> ReconciliationProtoMapper.toProto(required(tasks).cancel(parse(() -> ReconciliationProtoMapper.fromProto(request)))));
    }
    /** 固定最多31份报告，不开放无界历史扫描。 */
    @Override public void getDays(ReconciliationTaskIdentity request, StreamObserver<ReconciliationDaysReply> observer) {
        respond(observer, "getDays", true, () -> {
            var id = identity(request);
            return ReconciliationProtoMapper.days(request.getAdvertiserId(), id, required(tasks).days(request.getAdvertiserId(), id));
        });
    }
    /** 恢复目标及命令一次提交；异常维持未知，不能自动换ID重试。 */
    @Override public void recover(BillingRecoveryCommand request, StreamObserver<BillingRecoveryReply> observer) {
        respond(observer, "recover", true, () -> ReconciliationProtoMapper.toProto(required(recovery).recover(parse(() -> ReconciliationProtoMapper.fromProto(request)))));
    }
    /** 未观察到结果不代表原请求未提交，仍保留相同命令标识。 */
    @Override public void getRecovery(BillingRecoveryIdentity request, StreamObserver<BillingRecoveryReply> observer) {
        respond(observer, "getRecovery", true, () -> {
            var id = parse(() -> {
                var parsed = BudgetCommand.parseId(request.getCommandId()); ReconciliationTask.identity(request.getAdvertiserId(), parsed); return parsed;
            });
            return ReconciliationProtoMapper.toProto(required(recovery).find(request.getAdvertiserId(), id));
        });
    }

    private static UUID identity(ReconciliationTaskIdentity request) {
        return parse(() -> {
            var id = BudgetCommand.parseId(request.getTaskId()); ReconciliationTask.identity(request.getAdvertiserId(), id); return id;
        });
    }
    private static <T> T required(ObjectProvider<T> provider) {
        var value = provider.getIfAvailable();
        if (value == null) throw GrpcErrors.exception(Status.Code.FAILED_PRECONDITION, ErrorCode.RPC_RECONCILIATION_DISABLED);
        return value;
    }
    private static <T> T parse(Supplier<T> action) {
        try { return action.get(); }
        catch (IllegalArgumentException | NullPointerException ex) {
            throw GrpcErrors.exception(Status.Code.INVALID_ARGUMENT, ErrorCode.RPC_RECONCILIATION_INVALID_ARGUMENT);
        }
    }
    private <T> void respond(StreamObserver<T> observer, String operation, boolean management, Supplier<T> action) {
        T result;
        try {
            var caller = InternalAuthGrpcServerInterceptor.CALLER_SUBJECT.get();
            if (caller == null) throw GrpcErrors.exception(Status.Code.UNAUTHENTICATED, ErrorCode.UNAUTHENTICATED);
            if (!offlineCaller.equals(caller) && (management || !advertiserCaller.equals(caller))) {
                throw GrpcErrors.exception(Status.Code.PERMISSION_DENIED, ErrorCode.RPC_RECONCILIATION_FORBIDDEN);
            }
            result = action.get();
        } catch (ReconciliationTask.Conflict ex) {
            observer.onError(GrpcErrors.exception(Status.Code.ALREADY_EXISTS, ErrorCode.RPC_RECONCILIATION_CONFLICT));
            return;
        } catch (ReconciliationTask.NotFound ex) {
            observer.onError(GrpcErrors.exception(Status.Code.NOT_FOUND, ErrorCode.RPC_RECONCILIATION_NOT_FOUND));
            return;
        } catch (StatusRuntimeException ex) {
            observer.onError(GrpcErrors.sanitize(ex, ErrorCode.RPC_RECONCILIATION_RESULT_UNKNOWN));
            return;
        } catch (Exception ex) {
            // 原SQL、命令原因和actor不进入外部错误或日志，保留固定操作及失败类型供排查。
            log.warn("sandbox reconciliation operation={} failureType={}", operation, ex.getClass().getSimpleName());
            observer.onError(GrpcErrors.exception(Status.Code.UNAVAILABLE, ErrorCode.RPC_RECONCILIATION_RESULT_UNKNOWN));
            return;
        }
        // 传输写入失败交给gRPC处理，不在已开始发送结果后再次发送业务错误。
        observer.onNext(result); observer.onCompleted();
    }
    private static void caller(String value) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}") || "recsys".equals(value)) {
            throw new IllegalArgumentException("对账必须配置独立可信调用方");
        }
    }
}
