package com.recsys.ad;

import com.recsys.common.ad.BidType;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 多计费模式 eCPM 经济学契约(A1)—— 锁死各 billType 的 billFactor,使
 * {@code eCPM = pacedBid × billFactor} 在一场竞价里同尺可比,GSP {@code charged = nextRank/billFactor}
 * 天然以自身计费单位计价。billFactor 变错 = 跨类型竞价与计费全错,故此为变现正确性护栏。
 */
@Tag("money-chain")   // P1 钱链路验证闸门
class BiddingServiceBillFactorTest {

    private static final double EPS = 1e-9;
    private static final double PCTR = 0.2, PCVR = 0.1, Q = 1.2, REL = 0.8;

    @Test
    void cpc_and_ocpc_perClick_includePctr() {
        double expected = PCTR * Q * REL;
        assertEquals(expected, BiddingService.billFactor(BidType.CPC, PCTR, PCVR, Q, REL), EPS);
        assertEquals(expected, BiddingService.billFactor(BidType.OCPC, PCTR, PCVR, Q, REL), EPS);
    }

    @Test
    void cpm_perImpression_noPctr() {
        // 按曝光计费:收入不依赖点击 → billFactor 不含 pCTR
        assertEquals(Q * REL, BiddingService.billFactor(BidType.CPM, PCTR, PCVR, Q, REL), EPS);
    }

    @Test
    void cpa_and_ocpm_perConversion_includePctrAndPcvr() {
        double expected = PCTR * PCVR * Q * REL;
        assertEquals(expected, BiddingService.billFactor(BidType.CPA, PCTR, PCVR, Q, REL), EPS);
        assertEquals(expected, BiddingService.billFactor(BidType.OCPM, PCTR, PCVR, Q, REL), EPS);
    }

    @Test
    void cpm_ranksHigherThanCpc_whenBidPerImpressionDominates() {
        // 同样"每次曝光期望收入":CPM 出价 0.5/曝光 vs CPC 出价 2.0/点击(pCTR=0.2 → 0.4/曝光)
        double cpmEcpm = 0.5 * BiddingService.billFactor(BidType.CPM, PCTR, PCVR, 1.0, 1.0);   // 0.5
        double cpcEcpm = 2.0 * BiddingService.billFactor(BidType.CPC, PCTR, PCVR, 1.0, 1.0);   // 2.0*0.2=0.4
        org.junit.jupiter.api.Assertions.assertTrue(cpmEcpm > cpcEcpm,
                "同尺 eCPM 下 CPM(0.5) 应压过 CPC(0.4)");
    }
}
