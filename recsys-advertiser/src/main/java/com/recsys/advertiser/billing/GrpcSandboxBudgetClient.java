package com.recsys.advertiser.billing;

import com.recsys.common.ad.billing.BudgetAccount;
import com.recsys.common.ad.billing.BudgetCommand;
import com.recsys.common.ad.billing.BudgetCommandResult;
import com.recsys.common.error.ErrorCode;
import com.recsys.platform.web.PublicHttpException;
import com.recsys.proto.grpc.GrpcErrors;
import com.recsys.proto.BudgetProtoMapper;
import com.recsys.proto.billing.v1.GetBudgetAccountRequest;
import com.recsys.proto.billing.v1.GetBudgetCommandRequest;
import com.recsys.proto.billing.v1.SandboxBudgetServiceGrpc;
import io.grpc.StatusRuntimeException;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.UUID;
import java.util.function.Supplier;

/** advertiser 只通过 owner 契约调额，不读写广告服务账务库；复用全局 deadline 和可信身份拦截器。 */
@Component
@ConditionalOnProperty(name = "recsys.ad.billing.sandbox-enabled", havingValue = "true")
public class GrpcSandboxBudgetClient {
    @GrpcClient("ad-serving")
    private SandboxBudgetServiceGrpc.SandboxBudgetServiceBlockingStub stub;

    /** 不盲目重试写 RPC，超时由调用方用原命令 ID 恢复。 */
    public BudgetCommandResult setLimit(BudgetCommand command) {
        return invoke(() -> BudgetProtoMapper.fromProto(stub.setLimit(BudgetProtoMapper.toProto(command))));
    }

    /** 获取当前账户快照。 */
    public BudgetAccount getAccount(long advertiserId, LocalDate date) {
        return invoke(() -> BudgetProtoMapper.fromProto(stub.getAccount(GetBudgetAccountRequest.newBuilder()
                .setAdvertiserId(advertiserId).setBudgetDate(date.toString()).build())));
    }

    /** 获取原命令结果，不将 404 解释为可更换 ID。 */
    public BudgetCommandResult getCommand(long advertiserId, UUID commandId) {
        return invoke(() -> BudgetProtoMapper.fromProto(stub.getCommand(GetBudgetCommandRequest.newBuilder()
                .setAdvertiserId(advertiserId).setCommandId(commandId.toString()).build())));
    }

    private <T> T invoke(Supplier<T> call) {
        try {
            return call.get();
        } catch (StatusRuntimeException ex) {
            if (GrpcErrors.isInternalAccessFailure(ex)) throw new PublicHttpException(ErrorCode.BUDGET_DEPENDENCY_UNAVAILABLE);
            throw switch (ex.getStatus().getCode()) {
                case INVALID_ARGUMENT -> new PublicHttpException(ErrorCode.BUDGET_INVALID_ARGUMENT);
                case ALREADY_EXISTS -> new PublicHttpException(ErrorCode.BUDGET_COMMAND_CONFLICT);
                case NOT_FOUND -> new PublicHttpException(ErrorCode.BUDGET_RESULT_NOT_OBSERVED);
                case PERMISSION_DENIED -> new PublicHttpException(ErrorCode.FORBIDDEN);
                default -> new PublicHttpException(ErrorCode.BUDGET_RESULT_UNKNOWN);
            };
        } catch (IllegalArgumentException ex) {
            // 上游回复格式错误不属于终端输入错误，不能把未知写入结果变成确定失败。
            throw new PublicHttpException(ErrorCode.BUDGET_RESPONSE_INVALID);
        }
    }
}
