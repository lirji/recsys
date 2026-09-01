package com.recsys.ad;

import com.recsys.common.ad.BidType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BiddingServiceUpliftTest {
    private static final double EPS = 1e-12;

    @Test
    void cpaAndOcpmUsePerOpportunityIncrementWithoutMultiplyingPctrAgain() {
        double expected = 0.04 * 1.2 * 0.8;
        assertEquals(expected, BiddingService.incrementalBillFactor(
                BidType.CPA, 0.2, 0.04, 1.2, 0.8), EPS);
        assertEquals(expected, BiddingService.incrementalBillFactor(
                BidType.OCPM, 0.2, 0.04, 1.2, 0.8), EPS);
    }

    @Test
    void ocpcKeepsPerClickBillingFactorAndMovesUpliftIntoBid() {
        assertEquals(0.2 * 1.2 * 0.8, BiddingService.incrementalBillFactor(
                BidType.OCPC, 0.2, 0.04, 1.2, 0.8), EPS);
    }

    @Test
    void cpcAndCpmEconomicsAreUnchanged() {
        assertEquals(BiddingService.billFactor(BidType.CPC, 0.2, 0.9, 1.2, 0.8),
                BiddingService.incrementalBillFactor(BidType.CPC, 0.2, 0.04, 1.2, 0.8), EPS);
        assertEquals(BiddingService.billFactor(BidType.CPM, 0.2, 0.9, 1.2, 0.8),
                BiddingService.incrementalBillFactor(BidType.CPM, 0.2, 0.04, 1.2, 0.8), EPS);
    }

    @Test
    void negativeUpliftCannotBecomePositiveValue() {
        assertEquals(0.0, BiddingService.incrementalBillFactor(
                BidType.CPA, 0.2, -0.04, 1.2, 0.8), EPS);
    }
}
