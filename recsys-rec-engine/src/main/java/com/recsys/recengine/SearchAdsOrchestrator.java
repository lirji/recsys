package com.recsys.recengine;

import com.recsys.ad.AdEventLogger;
import com.recsys.ad.AntiFraudService;
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
    private final com.recsys.recengine.experiment.ExperimentService experimentService;
    private final com.recsys.ad.AntiFraudService antiFraudService;

    public SearchAdsOrchestrator(QueryUnderstandingService queryService,
                                 AdRecallService adRecallService,
                                 RelevanceGate relevanceGate,
                                 PacingService pacingService,
                                 BiddingService biddingService,
                                 AdEventLogger adEventLogger,
                                 AdRepository adRepository,
                                 RankRouter rankRouter,
                                 AdProperties props,
                                 MeterRegistry meterRegistry,
                                 com.recsys.recengine.experiment.ExperimentService experimentService,
                                 com.recsys.ad.AntiFraudService antiFraudService) {
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
        this.experimentService = experimentService;
        this.antiFraudService = antiFraudService;
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

            // 5. pCTR/pCVR:复用排序模型对候选广告关联的 item 打分(同推荐口径;pCVR 仅 mmoe/din 给出)
            RankScores rankScores = score(userId, candidates, scene);

            // 标题 + oCPC 参数:为参与竞价的候选批量加载
            Set<Long> adIds = new LinkedHashSet<>();
            for (AdCandidate c : candidates) {
                adIds.add(c.adId());
            }
            Map<Long, String> titleByAd = adRepository.titles(adIds);
            Map<Long, AdRepository.OcpcParams> ocpcByAd = adRepository.ocpcParams(adIds);

            // 广告分层 A/B(docs/05):按用户在 ad 层分桶,变体可覆盖 reserve-price 等;ad_bucket 落库供分桶报表
            var adVariant = experimentService.adVariant(userId);
            String adBucket = adVariant != null ? adVariant.getName() : "default";
            double reserve = adVariant != null && adVariant.getParams().containsKey("reserve-price")
                    ? parseDouble(adVariant.getParams().get("reserve-price"), props.getAuction().getReservePrice())
                    : props.getAuction().getReservePrice();

            // 6-8. 校准 → oCPC 自动出价 → eCPM 竞价(含 EE 探索)→ GSP 拍卖计费(reserve 受实验覆盖)
            List<SponsoredAd> ads = biddingService.auction(
                    candidates, rankScores.pctr(), rankScores.pcvr(), ocpcByAd,
                    titleByAd, props.getCalibModel(), wantSlots, reserve);

            // 9. 曝光埋点(异步,带 ad_bucket)。CPC 在点击时扣预算,故此处不扣。
            adEventLogger.logImpressions(requestId, sq.normalized(), userId, ads, adBucket);

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
     * 记录点击 + CPC 计费,带<b>反作弊</b>(docs/05 §6):
     * <ol>
     *   <li>{@link AntiFraudService} 判去重/频次;</li>
     *   <li>点击须能归因到真实曝光({@link AdEventLogger#readAttribution} 非空)。</li>
     * </ol>
     * 任一不过 → 落 {@code INVALID_CLICK}(不进 CTR/计费)+ 打点;有效 → 落 {@code CLICK} 并按 GSP 价扣预算。
     */
    public void recordClick(String requestId, long adId, long userId) {
        AntiFraudService.Verdict verdict = antiFraudService.check(requestId, adId, userId);
        AdEventLogger.ClickAttribution attr = adEventLogger.readAttribution(requestId, adId);
        String reason = !verdict.valid() ? verdict.reason() : (attr == null ? "no_impression" : "");
        boolean valid = reason.isEmpty();

        adEventLogger.logFeedback(requestId, adId, userId, valid ? "CLICK" : "INVALID_CLICK");
        if (valid) {
            pacingService.charge(attr.advertiserId(), attr.chargedPrice());
            meterRegistry.counter("recsys.ad.click").increment();
            meterRegistry.counter("recsys.ad.revenue.cents")
                    .increment(Math.round(attr.chargedPrice() * 100));
        } else {
            meterRegistry.counter("recsys.ad.invalid_click", "reason", reason).increment();
            log.debug("无效点击拦截 req={} ad={} user={} reason={}", requestId, adId, userId, reason);
        }
    }

    /** 记录转化(advertiser 回传):落 CONVERSION 行,供延迟转化建模 / oCPC(M6)。 */
    public void recordConversion(String requestId, long adId, long userId) {
        adEventLogger.logFeedback(requestId, adId, userId, "CONVERSION");
        meterRegistry.counter("recsys.ad.conversion").increment();
    }

    /**
     * 复用 RankRouter 给候选广告关联的 item 打分,得 itemId → 原始 pCTR + pCVR。
     * pCTR 取排序分;pCVR 从特征快照的 "pCVR" 读(仅 mmoe/din 多目标模型写入,
     * 其余策略无 → 留空,oCPC 用先验兜底)。
     */
    private RankScores score(long userId, List<AdCandidate> candidates, String scene) {
        Set<Long> itemIds = new LinkedHashSet<>();
        for (AdCandidate c : candidates) {
            itemIds.add(c.itemId());
        }
        List<RankedItem> ranked = rankRouter.rank(userId, new ArrayList<>(itemIds), scene);
        Map<Long, Double> pctr = new HashMap<>(ranked.size());
        Map<Long, Double> pcvr = new HashMap<>();
        for (RankedItem ri : ranked) {
            pctr.put(ri.itemId(), ri.score());
            Map<String, Double> snap = ri.featureSnapshot();
            if (snap != null && snap.containsKey("pCVR")) {
                pcvr.put(ri.itemId(), snap.get("pCVR"));
            }
        }
        return new RankScores(pctr, pcvr);
    }

    /** 排序模型产出的 pCTR / pCVR 两张表(pCVR 可能为空)。 */
    private record RankScores(Map<Long, Double> pctr, Map<Long, Double> pcvr) {
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
