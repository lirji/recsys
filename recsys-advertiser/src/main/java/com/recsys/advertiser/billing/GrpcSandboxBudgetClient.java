package com.recsys.advertiser.billing;

import com.recsys.common.ad.billing.BudgetAccount;
import com.recsys.common.ad.billing.BudgetCommand;
import com.recsys.common.ad.billing.BudgetCommandResult;
import com.recsys.proto.BudgetProtoMapper;
import com.recsys.proto.billing.v1.GetBudgetAccountRequest;
import com.recsys.proto.billing.v1.GetBudgetCommandRequest;
import com.recsys.proto.billing.v1.SandboxBudgetServiceGrpc;
import io.grpc.StatusRuntimeException;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

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
            throw switch (ex.getStatus().getCode()) {
                case INVALID_ARGUMENT -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "预算命令参数非法");
                case ALREADY_EXISTS -> new ResponseStatusException(HttpStatus.CONFLICT, "commandId 已用于不同预算命令");
                case NOT_FOUND -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "当前未查到账户或命令；在途命令请保留原 commandId 查询或重试");
                case UNAUTHENTICATED, PERMISSION_DENIED -> new ResponseStatusException(HttpStatus.FORBIDDEN, "内部调用方无账务权限");
                default -> new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "账务结果未知，请保留原 commandId 查询或重试");
            };
        } catch (IllegalArgumentException ex) {
            // 上游回复格式错误不属于终端输入错误，不能把未知写入结果变成确定失败。
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "账务响应无法验证，请保留原 commandId 查询");
        }
    }
}
