package com.recsys.adserving.grpc;

import com.recsys.ad.billing.ConversionPreparationService;
import com.recsys.ad.billing.ConversionSettlementService;
import com.recsys.common.ad.billing.*;
import com.recsys.common.ad.delivery.SandboxDelivery;
import com.recsys.proto.ConversionProtoMapper;
import com.recsys.proto.DeliveryProtoMapper;
import com.recsys.proto.conversion.v1.*;
import com.recsys.proto.delivery.v1.*;
import com.recsys.proto.security.InternalAuthGrpcServerInterceptor;
import io.grpc.Status;
import com.recsys.common.error.ErrorCode;
import com.recsys.proto.grpc.GrpcErrors;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import java.util.function.Supplier;

/** CPA资格获取与双条件收费边界；nosec也要求各方法对应的独立engine/advertiser身份。 */
@GrpcService
@ConditionalOnProperty(prefix="recsys.ad.billing",name={"sandbox-enabled","conversions-enabled","conversion-settlement-enabled","conversion-delivery-enabled"},havingValue="true")
public class SandboxConversionGrpcService extends SandboxConversionServiceGrpc.SandboxConversionServiceImplBase {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(SandboxConversionGrpcService.class);
    private final ConversionPreparationService preparations;
    private final ConversionSettlementService settlements;
    private final String engineCaller;
    private final String advertiserCaller;
    public SandboxConversionGrpcService(ConversionPreparationService preparations,ConversionSettlementService settlements,
            @Value("${recsys.ad.delivery.engine-caller:recsys-rec-engine}") String engineCaller,
            @Value("${recsys.ad.billing.advertiser-caller:recsys-advertiser}") String advertiserCaller) {
        if(engineCaller==null || engineCaller.isBlank() || "recsys".equals(engineCaller) || advertiserCaller==null || advertiserCaller.isBlank()
                || "recsys".equals(advertiserCaller) || engineCaller.equals(advertiserCaller))throw new IllegalArgumentException("须配置互不相同的独立engine和advertiser身份");
        this.preparations=preparations;this.settlements=settlements;this.engineCaller=engineCaller;this.advertiserCaller=advertiserCaller;
    }
    /** 纯候选失败保持未知，不能成功返回空广告。 */
    @Override public void candidates(CandidateQuery request,StreamObserver<CandidateReply> observer) {
        respond(observer,engineCaller,()->CandidateReply.newBuilder().addAllCandidates(preparations.candidates(DeliveryProtoMapper.fromProto(request))
                .stream().map(DeliveryProtoMapper::toProto).toList()).build());
    }
    /** 写调用仅原childId重试，不新增身份。 */
    @Override public void prepare(PrepareDelivery request,StreamObserver<PreparedDelivery> observer) {
        respond(observer,engineCaller,()->DeliveryProtoMapper.toProto(preparations.prepare(DeliveryProtoMapper.fromProto(request))));
    }
    /** 未观察到原结果由NOT_FOUND表达，不是确定未执行。 */
    @Override public void getPreparation(GetPreparationRequest request,StreamObserver<PreparedDelivery> observer) {
        respond(observer,engineCaller,()->DeliveryProtoMapper.toProto(preparations.find(BudgetCommand.parseId(request.getChildId()),request.getSubject()).orElseThrow(SandboxConversionGrpcService::notFound)));
    }
    /** 拒绝决定同样是持久化结果，只有依赖失败返回未知。 */
    @Override public void acceptVisible(ConversionVisibleCommand request,StreamObserver<ConversionVisibleResult> observer) {
        respond(observer,engineCaller,()->ConversionProtoMapper.toProto(settlements.acceptVisible(ConversionProtoMapper.fromProto(request))));
    }
    /** 返回原决定及查询时最新资金状态。 */
    @Override public void getVisible(GetConversionVisibleRequest request,StreamObserver<ConversionVisibleResult> observer) {
        respond(observer,engineCaller,()->ConversionProtoMapper.toProto(settlements.findVisible(BudgetCommand.parseId(request.getReceiptId()),request.getSubject()).orElseThrow(SandboxConversionGrpcService::notFound)));
    }
    /** 当前状态查询不重签、不重新预占或扣费。 */
    @Override public void getSettlement(GetConversionSettlementRequest request,StreamObserver<ConversionSettlementSnapshot> observer) {
        respond(observer,engineCaller,()->ConversionProtoMapper.toProto(settlements.find(BudgetCommand.parseId(request.getQualificationId()),request.getSubject()).orElseThrow(SandboxConversionGrpcService::notFound)));
    }
    /** 只有已认证授权的advertiser服务可提交业务转化，engine不能声明广告主权限。 */
    @Override public void acceptConversion(ConversionEventCommand request,StreamObserver<ConversionOutcomeResult> observer) {
        respond(observer,advertiserCaller,()->ConversionProtoMapper.toProto(settlements.acceptAuthorizedConversion(ConversionProtoMapper.fromProto(request))));
    }
    /** 每次查询亦要求可信advertiser及合法actor，由advertiser边界重新完成归属检查。 */
    @Override public void getOutcome(GetConversionOutcomeRequest request,StreamObserver<ConversionOutcomeResult> observer) {
        respond(observer,advertiserCaller,()->{
            Reservation.validateSubject(request.getActor());
            return ConversionProtoMapper.toProto(settlements.findAuthorizedOutcome(request.getAdvertiserId(),BudgetCommand.parseId(request.getEventId()))
                    .orElseThrow(SandboxConversionGrpcService::notFound));
        });
    }
    private static StatusRuntimeException notFound() { return GrpcErrors.exception(Status.Code.NOT_FOUND, ErrorCode.RPC_CONVERSION_NOT_FOUND); }
    private <T> void respond(StreamObserver<T> observer,String expectedCaller,Supplier<T> action) {
        try {
            var subject=InternalAuthGrpcServerInterceptor.CALLER_SUBJECT.get();
            if(subject==null)throw GrpcErrors.exception(Status.Code.UNAUTHENTICATED, ErrorCode.UNAUTHENTICATED);
            if(!expectedCaller.equals(subject))throw GrpcErrors.exception(Status.Code.PERMISSION_DENIED, ErrorCode.RPC_INTERNAL_CALLER_FORBIDDEN);
            observer.onNext(action.get());observer.onCompleted();
        } catch(SandboxDelivery.InvalidProof | Reservation.AccessDenied ex) {
            observer.onError(GrpcErrors.exception(Status.Code.PERMISSION_DENIED, ErrorCode.RPC_CONVERSION_FORBIDDEN));
        } catch(SandboxDelivery.Conflict | FrozenConversionQuote.Conflict | ConversionSettlement.Conflict ex) {
            observer.onError(GrpcErrors.exception(Status.Code.ALREADY_EXISTS, ErrorCode.RPC_CONVERSION_CONFLICT));
        } catch(IllegalArgumentException ex) {
            observer.onError(GrpcErrors.exception(Status.Code.INVALID_ARGUMENT, ErrorCode.RPC_CONVERSION_INVALID_ARGUMENT));
        } catch(StatusRuntimeException ex) { observer.onError(GrpcErrors.sanitize(ex, ErrorCode.RPC_CONVERSION_RESULT_UNKNOWN));
        } catch(RuntimeException ex) {
            LOG.warn("sandbox conversion dependency failure type={}",ex.getClass().getSimpleName());
            observer.onError(GrpcErrors.exception(Status.Code.UNAVAILABLE, ErrorCode.RPC_CONVERSION_RESULT_UNKNOWN));
        }
    }
}
