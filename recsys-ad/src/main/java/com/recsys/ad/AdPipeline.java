package com.recsys.ad;

import com.recsys.common.ad.AdCandidate;
import com.recsys.common.ad.AdRecallContext;
import com.recsys.common.ad.AdRecallService;
import com.recsys.common.ad.SearchAdsResponse;
import com.recsys.common.ad.SponsoredAd;
import com.recsys.common.query.StructuredQuery;
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
import java.util.stream.Collectors;

/**
 * 广告在线管线(可复用核):从「已解析 query + 实验上下文」到「竞得赞助广告」。
 *
 * <p>这是搜索广告编排里 <b>query 理解 / 实验分配之后</b>的整条链路——
 * 广告召回 → 相关性门槛 → 预算过滤 → 智能定向 → pCTR/pCVR(复用排序模型)→ 校准 → oCPC 出价 →
 * eCPM 竞价(含 EE/List-wise)→ GSP 计费 → DCO 创意 → GD 保量 → 曝光埋点 → 结算分流。
 *
 * <p><b>为什么抽成独立类</b>(微服务化,DDD Shared Kernel):同一份管线被两处复用——
 * 单体回退路径下由 rec-engine 的 {@code InProcessAdServingGateway} 直接调,
 * 微服务路径下由 {@code recsys-ad-serving} 的 gRPC 服务端调。query 理解与实验分桶留在 rec-engine
 * (单一来源),此处只接收<b>已解析</b>的 {@link StructuredQuery} 与实验产出的 {@code adBucket}/{@code reserve}。
 * pCTR/pCVR 仍以 {@link RankRouter} lib 就地打分(逐候选,绝不走网络),即 ads↔ranking 的进程内共享内核。
 *
 * <p>每层优雅降级:召回某路空→其他路兜底,模型未就绪→规则分,校准缺失→identity,Redis 挂→不限预算,
 * 整体异常→返回空广告(不出比出错强)。逻辑与原 {@code SearchAdsOrchestrator} 逐行等价,仅去掉
 * query 理解与实验分配两步(上移到调用方)。
 */
@Service
@EnableConfigurationProperties(AdProperties.class)
public class AdPipeline {

    private static final Logger log = LoggerFactory.getLogger(AdPipeline.class);

    private final AdRecallService adRecallService;
    private final RelevanceGate relevanceGate;
    private final PacingService pacingService;
    private final BiddingService biddingService;
    private final AdEventLogger adEventLogger;
    private final AdCatalogReader catalogReader;
    private final RankRouter rankRouter;
    private final AdProperties props;
    private final MeterRegistry meterRegistry;
    private final AntiFraudService antiFraudService;
    private final AdEmbeddingSimilarity adEmbeddingSimilarity;
    private final CreativeSelector creativeSelector;
    private final AudienceTargeting audienceTargeting;
    private final GuaranteedDeliveryService guaranteedDelivery;
    private final DfmCvrService dfmCvrService;

    public AdPipeline(AdRecallService adRecallService,
                      RelevanceGate relevanceGate,
                      PacingService pacingService,
                      BiddingService biddingService,
                      AdEventLogger adEventLogger,
                      AdCatalogReader catalogReader,
                      RankRouter rankRouter,
                      AdProperties props,
                      MeterRegistry meterRegistry,
                      AntiFraudService antiFraudService,
                      AdEmbeddingSimilarity adEmbeddingSimilarity,
                      CreativeSelector creativeSelector,
                      AudienceTargeting audienceTargeting,
                      GuaranteedDeliveryService guaranteedDelivery,
                      DfmCvrService dfmCvrService) {
        this.adRecallService = adRecallService;
        this.relevanceGate = relevanceGate;
        this.pacingService = pacingService;
        this.biddingService = biddingService;
        this.adEventLogger = adEventLogger;
        this.catalogReader = catalogReader;
        this.rankRouter = rankRouter;
        this.props = props;
        this.meterRegistry = meterRegistry;
        this.antiFraudService = antiFraudService;
        this.adEmbeddingSimilarity = adEmbeddingSimilarity;
        this.creativeSelector = creativeSelector;
        this.audienceTargeting = audienceTargeting;
        this.guaranteedDelivery = guaranteedDelivery;
        this.dfmCvrService = dfmCvrService;
    }

