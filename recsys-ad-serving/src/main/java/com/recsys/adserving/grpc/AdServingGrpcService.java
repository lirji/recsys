package com.recsys.adserving.grpc;

import com.recsys.ad.AdPipeline;
import com.recsys.common.ad.SearchAdsResponse;
import com.recsys.common.ad.SponsoredAd;
import com.recsys.common.query.StructuredQuery;
import com.recsys.proto.AdProtoMapper;
import com.recsys.proto.ad.v1.Ack;
import com.recsys.proto.ad.v1.AdServingServiceGrpc;
import com.recsys.proto.ad.v1.AdsReply;
import com.recsys.proto.ad.v1.ClickRequest;
import com.recsys.proto.ad.v1.ConversionRequest;
import com.recsys.proto.ad.v1.SearchAdsRequest;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 广告在线服务 gRPC 服务端。把从 rec-engine 剥离出的 {@link AdPipeline} 暴露为 gRPC RPC。
 *
 * <p>边界处经 {@link AdProtoMapper}(ACL)做 proto ↔ 领域 record 互转,领域模型不泄漏到网络契约。
 * query 理解 / 实验分桶由 rec-engine(Customer)完成后,把已解析 {@link StructuredQuery} 与
 * {@code adBucket}/{@code reservePrice} 随请求传入。异常在 supplier 侧兜底(返回空广告),与单体一致。
 */
@GrpcService
public class AdServingGrpcService extends AdServingServiceGrpc.AdServingServiceImplBase {

    private static final Logger log = LoggerFactory.getLogger(AdServingGrpcService.class);

    private final AdPipeline pipeline;

    public AdServingGrpcService(AdPipeline pipeline) {
        this.pipeline = pipeline;
    }

    @Override
    public void searchAds(SearchAdsRequest req, StreamObserver<AdsReply> obs) {
        StructuredQuery sq = AdProtoMapper.fromProto(req.getQuery());
        // query 为空(未解析)时用 raw_query 兜底成最小结构(supplier 不再重解析,保持 query 理解单一来源)
        if ((sq.normalized() == null || sq.normalized().isBlank()) && !req.getRawQuery().isBlank()) {
            String raw = req.getRawQuery();
            sq = new StructuredQuery(raw, com.recsys.common.query.QueryTokens.normalize(raw),
                    sq.terms(), sq.intents(), sq.rewrites(), sq.embedding());
        }
        SearchAdsResponse resp = pipeline.run(
                req.getUserId(), sq, req.getSlots(), req.getScene(),
                req.getAdBucket(), req.getReservePrice());
        obs.onNext(toReply(resp));
        obs.onCompleted();
    }

    @Override
    public void recordClick(ClickRequest req, StreamObserver<Ack> obs) {
        pipeline.recordClick(req.getRequestId(), req.getAdId(), req.getUserId());
        obs.onNext(Ack.newBuilder().setOk(true).build());
        obs.onCompleted();
    }

    @Override
    public void recordConversion(ConversionRequest req, StreamObserver<Ack> obs) {
        pipeline.recordConversion(req.getRequestId(), req.getAdId(), req.getUserId());
        obs.onNext(Ack.newBuilder().setOk(true).build());
        obs.onCompleted();
    }

    private static AdsReply toReply(SearchAdsResponse resp) {
        AdsReply.Builder rb = AdsReply.newBuilder()
                .setUserId(resp.userId())
                .setRequestId(resp.requestId() == null ? "" : resp.requestId())
                .setTraceId(resp.traceId() == null ? "" : resp.traceId());
        for (SponsoredAd a : resp.ads()) {
            rb.addAds(AdProtoMapper.toProto(a));
        }
        return rb.build();
    }
}
