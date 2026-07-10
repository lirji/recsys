package com.recsys.recengine.ad;

import com.recsys.common.ad.SearchAdsResponse;
import com.recsys.common.query.StructuredQuery;

/**
 * 广告在线服务网关(rec-engine 侧的调用抽象)。屏蔽"进程内直调 vs 跨进程 gRPC"两种实现,
 * 供绞杀者迁移期以 {@code recsys.ad.serving.mode} 一键切换与回滚。
 *
 * <p>入参已是 rec-engine 解析好的 {@link StructuredQuery} 与实验产出的 {@code adBucket}/{@code reserve}
 * ——query 理解与实验分桶是 rec-engine 单一来源,广告 supplier 只消费。
 *
 * @see InProcessAdServingGateway 默认(in-process):直调进程内 {@code AdPipeline}(recsys-ad lib),行为等同单体
 * @see GrpcAdServingGateway grpc:调 recsys-ad-serving 的 gRPC(广告在线服务独立进程)
 */
public interface AdServingGateway {

    /** 搜索广告:召回→竞价→GSP→曝光埋点,返回竞得赞助广告。 */
    SearchAdsResponse searchAds(long userId, StructuredQuery sq, int slots,
                                String scene, String adBucket, double reserve);

    /** CPC/OCPC 点击计费(反作弊 + 归因校验在 supplier 侧执行)。 */
    void recordClick(String requestId, long adId, long userId);

    /** 转化回传(CPA 计费 + 延迟转化建模)。 */
    void recordConversion(String requestId, long adId, long userId);
}
