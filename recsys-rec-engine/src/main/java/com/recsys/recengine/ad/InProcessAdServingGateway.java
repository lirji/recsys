package com.recsys.recengine.ad;

import com.recsys.ad.AdPipeline;
import com.recsys.common.ad.SearchAdsResponse;
import com.recsys.common.query.StructuredQuery;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 默认(单体回退)实现:进程内直调 {@link AdPipeline}(recsys-ad lib,与广告在线服务共用同一份管线代码)。
 * 行为与拆分前的单体逐行等价——广告链路仍在 rec-engine 进程内,零网络跳、零 gRPC。
 *
 * <p>{@code recsys.ad.serving.mode} 缺省或 = {@code in-process} 时生效({@code matchIfMissing=true}),
 * 是绞杀者迁移的安全默认与回滚落点。
 */
@Component
@ConditionalOnProperty(name = "recsys.ad.serving.mode", havingValue = "in-process", matchIfMissing = true)
public class InProcessAdServingGateway implements AdServingGateway {

    private final AdPipeline pipeline;

    public InProcessAdServingGateway(AdPipeline pipeline) {
        this.pipeline = pipeline;
    }

    @Override
    public SearchAdsResponse searchAds(long userId, StructuredQuery sq, int slots,
                                       String scene, String adBucket, double reserve) {
        return pipeline.run(userId, sq, slots, scene, adBucket, reserve);
    }

    @Override
    public void recordClick(String requestId, long adId, long userId) {
        pipeline.recordClick(requestId, adId, userId);
    }

    @Override
    public void recordConversion(String requestId, long adId, long userId) {
        pipeline.recordConversion(requestId, adId, userId);
    }
}
