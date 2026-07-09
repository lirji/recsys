package com.recsys.offline;

import com.recsys.common.feature.FtrlHashing;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link FtrlHashing} 契约测试 —— 在线打分(FtrlScorer)与离线训练(train-ftrl)必须逐位一致的特征哈希。
 * 覆盖:确定性、下标在 [0,DIM)、不同 (user,item) 不同哈希、特征数=3。
 */
class FtrlHashingTest {

    @Test
    void deterministic() {
        assertArrayEquals(FtrlHashing.features(1, 100), FtrlHashing.features(1, 100),
                "同输入必须同输出(否则在线/离线错位)");
    }

    @Test
    void indicesInRange() {
        for (int f : FtrlHashing.features(12345, 67890)) {
            assertTrue(f >= 0 && f < FtrlHashing.DIM, "下标越界: " + f);
        }
    }

    @Test
    void distinctPairsDifferentHash() {
        assertFalse(Arrays.equals(FtrlHashing.features(1, 100), FtrlHashing.features(1, 200)));
        assertFalse(Arrays.equals(FtrlHashing.features(1, 100), FtrlHashing.features(2, 100)));
    }

    @Test
    void threeFeatures() {
        assertEquals(3, FtrlHashing.features(1, 100).length, "user / item / user×item 交叉");
    }
}
