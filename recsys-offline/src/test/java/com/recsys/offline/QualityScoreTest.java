package com.recsys.offline;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link QualityScore} 单元测试——质量度精细化的数学契约(docs/05 §7 M7)。
 * 覆盖:贝叶斯收缩两端行为、归一围绕 1.0 + 基准缺失中性化 + clamp、加权融合(平均广告→1.0、好/差广告偏离)。
 */
class QualityScoreTest {

    private static final double EPS = 1e-9;

    // ---- 贝叶斯收缩 ----

    @Test
    void shrink_pullsLowVolumeTowardPrior() {
        double popRate = 0.2;
        // 0 试验 → 完全等于大盘先验
        assertEquals(popRate, QualityScore.shrink(0, 0, popRate, 100), EPS);
        // 少量样本(1 次点击/10 次曝光,经验 0.1)被强先验拉回接近大盘 0.2
        double shrunk = QualityScore.shrink(1, 10, popRate, 100);
        assertTrue(shrunk > 0.18 && shrunk < 0.2, "低量应收缩接近大盘:" + shrunk);
    }

    @Test
    void shrink_largeVolumeApproachesEmpirical() {
        // 大样本(2000 点击/10000 曝光,经验 0.2)几乎不被先验影响
        double shrunk = QualityScore.shrink(2000, 10000, 0.05, 100);
        assertEquals(0.2, shrunk, 0.005, "大样本应接近经验率");
    }

    // ---- 归一 ----

    @Test
    void norm_centersAtOne() {
        assertEquals(1.0, QualityScore.norm(0.2, 0.2, 0.5), EPS, "等于大盘 → 1.0");
        assertEquals(1.25, QualityScore.norm(0.25, 0.2, 0.5), EPS, "高于大盘 → >1");
        assertEquals(0.75, QualityScore.norm(0.15, 0.2, 0.5), EPS, "低于大盘 → <1");
    }

    @Test
    void norm_clampsExtremes() {
        assertEquals(1.5, QualityScore.norm(10.0, 0.2, 0.5), EPS, "上限 1+clamp");
        assertEquals(0.5, QualityScore.norm(0.0, 0.2, 0.5), EPS, "下限 1-clamp");
    }

    @Test
    void norm_neutralWhenBaseUnavailable() {
        assertEquals(1.0, QualityScore.norm(0.3, 0.0, 0.5), EPS, "大盘基准缺失 → 中性 1.0");
        assertEquals(1.0, QualityScore.norm(0.3, -1, 0.5), EPS);
    }

    // ---- 融合 ----

    @Test
    void fuse_averageAdScoresOne() {
        // 三因子全 1.0(平均广告)→ 质量度 1.0(中性,等同旧随机基线中位)
        assertEquals(1.0, QualityScore.fuse(1.0, 1.0, 1.0, 0.3, 0.4, 0.3, 0.5, 1.5), EPS);
    }

    @Test
    void fuse_isWeightedMean() {
        // relN=0.5, ctrN=1.5, cvrN=1.0,权重 0.3/0.4/0.3 → (0.15+0.6+0.3)/1.0 = 1.05
        assertEquals(1.05, QualityScore.fuse(0.5, 1.5, 1.0, 0.3, 0.4, 0.3, 0.5, 1.5), EPS);
    }

    @Test
    void fuse_clampsToRange() {
        assertEquals(1.5, QualityScore.fuse(1.5, 1.5, 1.5, 0.3, 0.4, 0.3, 0.5, 1.5), EPS, "全高 → 上限");
        assertEquals(0.5, QualityScore.fuse(0.5, 0.5, 0.5, 0.3, 0.4, 0.3, 0.5, 1.5), EPS, "全低 → 下限");
    }

    @Test
    void fuse_goodAdAboveOne_poorAdBelowOne() {
        double good = QualityScore.fuse(1.5, 1.2, 1.3, 0.3, 0.4, 0.3, 0.5, 1.5);
        double poor = QualityScore.fuse(0.5, 0.8, 0.7, 0.3, 0.4, 0.3, 0.5, 1.5);
        assertTrue(good > 1.0, "好广告 >1:" + good);
        assertTrue(poor < 1.0, "差广告 <1:" + poor);
    }
}
