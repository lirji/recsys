package com.recsys.ad;

import com.recsys.common.ad.AdCandidate;
import com.recsys.common.ad.Calibrator;
import com.recsys.common.ad.SponsoredAd;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 竞价排序(eCPM)+ 拍卖计费(GSP),docs/05 §4.5/§4.6。
 *
 * <p><b>排序与计费分离</b>(变现正确性的核心)。排序分(Ad Rank)对齐业界 Quality Score 思路——
 * 把 query↔ad 相关性纳入排序,避免高出价但不相关的兜底广告盖过相关广告:
 * <ul>
 *   <li>有效质量 {@code effQuality = pCTR_calib × quality × relevance};</li>
 *   <li>排序:{@code eCPM(AdRank) = pacedBid × effQuality},降序,过滤 {@code eCPM < reserve};</li>
 *   <li>计费:GSP 次价——位置 i 扣费 = 让它保住该位所需的最小出价
 *       {@code price_i = eCPM_{i+1} / effQuality_i + ε},下限 reserve,上限自身出价。</li>
 * </ul>
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

        // 1. 算 eCPM(AdRank = pacedBid × pCTR_calib × quality × relevance),过滤低于 reserve
        List<Scored> scored = new ArrayList<>(candidates.size());
        for (AdCandidate c : candidates) {
            double pctrRaw = pctrRawByItem.getOrDefault(c.itemId(), 0.0);
            double pctrCalib = clampProb(calibrator.calibrate(pctrRaw, calibModel));
            // oCPC:把目标 CPA 自动换算成出价(bid = targetCpa × pCVR × k);CPC 广告退手动出价
            AdRepository.OcpcParams ocpc = ocpcByAd.getOrDefault(c.adId(), DEFAULT_CPC);
            double pcvr = pcvrByItem == null ? 0.0 : pcvrByItem.getOrDefault(c.itemId(), 0.0);
            double effBid = ocpcBidder.effectiveBid(
                    ocpc.optimizationType(), ocpc.targetCpa(), c.bid(), c.advertiserId(), pcvr);
            double pacedBid = effBid * pacing.pacingFactor(c.advertiserId());
            double relevance = gate.relevance(c);
            // 精细化质量度(M7):有数据的广告用 ad-quality 算好的数据驱动分,缺失退广告自带 quality_score
            double quality = qualityScore.refined(c.adId(), c.quality());
            double effQuality = pctrCalib * quality * relevance; // 有效质量(含相关性)
            double ecpm = pacedBid * effQuality;
            // EE 探索:新广告(曝光不足)得 UCB 加成抬升<b>排序</b> eCPM;计费仍按未加成的 effQuality(守红线)
            double rankEcpm = ecpm * explorer.boost(c.adId());
            if (rankEcpm < reserve) {
                continue;
            }
            scored.add(new Scored(c, effBid, pacedBid, pctrRaw, pctrCalib, quality, effQuality, ecpm, rankEcpm, relevance));
        }

        // 2a. List-wise 外部性(docs/05 §7 M7):整页贪心选择 + 外部性折扣后的 GSP。委托纯函数 ListwiseExternality。
        AdProperties.Listwise lw = cfg.getListwise();
        if (lw.isEnabled() && sim != null) {
            return listwiseAuction(scored, sim, lw, titleByAd, slots, reserve, cfg.getPriceIncrement());
        }

        // 2b. 逐条 eCPM 降序(默认路径)
        scored.sort((a, b) -> Double.compare(b.rankEcpm, a.rankEcpm));

        // 3. 取 top slots,GSP 次价计费(阈值用次位 Ad Rank,除以自身未加成 effQuality → 探索不抬高自身价)
        int n = Math.min(slots, scored.size());
        List<SponsoredAd> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            Scored s = scored.get(i);
            // 次位 Ad Rank:末位用 reserve 兜底
            double nextRank = (i + 1 < scored.size()) ? scored.get(i + 1).rankEcpm : reserve;
            double charged = s.effQuality <= 0 ? reserve : nextRank / s.effQuality + cfg.getPriceIncrement();
            charged = Math.max(reserve, Math.min(charged, s.pacedBid)); // [reserve, 自身出价]
            out.add(toAd(s, titleByAd, charged, i + 1));
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
                    i, s.candidate.itemId(), s.pacedBid, s.effQuality, s.rankEcpm));
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
                s.pctrRaw, s.pctrCalib, s.ecpm, charged, position);
    }

    private static double clampProb(double p) {
        return Math.max(0.0, Math.min(1.0, p));
    }

    private record Scored(AdCandidate candidate, double effBid, double pacedBid, double pctrRaw,
                          double pctrCalib, double quality, double effQuality, double ecpm,
                          double rankEcpm, double relevance) {
    }
}
