package com.recsys.rank;

import com.recsys.common.feature.FeatureService;
import com.recsys.common.rank.TowerScorer;
import com.recsys.content.ContentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link PreRankService} 双塔粗排(R6)——验证 mode=two-tower 时高双塔分候选被排到前列;
 * 双塔未就绪则退纯线性(优雅降级)。
 */
class PreRankServiceTowerTest {

    @SuppressWarnings("unchecked")
    private PreRankService service(TowerScorer scorer, String mode) {
        FeatureService fs = mock(FeatureService.class);
        when(fs.userFeatures(anyLong())).thenReturn(Map.of());
        when(fs.itemFeatures(any())).thenReturn(Map.of());
        ContentService cs = mock(ContentService.class);
        when(cs.findByIds(any())).thenReturn(Map.of());
        ObjectProvider<TowerScorer> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(scorer);

        RankProperties props = new RankProperties();
        props.getPreRank().setEnabled(true);
        props.getPreRank().setLimit(2);
        props.getPreRank().setMode(mode);
        props.getPreRank().setTowerWeight(5.0);
        return new PreRankService(fs, cs, provider, props);
    }

    @Test
    void twoTowerMode_highTowerScoreRanksFirst() {
        TowerScorer scorer = mock(TowerScorer.class);
        when(scorer.isReady()).thenReturn(true);
        // A 双塔相似度高、B 中、C/D/E 无向量(缺席)→ 退线性(彼此相等)
        when(scorer.score(anyLong(), any())).thenReturn(Map.of(1L, 1.0, 2L, 0.5));

        List<Long> cands = List.of(1L, 2L, 3L, 4L, 5L);
        Map<Long, Double> recall = Map.of(1L, 1.0, 2L, 1.0, 3L, 1.0, 4L, 1.0, 5L, 1.0); // 召回分相等
        List<Long> out = service(scorer, "two-tower").preRank(1L, cands, recall, "rec");

        assertEquals(2, out.size(), "粗排截断到 limit=2");
        assertEquals(1L, out.get(0), "双塔分最高的候选应排首位");
        assertEquals(2L, out.get(1), "双塔分次高的排第二");
    }

    @Test
    void towerNotReady_fallsBackToLinear() {
        TowerScorer scorer = mock(TowerScorer.class);
        when(scorer.isReady()).thenReturn(false);   // 塔未就绪 → 退线性
        List<Long> cands = List.of(1L, 2L, 3L);
        // 召回分决定顺序(线性):3 > 2 > 1
        Map<Long, Double> recall = Map.of(1L, 0.1, 2L, 0.5, 3L, 0.9);
        List<Long> out = service(scorer, "two-tower").preRank(1L, cands, recall, "rec");
        // limit=2,纯线性按召回降序 → [3, 2]
        assertEquals(List.of(3L, 2L), out);
    }
}
