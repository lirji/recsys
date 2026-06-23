package com.recsys.ad;

import java.util.ArrayList;
import java.util.List;

/**
 * VCG(Vickrey–Clarke–Groves)位置拍卖的**纯函数**核心(docs/05 §4.6/§7 M7)。无 Spring、无 IO,
 * 数学全收敛于此便于单测——同 {@link ListwiseExternality} / {@code DelayModel} / {@code FeatureAssembler}
 * 的角色。{@link BiddingService} 算好每条候选的 {@link Entry} 后委托本类排版与定价。
 *
 * <p><b>为什么 VCG</b>:GSP(逐位次价)只让每个广告付"保住该位的最小出价",非激励相容——广告主有动机
 * 谎报出价套利。VCG 让每个广告付它给<b>其他人</b>造成的福利损失(externality),从而<b>说真话出价</b>是占优策略
 * (truthful)。代价是解释成本高(工业界仍多用 GSP),故本仓库默认关、与 GSP/List-wise 并存可切换。
 *
 * <p><b>位置点击折扣模型</b>(完整 VCG 相对 List-wise 近似补的关键一环):点击率按位次衰减,
 * {@code CTR(i, j) = effQuality_i × θ_j},其中 {@code θ_1 ≥ θ_2 ≥ … ≥ θ_m > θ_{m+1}=0} 是位置折扣
 * (越靠下越少被点)。某广告在第 j 位的**每次曝光价值** = {@code value_i × θ_j},
 * 其中 {@code value_i = pacedBid_i × effQuality_i}(= 不含探索加成的 eCPM,每次曝光价值@θ=1)。
 *
 * <p><b>分配</b>:按 {@code rankEcpm}(含 EE 探索加成)降序占位 1..m——与 GSP/List-wise 同口径,
 * 探索只影响"谁出现",不进计费(守 §8 #3 红线)。
 *
 * <p><b>计费(VCG 外部性)</b>:第 k 位广告的**每次曝光**总付费 = 它在场 vs 不在场时其他人福利之差。
 * 移除第 k 位 → 其下所有广告各上移一位(第 m+1 名的首个落选者补进第 m 位):
 * <pre>T_k = Σ_{j=k+1}^{m+1} value_{(j)} · (θ_{j-1} − θ_j)    (θ_{m+1}=0,value_{(m+1)}=首落选者价值)</pre>
 * 换算成 CPC(每次点击价,与 GSP 同单位):除以本位期望点击 {@code θ_k × effQuality_k}:
 * <pre>price_k = T_k / (θ_k × effQuality_k) + ε,clamp 到 [reserve, 自身出价]</pre>
 * 保留价以"边际落选者价值的下限"({@code max(首落选者价值, reserve)})进入 T 的末项,等价于 GSP 的 reserve 兜底。
 *
 * <p><b>退化自洽</b>:单广告位(m=1)时 {@code price = value_{(2)} / effQuality_1} —— 与 GSP 单位次完全一致;
 * 位置折扣全相等(θ_j 恒定)时人人付"首落选者 eCPM ÷ 自身有效质量"。验证见 {@code VcgAuctionTest}。
 */
public final class VcgAuction {

    private VcgAuction() {
    }

    /**
     * 一条参与拍卖的候选(已由 {@link BiddingService} 完成校准/出价/质量计算)。
     *
     * @param idx        回指 BiddingService 内部 scored 列表的下标(产出按此映射回 SponsoredAd)
     * @param pacedBid   pacing 折扣后的出价(元/点击),计费上限
     * @param effQuality 有效质量 = pCTR_calib × quality × relevance(位置无关的基线点击倾向,不含探索加成)
     * @param rankEcpm   排序分 = eCPM × 探索加成(分配占位的依据)
     * @param value      每次曝光价值@θ=1 = pacedBid × effQuality(不含探索加成,计费用)
     */
    record Entry(int idx, double pacedBid, double effQuality, double rankEcpm, double value) {
    }

    /**
     * 一个竞得位次的结果。
     *
     * @param idx      回指 scored 下标
     * @param position 位次(1 基)
     * @param charged  VCG 外部性 CPC 扣费(元/点击)
     */
    record Placed(int idx, int position, double charged) {
    }

    /**
     * VCG 位置拍卖:按 rankEcpm 降序占位 + 外部性定价。
     *
     * @param scored          候选(顺序无关;内部按 rankEcpm 降序占位)
     * @param theta           位置点击折扣 θ_1..θ_m(0 基,单调非增,(0,1]);长度即可用广告位上限
     * @param slots           广告位数
     * @param reserve         保留价(边际外部性下限 + 计费下限)
     * @param priceIncrement  加价 ε
     * @return 竞得位次,按 position 升序;空表示无广告
     */
    static List<Placed> select(List<Entry> scored, double[] theta, int slots,
                               double reserve, double priceIncrement) {
        List<Entry> sorted = new ArrayList<>(scored);
        sorted.sort((a, b) -> Double.compare(b.rankEcpm(), a.rankEcpm()));
        int n = sorted.size();
        int m = Math.min(Math.min(slots, n), theta.length);

        // 首个落选者价值(第 m+1 名,移除某胜者时它补进第 m 位);无落选者则 0,再以 reserve 托底
        double firstLoserValue = (n > m) ? sorted.get(m).value() : 0.0;
        double marginValue = Math.max(firstLoserValue, reserve);

        List<Placed> out = new ArrayList<>(m);
        for (int k = 1; k <= m; k++) {
            Entry e = sorted.get(k - 1);
            // T_k = Σ_{j=k+1}^{m} value_{(j)}·(θ_{j-1}−θ_j) + marginValue·θ_m
            double total = 0.0;
            for (int j = k + 1; j <= m; j++) {
                total += sorted.get(j - 1).value() * (theta[j - 2] - theta[j - 1]);
            }
            total += marginValue * theta[m - 1];

            double clicks = theta[k - 1] * e.effQuality();   // 本位期望点击
            double charged = clicks <= 0 ? reserve : total / clicks + priceIncrement;
            charged = Math.max(reserve, Math.min(charged, e.pacedBid()));  // [reserve, 自身出价]
            out.add(new Placed(e.idx(), k, charged));
        }
        return out;
    }

    /**
     * 构造长度 {@code m} 的位置折扣 θ_1..θ_m(0 基):前 {@code discounts.size()} 位取配置值,
     * 超出部分按 {@code tailDecay} 几何衰减;最后强制单调非增并 clamp 到 (0,1]。
     */
    static double[] theta(int m, List<Double> discounts, double tailDecay) {
        double[] t = new double[Math.max(m, 0)];
        for (int i = 0; i < t.length; i++) {
            double raw = i < discounts.size() ? discounts.get(i)
                    : (i == 0 ? 1.0 : t[i - 1] * tailDecay);
            double v = Math.max(1e-9, Math.min(1.0, raw));      // (0,1]
            t[i] = i > 0 ? Math.min(v, t[i - 1]) : v;            // 单调非增
        }
        return t;
    }
}
