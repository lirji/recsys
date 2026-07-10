package com.recsys.recengine;

import com.recsys.ad.AdProperties;
import com.recsys.common.ad.SearchAdsResponse;
import com.recsys.common.query.StructuredQuery;
import com.recsys.common.query.QueryUnderstandingService;
import com.recsys.recengine.ad.AdServingGateway;
import com.recsys.recengine.experiment.ExperimentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * 搜索广告编排(docs/05 §5)——微服务化后瘦身为「query 理解 + 实验分配 + 委托广告在线服务」。
 *
 * <p>rec-engine 保留 <b>query 理解</b>({@link QueryUnderstandingService},与推荐同一实现、单一来源)
 * 与 <b>广告分层 A/B 分配</b>({@link ExperimentService#adVariant},本地态、变体覆盖 reserve-price 等),
 * 之后把「已解析 query + adBucket + reserve」交给 {@link AdServingGateway}——
 * 默认进程内直调 {@code AdPipeline}(单体回退),{@code recsys.ad.serving.mode=grpc} 时跨进程调
 * {@code recsys-ad-serving}。广告召回/竞价/GSP/DCO/GD/曝光/计费的实现在 supplier 侧,rec-engine 不再感知。
 *
 * <p>公开方法签名保持不变({@code SearchAdsController} / {@code FeedOrchestrator} 零改动)。
 */
@Service
@EnableConfigurationProperties(AdProperties.class)
public class SearchAdsOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(SearchAdsOrchestrator.class);

    private final QueryUnderstandingService queryService;
    private final ExperimentService experimentService;
    private final AdServingGateway gateway;
    private final AdProperties props;

    public SearchAdsOrchestrator(QueryUnderstandingService queryService,
                                 ExperimentService experimentService,
                                 AdServingGateway gateway,
                                 AdProperties props) {
        this.queryService = queryService;
        this.experimentService = experimentService;
        this.gateway = gateway;
        this.props = props;
    }

    public SearchAdsResponse searchAds(long userId, String query, int slots, String scene) {
        if (query == null || query.isBlank()) {
            String traceId = UUID.randomUUID().toString().substring(0, 8);
            return new SearchAdsResponse(userId, query, UUID.randomUUID().toString(), List.of(), traceId);
        }

        // 1. Query 理解(复用与推荐同一实现;query 理解单一来源在 rec-engine)
        StructuredQuery sq = queryService.parse(query, userId);

        // 2. 广告分层 A/B 分配:adBucket(落 ad_event.ad_bucket 供分桶报表)+ reserve(变体可覆盖)
        var adVariant = experimentService.adVariant(userId);
        String adBucket = adVariant != null ? adVariant.getName() : "default";
        double reserve = adVariant != null && adVariant.getParams().containsKey("reserve-price")
                ? parseDouble(adVariant.getParams().get("reserve-price"), props.getAuction().getReservePrice())
                : props.getAuction().getReservePrice();

        // 3. 委托广告在线服务(in-process AdPipeline 或 gRPC recsys-ad-serving)执行整条广告管线
        SearchAdsResponse resp = gateway.searchAds(userId, sq, slots, scene, adBucket, reserve);
        log.debug("搜索广告 user={} q=[{}] adBucket={} 竞得={} trace={}",
                userId, sq.normalized(), adBucket, resp.ads().size(), resp.traceId());
        return resp;
    }

    /** 点击计费(委托 supplier;反作弊 + 归因校验在 supplier 侧)。 */
    public void recordClick(String requestId, long adId, long userId) {
        gateway.recordClick(requestId, adId, userId);
    }

    /** 转化回传(委托 supplier)。 */
    public void recordConversion(String requestId, long adId, long userId) {
        gateway.recordConversion(requestId, adId, userId);
    }

    private static double parseDouble(String s, double def) {
        if (s == null) {
            return def;
        }
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
