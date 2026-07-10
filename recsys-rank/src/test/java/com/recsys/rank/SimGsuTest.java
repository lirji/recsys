package com.recsys.rank;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SimGsu} 单测(R3)——GSU 类目硬检索:从长历史按候选类目取最近 k 个同类目,返回 oldest→newest。
 */
class SimGsuTest {

    private static SimGsu.Hist h(long id, String cat) {
        return new SimGsu.Hist(id, cat);
    }

    @Test
    void retrievesMostRecentSameCategory_oldestFirst() {
        // 历史 oldest→newest;Action item:1,3,5,7(其中最近的 3 个是 3,5,7)
        List<SimGsu.Hist> hist = List.of(
                h(1, "Action"), h(2, "Drama"), h(3, "Action"), h(4, "Comedy"),
                h(5, "Action"), h(6, "Drama"), h(7, "Action"));
        // k=3 → 最近 3 个 Action:{3,5,7},oldest→newest
        assertEquals(List.of(3L, 5L, 7L), SimGsu.retrieve(hist, "Action", 3));
        // k 大于该类目数 → 返回全部同类目
        assertEquals(List.of(1L, 3L, 5L, 7L), SimGsu.retrieve(hist, "Action", 10));
    }

    @Test
    void differentCandidateCategory_retrievesThatCategory() {
        List<SimGsu.Hist> hist = List.of(h(1, "Action"), h(2, "Drama"), h(3, "Drama"));
        assertEquals(List.of(2L, 3L), SimGsu.retrieve(hist, "Drama", 5));
    }

    @Test
    void emptyOrNoMatch_returnsEmpty() {
        List<SimGsu.Hist> hist = List.of(h(1, "Action"));
        assertTrue(SimGsu.retrieve(hist, "SciFi", 5).isEmpty(), "无同类目 → 空(ESU 池化置 0)");
        assertTrue(SimGsu.retrieve(List.of(), "Action", 5).isEmpty());
        assertTrue(SimGsu.retrieve(hist, null, 5).isEmpty());
        assertTrue(SimGsu.retrieve(hist, "Action", 0).isEmpty());
    }
}
