package com.recsys.recengine.ad;

import com.recsys.common.ad.delivery.SandboxDelivery.Candidate;
import com.recsys.common.ad.delivery.SandboxDelivery.Query;
import com.recsys.common.ad.delivery.SandboxDelivery.Prepared;
import com.recsys.common.ad.delivery.SandboxDelivery.Prepare;
import com.recsys.common.error.ErrorCode;
import com.recsys.proto.grpc.GrpcErrors;
import java.util.Optional;
import java.util.UUID;
import com.recsys.proto.DeliveryProtoMapper;
import com.recsys.proto.ClickProtoMapper;
import com.recsys.proto.ConversionProtoMapper;
import com.recsys.proto.conversion.v1.*;
import com.recsys.common.ad.billing.ConversionSettlement;
import com.recsys.proto.click.v1.*;
import com.recsys.common.ad.billing.ClickSettlement;
import com.recsys.proto.delivery.v1.SandboxDeliveryServiceGrpc;
import com.recsys.proto.delivery.v1.GetPreparationRequest;
import io.grpc.StatusRuntimeException;
import io.grpc.Status;
import com.recsys.common.ad.SearchAdsResponse;
import com.recsys.common.query.StructuredQuery;
import com.recsys.proto.AdProtoMapper;
import com.recsys.proto.ImpressionProtoMapper;
import com.recsys.proto.impression.v1.*;
import com.recsys.common.ad.billing.ImpressionReceipt;
import com.recsys.proto.ad.v1.Ack;
import com.recsys.proto.ad.v1.AdServingServiceGrpc;
import com.recsys.proto.ad.v1.AdsReply;
import com.recsys.proto.ad.v1.ClickRequest;
import com.recsys.proto.ad.v1.ConversionRequest;
import com.recsys.proto.ad.v1.SearchAdsRequest;
import com.recsys.proto.ad.v1.OutcomeRequest;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.time.Instant;

/**
 * 微服务实现:经 gRPC 调 {@code recsys-ad-serving}(广告在线服务独立进程)。
 * 边界处用 {@link AdProtoMapper}(ACL)做 record ↔ proto 互转,rec-engine 内部领域模型不泄漏到网络。
 *
 * <p>{@code recsys.ad.serving.mode=grpc} 时才装配(否则不建 gRPC 通道)。gRPC 目标地址与协商方式见
 * {@code grpc.client.ad-serving.*}(默认 {@code static://localhost:9095} plaintext,无需 Nacos;
 * 接 Nacos discovery 后可换 {@code discovery://recsys-ad-serving})。
 */
@Component
@ConditionalOnProperty(name = "recsys.ad.serving.mode", havingValue = "grpc")
public class GrpcAdServingGateway implements AdServingGateway {

    private static final Logger log = LoggerFactory.getLogger(GrpcAdServingGateway.class);

    @GrpcClient("ad-serving")
    private AdServingServiceGrpc.AdServingServiceBlockingStub stub;

    @GrpcClient("ad-serving")
    private SandboxDeliveryServiceGrpc.SandboxDeliveryServiceBlockingStub deliveryStub;

    /** 新契约独立于旧 Retry/fallback：不可达必须保留未知状态。 */
    @Override
    public List<Candidate> sandboxCandidates(
            Query query) {
        var input = DeliveryProtoMapper.toProto(query);
        try {
            var reply = deliveryStub.candidates(input);
            return ownerReply(ErrorCode.RPC_DELIVERY_REPLY_INVALID,
                    () -> reply.getCandidatesList().stream().map(DeliveryProtoMapper::fromProto).toList());
        } catch (StatusRuntimeException ex) { throw GrpcErrors.sanitize(ex, ErrorCode.RPC_DELIVERY_RESULT_UNKNOWN); }
    }
    /** 新方法保持公共 deadline interceptor 的边界，不把 UNIMPLEMENTED 伪装为成功候选。 */
    @Override
    public List<Candidate> sandboxFeedCandidates(Query query) {
        var input = DeliveryProtoMapper.toProto(query);
        try {
            var reply = deliveryStub.feedCandidates(input);
            return ownerReply(ErrorCode.RPC_DELIVERY_REPLY_INVALID,
                    () -> reply.getCandidatesList().stream().map(DeliveryProtoMapper::fromProto).toList());
        } catch (StatusRuntimeException ex) { throw GrpcErrors.sanitize(ex, ErrorCode.RPC_DELIVERY_RESULT_UNKNOWN); }
    }
    @Override
    public Prepared prepareSandbox(
            Prepare request) {
        var input = DeliveryProtoMapper.toProto(request);
        try {
            var reply = deliveryStub.prepare(input);
            return ownerReply(ErrorCode.RPC_DELIVERY_REPLY_INVALID, () -> DeliveryProtoMapper.fromProto(reply));
        } catch (StatusRuntimeException ex) { throw GrpcErrors.sanitize(ex, ErrorCode.RPC_DELIVERY_RESULT_UNKNOWN); }
    }
    @Override
    public Optional<Prepared> findSandboxPreparation(UUID id, String subject) {
        try {
            var reply = deliveryStub.getPreparation(GetPreparationRequest.newBuilder().setChildId(id.toString()).setSubject(subject).build());
            return Optional.of(ownerReply(ErrorCode.RPC_DELIVERY_REPLY_INVALID, () -> DeliveryProtoMapper.fromProto(reply)));
        } catch (StatusRuntimeException ex) {
            if (ex.getStatus().getCode() == Status.Code.NOT_FOUND) return Optional.empty();
            throw GrpcErrors.sanitize(ex, ErrorCode.RPC_DELIVERY_RESULT_UNKNOWN);
        }
    }

