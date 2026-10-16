package com.recsys.advertiser.billing;

import com.recsys.common.ad.billing.BillingReconciliation.*;
import com.recsys.common.error.ErrorCode;
import com.recsys.platform.web.PublicHttpException;
import com.recsys.proto.ReconciliationProtoMapper;
import com.recsys.proto.reconciliation.v1.SandboxReconciliationServiceGrpc;
import io.grpc.StatusRuntimeException;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 管理面只暴露Inspect，复用全局deadline和内部身份，不依赖任何资金库Mapper。 */
@Component
@ConditionalOnProperty(name = {"recsys.ad.billing.sandbox-enabled", "recsys.advertiser.sandbox-reconciliation-enabled"}, havingValue = "true")
public class GrpcSandboxReconciliationClient {
    @GrpcClient("ad-serving") private SandboxReconciliationServiceGrpc.SandboxReconciliationServiceBlockingStub stub;

    /** RPC身份/关闭/依赖失败归为服务不可用，不能冒充终端无归属或零账户。 */
    public Snapshot inspect(Scope scope) {
        try {
            var result = ReconciliationProtoMapper.fromProto(stub.inspect(ReconciliationProtoMapper.toProto(scope)));
            if (!scope.equals(result.scope())) throw new IllegalArgumentException("返回账户日范围不一致");
            return result;
        } catch (StatusRuntimeException ex) {
            throw new PublicHttpException(ErrorCode.RECONCILIATION_UNAVAILABLE);
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new PublicHttpException(ErrorCode.RECONCILIATION_RESPONSE_INVALID);
        }
    }
}
