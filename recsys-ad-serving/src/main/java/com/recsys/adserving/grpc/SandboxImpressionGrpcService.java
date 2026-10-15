package com.recsys.adserving.grpc;

import com.recsys.ad.billing.ImpressionReceiptService;
import com.recsys.common.ad.billing.*;
import com.recsys.common.ad.delivery.SandboxDelivery;
import com.recsys.proto.ImpressionProtoMapper;
import com.recsys.proto.impression.v1.*;
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
                .orElseThrow(() -> GrpcErrors.exception(Status.Code.NOT_FOUND, ErrorCode.RPC_RECEIPT_NOT_FOUND))));
    }
    private <T> void respond(StreamObserver<T> observer, String receiptId, Supplier<T> action) {
        try {
            String subject = InternalAuthGrpcServerInterceptor.CALLER_SUBJECT.get();
            if (subject == null) throw GrpcErrors.exception(Status.Code.UNAUTHENTICATED, ErrorCode.UNAUTHENTICATED);
            if (!caller.equals(subject)) throw GrpcErrors.exception(Status.Code.PERMISSION_DENIED, ErrorCode.RPC_INTERNAL_CALLER_FORBIDDEN);
            T result=action.get(); observer.onNext(result); observer.onCompleted();
        } catch (ImpressionReceipt.Conflict ex) {
            observer.onError(GrpcErrors.exception(Status.Code.ALREADY_EXISTS, ErrorCode.RPC_RECEIPT_CONFLICT));
        } catch (SandboxDelivery.InvalidProof | Reservation.AccessDenied ex) {
            observer.onError(GrpcErrors.exception(Status.Code.PERMISSION_DENIED, ErrorCode.RPC_RECEIPT_FORBIDDEN));
        } catch (IllegalArgumentException ex) {
            observer.onError(GrpcErrors.exception(Status.Code.INVALID_ARGUMENT, ErrorCode.RPC_RECEIPT_INVALID_ARGUMENT));
        } catch (StatusRuntimeException ex) { observer.onError(GrpcErrors.sanitize(ex, ErrorCode.RPC_RECEIPT_RESULT_UNKNOWN));
        } catch (RuntimeException ex) {
            String safeId = receiptId != null && receiptId.matches("[0-9a-fA-F-]{36}") ? receiptId : "invalid";
            LOG.error("sandbox receipt dependency failure receiptId={} type={}", safeId, ex.getClass().getSimpleName());
            observer.onError(GrpcErrors.exception(Status.Code.UNAVAILABLE, ErrorCode.RPC_RECEIPT_RESULT_UNKNOWN));
        }
    }
}