    @GrpcClient("ad-serving")
    private SandboxImpressionServiceGrpc.SandboxImpressionServiceBlockingStub impressionStub;

    /** 不套旧吞错 fallback，写入超时可能已经提交。 */
    @Override
    public ImpressionReceipt.Result acceptSandboxImpression(ImpressionReceipt.Command command) {
        var input = ImpressionProtoMapper.toProto(command);
        try {
            var reply = impressionStub.accept(input);
            return ownerReply(ErrorCode.RPC_RECEIPT_REPLY_INVALID, () -> ImpressionProtoMapper.fromProto(reply));
        }
        catch (StatusRuntimeException ex) { throw receiptFailure(ex); }
    }
    @Override
    public Optional<ImpressionReceipt.Result> findSandboxImpression(UUID id, String subject) {
        try {
            var reply = impressionStub.getReceipt(GetVisibleReceiptRequest.newBuilder().setReceiptId(id.toString()).setSubject(subject).build());
            return Optional.of(ownerReply(ErrorCode.RPC_RECEIPT_REPLY_INVALID, () -> ImpressionProtoMapper.fromProto(reply)));
        } catch (StatusRuntimeException ex) {
            if (ex.getStatus().getCode() == Status.Code.NOT_FOUND) return Optional.empty();
            throw receiptFailure(ex);
        }
    }
    private RuntimeException receiptFailure(StatusRuntimeException ex) {
        if (GrpcErrors.isInternalAccessFailure(ex)) return GrpcErrors.exception(ex.getStatus().getCode(), ErrorCode.RPC_INTERNAL_ACCESS_FAILURE);
        return switch (ex.getStatus().getCode()) {
            case ALREADY_EXISTS -> new ImpressionReceipt.Conflict();
            case PERMISSION_DENIED -> new com.recsys.common.ad.delivery.SandboxDelivery.InvalidProof();
            case INVALID_ARGUMENT -> new IllegalArgumentException(ErrorCode.RPC_RECEIPT_INVALID_ARGUMENT.displayMessage());
            default -> GrpcErrors.sanitize(ex, ErrorCode.RPC_RECEIPT_RESULT_UNKNOWN);
        };
    }

    @GrpcClient("ad-serving")
    private SandboxClickServiceGrpc.SandboxClickServiceBlockingStub clickStub;

