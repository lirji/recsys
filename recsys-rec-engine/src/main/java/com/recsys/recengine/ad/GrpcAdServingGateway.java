package com.recsys.recengine.ad;

import com.recsys.common.ad.SearchAdsResponse;
import com.recsys.common.query.StructuredQuery;
import com.recsys.proto.AdProtoMapper;
import com.recsys.proto.ad.v1.Ack;
import com.recsys.proto.ad.v1.AdServingServiceGrpc;
import com.recsys.proto.ad.v1.AdsReply;
import com.recsys.proto.ad.v1.ClickRequest;
import com.recsys.proto.ad.v1.ConversionRequest;
import com.recsys.proto.ad.v1.SearchAdsRequest;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

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

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    @Override
    @CircuitBreaker(name = "ad-serving-grpc", fallbackMethod = "searchAdsFallback")
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
}
