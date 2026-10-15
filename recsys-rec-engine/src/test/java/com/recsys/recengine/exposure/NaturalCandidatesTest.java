package com.recsys.recengine.exposure;

import com.recsys.common.dto.*;
import com.recsys.common.query.QueryUnderstandingService;
import com.recsys.common.rank.RankedItem;
import com.recsys.common.recall.*;
import com.recsys.rank.*;
import com.recsys.recengine.*;
import com.recsys.recengine.content.ContentGateway;
import com.recsys.recengine.coldstart.ColdStartDetector;
import com.recsys.recengine.experiment.*;
import com.recsys.recengine.filter.SeenItemsFilter;
import com.recsys.recengine.rerank.RerankRouter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 对真实编排方法回归两条候选路径，防止自然签发再次触发响应即曝光。 */
@Tag("natural-exposure")
class NaturalCandidatesTest {
    RecCache cache=mock(RecCache.class);
    ExposureLogger exposures=mock(ExposureLogger.class);
    RecallService recall=mock(RecallService.class);
    RankRouter ranks=mock(RankRouter.class);
    PreRankService pre=mock(PreRankService.class);
    ExperimentService experiments=mock(ExperimentService.class);
    RerankRouter reranks=mock(RerankRouter.class);
    RecommendOrchestrator orchestrator;
    RecommendRequest request=new RecommendRequest(1,2,"feed");
    @BeforeEach void setup() {
        var trace=mock(TraceIds.class);when(trace.current()).thenReturn("trace");
        orchestrator=new RecommendOrchestrator(recall,ranks,pre,mock(ShadowRankRunner.class),mock(ContentGateway.class),cache,new RecEngineProperties(),
                experiments,reranks,exposures,mock(ColdStartDetector.class),mock(SeenItemsFilter.class),mock(QueryUnderstandingService.class),
                mock(PersonalizationScorer.class),mock(RecScoreCalibrator.class),mock(FtrlScorer.class),mock(BanditScorer.class),mock(DynamicTuningService.class),trace,new SimpleMeterRegistry());
    }
    @Test void cachedCandidatesDropOldExposureIdentityWithoutLegacySideEffects() {
        var response=new RecommendResponse(1,"feed",List.of(new RecommendItem(1,.5,List.of("HOT"),"reason","old")),"old-trace");
        when(cache.get(1,"feed")).thenReturn(new RecCache.CacheEntry(response,"rank:original"));
        var candidates=orchestrator.naturalCandidates(request);
        assertNull(candidates.response().items().getFirst().exposureId());assertEquals("rank:original",candidates.bucket());
        verifyNoInteractions(exposures,recall);verify(cache,never()).put(anyLong(),anyString(),any(),anyString());
        var legacy=orchestrator.recommend(request);assertNotEquals("old",legacy.items().getFirst().exposureId());
        verify(exposures).log(eq(1L),eq("feed"),eq("rank:original"),eq(legacy.items()));
    }
    @Test void freshPipelineAlsoAvoidsLegacyExposureAndCacheWrite() {
        var decision=new ExperimentDecision();decision.put("rank","test",Map.of());when(experiments.assign(1,"feed")).thenReturn(decision);
        when(recall.recall(any())).thenReturn(List.of(new RecallItem(1,1,RecallChannel.HOT)));
        when(pre.preRank(anyLong(),anyList(),anyMap(),anyString())).thenReturn(List.of(1L));
        when(ranks.rank(anyLong(),anyList(),anyString(),isNull())).thenReturn(List.of(new RankedItem(1,.5,Map.of())));
        when(reranks.rerank(anyString(),anyList(),any())).thenReturn(List.of(new RecommendItem(1,.5,List.of("HOT"),"reason")));
        var result=orchestrator.naturalCandidates(request);assertEquals(1,result.response().items().size());assertEquals("rank:test",result.bucket());
        assertNull(result.response().items().getFirst().exposureId());verifyNoInteractions(exposures);verify(cache,never()).put(anyLong(),anyString(),any(),anyString());
        var legacy=orchestrator.recommend(request);assertNotNull(legacy.items().getFirst().exposureId());
        verify(exposures).log(eq(1L),eq("feed"),eq("rank:test"),eq(legacy.items()));verify(cache).put(eq(1L),eq("feed"),eq(legacy),eq("rank:test"));
    }
    @Test void dependencyExceptionPropagatesForDurablePathAndLegacyFallbackStaysCompatible() {
        when(experiments.assign(1,"feed")).thenThrow(new IllegalStateException("test outage"));
        assertThrows(IllegalStateException.class,()->orchestrator.naturalCandidates(request));
        assertTrue(orchestrator.recommend(request).items().isEmpty());verifyNoInteractions(exposures);
    }
}
