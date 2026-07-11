package com.recsys.ad;

import com.recsys.common.ad.AdCandidate;
import com.recsys.common.ad.BidType;
import com.recsys.common.ad.Calibrator;
import com.recsys.common.ad.SponsoredAd;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 竞价排序(eCPM)+ 拍卖计费(GSP),docs/05 §4.5/§4.6。
 *
 * <p><b>排序与计费分离</b>(变现正确性的核心)。排序分(Ad Rank)对齐业界 Quality Score 思路——
 * 把 query↔ad 相关性纳入排序,避免高出价但不相关的兜底广告盖过相关广告:
 * <ul>
 *   <li>计费因子 {@code billFactor}(每单位出价的每次曝光期望收入,<b>随计费模式变</b>,A1):
 *       CPC/OCPC=pCTR×quality×relevance、CPM=quality×relevance、CPA/OCPM=pCTR×pCVR×quality×relevance;</li>
 *   <li>排序:{@code eCPM(AdRank) = pacedBid × billFactor},降序,过滤 {@code eCPM < reserve};</li>
 *   <li>计费:GSP 次价——{@code price_i = eCPM_{i+1} / billFactor_i + ε},<b>天然以自身计费单位计价</b>
 *       (CPC 得每点击价、CPM 得每曝光价、CPA 得每转化价),下限 reserve,上限自身出价。</li>
 * </ul>
 * <p>不同计费单位因此在同一场竞价里同尺可比;结算发生在各自计费事件(CPC/OCPC 点击、CPM/OCPM 曝光、
 * CPA 转化),由编排层按 {@link com.recsys.common.ad.BidType} 分流(见 SearchAdsOrchestrator)。
 *
 * <p>红线:进 eCPM/计费的是 <b>校准后</b> pCTR(经 {@link Calibrator});pacing 折扣在出价上施加。
 * 本类无副作用(不扣预算、不写日志)——计费扣减与 ad_event 落库由编排层在拿到结果后执行,便于测试。
 */
@Service
public class BiddingService {

    private final Calibrator calibrator;
    private final PacingService pacing;
    private final RelevanceGate gate;
    private final OcpcBidder ocpcBidder;
    private final ExplorationService explorer;
    private final QualityScoreService qualityScore;
    private final AdProperties props;

    public BiddingService(Calibrator calibrator, PacingService pacing,
                          RelevanceGate gate, OcpcBidder ocpcBidder,
                          ExplorationService explorer, QualityScoreService qualityScore,
                          AdProperties props) {
        this.calibrator = calibrator;
        this.pacing = pacing;
        this.gate = gate;
        this.ocpcBidder = ocpcBidder;
        this.explorer = explorer;
        this.qualityScore = qualityScore;
        this.props = props;
    }

    private static final AdRepository.OcpcParams DEFAULT_CPC = new AdRepository.OcpcParams("CPC", 0.0);