    /**
     * 执行广告管线。{@code sq} 已由调用方(rec-engine)解析,{@code adBucket}/{@code reserve} 来自实验分配。
     *
     * @param userId   用户 ID
     * @param sq       已解析结构化查询(query 理解单一来源在 rec-engine)
     * @param slots    广告位数(<=0 用配置默认)
     * @param scene    场景
     * @param adBucket 广告实验分桶名(落 ad_event.ad_bucket 供分桶报表)
     * @param reserve  保留价(可被实验变体覆盖)
     */
    public SearchAdsResponse run(long userId, StructuredQuery sq, int slots,
                                 String scene, String adBucket, double reserve) {
        String traceId = UUID.randomUUID().toString().substring(0, 8);
        String requestId = UUID.randomUUID().toString();
        int wantSlots = slots > 0 ? slots : props.getSlots();
        String queryStr = sq != null ? sq.raw() : "";
        Timer.Sample sample = Timer.start(meterRegistry);
        String outcome = "ok";
        try {
            if (sq == null || sq.normalized() == null || sq.normalized().isBlank()) {
                outcome = "empty_query";
                return new SearchAdsResponse(userId, queryStr, requestId, List.of(), traceId);
            }

            // 1. 广告召回(query→ad 多路)
            List<AdCandidate> candidates = adRecallService.recall(
                    new AdRecallContext(sq, userId, props.getRecall().getKw(), List.of()));

            // 2. 相关性门槛 + 3. 预算过滤 + 4. 智能定向(A3 人群包 Look-alike)
            candidates = relevanceGate.filter(candidates);
            candidates = pacingService.filterBudget(candidates);
            candidates = audienceTargeting.filter(userId, candidates);
            if (candidates.isEmpty()) {
                meterRegistry.counter("recsys.ad.empty", "stage", "recall").increment();
                outcome = "no_candidate";
                return new SearchAdsResponse(userId, queryStr, requestId, List.of(), traceId);
            }

            // 5. pCTR/pCVR:复用排序模型对候选广告关联 item 打分(同推荐口径;pCVR 仅 mmoe/din 给出)
            RankScores rankScores = score(userId, candidates, scene);

            // 标题 + oCPC 参数批量加载
            Set<Long> adIds = new LinkedHashSet<>();
            for (AdCandidate c : candidates) {
                adIds.add(c.adId());
            }
            Map<Long, String> titleByAd = catalogReader.titles(adIds);
            Map<Long, AdRepository.OcpcParams> ocpcByAd = catalogReader.ocpcParams(adIds);

            // List-wise 外部性:开启则预载候选 item 向量供整页贪心去重蚕食;关闭→null→逐条 eCPM
            ListwiseExternality.Sim sim = props.getAuction().getListwise().isEnabled()
                    ? adEmbeddingSimilarity.forItems(
                            candidates.stream().map(AdCandidate::itemId).collect(Collectors.toSet()))
                    : null;

            // 6-8. 校准 → oCPC 出价 → eCPM 竞价(含 EE + 可选 List-wise)→ GSP 计费(reserve 由实验覆盖)
            List<SponsoredAd> ads = biddingService.auction(
                    candidates, rankScores.pctr(), rankScores.pcvr(), ocpcByAd,
                    titleByAd, props.getCalibModel(), wantSlots, reserve, sim);

            // 8.5 DCO 动态创意优化(竞价后为每条竞得广告选展示创意,不动排序/计费)
            ads = applyDco(ads);

            // 8.6 GD 保量(有落后合约则置首位、竞价广告让位)
            ads = applyGuaranteedDelivery(userId, ads, wantSlots);

            // 9. 曝光埋点(异步,带 ad_bucket + creative_id 归因)+ 结算分流(CPM/OCPM 曝光即扣)
            adEventLogger.logImpressions(requestId, sq.normalized(), userId, ads, adBucket);
            chargeImpressionBilled(ads);

            meterRegistry.counter("recsys.ad.fill").increment(ads.size());
            log.debug("广告管线 user={} q=[{}] 候选={} 竞得={} trace={} req={}",
                    userId, sq.normalized(), candidates.size(), ads.size(), traceId, requestId);
            return new SearchAdsResponse(userId, queryStr, requestId, ads, traceId);
        } catch (Exception e) {
            outcome = "error";
            log.error("广告管线失败 user={} q=[{}] trace={}: {}", userId, queryStr, traceId, e.getMessage(), e);
            return new SearchAdsResponse(userId, queryStr, requestId, List.of(), traceId);
        } finally {
            sample.stop(Timer.builder("recsys.ad.duration")
                    .description("广告管线端到端耗时")
                    .tag("outcome", outcome)
                    .publishPercentileHistogram()
                    .register(meterRegistry));
        }
    }

    /**
     * 记录点击 + 计费,带反作弊(去重/频次 + 归因校验)。仅 CPC/OCPC 在此扣预算(A1)。
     * 逻辑与原 {@code SearchAdsOrchestrator.recordClick} 等价。
     */
    public void recordClick(String requestId, long adId, long userId) {
        AntiFraudService.Verdict verdict = antiFraudService.check(requestId, adId, userId);
        AdEventLogger.ClickAttribution attr = adEventLogger.readAttribution(requestId, adId);
        String reason = !verdict.valid() ? verdict.reason() : (attr == null ? "no_impression" : "");
        boolean valid = reason.isEmpty();

        adEventLogger.logFeedback(requestId, adId, userId, valid ? "CLICK" : "INVALID_CLICK");
        if (valid) {
            if (com.recsys.common.ad.BidType.from(attr.bidType()).chargeOnClick()) {
                pacingService.charge(attr.advertiserId(), attr.chargedPrice());
                meterRegistry.counter("recsys.ad.revenue.cents")
                        .increment(Math.round(attr.chargedPrice() * 100));
            }
            meterRegistry.counter("recsys.ad.click").increment();
        } else {
            meterRegistry.counter("recsys.ad.invalid_click", "reason", reason).increment();
            log.debug("无效点击拦截 req={} ad={} user={} reason={}", requestId, adId, userId, reason);
        }
    }

