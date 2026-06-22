package com.recsys.ad;

import java.util.ArrayList;
import java.util.List;

/**
 * List-wise 外部性拍卖的**纯函数**核心(docs/05 §7 M7):贪心整页选择 + 外部性折扣后的 GSP 计费。
 * 无 Spring、无 IO,数学全收敛于此便于单测——同 {@code DelayModel} 之于延迟转化、{@code FeatureAssembler}
 * 之于特征一致性的角色。{@link BiddingService} 算好每条候选的 {@link Entry} 后委托本类排版与定价。
 *
 * <p><b>为什么</b>:逐条 eCPM 降序 + 逐位独立 GSP 忽略广告间相互影响——相邻同类广告蚕食彼此 CTR
 * (替代效应)。整页最优 ≠ 逐条最优。
 *
 * <p><b>选择(贪心 MMR 思路)</b>:第 1 位取 rankEcpm 最高(无已选、无衰减)。之后每位选
 * {@code adjRank = rankEcpm × extFactor} 最大者,其中
 * {@code extFactor = clamp(1 − λ·maxSim(已选), minRetention, 1)} —— 与已展示广告越像、衰减越狠,
 * 于是"出价略低但更多样"的广告可能后来居上。{@code adjRank < reserve} 即停止补位(空位也比贴片强)。
 *
 * <p><b>计费(外部性 GSP)</b>:位 i 扣费 = 它在<b>该位竞争时</b>被它挤掉的次优广告的 {@code adjRank},
 * 除以它自己<b>在上下文中的</b>有效质量 {@code effQuality × extFactor}:
 * <pre>price_i = thresholdAdj_i / (effQuality_i × extFactor_i) + ε,clamp 到 [reserve, 自身出价]</pre>
 * 经济含义:在上下文里表现更差(extFactor 低)的广告需更高出价才能保位,于是按其更低的上下文质量
 * 折算出更高的每次点击价——可审计、可对账(守 §8 #3 红线)。这是 <b>GSP-with-externality</b> 的教学近似,
 * 非完整 VCG(完整 VCG 需位置点击折扣模型 + 每次点击单位换算,审计成本高,列为后续)。
 *
 * <p>退化:相似度恒 0(无向量)→ extFactor 恒 1 → 选择与计费等同逐条 eCPM + 原 GSP。
 */
public final class ListwiseExternality {

    private ListwiseExternality() {
    }

    /** 物品对相似度,[0,1];无向量则约定返回 0(不施加外部性衰减)。 */
    @FunctionalInterface
    public interface Sim {
        double between(long itemA, long itemB);
    }

    /**
     * 一条参与排版的候选(已由 {@link BiddingService} 完成校准/出价/质量计算)。
     *
     * @param idx        回指 BiddingService 内部 scored 列表的下标(产出按此映射回 SponsoredAd)
     * @param itemId     关联物品(算外部性相似度用)
     * @param pacedBid   pacing 折扣后的出价(元/点击),计费上限
     * @param effQuality 有效质量 = pCTR_calib × quality × relevance(不含探索加成)
     * @param rankEcpm   排序分 = eCPM × 探索加成(贪心选择的基准)
     */
    record Entry(int idx, long itemId, double pacedBid, double effQuality, double rankEcpm) {
    }

    /**
     * 一个竞得位次的结果。
     *
     * @param idx       回指 scored 下标
     * @param position  位次(1 基)
     * @param extFactor 该位的外部性折扣因子(∈[minRetention,1])
     * @param charged   外部性 GSP 扣费(元/点击)
     */
    record Placed(int idx, int position, double extFactor, double charged) {
    }

    /**
     * 贪心整页选择 + 外部性 GSP 计费。
     *
     * @param scored          候选(顺序无关;内部按 adjRank 贪心)
     * @param sim             物品相似度
     * @param lambda          外部性强度 λ
     * @param minRetention    extFactor 下限
     * @param slots           广告位数
     * @param reserve         保留价(选择停止阈值 + 计费下限)
     * @param priceIncrement  GSP 加价 ε
     * @return 竞得位次,按 position 升序;空表示无广告达到 reserve
     */
    static List<Placed> select(List<Entry> scored, Sim sim, double lambda, double minRetention,
                               int slots, double reserve, double priceIncrement) {
        int n = Math.min(slots, scored.size());
        List<Placed> out = new ArrayList<>(n);
        List<Entry> remaining = new ArrayList<>(scored);
        List<Long> selectedItems = new ArrayList<>(n);

        for (int pos = 1; pos <= n && !remaining.isEmpty(); pos++) {
            // 在当前已选上下文下,为每个候选算 adjRank,取最高(winner)与次高(threshold)
            int bestK = -1;
            double bestAdj = -Double.MAX_VALUE;
            double secondAdj = -Double.MAX_VALUE;
            double bestExt = 1.0;
            for (int k = 0; k < remaining.size(); k++) {
                Entry e = remaining.get(k);
                double ext = extFactor(e.itemId(), selectedItems, sim, lambda, minRetention);
                double adj = e.rankEcpm() * ext;
                if (adj > bestAdj) {
                    secondAdj = bestAdj;
                    bestAdj = adj;
                    bestExt = ext;
                    bestK = k;
                } else if (adj > secondAdj) {
                    secondAdj = adj;
                }
            }
            if (bestAdj < reserve) {
                break;  // 连最优都不及保留价,余位留空
            }
            Entry winner = remaining.remove(bestK);
            // 计费阈值:被挤掉的次优 adjRank;无次优时退保留价
            double threshold = Math.max(secondAdj == -Double.MAX_VALUE ? reserve : secondAdj, reserve);
            double ctxQuality = winner.effQuality() * bestExt;
            double charged = ctxQuality <= 0 ? reserve : threshold / ctxQuality + priceIncrement;
            charged = Math.max(reserve, Math.min(charged, winner.pacedBid()));
            out.add(new Placed(winner.idx(), pos, bestExt, charged));
            selectedItems.add(winner.itemId());
        }
        return out;
    }

    /** extFactor = clamp(1 − λ·max_{已选} sim, minRetention, 1)。已选为空 → 1。 */
    private static double extFactor(long itemId, List<Long> selected, Sim sim,
                                    double lambda, double minRetention) {
        double maxSim = 0.0;
        for (long s : selected) {
            maxSim = Math.max(maxSim, sim.between(itemId, s));
        }
        double f = 1.0 - lambda * maxSim;
        return Math.max(minRetention, Math.min(1.0, f));
    }
}
