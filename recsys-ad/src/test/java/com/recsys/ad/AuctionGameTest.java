package com.recsys.ad;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AuctionGame} 单元测试——拍卖机制的博弈稳定性契约(docs/05 §6/§7 M7)。
 * 覆盖:VCG 激励相容(全员真话无获利偏离)、GSP 非真实(顶位可压价改抢下位多赚)、
 * GSP 对称纳什均衡复现 VCG 价(EOS/Varian 等价)、联合出价套利(GSP 与 VCG 都可被联盟操纵)。
 *
 * <p>设定:3 个广告主 value=[10,8,5],等广告效应 effQuality=1(等价定理的经典同质设定),
 * 2 个广告位 θ=[1.0,0.6],reserve=0.5,加价 ε=0。手算锚:VCG 价 = {顶位 6.2, 次位 5.0}。
 */
class AuctionGameTest {

    private static final double[] VALUE = {10.0, 8.0, 5.0};
    private static final double[] EFFQ = {1.0, 1.0, 1.0};
    private static final double[] THETA = VcgAuction.theta(2, List.of(1.0, 0.6), 0.6);
    private static final double RESERVE = 0.5;
    private static final double INC = 0.0;
    private static final double EPS = 1e-6;

    @Test
    void vcg_prices_matchHandComputed() {
        // 顶位 T=8·(1−0.6)+5·0.6=6.2,clicks=1 → 6.2;次位 T=5·0.6=3.0,clicks=0.6 → 5.0
        AuctionGame.Outcome o = AuctionGame.run(
                AuctionGame.Mechanism.VCG, VALUE, EFFQ, VALUE, THETA, RESERVE, INC);
        assertEquals(1, o.slot()[0]);
        assertEquals(2, o.slot()[1]);
        assertEquals(0, o.slot()[2], "第 3 名落选(仅 2 位)");
        assertEquals(6.2, o.perClickPrice()[0], EPS);
        assertEquals(5.0, o.perClickPrice()[1], EPS);
    }

    @Test
    void vcg_isTruthful_noProfitableDeviation() {
        // VCG 激励相容:全员说真话时,任何人单边改报都赚不到更多。
        double regret = AuctionGame.maxTruthfulRegret(
                AuctionGame.Mechanism.VCG, VALUE, EFFQ, THETA, RESERVE, INC);
        assertTrue(regret < EPS, "VCG 应无获利偏离,实测最大获利=" + regret);
    }

    @Test
    void gsp_isNotTruthful_topBidderShadesDownToWin() {
        // GSP 非真实:全员真话时顶位 A 付 8、效用 1·(10−8)=2;A 改报落到次位付 5、效用 0.6·(10−5)=3 → 多赚 1。
        double regret = AuctionGame.maxTruthfulRegret(
                AuctionGame.Mechanism.GSP, VALUE, EFFQ, THETA, RESERVE, INC);
        assertTrue(regret > 0.5, "GSP 应存在获利偏离,实测最大获利=" + regret);

        AuctionGame.BestResponse br = AuctionGame.bestResponse(
                AuctionGame.Mechanism.GSP, 0, VALUE, EFFQ, VALUE, THETA, RESERVE, INC);
        assertEquals(1.0, br.gainOverBaseline(), 1e-6, "A 压价抢次位恰好多赚 1");
        // 偏离出价应落到次位(rankScore 介于 C=5 与 B=8 之间)
        AuctionGame.Outcome dev = AuctionGame.run(AuctionGame.Mechanism.GSP,
                new double[]{br.bid(), 8.0, 5.0}, EFFQ, VALUE, THETA, RESERVE, INC);
        assertEquals(2, dev.slot()[0], "获利偏离把 A 放到次位");
    }