    /** 记录转化(advertiser 回传):落 CONVERSION;按转化计费(CPA)在此扣预算(A1)。 */
    public void recordConversion(String requestId, long adId, long userId) {
        adEventLogger.logFeedback(requestId, adId, userId, "CONVERSION");
        meterRegistry.counter("recsys.ad.conversion").increment();
        AdEventLogger.ClickAttribution attr = adEventLogger.readAttribution(requestId, adId);
        if (attr != null && com.recsys.common.ad.BidType.from(attr.bidType()).chargeOnConversion()) {
            pacingService.charge(attr.advertiserId(), attr.chargedPrice());
            meterRegistry.counter("recsys.ad.revenue.cents")
                    .increment(Math.round(attr.chargedPrice() * 100));
        }
    }

    /** DCO:为竞得广告选展示创意(多臂老虎机);开关关/无创意原样返回。 */
    private List<SponsoredAd> applyDco(List<SponsoredAd> ads) {
        if (ads.isEmpty()) {
            return ads;
        }
        Set<Long> adIds = new LinkedHashSet<>();
        for (SponsoredAd a : ads) {
            adIds.add(a.adId());
        }
        Map<Long, CreativeSelector.Choice> chosen = creativeSelector.selectFor(adIds);
        if (chosen.isEmpty()) {
            return ads;
        }
        List<SponsoredAd> out = new ArrayList<>(ads.size());
        for (SponsoredAd a : ads) {
            CreativeSelector.Choice c = chosen.get(a.adId());
            out.add(c == null ? a : new SponsoredAd(
                    a.adId(), a.itemId(), a.advertiserId(), a.bidwordId(),
                    c.title(), a.channel(), a.bid(), a.quality(), a.relevance(),
                    a.pctr(), a.pctrCalibrated(), a.ecpm(), a.chargedPrice(), a.position(), c.creativeId(),
                    a.bidType()));
        }
        return out;
    }

    /** GD 保量:有落后合约则把 GD 广告置首位、其余顺位后移并截断,记一次交付;无则原样返回。 */
    private List<SponsoredAd> applyGuaranteedDelivery(long userId, List<SponsoredAd> ads, int wantSlots) {
        var sel = guaranteedDelivery.select(userId, 1);
        if (sel.isEmpty()) {
            return ads;
        }
        long gdAdId = sel.get().ad().adId();
        List<SponsoredAd> out = new ArrayList<>(wantSlots);
        out.add(reposition(sel.get().ad(), 1));
        for (SponsoredAd a : ads) {
            if (out.size() >= wantSlots) {
                break;
            }
            if (a.adId() == gdAdId) {
                continue;
            }
            out.add(reposition(a, out.size() + 1));
        }
        guaranteedDelivery.recordDelivery(sel.get().contractId());
        return out;
    }

    /** 复制 SponsoredAd 并改位次(GD 插位后重排)。 */
    private static SponsoredAd reposition(SponsoredAd a, int pos) {
        return new SponsoredAd(a.adId(), a.itemId(), a.advertiserId(), a.bidwordId(), a.title(),
                a.channel(), a.bid(), a.quality(), a.relevance(), a.pctr(), a.pctrCalibrated(),
                a.ecpm(), a.chargedPrice(), pos, a.creativeId(), a.bidType());
    }

    /** CPM/OCPM 广告曝光即扣(A1);其余计费模式在各自事件扣,此处跳过。 */
    private void chargeImpressionBilled(List<SponsoredAd> ads) {
        for (SponsoredAd a : ads) {
            if (com.recsys.common.ad.BidType.from(a.bidType()).chargeOnImpression()) {
                pacingService.charge(a.advertiserId(), a.chargedPrice());
                meterRegistry.counter("recsys.ad.revenue.cents")
                        .increment(Math.round(a.chargedPrice() * 100));
            }
        }
    }

    /** 复用 RankRouter 给候选广告关联 item 打分,得 itemId → 原始 pCTR + pCVR(A6 DFM 去偏 pCVR 可覆盖)。 */
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
        if (props.getCvr().isEnabled() && dfmCvrService.isReady()) {
            pcvr.putAll(dfmCvrService.pcvr(userId, new ArrayList<>(itemIds)));
        }
        return new RankScores(pctr, pcvr);
    }

    /** 排序模型产出的 pCTR / pCVR 两张表(pCVR 可能为空)。 */
    private record RankScores(Map<Long, Double> pctr, Map<Long, Double> pcvr) {
    }
}
