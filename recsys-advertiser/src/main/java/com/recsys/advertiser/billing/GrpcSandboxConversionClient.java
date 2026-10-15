package com.recsys.advertiser.billing;

import com.recsys.common.ad.billing.ConversionSettlement.*;
import com.recsys.common.error.ErrorCode;
import com.recsys.platform.web.PublicHttpException;
import com.recsys.proto.grpc.GrpcErrors;
import com.recsys.proto.ConversionProtoMapper;
import com.recsys.proto.conversion.v1.*;
import io.grpc.StatusRuntimeException;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.util.UUID;
import java.util.function.Supplier;

/** advertiser只访问唯一资金owner，不读写其表；调用前必须已完成当前归属授权。 */
@Component
@ConditionalOnProperty(prefix="recsys.ad.billing",name={"sandbox-enabled","conversions-enabled","conversion-settlement-enabled","conversion-delivery-enabled"},havingValue="true")
public class GrpcSandboxConversionClient {
    @GrpcClient("ad-serving") private SandboxConversionServiceGrpc.SandboxConversionServiceBlockingStub stub;
    /** 写超时不自动换业务键或退回旧outcome路径。 */
    public OutcomeResult accept(ConversionCommand c) {
        return invoke(c.advertiserId(),c.eventId(),()->ConversionProtoMapper.fromProto(stub.acceptConversion(ConversionProtoMapper.toProto(c))));
    }
    /** 原业务结果只能按广告主作用域查询，actor来自本次授权主体。 */
    public OutcomeResult find(long advertiserId,UUID eventId,String actor) {
        return invoke(advertiserId,eventId,()->ConversionProtoMapper.fromProto(stub.getOutcome(GetConversionOutcomeRequest.newBuilder()
                .setAdvertiserId(advertiserId).setEventId(eventId.toString()).setActor(actor).build())));
    }
    private OutcomeResult invoke(long advertiserId,UUID eventId,Supplier<OutcomeResult> call) {
        try {
            var result=call.get();
            if(result.outcome().advertiserId()!=advertiserId || !result.outcome().eventId().equals(eventId)) throw new IllegalArgumentException("owner响应身份不一致");
            return result;
        } catch(StatusRuntimeException ex) {
            if (GrpcErrors.isInternalAccessFailure(ex)) throw new PublicHttpException(ErrorCode.CONVERSION_503);
            throw switch(ex.getStatus().getCode()) {
                case INVALID_ARGUMENT->new PublicHttpException(ErrorCode.CONVERSION_400);
                case ALREADY_EXISTS->new PublicHttpException(ErrorCode.CONVERSION_409);
                case NOT_FOUND->new PublicHttpException(ErrorCode.CONVERSION_404);
                case PERMISSION_DENIED->new PublicHttpException(ErrorCode.CONVERSION_403);
                default->new PublicHttpException(ErrorCode.CONVERSION_503);
            };
        } catch(IllegalArgumentException ex) {
            throw new PublicHttpException(ErrorCode.CONVERSION_503);
        }
    }
}
