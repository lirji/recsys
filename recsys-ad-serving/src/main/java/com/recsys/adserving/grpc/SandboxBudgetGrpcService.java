package com.recsys.adserving.grpc;

import com.recsys.ad.billing.BudgetAccountService;
import com.recsys.common.ad.billing.BudgetCommand;
import com.recsys.common.ad.billing.BudgetCommandResult;
import com.recsys.proto.BudgetProtoMapper;
import com.recsys.proto.billing.v1.BudgetAccountReply;
import com.recsys.proto.billing.v1.BudgetCommandReply;
import com.recsys.proto.billing.v1.GetBudgetAccountRequest;
import com.recsys.proto.billing.v1.GetBudgetCommandRequest;
import com.recsys.proto.billing.v1.SandboxBudgetServiceGrpc;
import com.recsys.proto.billing.v1.SetBudgetLimitRequest;
import com.recsys.proto.security.InternalAuthGrpcServerInterceptor;
import io.grpc.Status;
import com.recsys.common.error.ErrorCode;
import com.recsys.proto.grpc.GrpcErrors;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.util.function.Supplier;

/** sandbox 账务 owner 边界；旧 nosec 模式没有可信身份时仍拒绝调用。 */
@GrpcService
@ConditionalOnProperty(name = "recsys.ad.billing.sandbox-enabled", havingValue = "true")
public class SandboxBudgetGrpcService extends SandboxBudgetServiceGrpc.SandboxBudgetServiceImplBase {
    private static final Logger log = LoggerFactory.getLogger(SandboxBudgetGrpcService.class);
    private final BudgetAccountService service;
    private final String advertiserCaller;

    public SandboxBudgetGrpcService(BudgetAccountService service,
            @Value("${recsys.ad.billing.advertiser-caller:recsys-advertiser}") String advertiserCaller) {
        if (advertiserCaller == null || advertiserCaller.isBlank() || "recsys".equals(advertiserCaller)) {
            throw new IllegalArgumentException("账务必须配置独立 advertiser 调用方身份");
        }
        this.service = service;
        this.advertiserCaller = advertiserCaller;
    }

    /** 仅提交成功返回结果；数据库异常可能包含提交未知，调用方必须保留命令 ID。 */
    @Override
    public void setLimit(SetBudgetLimitRequest request, StreamObserver<BudgetCommandReply> observer) {
        respond(observer, "setLimit", request.getAdvertiserId(), request.getCommandId(),
                () -> BudgetProtoMapper.toProto(service.setLimit(BudgetProtoMapper.fromProto(request))));
    }

    /** 账户查询同样受可信来源限制，避免 nosec 暴露资金信息。 */
    @Override
    public void getAccount(GetBudgetAccountRequest request, StreamObserver<BudgetAccountReply> observer) {
        respond(observer, "getAccount", request.getAdvertiserId(), "", () -> BudgetProtoMapper.toProto(
                service.findAccount(request.getAdvertiserId(), BudgetCommand.parseDate(request.getBudgetDate()))
                        .orElseThrow(() -> GrpcErrors.exception(Status.Code.NOT_FOUND, ErrorCode.RPC_BUDGET_ACCOUNT_NOT_FOUND))));
    }

    /** 不存在仅表示当前未观察到结果，不证明在途命令未执行。 */
    @Override
    public void getCommand(GetBudgetCommandRequest request, StreamObserver<BudgetCommandReply> observer) {
        respond(observer, "getCommand", request.getAdvertiserId(), request.getCommandId(), () -> BudgetProtoMapper.toProto(
                service.findCommand(request.getAdvertiserId(), BudgetCommand.parseId(request.getCommandId()))
                        .orElseThrow(() -> GrpcErrors.exception(Status.Code.NOT_FOUND, ErrorCode.RPC_BUDGET_COMMAND_NOT_FOUND))));
    }

    private <T> void respond(StreamObserver<T> observer, String operation, long advertiserId,
                              String commandId, Supplier<T> action) {
        try {
            String caller = InternalAuthGrpcServerInterceptor.CALLER_SUBJECT.get();
            if (caller == null) throw GrpcErrors.exception(Status.Code.UNAUTHENTICATED, ErrorCode.UNAUTHENTICATED);
            if (!advertiserCaller.equals(caller)) throw GrpcErrors.exception(Status.Code.PERMISSION_DENIED, ErrorCode.RPC_BUDGET_CALLER_FORBIDDEN);
            if (advertiserId <= 0) throw new IllegalArgumentException("advertiserId 须为正整数");
            T result = action.get();
            observer.onNext(result);
            observer.onCompleted();
        } catch (BudgetCommandResult.PayloadConflict ex) {
            observer.onError(GrpcErrors.exception(Status.Code.ALREADY_EXISTS, ErrorCode.RPC_BUDGET_PAYLOAD_CONFLICT));
        } catch (IllegalArgumentException ex) {
            observer.onError(GrpcErrors.exception(Status.Code.INVALID_ARGUMENT, ErrorCode.RPC_BUDGET_INVALID_ARGUMENT));
        } catch (StatusRuntimeException ex) {
            observer.onError(GrpcErrors.sanitize(ex, ErrorCode.RPC_BUDGET_RESULT_UNKNOWN));
        } catch (Exception ex) {
            // 仅记录已校验格式的 ID，避免恶意长文本或换行污染账务故障日志。
            String safeId = commandId != null && commandId.matches("[0-9a-fA-F-]{36}") ? commandId : "invalid-or-query";
            log.error("sandbox budget failure operation={} advertiserId={} commandId={}", operation, advertiserId, safeId, ex);
            observer.onError(GrpcErrors.exception(Status.Code.UNAVAILABLE, ErrorCode.RPC_BUDGET_RESULT_UNKNOWN));
        }
    }
}
