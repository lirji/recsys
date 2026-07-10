package com.recsys.recengine;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link ShadowRankRunner#overlapAtK} 单测(P5)——top-K 重合率是影子流量对比主/影子策略的核心口径。
 */
class ShadowRankRunnerTest {

    @Test
    void identicalTopK_overlap1() {
        assertEquals(1.0, ShadowRankRunner.overlapAtK(List.of(1L, 2L, 3L), List.of(1L, 2L, 3L)), 1e-9);
        assertEquals(1.0, ShadowRankRunner.overlapAtK(List.of(), List.of()), 1e-9);
    }

    @Test
    void disjointTopK_overlap0() {
        assertEquals(0.0, ShadowRankRunner.overlapAtK(List.of(1L, 2L, 3L), List.of(4L, 5L, 6L)), 1e-9);
    }

    @Test
    void partialOverlap_ratioOfIntersectionOverLarger() {
        // {1,2,3} ∩ {2,3,9} = {2,3} → 2/3(顺序无关,只看集合重合)
        assertEquals(2.0 / 3.0, ShadowRankRunner.overlapAtK(List.of(1L, 2L, 3L), List.of(2L, 3L, 9L)), 1e-9);
    }

    @Test
    void differentSizes_dividesByLarger() {
        // {1,2} ∩ {1,2,3,4} = {1,2} → 2/4
        assertEquals(0.5, ShadowRankRunner.overlapAtK(List.of(1L, 2L), List.of(1L, 2L, 3L, 4L)), 1e-9);
    }
}
