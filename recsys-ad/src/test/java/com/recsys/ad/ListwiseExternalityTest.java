package com.recsys.ad;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ListwiseExternality} 单元测试——List-wise 外部性拍卖的数学契约(docs/05 §7 M7)。
 * 覆盖:无相似度时等同逐条 eCPM + GSP、外部性把冗余高价广告挤到多样广告之后、外部性 GSP 定价、
 * minRetention 衰减下限、低于 reserve 的位次留空。
 */
class ListwiseExternalityTest {

    private static final double EPS = 1e-6;
    private static final double RESERVE = 0.1;
    private static final double EPSILON_PRICE = 0.01;

    /** item 1 与 item 2 互为冗余(相似度 1),其余两两不相似。 */
    private static final ListwiseExternality.Sim REDUNDANT_1_2 =
            (a, b) -> (a != b && a <= 2 && b <= 2) ? 1.0 : 0.0;

    private static final ListwiseExternality.Sim NO_SIM = (a, b) -> 0.0;

    /** Entry(idx, itemId, pacedBid, effQuality, rankEcpm);测试里令 rankEcpm = pacedBid×effQuality(boost=1)。 */
    private static ListwiseExternality.Entry entry(int idx, long item, double bid, double q) {
        return new ListwiseExternality.Entry(idx, item, bid, q, bid * q);
    }

    @Test
    void noSimilarity_equalsPlainEcpmDescGsp() {
        // 三条互不相似的广告:选择应按 rankEcpm 降序,定价 = 次位 rankEcpm / 自身 effQuality + ε。
        List<ListwiseExternality.Entry> scored = List.of(
                entry(0, 1, 2.0, 0.5),   // rankEcpm 1.00
                entry(1, 2, 1.9, 0.5),   // rankEcpm 0.95
                entry(2, 3, 1.6, 0.5));  // rankEcpm 0.80
        List<ListwiseExternality.Placed> placed = ListwiseExternality.select(
                scored, NO_SIM, 0.5, 0.3, 2, RESERVE, EPSILON_PRICE);

        assertEquals(2, placed.size());
        assertEquals(0, placed.get(0).idx(), "slot1 = 最高 rankEcpm");
        assertEquals(1, placed.get(1).idx(), "slot2 = 次高 rankEcpm(无外部性)");
        assertEquals(1.0, placed.get(0).extFactor(), EPS, "无相似度 → 不衰减");
        // slot1 价 = 次位 0.95 / 0.5 + 0.01 = 1.91
        assertEquals(0.95 / 0.5 + EPSILON_PRICE, placed.get(0).charged(), EPS);
    }

    @Test
    void externality_demotesRedundantAdBelowDiverseOne() {
        // A(item1) 与 B(item2) 冗余;C(item3) 多样但 rankEcpm 略低。选完 A 后 B 被衰减,C 应反超占 slot2。
        List<ListwiseExternality.Entry> scored = List.of(
                entry(0, 1, 2.0, 0.5),   // A rankEcpm 1.00
                entry(1, 2, 1.9, 0.5),   // B rankEcpm 0.95(与 A 冗余)
                entry(2, 3, 1.6, 0.5));  // C rankEcpm 0.80(多样)
        List<ListwiseExternality.Placed> placed = ListwiseExternality.select(
                scored, REDUNDANT_1_2, 0.5, 0.3, 2, RESERVE, EPSILON_PRICE);

        assertEquals(2, placed.size());
        assertEquals(0, placed.get(0).idx(), "slot1 = A");
        assertEquals(2, placed.get(1).idx(), "slot2 = C(多样),而非更高价但冗余的 B");
        // B 的衰减:ext = 1 − 0.5×1 = 0.5;slot2 winner=C(adj 0.80) 次优=B(adj 0.475)
        assertEquals(1.0, placed.get(1).extFactor(), EPS, "C 与已选不相似 → 不衰减");
        // slot2(C)价 = 次优 B 的 adjRank 0.475 / (C 的 effQuality 0.5 × ext 1.0) + 0.01 = 0.96
        assertEquals(0.475 / 0.5 + EPSILON_PRICE, placed.get(1).charged(), EPS);
    }

    @Test
    void chargedPrice_withinReserveAndOwnBid() {
        List<ListwiseExternality.Entry> scored = List.of(
                entry(0, 1, 2.0, 0.5),
                entry(1, 2, 1.9, 0.5),
                entry(2, 3, 1.6, 0.5));
        for (ListwiseExternality.Placed p : ListwiseExternality.select(
                scored, REDUNDANT_1_2, 0.5, 0.3, 3, RESERVE, EPSILON_PRICE)) {
            double ownBid = scored.get(p.idx()).pacedBid();
            assertTrue(p.charged() >= RESERVE - EPS && p.charged() <= ownBid + EPS,
                    "扣费须落 [reserve, 自身出价]:charged=" + p.charged() + " bid=" + ownBid);
        }
    }

    @Test
    void minRetention_capsTheDecay() {
        // λ=1、相似度 1 本会把 ext 压到 0;minRetention=0.3 应托住,B 仍按 0.3 折扣参与。
        List<ListwiseExternality.Entry> scored = List.of(
                entry(0, 1, 2.0, 0.5),   // A rankEcpm 1.00
                entry(1, 2, 1.8, 0.5));  // B rankEcpm 0.90(与 A 冗余)
        List<ListwiseExternality.Placed> placed = ListwiseExternality.select(
                scored, REDUNDANT_1_2, 1.0, 0.3, 2, RESERVE, EPSILON_PRICE);

        assertEquals(2, placed.size());
        assertEquals(0.3, placed.get(1).extFactor(), EPS, "ext 不低于 minRetention");
    }

    @Test
    void belowReserveSlotsLeftEmpty() {
        // reserve 抬高到 0.5;B 与 A 冗余,衰减后 adjRank=0.9×0.5=0.45 < 0.5 → 不占位,只竞得 A。
        List<ListwiseExternality.Entry> scored = List.of(
                entry(0, 1, 2.0, 0.5),   // A rankEcpm 1.00
                entry(1, 2, 1.8, 0.5));  // B rankEcpm 0.90
        List<ListwiseExternality.Placed> placed = ListwiseExternality.select(
                scored, REDUNDANT_1_2, 0.5, 0.3, 2, 0.5, EPSILON_PRICE);

        assertEquals(1, placed.size(), "衰减后不及 reserve 的位次留空");
        assertEquals(0, placed.get(0).idx());
    }
}
