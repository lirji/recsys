package com.recsys.offline;

import com.google.protobuf.Message;
import com.recsys.common.ad.billing.*;
import com.recsys.common.ad.delivery.SandboxDelivery.ChargeMode;
import com.recsys.common.error.ErrorCode;
import com.recsys.common.error.PublicErrorException;
import com.recsys.proto.ReconciliationProtoMapper;
import com.recsys.proto.grpc.GrpcErrors;
import com.recsys.proto.reconciliation.v1.*;
import io.grpc.StatusRuntimeException;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** 一次显式CLI最多一次有意写RPC；不直连资金库、不自动循环、更不替未知结果生成新命令。 */
@Component
@ConditionalOnProperty(name = "recsys.offline.reconciliation.enabled", havingValue = "true")
public class BillingReconciliationJob implements OfflineJob {
    private static final Logger log = LoggerFactory.getLogger(BillingReconciliationJob.class);
    @GrpcClient("ad-serving") private SandboxReconciliationServiceGrpc.SandboxReconciliationServiceBlockingStub stub;
    private final String actor;
    private final long deadlineMs;

    /** 操作主体只来自可信运行配置；期限有界且每次RPC单独计算。 */
    public BillingReconciliationJob(@Value("${recsys.offline.reconciliation.actor:}") String actor,
            @Value("${recsys.offline.reconciliation.deadline-ms:20000}") long deadlineMs) {
        if (actor == null || actor.isBlank() || actor.length() > 128 || actor.chars().anyMatch(Character::isISOControl)
                || deadlineMs < 1000 || deadlineMs > 30000) throw new IllegalArgumentException("对账CLI主体或期限配置非法");
        this.actor = actor; this.deadlineMs = deadlineMs;
    }
    /** 与其他旧报表作业分开命名，防止误用旧统计作为资金权威。 */
    @Override public String name() { return "billing-reconciliation"; }

    /** CLI输出经过严格校验的protobuf文本，金额保持字符串，协议中不含任何令牌。 */
    @Override public void run(ApplicationArguments args) { log.info("reconciliation result={}", execute(args)); }