    /** 点击RPC不使用旧Retry/fallback，超时保留原ID恢复；公共拦截器仍限制deadline。 */
    @Override public List<Candidate> sandboxClickCandidates(Query query) {
        var input = DeliveryProtoMapper.toProto(query);
        try {
            var reply = clickStub.candidates(input);
            return ownerReply(ErrorCode.RPC_CLICK_REPLY_INVALID, () -> reply.getCandidatesList().stream().map(DeliveryProtoMapper::fromProto).toList());
        }
        catch(StatusRuntimeException ex) { throw clickFailure(ex,false); }
    }
    @Override public Prepared prepareSandboxClick(Prepare request) {
        var input = DeliveryProtoMapper.toProto(request);
        try {
            var reply = clickStub.prepare(input);
            return ownerReply(ErrorCode.RPC_CLICK_REPLY_INVALID, () -> DeliveryProtoMapper.fromProto(reply));
        }
        catch(StatusRuntimeException ex) { throw clickFailure(ex,false); }
    }
    @Override public Optional<Prepared> findSandboxClickPreparation(UUID id,String subject) {
        try {
            var reply = clickStub.getPreparation(GetPreparationRequest.newBuilder().setChildId(id.toString()).setSubject(subject).build());
            return Optional.of(ownerReply(ErrorCode.RPC_CLICK_REPLY_INVALID, () -> DeliveryProtoMapper.fromProto(reply)));
        }
        catch(StatusRuntimeException ex) { if(ex.getStatus().getCode()==Status.Code.NOT_FOUND)return Optional.empty();throw clickFailure(ex,false); }
    }
    @Override public ClickSettlement.Result acceptSandboxClick(ClickSettlement.Command command) {
        var input = ClickProtoMapper.toProto(command);
        try {
            var reply = clickStub.accept(input);
            return ownerReply(ErrorCode.RPC_CLICK_REPLY_INVALID, () -> ClickProtoMapper.fromProto(reply));
        }
        catch(StatusRuntimeException ex) { throw clickFailure(ex,true); }
    }
    @Override public Optional<ClickSettlement.Result> findSandboxClickReceipt(UUID id,String subject) {
        try {
            var reply = clickStub.getReceipt(GetClickReceiptRequest.newBuilder().setReceiptId(id.toString()).setSubject(subject).build());
            return Optional.of(ownerReply(ErrorCode.RPC_CLICK_REPLY_INVALID, () -> ClickProtoMapper.fromProto(reply)));
        }
        catch(StatusRuntimeException ex) { if(ex.getStatus().getCode()==Status.Code.NOT_FOUND)return Optional.empty();throw clickFailure(ex,true); }
    }
    @Override public Optional<ClickSettlement.Snapshot> findSandboxClickSettlement(UUID id,String subject) {
        try {
            var reply = clickStub.getSettlement(GetClickSettlementRequest.newBuilder().setQualificationId(id.toString()).setSubject(subject).build());
            return Optional.of(ownerReply(ErrorCode.RPC_CLICK_REPLY_INVALID, () -> ClickProtoMapper.fromProto(reply)));
        }
        catch(StatusRuntimeException ex) { if(ex.getStatus().getCode()==Status.Code.NOT_FOUND)return Optional.empty();throw clickFailure(ex,true); }
    }
    private RuntimeException clickFailure(StatusRuntimeException ex,boolean receipt) {
        if (GrpcErrors.isInternalAccessFailure(ex)) return GrpcErrors.exception(ex.getStatus().getCode(), ErrorCode.RPC_INTERNAL_ACCESS_FAILURE);
        return switch(ex.getStatus().getCode()) {
            case ALREADY_EXISTS -> receipt?new ClickSettlement.Conflict():new com.recsys.common.ad.delivery.SandboxDelivery.Conflict();
            case PERMISSION_DENIED -> new com.recsys.common.ad.delivery.SandboxDelivery.InvalidProof();
            case INVALID_ARGUMENT -> new IllegalArgumentException(ErrorCode.RPC_CLICK_INVALID_ARGUMENT.displayMessage());
            default -> GrpcErrors.sanitize(ex, ErrorCode.RPC_CLICK_RESULT_UNKNOWN);
        };
    }

    @GrpcClient("ad-serving")
    private SandboxConversionServiceGrpc.SandboxConversionServiceBlockingStub conversionStub;