    @Test
    void gspSymmetricNash_reproducesVcgPrices() {
        // GSP 对称纳什均衡出价的每次点击价 = VCG 价(等价定理)。
        double[] sne = AuctionGame.gspSymmetricNashBids(VALUE, EFFQ, THETA, RESERVE, INC);
        // SNE: 顶位真值 10、次位 = VCG 顶价 6.2、落选者真值 5
        assertEquals(10.0, sne[0], EPS);
        assertEquals(6.2, sne[1], EPS);
        assertEquals(5.0, sne[2], EPS);

        AuctionGame.Outcome gsp = AuctionGame.run(
                AuctionGame.Mechanism.GSP, sne, EFFQ, VALUE, THETA, RESERVE, INC);
        AuctionGame.Outcome vcg = AuctionGame.run(
                AuctionGame.Mechanism.VCG, VALUE, EFFQ, VALUE, THETA, RESERVE, INC);
        assertEquals(vcg.slot()[0], gsp.slot()[0], "分配一致");
        assertEquals(vcg.slot()[1], gsp.slot()[1]);
        assertEquals(vcg.perClickPrice()[0], gsp.perClickPrice()[0], EPS, "顶位价收敛到 VCG");
        assertEquals(vcg.perClickPrice()[1], gsp.perClickPrice()[1], EPS, "次位价收敛到 VCG");
    }

    @Test
    void gspSymmetricNash_isAnEquilibrium() {
        // SNE 出价下,GSP 中无人有单边获利偏离(纳什均衡)。
        double[] sne = AuctionGame.gspSymmetricNashBids(VALUE, EFFQ, THETA, RESERVE, INC);
        for (int i = 0; i < sne.length; i++) {
            double gain = AuctionGame.bestResponse(
                    AuctionGame.Mechanism.GSP, i, sne, EFFQ, VALUE, THETA, RESERVE, INC).gainOverBaseline();
            assertTrue(gain < EPS, "SNE 应是均衡,玩家 " + i + " 仍可多赚 " + gain);
        }
    }

    @Test
    void vcg_isManipulableByCoalition() {
        // VCG 防单边、不防合谋:联盟 {A,B} 让 B 压价(仍保住次位)→ A 的外部性下降 → A 付费降,总付费降。
        double[] grid = {1.0, 0.9, 0.8, 0.7};
        AuctionGame.Collusion col = AuctionGame.findCollusionArbitrage(
                AuctionGame.Mechanism.VCG, new int[]{0, 1}, VALUE, EFFQ, THETA, RESERVE, INC, grid);
        assertTrue(col.found(), "VCG 应被联盟套利");
        assertTrue(col.bestPayment() < col.baselinePayment() - 1e-6,
                "联盟总付费应下降:" + col.baselinePayment() + " → " + col.bestPayment());
    }

    @Test
    void gsp_isManipulableByCoalition() {
        // GSP 同样不防合谋:B 压价直接降低 A(紧邻上位)的次价。
        double[] grid = {1.0, 0.9, 0.8, 0.7};
        AuctionGame.Collusion col = AuctionGame.findCollusionArbitrage(
                AuctionGame.Mechanism.GSP, new int[]{0, 1}, VALUE, EFFQ, THETA, RESERVE, INC, grid);
        assertTrue(col.found(), "GSP 应被联盟套利");
        assertTrue(col.bestPayment() < col.baselinePayment() - 1e-6);
    }

    @Test
    void singleSlot_gspEqualsVcg() {
        // 单广告位退化:GSP 与 VCG 价相同(均 = 次高 rankScore / 自身 effQuality)。
        double[] theta1 = VcgAuction.theta(1, List.of(1.0), 0.6);
        AuctionGame.Outcome gsp = AuctionGame.run(
                AuctionGame.Mechanism.GSP, VALUE, EFFQ, VALUE, theta1, RESERVE, INC);
        AuctionGame.Outcome vcg = AuctionGame.run(
                AuctionGame.Mechanism.VCG, VALUE, EFFQ, VALUE, theta1, RESERVE, INC);
        assertEquals(8.0, gsp.perClickPrice()[0], EPS);
        assertEquals(gsp.perClickPrice()[0], vcg.perClickPrice()[0], EPS);
    }
}