    Message execute(ApplicationArguments args) {
        try {
            if (args.containsOption("actor")) throw new IllegalArgumentException("主体只能来自可信配置");
            String action = required(args, "action"); long advertiser = JobArguments.longInteger(args, "advertiser-id", 0);
            if (advertiser <= 0) throw new IllegalArgumentException("广告主非法");
            var client = stub.withDeadlineAfter(deadlineMs, TimeUnit.MILLISECONDS);
            return switch (action) {
                case "inspect" -> {
                    var scope = new BillingReconciliation.Scope(advertiser, BudgetCommand.parseDate(required(args, "budget-date")));
                    var reply = client.inspect(ReconciliationProtoMapper.toProto(scope));
                    validated(() -> { require(scope.equals(ReconciliationProtoMapper.fromProto(reply).scope())); return reply; });
                    yield reply;
                }
                case "create" -> {
                    var command = new ReconciliationTask.Create(advertiser, id(args, "task-id"), BudgetCommand.parseDate(required(args, "start-date")),
                            BudgetCommand.parseDate(required(args, "end-date")), required(args, "reason"), actor);
                    var reply = client.createTask(ReconciliationProtoMapper.toProto(command));
                    var task = taskReply(reply, advertiser, command.taskId());
                    validated(() -> { require(task.startDate().equals(command.startDate()) && task.endDate().equals(command.endDate()) && task.reason().equals(command.reason())); return task; });
                    yield reply;
                }
                case "get", "advance" -> {
                    var id = id(args, "task-id"); var request = ReconciliationProtoMapper.task(advertiser, id);
                    var reply = "get".equals(action) ? client.getTask(request) : client.advanceTask(request);
                    taskReply(reply, advertiser, id); yield reply;
                }
                case "cancel" -> {
                    var command = new ReconciliationTask.Cancel(advertiser, id(args, "task-id"), id(args, "command-id"), required(args, "reason"), actor);
                    var reply = client.cancelTask(ReconciliationProtoMapper.toProto(command)); var task = taskReply(reply, advertiser, command.taskId());
                    validated(() -> { require(command.commandId().equals(task.cancelCommandId()) && command.reason().equals(task.cancelReason())); return task; });
                    yield reply;
                }
                case "days" -> {
                    var request = ReconciliationProtoMapper.task(advertiser, id(args, "task-id")); var reply = client.getDays(request);
                    validated(() -> { require(reply.getTask().equals(request)); ReconciliationProtoMapper.fromProto(reply); return reply; }); yield reply;
                }
                case "recover" -> {
                    var command = new BillingRecovery.Command(id(args, "command-id"), advertiser, BudgetCommand.parseDate(required(args, "budget-date")),
                            ChargeMode.valueOf(required(args, "family")), BillingRecovery.Action.valueOf(required(args, "recovery-action")),
                            id(args, "target-id"), required(args, "reason"), actor);
                    var reply = client.recover(ReconciliationProtoMapper.toProto(command));
                    validated(() -> { require(command.sameIntent(ReconciliationProtoMapper.fromProto(reply).command())); return reply; }); yield reply;
                }
                case "get-recovery" -> {
                    var id = id(args, "command-id");
                    var reply = client.getRecovery(BillingRecoveryIdentity.newBuilder().setAdvertiserId(advertiser).setCommandId(id.toString()).build());
                    validated(() -> { var command = ReconciliationProtoMapper.fromProto(reply).command(); require(command.advertiserId() == advertiser && command.commandId().equals(id)); return reply; }); yield reply;
                }
                default -> throw new IllegalArgumentException("未知对账动作");
            };
        } catch (IllegalArgumentException ex) {
            throw new PublicErrorException(ErrorCode.JOB_INVALID_ARGUMENT, ex);
        } catch (StatusRuntimeException ex) {
            // 不读取任意远端描述或trailer；协议状态对应固定作业文案，未知时保留原ID。
            var code = switch (ex.getStatus().getCode()) {
                case INVALID_ARGUMENT, OUT_OF_RANGE -> ErrorCode.JOB_INVALID_ARGUMENT;
                case UNAUTHENTICATED, PERMISSION_DENIED -> ErrorCode.JOB_RECONCILIATION_FORBIDDEN;
                case NOT_FOUND -> ErrorCode.JOB_RECONCILIATION_NOT_FOUND;
                case ALREADY_EXISTS, ABORTED -> ErrorCode.JOB_RECONCILIATION_CONFLICT;
                case FAILED_PRECONDITION -> ErrorCode.JOB_RECONCILIATION_DISABLED;
                default -> ErrorCode.JOB_RECONCILIATION_RESULT_UNKNOWN;
            };
            // Spring启动失败可能打印cause链，连cause也必须先剥离任意远端描述和metadata。
            throw new PublicErrorException(code, GrpcErrors.sanitize(ex, ErrorCode.RPC_RECONCILIATION_RESULT_UNKNOWN));
        }
    }
    private static ReconciliationTask.Task taskReply(ReconciliationTaskReply reply, long advertiser, UUID id) {
        return validated(() -> { var task = ReconciliationProtoMapper.fromProto(reply); require(task.advertiserId() == advertiser && task.taskId().equals(id)); return task; });
    }
    private static <T> T validated(Supplier<T> action) {
        try { return action.get(); }
        catch (IllegalArgumentException | NullPointerException ex) {
            // valueOf等异常可能包含不可信响应字段，CLI不能把它带入公开启动失败堆栈。
            throw new PublicErrorException(ErrorCode.JOB_RECONCILIATION_REPLY_INVALID);
        }
    }
    private static void require(boolean condition) { if (!condition) throw new IllegalArgumentException("对账结果范围不一致"); }
    private static UUID id(ApplicationArguments args, String name) { return BudgetCommand.parseId(required(args, name)); }
    private static String required(ApplicationArguments args, String name) {
        var value = JobArguments.text(args, name, null); if (value == null) throw new IllegalArgumentException("必要参数缺失"); return value;
    }
}