    /**
     * @param candidates    过了相关性门槛 + 预算过滤的候选
     * @param pctrRawByItem  itemId → 排序模型原始 pCTR(编排层用 RankRouter 算好传入)
     * @param pcvrByItem     itemId → 排序模型 pCVR(mmoe/din 给出,供 oCPC 自动出价;缺则用先验)
     * @param ocpcByAd       adId → oCPC 参数(优化方式 / 目标 CPA);CPC 广告退手动出价
     * @param titleByAd      adId → 创意标题(编排层批量加载)
     * @param calibModel     校准表标识
     * @param slots          广告位数
     * @return 竞得展示的广告,按位次升序;空表示无广告达到 reserve
     */
    public List<SponsoredAd> auction(List<AdCandidate> candidates,
                                     Map<Long, Double> pctrRawByItem,
                                     Map<Long, Double> pcvrByItem,
                                     Map<Long, AdRepository.OcpcParams> ocpcByAd,
                                     Map<Long, String> titleByAd,
                                     String calibModel,
                                     int slots,
                                     double reserve,
                                     ListwiseExternality.Sim sim) {
        AdProperties.Auction cfg = props.getAuction();

        // 0. 批量预取每候选/每广告主的 Redis 参数(各一次 MGET),避免竞价循环里逐候选 N 次串行往返。
        //    返回稀疏 Map(缺失/默认值不入 Map),循环内 getOrDefault 保持与逐条读完全一致的数值语义。
        Set<Long> adIds = new HashSet<>();
        Set<Long> advIds = new HashSet<>();
        for (AdCandidate c : candidates) {
            adIds.add(c.adId());
            advIds.add(c.advertiserId());
        }
        Map<Long, Double> pacingByAdv = pacing.pacingFactors(advIds);
        Map<Long, Double> qualityByAd = qualityScore.refinedBatch(adIds);
        Map<Long, Double> boostByAd = explorer.boosts(adIds);
        Map<Long, Double> ocpcCoefByAdv = ocpcBidder.coefficients(advIds);

        // 1. 算 eCPM(AdRank = pacedBid × billFactor),过滤低于 reserve。
        //    billFactor 是"每单位出价的每次曝光期望收入",按计费模式变——使 CPC/CPM/CPA 同尺可比(A1)。
        List<Scored> scored = new ArrayList<>(candidates.size());
        for (AdCandidate c : candidates) {
            double pctrRaw = pctrRawByItem.getOrDefault(c.itemId(), 0.0);
            double pctrCalib = clampProb(calibrator.calibrate(pctrRaw, calibModel));
            AdRepository.OcpcParams ocpc = ocpcByAd.getOrDefault(c.adId(), DEFAULT_CPC);
            BidType type = BidType.from(ocpc.optimizationType());
            double pcvr = pcvrByItem == null ? 0.0 : pcvrByItem.getOrDefault(c.itemId(), 0.0);
            double effBid = effectiveBid(type, ocpc, c, pcvr, ocpcCoefByAdv.getOrDefault(c.advertiserId(), 1.0));
            double pacedBid = effBid * pacingByAdv.getOrDefault(c.advertiserId(), 1.0);
            double relevance = gate.relevance(c);
            // 精细化质量度(M7):有数据的广告用 ad-quality 算好的数据驱动分,缺失退广告自带 quality_score
            double quality = qualityByAd.getOrDefault(c.adId(), c.quality());
            double billFactor = billFactor(type, pctrCalib, pcvr, quality, relevance);
            double ecpm = pacedBid * billFactor;
            // EE 探索:新广告(曝光不足)得 UCB 加成抬升<b>排序</b> eCPM;计费仍按未加成的 billFactor(守红线)
            double rankEcpm = ecpm * boostByAd.getOrDefault(c.adId(), 1.0);
            if (rankEcpm < reserve) {
                continue;
            }
            scored.add(new Scored(c, type, effBid, pacedBid, pctrRaw, pctrCalib, quality, billFactor, ecpm, rankEcpm, relevance));
        }

        // 2a. VCG 位置拍卖(docs/05 §4.6/§7 M7):激励相容,付"对其他人的外部性"。委托纯函数 VcgAuction。
        AdProperties.Vcg vcg = cfg.getVcg();
        if (vcg.isEnabled()) {
            return vcgAuction(scored, vcg, titleByAd, slots, reserve, cfg.getPriceIncrement());
        }

        // 2b. List-wise 外部性(docs/05 §7 M7):整页贪心选择 + 外部性折扣后的 GSP。委托纯函数 ListwiseExternality。
        AdProperties.Listwise lw = cfg.getListwise();
        if (lw.isEnabled() && sim != null) {
            return listwiseAuction(scored, sim, lw, titleByAd, slots, reserve, cfg.getPriceIncrement());
        }

        // 2c. 逐条 eCPM 降序(默认路径)
        scored.sort((a, b) -> Double.compare(b.rankEcpm, a.rankEcpm));

        // 3. 取 top slots,GSP 次价计费(阈值用次位 Ad Rank,除以自身未加成 effQuality → 探索不抬高自身价)
        int n = Math.min(slots, scored.size());
        List<SponsoredAd> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            Scored s = scored.get(i);
            // 次位 Ad Rank:末位用 reserve 兜底。charged = nextRank/billFactor 天然以自身计费单位计价
            double nextRank = (i + 1 < scored.size()) ? scored.get(i + 1).rankEcpm : reserve;
            double charged = s.billFactor <= 0 ? reserve : nextRank / s.billFactor + cfg.getPriceIncrement();
            charged = Math.max(reserve, Math.min(charged, s.pacedBid)); // [reserve, 自身出价]
            out.add(toAd(s, titleByAd, charged, i + 1));
        }
        return out;
    }

    /** VCG 路径:scored → VcgAuction 按 rankEcpm 占位 + 外部性定价 → SponsoredAd。 */
    private List<SponsoredAd> vcgAuction(List<Scored> scored, AdProperties.Vcg cfg,
                                         Map<Long, String> titleByAd, int slots,
                                         double reserve, double priceIncrement) {
        double[] theta = VcgAuction.theta(slots, cfg.getPositionDiscounts(), cfg.getTailDecay());
        List<VcgAuction.Entry> entries = new ArrayList<>(scored.size());
        for (int i = 0; i < scored.size(); i++) {
            Scored s = scored.get(i);
            entries.add(new VcgAuction.Entry(i, s.pacedBid, s.billFactor, s.rankEcpm, s.ecpm));
        }
        List<VcgAuction.Placed> placed = VcgAuction.select(entries, theta, slots, reserve, priceIncrement);
        List<SponsoredAd> out = new ArrayList<>(placed.size());
        for (VcgAuction.Placed p : placed) {
            out.add(toAd(scored.get(p.idx()), titleByAd, p.charged(), p.position()));
        }
        return out;
    }

    /** List-wise 外部性路径:scored → ListwiseExternality 贪心排版 + 外部性 GSP 定价 → SponsoredAd。 */
    private List<SponsoredAd> listwiseAuction(List<Scored> scored, ListwiseExternality.Sim sim,
                                              AdProperties.Listwise lw, Map<Long, String> titleByAd,
                                              int slots, double reserve, double priceIncrement) {
        List<ListwiseExternality.Entry> entries = new ArrayList<>(scored.size());
        for (int i = 0; i < scored.size(); i++) {
            Scored s = scored.get(i);
            entries.add(new ListwiseExternality.Entry(
                    i, s.candidate.itemId(), s.pacedBid, s.billFactor, s.rankEcpm));
        }
        List<ListwiseExternality.Placed> placed = ListwiseExternality.select(
                entries, sim, lw.getExternalityWeight(), lw.getMinRetention(),
                slots, reserve, priceIncrement);
        List<SponsoredAd> out = new ArrayList<>(placed.size());
        for (ListwiseExternality.Placed p : placed) {
            out.add(toAd(scored.get(p.idx()), titleByAd, p.charged(), p.position()));
        }
        return out;
    }

    private static SponsoredAd toAd(Scored s, Map<Long, String> titleByAd, double charged, int position) {
        AdCandidate c = s.candidate;
        return new SponsoredAd(
                c.adId(), c.itemId(), c.advertiserId(), c.bidwordId(),
                titleByAd.getOrDefault(c.adId(), ""),
                c.channel(), s.effBid, s.quality, s.relevance,   // quality = 进 eCPM 的精细化质量度(审计一致)
                s.pctrRaw, s.pctrCalib, s.ecpm, charged, position, 0L,  // creativeId=0:默认创意,DCO 开启时编排层覆盖
                s.type.name());   // 计费模式:决定 charged 的计费单位 + 结算事件(A1)
    }

    /**
     * 有效出价:OCPC 用 {@link OcpcBidder}(targetCpa×pCVR×k,per click);OCPM 取 targetCpa
     * (billFactor 已含 pCTR×pCVR,故此处不再乘 pCVR);其余用手动出价。
     */
    private double effectiveBid(BidType type, AdRepository.OcpcParams ocpc, AdCandidate c, double pcvr,
                                double ocpcCoef) {
        return switch (type) {
            case OCPC -> ocpcBidder.effectiveBid(
                    ocpc.optimizationType(), ocpc.targetCpa(), c.bid(), pcvr, ocpcCoef);
            case OCPM -> ocpc.targetCpa() > 0 ? ocpc.targetCpa() : c.bid();
            default -> c.bid();   // CPC / CPM / CPA:手动出价(各自的计费单位)
        };
    }

    /**
     * billFactor:eCPM = pacedBid × billFactor 中的因子,把不同计费单位的出价换算为每次曝光期望收入。
     * <ul>
     *   <li>CPC/OCPC(按点击):pCTR × quality × relevance;</li>
     *   <li>CPM(按曝光):quality × relevance(收入不依赖点击);</li>
     *   <li>CPA / OCPM(按转化优化):pCTR × pCVR × quality × relevance(曝光→点击→转化)。</li>
     * </ul>
     */
    // package-private:供 BiddingServiceBillFactorTest 直接校验各计费模式的 eCPM 经济学(A1)
    static double billFactor(BidType type, double pctrCalib, double pcvr,
                             double quality, double relevance) {
        double base = quality * relevance;
        return switch (type) {
            case CPC, OCPC -> pctrCalib * base;
            case CPM -> base;
            case CPA, OCPM -> pctrCalib * pcvr * base;
        };
    }

    private static double clampProb(double p) {
        return Math.max(0.0, Math.min(1.0, p));
    }

    private record Scored(AdCandidate candidate, BidType type, double effBid, double pacedBid, double pctrRaw,
                          double pctrCalib, double quality, double billFactor, double ecpm,
                          double rankEcpm, double relevance) {
    }
}
