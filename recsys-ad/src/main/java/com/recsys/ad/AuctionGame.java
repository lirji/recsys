package com.recsys.ad;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * 拍卖机制的**博弈论分析器**(纯函数,docs/05 §6/§7 M7)。无 Spring、无 IO,数学全收敛于此便于单测——
 * 同 {@link VcgAuction} / {@link ListwiseExternality} / {@code DelayModel} 的角色。这是**离线诊断**工具,
 * 不改在线 {@link BiddingService} 行为;回答"我们的拍卖机制博弈稳定吗、能不能被套利"。
 *
 * <p><b>为什么</b>:有了机制(GSP / VCG)还不够——计费正确性(§8 #3)要求知道广告主能否通过**谎报出价**
 * 或**联合出价**套利。本类用分离-CTR 位置拍卖标准模型({@code CTR(i,j)=effQuality_i×θ_j},同 {@link VcgAuction})
 * 把"博弈稳定性"从口号变成可度量、可单测的结论:
 * <ol>
 *   <li><b>激励相容(DSIC)</b> {@link #maxTruthfulRegret}:全员说真话时,任一广告主单边改报出价能多赚多少。
 *       VCG ⇒ 0(说真话是占优策略);GSP ⇒ &gt;0(可暴露具体获利偏离,见 {@link #bestResponse})。</li>
 *   <li><b>GSP 对称纳什均衡 ⇒ VCG 结果</b> {@link #gspSymmetricNashBids}:GSP 虽不真实,但其(最低)对称纳什均衡
 *       的每次点击价 = VCG 价(EOS/Varian 经典等价定理)。所以即使线上跑 GSP,理性广告主的稳定出价会把
 *       价格收敛到 VCG——VCG 是正确的参考价。</li>
 *   <li><b>联合出价套利</b> {@link #findCollusionArbitrage}:联盟在**不改变各自位次**前提下能否压低总付费。
 *       结论(诚实):GSP 与 VCG <b>都</b>可被联盟操纵——VCG 只防单边偏离、不防合谋(VCG 非 group-strategyproof)。</li>
 * </ol>
 *
 * <p>玩家以 {@code value}(真实每次点击价值,私有)+ {@code effQuality}(广告效应 e_i,可观测)描述;
 * 出价 {@code bid} 为申报每次点击价。分配按 {@code bid×effQuality} 降序(同 {@link BiddingService}),
 * GSP 价 = 紧邻下位 rankScore ÷ 自身 effQuality(与位置折扣无关),VCG 价委托 {@link VcgAuction}。
 * 效用按真实价值算:{@code u = θ_slot × effQuality × (value − price)}。
 */
public final class AuctionGame {

    private static final double TOL = 1e-9;

    private AuctionGame() {
    }

    /** 拍卖机制。GSP=广义次价(非真实但工业界常用);VCG=委托 {@link VcgAuction}(真实/激励相容)。 */
    public enum Mechanism {GSP, VCG}

    /**
     * 一次拍卖结果(数组按玩家原始下标对齐)。
     *
     * @param slot          位次(1 基),0 = 未中标
     * @param perClickPrice 每次点击扣费(仅中标者有意义)
     * @param utility       每次曝光期望效用 = θ_slot×effQuality×(value−price);未中标为 0
     * @param revenue       平台每次曝光期望收入 = Σ θ_slot×effQuality×price
     */
    public record Outcome(int[] slot, double[] perClickPrice, double[] utility, double revenue) {
    }

    /** {@link #bestResponse} 的结果:最优偏离出价、其效用、相对基线(传入 bids[i])的获利。 */
    public record BestResponse(double bid, double utility, double gainOverBaseline) {
    }

    /**
     * 联合出价套利检测结果。
     *
     * @param found            是否找到"保各自位次、降总付费"的合谋偏离
     * @param baselinePayment  全员真实出价时联盟的每次曝光总付费
     * @param bestPayment      搜索网格内联盟能压到的最低总付费
     * @param bids             达到 bestPayment 的出价向量(联盟成员被压价,其余真实)
     */
    public record Collusion(boolean found, double baselinePayment, double bestPayment, double[] bids) {
    }

    /** 按 bid×effQuality 降序分配、按机制定价、按 trueValue 算效用。theta 为位置点击折扣(0 基)。 */
    static Outcome run(Mechanism mech, double[] bids, double[] effQ, double[] trueValue,
                       double[] theta, double reserve, double inc) {
        int n = bids.length;
        double[] rs = new double[n];
        for (int i = 0; i < n; i++) {
            rs[i] = bids[i] * effQ[i];
        }
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }
        Arrays.sort(order, (a, b) -> rs[a] != rs[b] ? Double.compare(rs[b], rs[a]) : Integer.compare(a, b));
        int m = Math.min(theta.length, n);

        int[] slot = new int[n];
        double[] price = new double[n];
        double[] util = new double[n];

        if (mech == Mechanism.GSP) {
            for (int s = 0; s < m; s++) {
                int i = order[s];
                if (rs[i] < reserve) {
                    break;  // 自上而下首个不及 reserve 即停(其下也不会更高)
                }
                double nextRs = Math.max((s + 1 < n) ? rs[order[s + 1]] : reserve, reserve);
                double p = Math.max(reserve, Math.min(nextRs / effQ[i] + inc, bids[i]));
                slot[i] = s + 1;
                price[i] = p;
            }
        } else {  // VCG:复用 VcgAuction(只把达到 reserve 的候选交给它,首落选者据此定边际外部性)
            List<VcgAuction.Entry> entries = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                if (rs[i] >= reserve) {
                    entries.add(new VcgAuction.Entry(i, bids[i], effQ[i], rs[i], rs[i]));
                }
            }
            for (VcgAuction.Placed p : VcgAuction.select(entries, theta, m, reserve, inc)) {
                slot[p.idx()] = p.position();
                price[p.idx()] = p.charged();
            }
        }

        double revenue = 0.0;
        for (int i = 0; i < n; i++) {
            if (slot[i] > 0) {
                double clicks = theta[slot[i] - 1] * effQ[i];
                util[i] = clicks * (trueValue[i] - price[i]);
                revenue += clicks * price[i];
            }
        }
        return new Outcome(slot, price, util, revenue);
    }

    /**
     * 玩家 i 在其他人出价固定时的最优反应(best response)。由于 GSP/VCG 的价格在"落在某一位次的区间内"
     * 与自身出价无关,只需对每个可达位次取一个代表出价探测即可精确求最优(无需网格)。
     *
     * @return 最优偏离出价、效用、相对当前 {@code bids[i]} 的获利(&gt;0 即说明可套利)
     */
    static BestResponse bestResponse(Mechanism mech, int i, double[] bids, double[] effQ,
                                     double[] trueValue, double[] theta, double reserve, double inc) {
        List<Double> others = new ArrayList<>();
        for (int k = 0; k < bids.length; k++) {
            if (k != i) {
                others.add(bids[k] * effQ[k]);
            }
        }
        others.sort(Comparator.reverseOrder());

        // 探测 rankScore:压过所有人 / 落在相邻两人之间 / 垫底 / 出局(每个位次区间一个代表)
        List<Double> probes = new ArrayList<>();
        if (others.isEmpty()) {
            probes.add(reserve + 1.0);
        } else {
            probes.add(others.get(0) * 1.5 + 1.0);
            for (int k = 0; k + 1 < others.size(); k++) {
                probes.add((others.get(k) + others.get(k + 1)) / 2);
            }
            probes.add((others.get(others.size() - 1) + reserve) / 2);
        }
        probes.add(0.0);  // 主动出局

        double baseUtil = run(mech, bids, effQ, trueValue, theta, reserve, inc).utility()[i];
        double bestU = baseUtil;
        double bestBid = bids[i];
        double[] trial = bids.clone();
        for (double rsProbe : probes) {
            trial[i] = rsProbe / effQ[i];
            double u = run(mech, trial, effQ, trueValue, theta, reserve, inc).utility()[i];
            if (u > bestU + TOL) {
                bestU = u;
                bestBid = trial[i];
            }
        }
        return new BestResponse(bestBid, bestU, bestU - baseUtil);
    }

    /**
     * 激励相容度量:全员说真话({@code bid=value})时,任一广告主单边改报出价能多赚的最大值。
     * VCG ⇒ ≈0(占优策略真实);GSP ⇒ &gt;0(存在获利偏离)。
     */
    static double maxTruthfulRegret(Mechanism mech, double[] value, double[] effQ,
                                    double[] theta, double reserve, double inc) {
        double max = 0.0;
        for (int i = 0; i < value.length; i++) {
            max = Math.max(max, bestResponse(mech, i, value, effQ, value, theta, reserve, inc).gainOverBaseline());
        }
        return max;
    }

    /**
     * 构造 GSP 的(最低)对称纳什均衡出价:其每次点击价 = VCG 真实价(EOS/Varian 等价定理)。
     * 由 VCG 价反推——令 GSP 对第 s−1 位收取其 VCG 价,需第 s 位占位者出价
     * {@code b_(s) = p^VCG_(s-1)·effQ_(s-1)/effQ_(s)};顶位出真值、落选者出真值(锚定末位价)。
     *
     * <p>注:一般 effQuality 异质时位次序可能被打破,等价定理的干净形式要求同质广告效应——
     * 故验证(见 {@code AuctionGameTest})用等 effQuality 的经典设定。
     */
    static double[] gspSymmetricNashBids(double[] value, double[] effQ,
                                         double[] theta, double reserve, double inc) {
        int n = value.length;
        Outcome vcg = run(Mechanism.VCG, value, effQ, value, theta, reserve, inc);
        int m = 0;
        for (int s : vcg.slot()) {
            m = Math.max(m, s);
        }
        int[] occ = new int[m + 1];  // occ[s] = 第 s 位占位者下标
        for (int i = 0; i < n; i++) {
            if (vcg.slot()[i] >= 1) {
                occ[vcg.slot()[i]] = i;
            }
        }
        double[] bid = value.clone();  // 顶位 + 落选者默认真实出价
        for (int s = 2; s <= m; s++) {
            int up = occ[s - 1];
            int cur = occ[s];
            bid[cur] = vcg.perClickPrice()[up] * effQ[up] / effQ[cur];
        }
        return bid;
    }

    /**
     * 联合出价套利检测:在网格 {@code scaleGrid} 上搜索"联盟成员各自出价下调、但**位次不变**"的偏离,
     * 找能压低联盟每次曝光总付费的方案。找到即说明该机制可被合谋操纵(守 §8 #3 红线的反面教材)。
     *
     * @param coalition  联盟成员下标
     * @param scaleGrid  对成员真实出价的乘子候选(如 {1.0,0.9,…,0.5});取笛卡尔积逐一试
     * @return 是否找到套利 + 基线/最优总付费 + 达到最优的出价向量
     */
    static Collusion findCollusionArbitrage(Mechanism mech, int[] coalition, double[] value, double[] effQ,
                                            double[] theta, double reserve, double inc, double[] scaleGrid) {
        Outcome base = run(mech, value, effQ, value, theta, reserve, inc);
        int[] baseSlots = new int[coalition.length];
        for (int t = 0; t < coalition.length; t++) {
            baseSlots[t] = base.slot()[coalition[t]];
        }
        double basePay = coalitionPayment(base, coalition, effQ, theta);

        double[] bestPay = {basePay};
        double[] bestBids = value.clone();
        searchCollusion(0, coalition, value, effQ, theta, reserve, inc, mech, scaleGrid,
                baseSlots, value.clone(), bestPay, bestBids);
        return new Collusion(bestPay[0] < basePay - 1e-6, basePay, bestPay[0], bestBids);
    }

    private static void searchCollusion(int depth, int[] coalition, double[] value, double[] effQ,
                                        double[] theta, double reserve, double inc, Mechanism mech,
                                        double[] scaleGrid, int[] baseSlots, double[] trial,
                                        double[] bestPay, double[] bestBids) {
        if (depth == coalition.length) {
            Outcome o = run(mech, trial, effQ, value, theta, reserve, inc);
            for (int t = 0; t < coalition.length; t++) {
                if (o.slot()[coalition[t]] != baseSlots[t]) {
                    return;  // 位次变了(有人掉位)→ 非"安全"合谋,弃
                }
            }
            double pay = coalitionPayment(o, coalition, effQ, theta);
            if (pay < bestPay[0] - TOL) {
                bestPay[0] = pay;
                System.arraycopy(trial, 0, bestBids, 0, trial.length);
            }
            return;
        }
        int member = coalition[depth];
        for (double sc : scaleGrid) {
            trial[member] = value[member] * sc;
            searchCollusion(depth + 1, coalition, value, effQ, theta, reserve, inc, mech, scaleGrid,
                    baseSlots, trial, bestPay, bestBids);
        }
    }

    /** 联盟每次曝光总付费 = Σ_{i∈联盟, 中标} θ_slot×effQuality_i×price_i。 */
    private static double coalitionPayment(Outcome o, int[] coalition, double[] effQ, double[] theta) {
        double pay = 0.0;
        for (int i : coalition) {
            if (o.slot()[i] > 0) {
                pay += theta[o.slot()[i] - 1] * effQ[i] * o.perClickPrice()[i];
            }
        }
        return pay;
    }
}
