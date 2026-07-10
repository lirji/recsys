package com.recsys.ad;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link VcgAuction} 单元测试——VCG 位置拍卖的数学契约(docs/05 §4.6/§7 M7)。
 * 覆盖:单广告位退化为 GSP、位置折扣下的外部性定价、位置折扣全相等时人人付首落选者价、
 * 落选者不足时 reserve 托底边际、扣费落 [reserve, 自身出价]、theta 单调非增构造。
 */
@Tag("money-chain")   // P1 钱链路验证闸门
class VcgAuctionTest {

    private static final double EPS = 1e-6;
    private static final double RESERVE = 0.1;
    private static final double EPSILON_PRICE = 0.01;

    /** Entry(idx, pacedBid, effQuality, rankEcpm, value);测试令 rankEcpm = value = pacedBid×effQuality(boost=1)。 */
    private static VcgAuction.Entry entry(int idx, double bid, double q) {
        return new VcgAuction.Entry(idx, bid, q, bid * q, bid * q);
    }

    @Test
    void singleSlot_equalsGsp() {
        // 单广告位:VCG 价 = 次高 value / 自身 effQuality + ε —— 与 GSP 单位次完全一致。
        List<VcgAuction.Entry> scored = List.of(
                entry(0, 2.0, 0.5),   // A value 1.00
                entry(1, 1.9, 0.5),   // B value 0.95
                entry(2, 1.6, 0.5));  // C value 0.80
        double[] theta = VcgAuction.theta(1, List.of(1.0), 0.7);
        List<VcgAuction.Placed> placed = VcgAuction.select(scored, theta, 1, RESERVE, EPSILON_PRICE);

        assertEquals(1, placed.size());
        assertEquals(0, placed.get(0).idx(), "胜者 = 最高 rankEcpm");
        // 价 = 次高 0.95 / 自身 effQuality 0.5 + ε
        assertEquals(0.95 / 0.5 + EPSILON_PRICE, placed.get(0).charged(), EPS);
    }

    @Test
    void twoSlots_positionDiscountedExternality() {
        // θ=[1.0,0.5]:第 1 位付下方两人因它而少得的福利,第 2 位付首落选者的外部性。
        List<VcgAuction.Entry> scored = List.of(
                entry(0, 2.0, 0.5),   // A value 1.00
                entry(1, 1.9, 0.5),   // B value 0.95
                entry(2, 1.6, 0.5));  // C value 0.80(首落选者)
        double[] theta = VcgAuction.theta(2, List.of(1.0, 0.5), 0.7);
        List<VcgAuction.Placed> placed = VcgAuction.select(scored, theta, 2, RESERVE, EPSILON_PRICE);

        assertEquals(2, placed.size());
        // slot1: T = B.value·(θ1−θ2) + C.value·θ2 = 0.95·0.5 + 0.8·0.5 = 0.875;clicks = θ1·0.5 = 0.5
        assertEquals(0.875 / 0.5 + EPSILON_PRICE, placed.get(0).charged(), EPS);
        // slot2: T = C.value·θ2 = 0.8·0.5 = 0.4;clicks = θ2·0.5 = 0.25
        assertEquals(0.4 / 0.25 + EPSILON_PRICE, placed.get(1).charged(), EPS);
    }

    @Test
    void flatDiscount_everyonePaysFirstLoser() {
        // θ 全相等 ⇒ 位置间无差异,人人付"首落选者 value / 自身 effQuality"。
        List<VcgAuction.Entry> scored = List.of(
                entry(0, 2.0, 0.5),   // value 1.00
                entry(1, 1.9, 0.5),   // value 0.95
                entry(2, 1.6, 0.5));  // value 0.80(首落选者)
        double[] theta = VcgAuction.theta(2, List.of(1.0, 1.0), 1.0);
        List<VcgAuction.Placed> placed = VcgAuction.select(scored, theta, 2, RESERVE, EPSILON_PRICE);

        double expected = 0.8 / 0.5 + EPSILON_PRICE;
        assertEquals(expected, placed.get(0).charged(), EPS);
        assertEquals(expected, placed.get(1).charged(), EPS);
    }

    @Test
    void fewerBiddersThanSlots_reserveFloorsMargin() {
        // 仅 2 个候选、2 个位次:无落选者,末位边际用 reserve 托底而非 0。
        List<VcgAuction.Entry> scored = List.of(
                entry(0, 2.0, 0.5),   // value 1.00
                entry(1, 1.8, 0.5));  // value 0.90
        double[] theta = VcgAuction.theta(2, List.of(1.0, 0.5), 0.7);
        List<VcgAuction.Placed> placed = VcgAuction.select(scored, theta, 2, RESERVE, EPSILON_PRICE);

        assertEquals(2, placed.size());
        // slot2: marginValue = max(0, reserve) = 0.1;T = 0.1·θ2 = 0.05;clicks = θ2·0.5 = 0.25
        assertEquals(0.1 * 0.5 / 0.25 + EPSILON_PRICE, placed.get(1).charged(), EPS);
    }

    @Test
    void chargedPrice_withinReserveAndOwnBid() {
        List<VcgAuction.Entry> scored = List.of(
                entry(0, 2.0, 0.5),
                entry(1, 1.9, 0.5),
                entry(2, 1.6, 0.5));
        double[] theta = VcgAuction.theta(3, List.of(1.0, 0.7, 0.5), 0.7);
        for (VcgAuction.Placed p : VcgAuction.select(scored, theta, 3, RESERVE, EPSILON_PRICE)) {
            double ownBid = scored.get(p.idx()).pacedBid();
            assertTrue(p.charged() >= RESERVE - EPS && p.charged() <= ownBid + EPS,
                    "扣费须落 [reserve, 自身出价]:charged=" + p.charged() + " bid=" + ownBid);
        }
    }

    @Test
    void theta_isMonotonicNonIncreasingAndExtrapolated() {
        // 列表只给 2 个,第 3 位起按 tailDecay 外推;整体单调非增、落在 (0,1]。
        double[] t = VcgAuction.theta(4, List.of(1.0, 0.5), 0.5);
        assertEquals(4, t.length);
        assertEquals(1.0, t[0], EPS);
        assertEquals(0.5, t[1], EPS);
        assertEquals(0.25, t[2], EPS);   // 0.5 × 0.5
        assertEquals(0.125, t[3], EPS);  // 0.25 × 0.5
        for (int i = 1; i < t.length; i++) {
            assertTrue(t[i] <= t[i - 1] + EPS && t[i] > 0 && t[i] <= 1.0);
        }
    }
}
