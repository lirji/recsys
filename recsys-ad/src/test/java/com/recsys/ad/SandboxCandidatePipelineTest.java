package com.recsys.ad;

import com.recsys.common.ad.*;
import com.recsys.common.query.StructuredQuery;
import com.recsys.common.ad.billing.CpmPrice;
import com.recsys.rank.RankRouter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

/** 旧 run 有收费副作用，必须验证新候选方法根本未调用那些入口。 */
@Tag("money-chain")
class SandboxCandidatePipelineTest {
    @Test
    void candidateSelectionPreservesExactQuoteAndNeverRecordsDeliveryOrMoney() {
        var recall = mock(AdRecallService.class); var relevance = mock(RelevanceGate.class); var pacing = mock(PacingService.class);
        var bidding = mock(BiddingService.class); var logger = mock(AdEventLogger.class); var catalog = mock(AdCatalogReader.class);
        var rank = mock(RankRouter.class); var creative = mock(CreativeSelector.class); var audience = mock(AudienceTargeting.class);
        var gd = mock(GuaranteedDeliveryService.class); var uplift = mock(UpliftAssignmentService.class);
        var pipeline = new AdPipeline(recall, relevance, pacing, bidding, logger, catalog, rank, new AdProperties(),
                new SimpleMeterRegistry(), mock(AntiFraudService.class), mock(AdEmbeddingSimilarity.class), creative, audience,
                gd, mock(DfmCvrService.class), mock(UpliftService.class), uplift, mock(AdOutcomeService.class));
        var candidate = mock(AdCandidate.class); when(candidate.adId()).thenReturn(1L); when(candidate.itemId()).thenReturn(2L);
        when(recall.recall(any())).thenReturn(List.of(candidate)); when(relevance.filter(any())).thenReturn(List.of(candidate));
        when(audience.filter(eq(1L), any())).thenReturn(List.of(candidate)); when(rank.rank(anyLong(), anyList(), anyString())).thenReturn(List.of());
        var ad = new SponsoredAd(1, 2, 3, 4, "creative", AdChannel.KW_EXACT, 1, 1, 1, 1, 1, 1, 1, 1, 0, "CPM");
        var price = new CpmPrice(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ONE);
        when(bidding.auctionSandboxCpm(anyList(), anyMap(), anyMap(), anyMap(), anyString(), eq(1), eq(0.1)))
                .thenReturn(List.of(new BiddingService.QuotedAd(ad, price)));
        when(creative.selectFor(anySet())).thenReturn(Map.of());
        var result = pipeline.sandboxCandidates(1, new StructuredQuery("q", "q", null, null, null, null), 1, "search", 0.1);
        assertEquals(price, result.getFirst().price()); assertEquals(ad, result.getFirst().ad());
        verifyNoInteractions(pacing, logger, gd, uplift);
        when(recall.recall(any())).thenThrow(new IllegalStateException("down"));
        assertThrows(IllegalStateException.class, () -> pipeline.sandboxCandidates(1, new StructuredQuery("q", "q", null, null, null, null), 1, "search", .1));
    }
}
