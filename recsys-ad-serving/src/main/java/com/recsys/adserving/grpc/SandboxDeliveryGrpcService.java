package com.recsys.adserving.grpc;

import com.recsys.ad.billing.SandboxPreparationService;
import com.recsys.common.ad.billing.BudgetCommand;
import com.recsys.common.ad.billing.Reservation;
import com.recsys.common.ad.billing.FrozenCpmQuote;
import com.recsys.common.ad.delivery.SandboxDelivery;
import com.recsys.proto.DeliveryProtoMapper;
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

/** 可信 engine 专用准备边界；nosec 或通用服务身份不获得签发权限。 */
@GrpcService
@ConditionalOnProperty(name = "recsys.ad.billing.sandbox-enabled", havingValue = "true")
public class SandboxDeliveryGrpcService extends SandboxDeliveryServiceGrpc.SandboxDeliveryServiceImplBase {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(SandboxDeliveryGrpcService.class);
    private final SandboxPreparationService service;
    private final String caller;
    public SandboxDeliveryGrpcService(SandboxPreparationService service,
            @Value("${recsys.ad.delivery.engine-caller:recsys-rec-engine}") String caller) {
        if (caller == null || caller.isBlank() || "recsys".equals(caller)) throw new IllegalArgumentException("须配置独立 engine 身份");
        this.service = service; this.caller = caller;
    }
    /** 候选失败不变成成功空结果。 */
    @Override
    public void candidates(CandidateQuery request, StreamObserver<CandidateReply> observer) {
        respond(observer, () -> CandidateReply.newBuilder().addAllCandidates(service.candidates(DeliveryProtoMapper.fromProto(request))
                .stream().map(DeliveryProtoMapper::toProto).toList()).build());
    }
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.beans.factory.ObjectProvider<com.recsys.ad.billing.FeedCandidateService> feedCandidates;

    /** 与已有方法使用相同可信 engine 身份校验；owner 缺能力时必须传播失败。 */
    @Override
    public void feedCandidates(CandidateQuery request, StreamObserver<CandidateReply> observer) {
        respond(observer, () -> {
            var owner=feedCandidates==null?null:feedCandidates.getIfAvailable();
            if(owner==null) throw GrpcErrors.exception(Status.Code.UNAVAILABLE, ErrorCode.RPC_DELIVERY_FEED_DISABLED);
            return CandidateReply.newBuilder().addAllCandidates(owner.candidates(DeliveryProtoMapper.fromProto(request))
                    .stream().map(DeliveryProtoMapper::toProto).toList()).build();
        });
    }
    /** 超时结果未知，调用方保留原操作 ID。 */
    @Override
    public void prepare(PrepareDelivery request, StreamObserver<PreparedDelivery> observer) {
        respond(observer, () -> DeliveryProtoMapper.toProto(service.prepare(DeliveryProtoMapper.fromProto(request))));
    }
    @Override
    public void getPreparation(GetPreparationRequest request, StreamObserver<PreparedDelivery> observer) {
        respond(observer, () -> DeliveryProtoMapper.toProto(service.find(BudgetCommand.parseId(request.getChildId()), request.getSubject())
                .orElseThrow(() -> GrpcErrors.exception(Status.Code.NOT_FOUND, ErrorCode.RPC_DELIVERY_NOT_FOUND))));
    }
    private <T> void respond(StreamObserver<T> observer, Supplier<T> action) {
        try {
            String subject = InternalAuthGrpcServerInterceptor.CALLER_SUBJECT.get();
            if (subject == null) throw GrpcErrors.exception(Status.Code.UNAUTHENTICATED, ErrorCode.UNAUTHENTICATED);
            if (!caller.equals(subject)) throw GrpcErrors.exception(Status.Code.PERMISSION_DENIED, ErrorCode.RPC_INTERNAL_CALLER_FORBIDDEN);
            T result = action.get(); observer.onNext(result); observer.onCompleted();
        } catch (SandboxDelivery.InvalidProof | Reservation.AccessDenied ex) {
            observer.onError(GrpcErrors.exception(Status.Code.PERMISSION_DENIED, ErrorCode.RPC_DELIVERY_FORBIDDEN));
        } catch (SandboxDelivery.Conflict | Reservation.Conflict | FrozenCpmQuote.Conflict ex) {
            observer.onError(GrpcErrors.exception(Status.Code.ALREADY_EXISTS, ErrorCode.RPC_DELIVERY_CONFLICT));
        } catch (IllegalArgumentException ex) {
            observer.onError(GrpcErrors.exception(Status.Code.INVALID_ARGUMENT, ErrorCode.RPC_DELIVERY_INVALID_ARGUMENT));
        } catch (StatusRuntimeException ex) { observer.onError(GrpcErrors.sanitize(ex, ErrorCode.RPC_DELIVERY_RESULT_UNKNOWN));
        } catch (RuntimeException ex) {
            // 不记录 token、主体或 SQL 参数；异常类型供告警聚合，业务 ID 在 engine 记录。
            LOG.error("sandbox prepare dependency failure type={}", ex.getClass().getSimpleName());
            observer.onError(GrpcErrors.exception(Status.Code.UNAVAILABLE, ErrorCode.RPC_DELIVERY_RESULT_UNKNOWN));
        }
    }
}
