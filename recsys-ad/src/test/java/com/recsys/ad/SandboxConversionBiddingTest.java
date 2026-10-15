package com.recsys.ad;

import com.recsys.common.ad.*;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 实际竞价边界验证转化报价，不以纯金额helper替代CPA/模式筛选链路。 */
@Tag("money-chain")
class SandboxConversionBiddingTest {
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
    @Test void exactHalfMicroAtConversionAuctionBoundaryAndOtherUnitsExcluded() {
        var result = bidding.auctionSandboxConversion(List.of(ad(1,1),ad(2,.0000005),ad(3,999)),
                Map.of(1L,1.0,2L,1.0,3L,1.0),Map.of(1L,1.0,2L,1.0,3L,1.0),Map.of(1L,mode("CPA",0),2L,mode("CPA",0),3L,mode("CPM",0)),
                Map.of(),"test",1,0);
        assertEquals(1,result.size()); assertEquals(1,result.getFirst().ad().adId());
        assertEquals("0.000001",result.getFirst().price().amount().toPlainString());
        assertEquals(BidType.CPA,result.getFirst().price().bidType());
    }
    @Test void conversionFactorIncludesPcvrButObligationIsFullPerConversionPrice() {
        when(pacing.pacingFactors(any())).thenReturn(Map.of(1L,.1));
        var result=bidding.auctionSandboxConversion(List.of(ad(1,20)),Map.of(1L,.2),Map.of(1L,.1),
                Map.of(1L,mode("CPA",999)),Map.of(),"test",1,.01).getFirst();
        assertEquals(BidType.CPA,result.price().bidType());assertEquals("2.000000",result.price().basis().cap().toPlainString());
        assertEquals("0.500000",result.price().amount().toPlainString());
        assertTrue(bidding.auctionSandboxConversion(List.of(ad(1,20)),Map.of(1L,.2),Map.of(1L,0.0),
                Map.of(1L,mode("CPA",999)),Map.of(),"test",1,0).isEmpty());
    }
    @Test void noProbabilityRoundingAndZeroOrTooSmallBidsAreNotEligible() {
        var r = bidding.auctionSandboxConversion(List.of(ad(1,20)),Map.of(1L,1e-4),Map.of(1L,1e-4),
                Map.of(1L,mode("CPA",0)),Map.of(),"test",1,1e-7).getFirst();
        assertEquals("10.000000",r.price().amount().toPlainString());
        assertTrue(bidding.auctionSandboxConversion(List.of(ad(1,1)),Map.of(1L,0.0),Map.of(1L,1.0),
                Map.of(1L,mode("CPA",0)),Map.of(),"test",1,0).isEmpty());
        assertTrue(bidding.auctionSandboxConversion(List.of(ad(1,.01)),Map.of(1L,1.0),Map.of(1L,1.0),
                Map.of(1L,mode("CPA",0)),Map.of(),"test",1,.1).isEmpty());
    }
    @Test void unknownModesAlgorithmsAndNonfiniteInputsCannotFallBackToLegacyPrice() {
        assertThrows(IllegalArgumentException.class,()->bidding.auctionSandboxConversion(List.of(ad(1,1)),Map.of(),Map.of(),
                Map.of(1L,mode("INVALID",0)),Map.of(),"test",1,0));
        assertThrows(IllegalArgumentException.class,()->bidding.auctionSandboxConversion(List.of(ad(1,Double.NaN)),Map.of(1L,1.0),Map.of(1L,1.0),Map.of(1L,mode("CPA",0)),Map.of(),"test",1,0));
        props.getAuction().getVcg().setEnabled(true);
        assertThrows(IllegalStateException.class,()->bidding.auctionSandboxConversion(List.of(),Map.of(),Map.of(),Map.of(),Map.of(),"test",1,0));
        props.getAuction().getVcg().setEnabled(false); props.getAuction().getListwise().setEnabled(true);
        assertThrows(IllegalStateException.class,()->bidding.auctionSandboxConversion(List.of(),Map.of(),Map.of(),Map.of(),Map.of(),"test",1,0));
    }
    @Test void allOtherExplicitBillingModesAreExcluded() {
        for(String mode:List.of("CPC","OCPC","CPM","OCPM")) {
            assertTrue(bidding.auctionSandboxConversion(List.of(ad(1,999)),Map.of(1L,1.0),Map.of(1L,1.0),
                    Map.of(1L,mode(mode,999)),Map.of(),"test",1,0).isEmpty());
        }
    }
    private static AdCandidate ad(long id,double bid) { return new AdCandidate(id,id,id,id,1,AdChannel.KW_EXACT,bid,1); }
    private static AdRepository.OcpcParams mode(String type,double target) { return new AdRepository.OcpcParams(type,target); }
}