    /** CPA RPC不使用旧Retry/fallback，超时保留原ID恢复；公共拦截器仍限制deadline。 */
    @Override public List<Candidate> sandboxConversionCandidates(Query query) {
        try { return ownerReply(ErrorCode.RPC_CONVERSION_REPLY_INVALID, ()->{
            var values=conversionStub.candidates(DeliveryProtoMapper.toProto(query)).getCandidatesList().stream().map(DeliveryProtoMapper::fromProto).toList();
            if(values.stream().anyMatch(c->!com.recsys.common.ad.BidType.CPA.name().equals(c.ad().bidType())))throw new IllegalArgumentException("CPA候选类型不一致");
            return values;
        }); } catch(StatusRuntimeException ex) { throw conversionFailure(ex,false); }
    }
    @Override public Prepared prepareSandboxConversion(Prepare request) {
        try { return ownerReply(ErrorCode.RPC_CONVERSION_REPLY_INVALID, ()->{
            var result=DeliveryProtoMapper.fromProto(conversionStub.prepare(DeliveryProtoMapper.toProto(request)));
            if(!request.childId().equals(result.childId()))throw new IllegalArgumentException("CPA准备身份不一致");return result;
        }); } catch(StatusRuntimeException ex) { throw conversionFailure(ex,false); }
    }
    @Override public Optional<Prepared> findSandboxConversionPreparation(UUID id,String subject) {
        try { return Optional.of(ownerReply(ErrorCode.RPC_CONVERSION_REPLY_INVALID, ()->{
            var result=DeliveryProtoMapper.fromProto(conversionStub.getPreparation(GetPreparationRequest.newBuilder().setChildId(id.toString()).setSubject(subject).build()));
            if(!id.equals(result.childId()))throw new IllegalArgumentException("CPA准备身份不一致");return result;
        })); } catch(StatusRuntimeException ex) { if(ex.getStatus().getCode()==Status.Code.NOT_FOUND)return Optional.empty();throw conversionFailure(ex,false); }
    }
    @Override public ConversionSettlement.VisibleResult acceptSandboxConversionVisible(ConversionSettlement.VisibleCommand command) {
        try { return ownerReply(ErrorCode.RPC_CONVERSION_REPLY_INVALID, ()->{
            var result=ConversionProtoMapper.fromProto(conversionStub.acceptVisible(ConversionProtoMapper.toProto(command)));
            if(!command.receiptId().equals(result.receipt().receiptId()))throw new IllegalArgumentException("CPA可见身份不一致");return result;
        }); } catch(StatusRuntimeException ex) { throw conversionFailure(ex,true); }
    }
    @Override public Optional<ConversionSettlement.VisibleResult> findSandboxConversionVisible(UUID id,String subject) {
        try { return Optional.of(ownerReply(ErrorCode.RPC_CONVERSION_REPLY_INVALID, ()->{
            var result=ConversionProtoMapper.fromProto(conversionStub.getVisible(GetConversionVisibleRequest.newBuilder().setReceiptId(id.toString()).setSubject(subject).build()));
            if(!id.equals(result.receipt().receiptId()))throw new IllegalArgumentException("CPA可见身份不一致");return result;
        })); } catch(StatusRuntimeException ex) { if(ex.getStatus().getCode()==Status.Code.NOT_FOUND)return Optional.empty();throw conversionFailure(ex,true); }
    }
    @Override public Optional<ConversionSettlement.Snapshot> findSandboxConversionSettlement(UUID id,String subject) {
        try { return Optional.of(ownerReply(ErrorCode.RPC_CONVERSION_REPLY_INVALID, ()->{
            var result=ConversionProtoMapper.fromProto(conversionStub.getSettlement(GetConversionSettlementRequest.newBuilder().setQualificationId(id.toString()).setSubject(subject).build()));
            if(!id.equals(result.prepared().qualificationId()))throw new IllegalArgumentException("CPA结算身份不一致");return result;
        })); } catch(StatusRuntimeException ex) { if(ex.getStatus().getCode()==Status.Code.NOT_FOUND)return Optional.empty();throw conversionFailure(ex,true); }
    }
    /** owner响应损坏属于依赖未知，不把它误报成客户端400或成功空结果。 */
    private <T> T ownerReply(ErrorCode invalidReply, java.util.function.Supplier<T> call) {
        try {return call.get();}catch(IllegalArgumentException ex){throw new IllegalStateException(invalidReply.displayMessage());}
    }
    private RuntimeException conversionFailure(StatusRuntimeException ex,boolean receipt) {
        if (GrpcErrors.isInternalAccessFailure(ex)) return GrpcErrors.exception(ex.getStatus().getCode(), ErrorCode.RPC_INTERNAL_ACCESS_FAILURE);
        return switch(ex.getStatus().getCode()) {
            case ALREADY_EXISTS -> receipt?new ConversionSettlement.Conflict():new com.recsys.common.ad.delivery.SandboxDelivery.Conflict();
            case PERMISSION_DENIED -> new com.recsys.common.ad.delivery.SandboxDelivery.InvalidProof();
            case INVALID_ARGUMENT -> new IllegalArgumentException(ErrorCode.RPC_CONVERSION_INVALID_ARGUMENT.displayMessage());
            default -> GrpcErrors.sanitize(ex, ErrorCode.RPC_CONVERSION_RESULT_UNKNOWN);
        };
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    // searchAds 是幂等读:@Retry(最外层)对瞬时错误(UNAVAILABLE/DEADLINE_EXCEEDED)重试,重试耗尽或非瞬时错误
    // 才落 fallback;@CircuitBreaker(内层,无 fallback)向 Retry 抛出以驱动重试/累计失败率。计费写(下方)不重试。
    @Override
    @Retry(name = "ad-serving-grpc", fallbackMethod = "searchAdsFallback")
    @CircuitBreaker(name = "ad-serving-grpc")
    public SearchAdsResponse searchAds(long userId, StructuredQuery sq, int slots,
                                       String scene, String adBucket, double reserve) {
        SearchAdsRequest req = SearchAdsRequest.newBuilder()
                .setUserId(userId)
                .setQuery(AdProtoMapper.toProto(sq))
                .setSlots(slots)
                .setScene(nz(scene))
                .setAdBucket(nz(adBucket))
                .setReservePrice(reserve)
                .setRawQuery(sq != null ? nz(sq.raw()) : "")
                .build();
        AdsReply reply = stub.searchAds(req);
        List<com.recsys.common.ad.SponsoredAd> ads = AdProtoMapper.fromProto(reply.getAdsList());
        return new SearchAdsResponse(
                reply.getUserId(),
                sq != null ? sq.raw() : "",
                reply.getRequestId(),
                ads,
                reply.getTraceId());
    }

    @Override
    @CircuitBreaker(name = "ad-serving-grpc", fallbackMethod = "recordClickFallback")
    public void recordClick(String requestId, long adId, long userId) {
        Ack ignored = stub.recordClick(ClickRequest.newBuilder()
                .setRequestId(nz(requestId)).setAdId(adId).setUserId(userId).build());
    }

    @Override
    @CircuitBreaker(name = "ad-serving-grpc", fallbackMethod = "recordConversionFallback")
    public void recordConversion(String requestId, long adId, long userId) {
        Ack ignored = stub.recordConversion(ConversionRequest.newBuilder()
                .setRequestId(nz(requestId)).setAdId(adId).setUserId(userId).build());
    }

    @Override
    @CircuitBreaker(name = "ad-serving-grpc", fallbackMethod = "recordOutcomeFallback")
    public boolean recordOutcome(String eventId, long advertiserId, long userId,
                                 String objective, double value, Instant occurredAt) {
        Ack ack = stub.recordOutcome(OutcomeRequest.newBuilder()
                .setEventId(nz(eventId)).setAdvertiserId(advertiserId).setUserId(userId)
                .setObjective(nz(objective)).setValue(value)
                .setOccurredAtEpochMs((occurredAt == null ? Instant.now() : occurredAt).toEpochMilli())
                .build());
        return ack.getOk();
    }

    // ---- 降级(P1):ad-serving 不可达/超时/熔断开启时的兜底,保证推荐主链路不因广告失败而 5xx ----

    /** 搜索广告降级:返回无广告(no-ad feed),自然结果照常返回。 */
    SearchAdsResponse searchAdsFallback(long userId, StructuredQuery sq, int slots,
                                        String scene, String adBucket, double reserve, Throwable t) {
        log.warn("ad-serving gRPC 降级为无广告 feed: {}", t.toString());
        return new SearchAdsResponse(userId, sq != null ? sq.raw() : "", "", List.of(), "");
    }

    /** 点击计费降级:记账链路暂不可达,记日志不阻断(计费幂等,可由离线补账/重试补齐)。 */
    void recordClickFallback(String requestId, long adId, long userId, Throwable t) {
        log.warn("ad-serving gRPC recordClick 降级(点击计费未落库): requestId={} adId={} userId={} err={}",
                requestId, adId, userId, t.toString());
    }

    /** 转化回传降级:同上,记日志不阻断。 */
    void recordConversionFallback(String requestId, long adId, long userId, Throwable t) {
        log.warn("ad-serving gRPC recordConversion 降级(转化未落库): requestId={} adId={} userId={} err={}",
                requestId, adId, userId, t.toString());
    }

    boolean recordOutcomeFallback(String eventId, long advertiserId, long userId,
                                  String objective, double value, Instant occurredAt, Throwable t) {
        log.warn("ad-serving gRPC recordOutcome 降级(独立 outcome 未落库): eventId={} err={}",
                eventId, t.toString());
        return false;
    }
}
