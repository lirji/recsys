package com.recsys.adserving.grpc;

import com.recsys.ad.billing.ClickPreparationService;
import com.recsys.ad.billing.ClickSettlementService;
import com.recsys.common.ad.billing.*;
import com.recsys.common.ad.delivery.SandboxDelivery;
import com.recsys.proto.ClickProtoMapper;
import com.recsys.proto.DeliveryProtoMapper;
import com.recsys.proto.click.v1.*;
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

/** 点击资格获取与双条件收费边界；nosec也要求独立可信engine身份。 */
@GrpcService
@ConditionalOnProperty(prefix="recsys.ad.billing",name={"sandbox-enabled","clicks-enabled","click-settlement-enabled","click-delivery-enabled"},havingValue="true")
public class SandboxClickGrpcService extends SandboxClickServiceGrpc.SandboxClickServiceImplBase {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(SandboxClickGrpcService.class);
    private final ClickPreparationService preparations;
    private final ClickSettlementService settlements;
    private final String caller;
    public SandboxClickGrpcService(ClickPreparationService preparations,ClickSettlementService settlements,
            @Value("${recsys.ad.delivery.engine-caller:recsys-rec-engine}") String caller) {
        if(caller==null || caller.isBlank() || "recsys".equals(caller))throw new IllegalArgumentException("须配置独立engine身份");
        this.preparations=preparations;this.settlements=settlements;this.caller=caller;
    }
    /** 纯候选失败保持未知，不能成功返回空广告。 */
    @Override public void candidates(CandidateQuery request,StreamObserver<CandidateReply> observer) {
        respond(observer,()->CandidateReply.newBuilder().addAllCandidates(preparations.candidates(DeliveryProtoMapper.fromProto(request))
                .stream().map(DeliveryProtoMapper::toProto).toList()).build());
    }
    /** 写调用仅原childId重试，不新增身份。 */
    @Override public void prepare(PrepareDelivery request,StreamObserver<PreparedDelivery> observer) {
        respond(observer,()->DeliveryProtoMapper.toProto(preparations.prepare(DeliveryProtoMapper.fromProto(request))));
    }
    /** 未观察到原结果由NOT_FOUND表达，不是确定未执行。 */
    @Override public void getPreparation(GetPreparationRequest request,StreamObserver<PreparedDelivery> observer) {
        respond(observer,()->DeliveryProtoMapper.toProto(preparations.find(BudgetCommand.parseId(request.getChildId()),request.getSubject()).orElseThrow(SandboxClickGrpcService::notFound)));
    }
    /** 拒绝决定同样是持久化结果，只有依赖失败返回未知。 */
    @Override public void accept(ClickReceiptCommand request,StreamObserver<ClickReceiptResult> observer) {
        respond(observer,()->ClickProtoMapper.toProto(settlements.accept(ClickProtoMapper.fromProto(request))));
    }
    /** 返回原决定及查询时最新资金状态。 */
    @Override public void getReceipt(GetClickReceiptRequest request,StreamObserver<ClickReceiptResult> observer) {
        respond(observer,()->ClickProtoMapper.toProto(settlements.findReceipt(BudgetCommand.parseId(request.getReceiptId()),request.getSubject()).orElseThrow(SandboxClickGrpcService::notFound)));
    }
    /** 当前状态查询不重签、不重新预占或扣费。 */
    @Override public void getSettlement(GetClickSettlementRequest request,StreamObserver<ClickSettlementSnapshot> observer) {
        respond(observer,()->ClickProtoMapper.toProto(settlements.find(BudgetCommand.parseId(request.getQualificationId()),request.getSubject()).orElseThrow(SandboxClickGrpcService::notFound)));
    }
    private static StatusRuntimeException notFound() { return GrpcErrors.exception(Status.Code.NOT_FOUND, ErrorCode.RPC_CLICK_NOT_FOUND); }
    private <T> void respond(StreamObserver<T> observer,Supplier<T> action) {
        try {
            var subject=InternalAuthGrpcServerInterceptor.CALLER_SUBJECT.get();
            if(subject==null)throw GrpcErrors.exception(Status.Code.UNAUTHENTICATED, ErrorCode.UNAUTHENTICATED);
            if(!caller.equals(subject))throw GrpcErrors.exception(Status.Code.PERMISSION_DENIED, ErrorCode.FORBIDDEN);
            observer.onNext(action.get());observer.onCompleted();
        } catch(SandboxDelivery.InvalidProof | Reservation.AccessDenied ex) {
            observer.onError(GrpcErrors.exception(Status.Code.PERMISSION_DENIED, ErrorCode.RPC_CLICK_FORBIDDEN));
        } catch(SandboxDelivery.Conflict | FrozenClickQuote.Conflict | ClickSettlement.Conflict ex) {
            observer.onError(GrpcErrors.exception(Status.Code.ALREADY_EXISTS, ErrorCode.RPC_CLICK_CONFLICT));
        } catch(IllegalArgumentException ex) {
            observer.onError(GrpcErrors.exception(Status.Code.INVALID_ARGUMENT, ErrorCode.RPC_CLICK_INVALID_ARGUMENT));
        } catch(StatusRuntimeException ex) { observer.onError(GrpcErrors.sanitize(ex, ErrorCode.RPC_CLICK_RESULT_UNKNOWN));
        } catch(RuntimeException ex) {
            LOG.warn("sandbox click dependency failure type={}",ex.getClass().getSimpleName());
            observer.onError(GrpcErrors.exception(Status.Code.UNAVAILABLE, ErrorCode.RPC_CLICK_RESULT_UNKNOWN));
        }
    }
}
