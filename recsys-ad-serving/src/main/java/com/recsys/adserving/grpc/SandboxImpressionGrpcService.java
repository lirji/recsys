package com.recsys.adserving.grpc;

import com.recsys.ad.billing.ImpressionReceiptService;
import com.recsys.common.ad.billing.*;
import com.recsys.common.ad.delivery.SandboxDelivery;
import com.recsys.proto.ImpressionProtoMapper;
import com.recsys.proto.impression.v1.*;
import com.recsys.proto.security.InternalAuthGrpcServerInterceptor;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import java.util.function.Supplier;

/** 回执接收是收费边界，即使 nosec 也须验证独立可信 engine 身份。 */
@GrpcService
@ConditionalOnProperty(prefix="recsys.ad.billing", name={"sandbox-enabled","receipts-enabled"}, havingValue="true")
public class SandboxImpressionGrpcService extends SandboxImpressionServiceGrpc.SandboxImpressionServiceImplBase {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(SandboxImpressionGrpcService.class);
    private final ImpressionReceiptService service;
    private final String caller;
    public SandboxImpressionGrpcService(ImpressionReceiptService service,
            @Value("${recsys.ad.delivery.engine-caller:recsys-rec-engine}") String caller) {
        if (caller == null || caller.isBlank() || "recsys".equals(caller)) throw new IllegalArgumentException("须配置独立 engine 身份");
        this.service=service; this.caller=caller;
    }
    /** 业务拒绝也返回持久化结果；只有未知执行才返回 UNAVAILABLE。 */
    @Override public void accept(VisibleReceiptCommand request, StreamObserver<VisibleReceiptResult> observer) {
        respond(observer, request.getReceiptId(), () -> ImpressionProtoMapper.toProto(service.accept(ImpressionProtoMapper.fromProto(request))));
    }
    /** 找不到不代表可以换 receiptId 重试。 */
    @Override public void getReceipt(GetVisibleReceiptRequest request, StreamObserver<VisibleReceiptResult> observer) {
        respond(observer, request.getReceiptId(), () -> ImpressionProtoMapper.toProto(service.find(BudgetCommand.parseId(request.getReceiptId()), request.getSubject())
                .orElseThrow(() -> Status.NOT_FOUND.withDescription("当前未观察到回执结果").asRuntimeException())));
    }
    private <T> void respond(StreamObserver<T> observer, String receiptId, Supplier<T> action) {
        try {
            String subject = InternalAuthGrpcServerInterceptor.CALLER_SUBJECT.get();
            if (subject == null) throw Status.UNAUTHENTICATED.asRuntimeException();
            if (!caller.equals(subject)) throw Status.PERMISSION_DENIED.asRuntimeException();
            T result=action.get(); observer.onNext(result); observer.onCompleted();
        } catch (ImpressionReceipt.Conflict ex) {
            observer.onError(Status.ALREADY_EXISTS.withDescription("回执负载冲突").asRuntimeException());
        } catch (SandboxDelivery.InvalidProof | Reservation.AccessDenied ex) {
            observer.onError(Status.PERMISSION_DENIED.withDescription("资格或主体无效").asRuntimeException());
        } catch (IllegalArgumentException ex) {
            observer.onError(Status.INVALID_ARGUMENT.withDescription("回执格式非法").asRuntimeException());
        } catch (StatusRuntimeException ex) { observer.onError(ex);
        } catch (RuntimeException ex) {
            String safeId = receiptId != null && receiptId.matches("[0-9a-fA-F-]{36}") ? receiptId : "invalid";
            LOG.error("sandbox receipt dependency failure receiptId={} type={}", safeId, ex.getClass().getSimpleName());
            observer.onError(Status.UNAVAILABLE.withDescription("账务结果未知，请保留原 receiptId 查询或重试").asRuntimeException());
        }
    }
}
