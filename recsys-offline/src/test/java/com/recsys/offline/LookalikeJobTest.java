package com.recsys.offline;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * {@link LookalikeJob#centroid} 单测(A3)——种子人群向量质心是 Look-alike 扩散的检索锚点,须逐维取均值。
 */
class LookalikeJobTest {

    @Test
    void centroid_isPerDimensionMean() {
        float[] a = {0f, 2f, 4f};
        float[] b = {2f, 4f, 6f};
        float[] c = {4f, 6f, 8f};
        assertArrayEquals(new float[]{2f, 4f, 6f}, LookalikeJob.centroid(List.of(a, b, c)), 1e-6f);
    }

    @Test
    void centroid_singleSeed_isItself() {
        float[] v = {1.5f, -2f, 3f};
        assertArrayEquals(v, LookalikeJob.centroid(List.of(v)), 1e-6f);
    }
}
