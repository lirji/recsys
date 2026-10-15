package com.recsys.adserving.grpc;

import com.recsys.ad.AdPipeline;
import com.recsys.adserving.report.AdReportStatsRepository;
import com.recsys.common.ad.SearchAdsResponse;
import com.recsys.common.ad.SponsoredAd;
import com.recsys.common.query.StructuredQuery;
import com.recsys.common.error.ErrorCode;
import com.recsys.proto.grpc.GrpcErrors;
import com.recsys.proto.AdProtoMapper;
import com.recsys.proto.ad.v1.Ack;
import com.recsys.proto.ad.v1.AdEventStat;
import com.recsys.proto.ad.v1.AdEventStatsReply;
import com.recsys.proto.ad.v1.AdEventStatsRequest;
import com.recsys.proto.ad.v1.AdServingServiceGrpc;
import com.recsys.proto.ad.v1.AdsReply;
import com.recsys.proto.ad.v1.ClickRequest;
import com.recsys.proto.ad.v1.ConversionRequest;
import com.recsys.proto.ad.v1.SearchAdsRequest;
import com.recsys.proto.ad.v1.OutcomeRequest;
import java.time.Instant;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;

/**
 * 广告在线服务 gRPC 服务端。把从 rec-engine 剥离出的 {@link AdPipeline} 暴露为 gRPC RPC。
 *
 * <p>边界处经 {@link AdProtoMapper}(ACL)做 proto ↔ 领域 record 互转,领域模型不泄漏到网络契约。
 * query 理解 / 实验分桶由 rec-engine(Customer)完成后,把已解析 {@link StructuredQuery} 与
 * {@code adBucket}/{@code reservePrice} 随请求传入。异常在 supplier 侧兜底(返回空广告),与单体一致。
 */
@GrpcService
public class AdServingGrpcService extends AdServingServiceGrpc.AdServingServiceImplBase {

    private final AdPipeline pipeline;
    private final AdReportStatsRepository statsRepo;

    public AdServingGrpcService(AdPipeline pipeline, AdReportStatsRepository statsRepo) {
        this.pipeline = pipeline;
        this.statsRepo = statsRepo;
    }

    /** 保留管线既有降级；RPC构造/调用失败使用原UNKNOWN和固定目录提示。 */
    @Override
    public void searchAds(SearchAdsRequest req, StreamObserver<AdsReply> obs) {
        GrpcErrors.respondWithUnknownOnFailure(obs, ErrorCode.RPC_AD_QUERY_FAILED, () -> {
            StructuredQuery sq = AdProtoMapper.fromProto(req.getQuery());
            // query为空时只构造最小结构，不在supplier重复执行query理解。
            if ((sq.normalized() == null || sq.normalized().isBlank()) && !req.getRawQuery().isBlank()) {
                String raw = req.getRawQuery();
                sq = new StructuredQuery(raw, com.recsys.common.query.QueryTokens.normalize(raw),
                        sq.terms(), sq.intents(), sq.rewrites(), sq.embedding());
            }
            return toReply(pipeline.run(req.getUserId(), sq, req.getSlots(), req.getScene(),
                    req.getAdBucket(), req.getReservePrice()));
        });
    }

    /** 写失败不能发送成功ACK；协议UNKNOWN保留原可能部分生效的语义。 */
    @Override
    public void recordClick(ClickRequest req, StreamObserver<Ack> obs) {
        GrpcErrors.respondWithUnknownOnFailure(obs, ErrorCode.RPC_AD_WRITE_RESULT_UNKNOWN, () -> {
            pipeline.recordClick(req.getRequestId(), req.getAdId(), req.getUserId());
            return Ack.newBuilder().setOk(true).build();
        });
    }

    /** 保留原转化计费逻辑，仅在异常时提供安全提示，不声称事务已回滚。 */
    @Override
    public void recordConversion(ConversionRequest req, StreamObserver<Ack> obs) {
        GrpcErrors.respondWithUnknownOnFailure(obs, ErrorCode.RPC_AD_WRITE_RESULT_UNKNOWN, () -> {
            pipeline.recordConversion(req.getRequestId(), req.getAdId(), req.getUserId());
            return Ack.newBuilder().setOk(true).build();
        });
    }

    /** 正常重复仍返回false；异常保持UNKNOWN，不把未知写入归类成重复。 */
    @Override
    public void recordOutcome(OutcomeRequest req, StreamObserver<Ack> obs) {
        GrpcErrors.respondWithUnknownOnFailure(obs, ErrorCode.RPC_AD_WRITE_RESULT_UNKNOWN, () -> {
            Instant occurredAt = req.getOccurredAtEpochMs() > 0
                    ? Instant.ofEpochMilli(req.getOccurredAtEpochMs()) : Instant.now();
            String objective = req.getObjective().isBlank() ? "purchase" : req.getObjective();
            boolean inserted = pipeline.recordOutcome(req.getEventId(), req.getAdvertiserId(), req.getUserId(),
                    objective, req.getValue(), occurredAt);
            return Ack.newBuilder().setOk(inserted).build();
        });
    }

    /** #3:advertiser 报表取自有 ad_event 聚合(不再跨库直读)。 */
    @Override
    public void getAdEventStats(AdEventStatsRequest req, StreamObserver<AdEventStatsReply> obs) {
        GrpcErrors.respondWithUnknownOnFailure(obs, ErrorCode.RPC_AD_REPORT_QUERY_FAILED, () -> {
            AdEventStatsReply.Builder rb = AdEventStatsReply.newBuilder();
            for (AdReportStatsRepository.Stat s : statsRepo.statsByAds(req.getAdIdList())) {
                rb.addStats(AdEventStat.newBuilder()
                        .setAdId(s.adId()).setImpressions(s.impressions()).setClicks(s.clicks())
                        .setConversions(s.conversions()).setSpend(s.spend()).build());
            }
            return rb.build();
        });
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
