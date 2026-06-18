package com.recsys.recengine;

import com.recsys.ad.AdEventLogger;
import com.recsys.ad.AdProperties;
import com.recsys.ad.AdRepository;
import com.recsys.ad.BiddingService;
import com.recsys.ad.PacingService;
import com.recsys.ad.RelevanceGate;
import com.recsys.common.ad.AdCandidate;
import com.recsys.common.ad.AdRecallContext;
import com.recsys.common.ad.AdRecallService;
import com.recsys.common.ad.SearchAdsResponse;
import com.recsys.common.ad.SponsoredAd;
import com.recsys.common.query.StructuredQuery;
import com.recsys.common.query.QueryUnderstandingService;
import com.recsys.common.rank.RankedItem;
import com.recsys.rank.RankRouter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 搜索广告编排(docs/05 §5 时序):
 * query 理解 → 广告召回 → 相关性门槛 → 预算过滤 → pCTR(复用排序模型)→ 校准 → eCPM 竞价 →
 * GSP 拍卖计费 → 曝光埋点。点击时(CPC)再扣预算,见 {@link #recordClick}。
 *
 * <p>复用优先(docs/05 §9.5):pCTR 直接复用推荐的 {@link RankRouter}(DeepFM/MMoE…),
 * 广告只补"query / 钱 / 校准 / 拍卖"四块净增量。每层降级:召回某路空→其他路兜底,
 * 模型未就绪→规则分,校准缺失→identity,Redis 挂→不限预算。整体异常返回空广告(不出比出错强)。
 */
@Service
@EnableConfigurationProperties(AdProperties.class)
public class SearchAdsOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(SearchAdsOrchestrator.class);

    private final QueryUnderstandingService queryService;
    private final AdRecallService adRecallService;
    private final RelevanceGate relevanceGate;
    private final PacingService pacingService;
    private final BiddingService biddingService;
    private final AdEventLogger adEventLogger;
    private final AdRepository adRepository;
    private final RankRouter rankRouter;
    private final AdProperties props;
    private final MeterRegistry meterRegistry;

    public SearchAdsOrchestrator(QueryUnderstandingService queryService,
                                 AdRecallService adRecallService,
                                 RelevanceGate relevanceGate,
                                 PacingService pacingService,
                                 BiddingService biddingService,
                                 AdEventLogger adEventLogger,
                                 AdRepository adRepository,
                                 RankRouter rankRouter,
                                 AdProperties props,
                                 MeterRegistry meterRegistry) {
        this.queryService = queryService;
        this.adRecallService = adRecallService;
        this.relevanceGate = relevanceGate;
        this.pacingService = pacingService;
        this.biddingService = biddingService;
        this.adEventLogger = adEventLogger;
        this.adRepository = adRepository;
        this.rankRouter = rankRouter;
        this.props = props;
        this.meterRegistry = meterRegistry;
    }

    public SearchAdsResponse searchAds(long userId, String query, int slots, String scene) {
        String traceId = UUID.randomUUID().toString().substring(0, 8);
        String requestId = UUID.randomUUID().toString();
        int wantSlots = slots > 0 ? slots : props.getSlots();
        Timer.Sample sample = Timer.start(meterRegistry);
        String outcome = "ok";
        try {
            if (query == null || query.isBlank()) {
                outcome = "empty_query";
                return new SearchAdsResponse(userId, query, requestId, List.of(), traceId);
            }

            // 1. Query 理解(复用与推荐同一实现)
            StructuredQuery sq = queryService.parse(query, userId);

            // 2. 广告召回(query→ad 多路)
            List<AdCandidate> candidates = adRecallService.recall(
                    new AdRecallContext(sq, userId, props.getRecall().getKw(), List.of()));

            // 3. 相关性门槛(广告独有硬过滤) + 4. 预算过滤(超预算广告主下线)
            candidates = relevanceGate.filter(candidates);
            candidates = pacingService.filterBudget(candidates);
            if (candidates.isEmpty()) {
                meterRegistry.counter("recsys.ad.empty", "stage", "recall").increment();
                outcome = "no_candidate";
                return new SearchAdsResponse(userId, query, requestId, List.of(), traceId);
            }

            // 5. pCTR:复用排序模型对候选广告关联的 item 打分(同推荐口径)
            Map<Long, Double> pctrByItem = pctr(userId, candidates, scene);

            // 标题:为参与竞价的候选批量取创意标题
            Set<Long> adIds = new LinkedHashSet<>();
            for (AdCandidate c : candidates) {
                adIds.add(c.adId());
            }
            Map<Long, String> titleByAd = adRepository.titles(adIds);

            // 6-8. 校准 → eCPM 竞价 → GSP 拍卖计费
            List<SponsoredAd> ads = biddingService.auction(
                    candidates, pctrByItem, titleByAd, props.getCalibModel(), wantSlots);

            // 9. 曝光埋点(异步)。CPC 在点击时扣预算,故此处不扣。
            adEventLogger.logImpressions(requestId, sq.normalized(), userId, ads);

            // 观测:填充数 + 潜在营收(eCPM/千次)
            meterRegistry.counter("recsys.ad.fill").increment(ads.size());
            log.debug("搜索广告 user={} q=[{}] 候选={} 竞得={} trace={} req={}",
                    userId, sq.normalized(), candidates.size(), ads.size(), traceId, requestId);
            return new SearchAdsResponse(userId, query, requestId, ads, traceId);
        } catch (Exception e) {
            outcome = "error";
            log.error("搜索广告失败 user={} q=[{}] trace={}: {}", userId, query, traceId, e.getMessage(), e);
            return new SearchAdsResponse(userId, query, requestId, List.of(), traceId);
        } finally {
            sample.stop(Timer.builder("recsys.ad.duration")
                    .description("搜索广告编排端到端耗时")
                    .tag("outcome", outcome)
                    .publishPercentileHistogram()
                    .register(meterRegistry));
        }
    }

    /**
     * 记录点击 + CPC 计费:落 CLICK 行,按该次曝光算好的 GSP 价扣广告主预算。
     * 找不到归因(过期等)则只落点击不扣费(宁可少扣不乱扣)。
     */
    public void recordClick(String requestId, long adId, long userId) {
        adEventLogger.logFeedback(requestId, adId, userId, "CLICK");
        AdEventLogger.ClickAttribution attr = adEventLogger.readAttribution(requestId, adId);
        if (attr != null) {
            pacingService.charge(attr.advertiserId(), attr.chargedPrice());
            meterRegistry.counter("recsys.ad.click").increment();
            meterRegistry.counter("recsys.ad.revenue.cents")
                    .increment(Math.round(attr.chargedPrice() * 100));
        }
    }

    /** 记录转化(advertiser 回传):落 CONVERSION 行,供延迟转化建模 / oCPC(M6)。 */
    public void recordConversion(String requestId, long adId, long userId) {
        adEventLogger.logFeedback(requestId, adId, userId, "CONVERSION");
        meterRegistry.counter("recsys.ad.conversion").increment();
    }

    /** 复用 RankRouter 给候选广告关联的 item 打分,得 itemId → 原始 pCTR。 */
    private Map<Long, Double> pctr(long userId, List<AdCandidate> candidates, String scene) {
        Set<Long> itemIds = new LinkedHashSet<>();
        for (AdCandidate c : candidates) {
            itemIds.add(c.itemId());
        }
        List<RankedItem> ranked = rankRouter.rank(userId, new ArrayList<>(itemIds), scene);
        Map<Long, Double> out = new HashMap<>(ranked.size());
        for (RankedItem ri : ranked) {
            out.put(ri.itemId(), ri.score());
        }
        return out;
    }
}
