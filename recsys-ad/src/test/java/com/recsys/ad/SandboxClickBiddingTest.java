package com.recsys.ad;

import com.recsys.common.ad.*;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 实际竞价边界验证点击报价，不以纯金额helper替代OCPC/模式筛选链路。 */
@Tag("money-chain")
class SandboxClickBiddingTest {
    final AdProperties props = new AdProperties();
    final PacingService pacing = mock(PacingService.class);
    BiddingService bidding;
    @BeforeEach void setup() {
        var calibrator = mock(Calibrator.class); var gate = mock(RelevanceGate.class);
        var ocpc = spy(new OcpcBidder(mock(org.springframework.data.redis.core.StringRedisTemplate.class),props));
        var exploration = mock(ExplorationService.class); var quality = mock(QualityScoreService.class);
        when(calibrator.calibrate(anyDouble(),anyString())).thenAnswer(i->i.getArgument(0));
        when(gate.relevance(any())).thenReturn(1.0); when(pacing.pacingFactors(any())).thenReturn(Map.of());
        doReturn(Map.of(1L,1.5)).when(ocpc).coefficients(any());
        when(exploration.boosts(any())).thenReturn(Map.of()); when(quality.refinedBatch(any())).thenReturn(Map.of());
        props.getAuction().setPriceIncrement(0);
        bidding = new BiddingService(calibrator,pacing,gate,ocpc,exploration,quality,props);
    }
    @Test void exactHalfMicroAtClickAuctionBoundaryAndOtherUnitsExcluded() {
        var result = bidding.auctionSandboxClick(List.of(ad(1,1),ad(2,.0000005),ad(3,999)),
                Map.of(1L,.1,2L,.1,3L,1.0),Map.of(),Map.of(1L,mode("CPC",0),2L,mode("CPC",0),3L,mode("CPM",0)),
                Map.of(),"test",1,0);
        assertEquals(1,result.size()); assertEquals(1,result.getFirst().ad().adId());
        assertEquals("0.000001",result.getFirst().price().amount().toPlainString());
        assertEquals(BidType.CPC,result.getFirst().price().bidType());
    }
    @Test void ocpcEffectiveBidUsesPcvrAndCoefficientButFreezesPerClickNotConversionPrice() {
        when(pacing.pacingFactors(any())).thenReturn(Map.of(1L,.1));
        var result = bidding.auctionSandboxClick(List.of(ad(1,999)),Map.of(1L,.2),Map.of(1L,.02),
                Map.of(1L,mode("OCPC",50)),Map.of(),"test",1,.01).getFirst();
        assertEquals(BidType.OCPC,result.price().bidType());
        assertEquals("0.150000",result.price().basis().cap().toPlainString());
        assertEquals("0.050000",result.price().amount().toPlainString());
    }
    @Test void noProbabilityRoundingAndZeroOrTooSmallBidsAreNotEligible() {
        var r = bidding.auctionSandboxClick(List.of(ad(1,20)),Map.of(1L,1e-8),Map.of(),
                Map.of(1L,mode("CPC",0)),Map.of(),"test",1,1e-7).getFirst();
        assertEquals("10.000000",r.price().amount().toPlainString());
        assertTrue(bidding.auctionSandboxClick(List.of(ad(1,1)),Map.of(1L,0.0),Map.of(),
                Map.of(1L,mode("CPC",0)),Map.of(),"test",1,0).isEmpty());
        assertTrue(bidding.auctionSandboxClick(List.of(ad(1,.01)),Map.of(1L,1.0),Map.of(),
                Map.of(1L,mode("CPC",0)),Map.of(),"test",1,.1).isEmpty());
    }
    @Test void unknownModesAlgorithmsAndNonfiniteInputsCannotFallBackToLegacyPrice() {
        assertThrows(IllegalArgumentException.class,()->bidding.auctionSandboxClick(List.of(ad(1,1)),Map.of(),Map.of(),
                Map.of(1L,mode("INVALID",0)),Map.of(),"test",1,0));
        assertThrows(IllegalArgumentException.class,()->bidding.auctionSandboxClick(List.of(ad(1,Double.NaN)),Map.of(),Map.of(),Map.of(),Map.of(),"test",1,0));
        props.getAuction().getVcg().setEnabled(true);
        assertThrows(IllegalStateException.class,()->bidding.auctionSandboxClick(List.of(),Map.of(),Map.of(),Map.of(),Map.of(),"test",1,0));
        props.getAuction().getVcg().setEnabled(false); props.getAuction().getListwise().setEnabled(true);
        assertThrows(IllegalStateException.class,()->bidding.auctionSandboxClick(List.of(),Map.of(),Map.of(),Map.of(),Map.of(),"test",1,0));
    }
    private static AdCandidate ad(long id,double bid) { return new AdCandidate(id,id,id,id,1,AdChannel.KW_EXACT,bid,1); }
    private static AdRepository.OcpcParams mode(String type,double target) { return new AdRepository.OcpcParams(type,target); }
}
