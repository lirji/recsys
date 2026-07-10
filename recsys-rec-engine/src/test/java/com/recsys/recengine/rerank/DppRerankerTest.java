package com.recsys.recengine.rerank;

import com.recsys.common.dto.RecommendItem;
import com.recsys.content.Item;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

/**
 * {@link DppReranker} 单测(R4)——无 DB(mock JdbcTemplate 抛异常 → 无向量 → 走类目相似),
 * 验证行列式点过程贪心 MAP 确实<b>整页多样化</b>:相关性相近时不把同类目扎堆全选,首位仍是相关性最高。
 */
class DppRerankerTest {

    private DppReranker reranker() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        // 让向量加载失败 → loadEmbeddings 退空 → 相似度走"同类目=1/异类目=0"
        doThrow(new RuntimeException("no db")).when(jdbc)
                .query(anyString(), (org.springframework.jdbc.core.RowCallbackHandler) any(), (Object[]) any());
        return new DppReranker(jdbc);
    }

    private RerankInput input(int size, Map<Long, Item> items) {
        Map<Long, List<String>> ch = new HashMap<>();
        for (Long id : items.keySet()) {
            ch.put(id, List.of("HOT"));
        }
        return new RerankInput(size, ch, items, Map.of("poolSize", "200"));
    }

    @Test
    void diversifiesAcrossCategories_notAllSameCategory() {
        // 6 个候选,相关性降序;类目 [A,A,A,B,B,C]。纯相关性会选 [0,1,2] 全 A;DPP 应跨类目多样化。
        String[] cats = {"A", "A", "A", "B", "B", "C"};
        Map<Long, Item> items = new HashMap<>();
        List<RerankCandidate> fused = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            long id = 100 + i;
            items.put(id, new Item(id, "t" + i, cats[i], List.of(), "", 0));
            fused.add(new RerankCandidate(id, 1.0 - i * 0.05));   // 降序相关性
        }

        List<RecommendItem> out = reranker().rerank(fused, input(3, items));

        assertEquals(3, out.size());
        assertEquals(100L, out.get(0).itemId(), "首位仍是相关性最高");
        long distinctCats = out.stream().map(r -> items.get(r.itemId()).category()).distinct().count();
        assertTrue(distinctCats >= 2, "DPP 应跨类目多样化,而非同类目扎堆(实得类目数=" + distinctCats + ")");
    }

    @Test
    void candidatesFewerThanSize_returnsAllByRelevance() {
        Map<Long, Item> items = new HashMap<>();
        List<RerankCandidate> fused = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            long id = 10 + i;
            items.put(id, new Item(id, "t", "A", List.of(), "", 0));
            fused.add(new RerankCandidate(id, 1.0 - i * 0.1));
        }
        List<RecommendItem> out = reranker().rerank(fused, input(5, items));
        assertEquals(2, out.size());
        assertEquals(10L, out.get(0).itemId());
    }
}
