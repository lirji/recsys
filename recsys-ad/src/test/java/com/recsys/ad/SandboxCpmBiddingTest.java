package com.recsys.ad;

import com.recsys.common.ad.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 在实际 BiddingService 新入口验证边界，不能只测金额 helper 就宣称竞价链路已精确。 */
@Tag("money-chain")
class SandboxCpmBiddingTest {
    private final AdProperties props = new AdProperties();
    private final PacingService pacing = mock(PacingService.class);
    private BiddingService bidding;

    @BeforeEach
    void setup() {
        Calibrator calibrator = mock(Calibrator.class);
        RelevanceGate gate = mock(RelevanceGate.class);
        OcpcBidder ocpc = mock(OcpcBidder.class);
        ExplorationService exploration = mock(ExplorationService.class);
        QualityScoreService quality = mock(QualityScoreService.class);
        when(calibrator.calibrate(anyDouble(), anyString())).thenAnswer(i -> i.getArgument(0));
        when(gate.relevance(any())).thenReturn(1.0);
        when(pacing.pacingFactors(any())).thenReturn(Map.of());
        when(ocpc.coefficients(any())).thenReturn(Map.of());
        when(exploration.boosts(any())).thenReturn(Map.of());
        when(quality.refinedBatch(any())).thenReturn(Map.of());
        props.getAuction().setPriceIncrement(0);
        bidding = new BiddingService(calibrator, pacing, gate, ocpc, exploration, quality, props);
    }

    @Test
    void halfMicroPriceComesFromExactInputsAtAuctionBoundary() {
        var quotes = bidding.auctionSandboxCpm(List.of(candidate(1, 1, 1), candidate(2, 0.0000005, 1)),
                Map.of(1L, 0.1), Map.of(1L, new AdRepository.OcpcParams("CPM", 0),
                        2L, new AdRepository.OcpcParams("CPM", 0)), Map.of(), "test", 1, 0);
        assertEquals(1, quotes.size());
        assertEquals("0.000001", quotes.getFirst().price().amount().toPlainString());
        assertEquals(new BigDecimal("0.0000005"), quotes.getFirst().price().threshold());
        assertEquals("0.000000", quotes.getFirst().price().floor().toPlainString());
        assertEquals("CPM", quotes.getFirst().ad().bidType());
    }

    @Test
    void decimalPacingAndUnsupportedCandidatesCannotChangeCpmBillUnit() {
        when(pacing.pacingFactors(any())).thenReturn(Map.of(1L, 0.1));
        var quotes = bidding.auctionSandboxCpm(List.of(candidate(1, 0.3, 1), candidate(2, 999, 1)),
                Map.of(), cpm(1), Map.of(), "test", 1, 0.000001);
        assertEquals(1, quotes.size());
        assertEquals(1, quotes.getFirst().ad().adId());
        assertEquals("0.030000", quotes.getFirst().price().cap().toPlainString());
        assertEquals("PER_IMPRESSION", com.recsys.common.ad.billing.CpmPrice.UNIT);
    }

    @Test
    void rejectsNonfiniteAndUnimplementedAlgorithmsWithoutLegacyFallback() {
        assertThrows(IllegalArgumentException.class, () -> bidding.auctionSandboxCpm(
                List.of(candidate(1, Double.NaN, 1)), Map.of(), cpm(1), Map.of(), "test", 1, 0));
        props.getAuction().getVcg().setEnabled(true);
        assertThrows(IllegalStateException.class, () -> bidding.auctionSandboxCpm(List.of(), Map.of(), Map.of(), Map.of(), "test", 1, 0));
        props.getAuction().getVcg().setEnabled(false);
        props.getAuction().getListwise().setEnabled(true);
        assertThrows(IllegalStateException.class, () -> bidding.auctionSandboxCpm(List.of(), Map.of(), Map.of(), Map.of(), "test", 1, 0));
    }

    @Test
    void capBelowFloorAndZeroFactorAreNotEligible() {
        assertTrue(bidding.auctionSandboxCpm(List.of(candidate(1, 0.1, 1), candidate(2, 1, 0)),
                Map.of(), Map.of(1L, new AdRepository.OcpcParams("CPM", 0), 2L, new AdRepository.OcpcParams("CPM", 0)),
                Map.of(), "test", 2, 0.2).isEmpty());
    }

    @Test
    void oldAuctionRemainsUnquantizedAndUsesOriginalUnits() {
        var old = bidding.auction(List.of(candidate(1, 1, 1)), Map.of(), Map.of(), cpm(1), Map.of(), "test", 1, 0.0000005, null);
        assertEquals(0.0000005, old.getFirst().chargedPrice());
    }

    private static AdCandidate candidate(long id, double bid, double quality) {
        return new AdCandidate(id, id, id, id, 1, AdChannel.KW_EXACT, bid, quality);
    }

    private static Map<Long, AdRepository.OcpcParams> cpm(long id) {
        return Map.of(id, new AdRepository.OcpcParams("CPM", 0));
    }
}
