package com.recsys.advertiser.billing;

import com.recsys.common.ad.billing.ConversionSettlement.*;
import com.recsys.proto.ConversionProtoMapper;
import com.recsys.proto.conversion.v1.*;
import io.grpc.StatusRuntimeException;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
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
            throw switch(ex.getStatus().getCode()) {
                case INVALID_ARGUMENT->new ResponseStatusException(HttpStatus.BAD_REQUEST,"转化请求格式非法");
                case ALREADY_EXISTS->new ResponseStatusException(HttpStatus.CONFLICT,"业务事件已用于其他请求");
                case NOT_FOUND->new ResponseStatusException(HttpStatus.NOT_FOUND,"当前未观察到业务事件，请保留原ID");
                case UNAUTHENTICATED,PERMISSION_DENIED->new ResponseStatusException(HttpStatus.FORBIDDEN,"转化证明或内部调用方无效");
                default->new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"转化结果未知，请保留原ID查询或重试");
            };
        } catch(IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"转化响应无法验证，请保留原ID查询");
        }
    }
}
