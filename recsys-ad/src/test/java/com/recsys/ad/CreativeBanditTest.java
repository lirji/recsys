package com.recsys.ad;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CreativeBandit} 单元测试——DCO 多臂老虎机(UCB1)的创意择优契约(docs/05 §7 M7)。
 * 覆盖:空/单臂退化、未探索创意强制先试、充分曝光后利用高 CTR 创意、探索系数对低曝创意的抬升。
 */
class CreativeBanditTest {

    private static CreativeBandit.Arm arm(long id, long imp, long clk) {
        return new CreativeBandit.Arm(id, imp, clk);
    }

    @Test
    void emptyArms_returnsNull() {
        assertNull(CreativeBandit.select(List.of(), 0.5));
        assertNull(CreativeBandit.select(null, 0.5));
    }

    @Test
    void singleArm_returnsIt() {
        assertEquals(7L, CreativeBandit.select(List.of(arm(7, 100, 5)), 0.5));
    }

    @Test
    void unexploredCreative_isForcedFirst() {
        // 创意 2 从未曝光(imp=0)→ UCB +∞ → 必选,即便其他创意已有不错 CTR
        Long pick = CreativeBandit.select(List.of(
                arm(1, 1000, 300),   // CTR 0.30,已充分曝光
                arm(2, 0, 0)), 0.5); // 全新
        assertEquals(2L, pick, "未探索创意应被强制先试");
    }

    @Test
    void allExplored_exploitsHigherCtr() {
        // 两创意都已充分曝光(探索项很小),选经验 CTR 更高者
        Long pick = CreativeBandit.select(List.of(
                arm(1, 5000, 500),    // CTR 0.10
                arm(2, 5000, 1500)),  // CTR 0.30
                0.5);
        assertEquals(2L, pick, "充分曝光后应利用高 CTR 创意");
    }

    @Test
    void exploration_liftsLowExposureArm() {
        // 创意 1 CTR 略高但已饱和,创意 2 CTR 略低但曝光少 → 大 coef 时探索项把 2 抬过 1
        List<CreativeBandit.Arm> arms = List.of(
                arm(1, 100000, 21000),  // CTR 0.21,几乎无探索空间
                arm(2, 50, 10));        // CTR 0.20,曝光少 → 探索项大
        assertEquals(1L, CreativeBandit.select(arms, 0.0), "coef=0(纯利用)→ 选 CTR 略高的 1");
        assertEquals(2L, CreativeBandit.select(arms, 2.0), "coef 大 → 探索抬升低曝的 2");
    }

    @Test
    void ctr_isClicksOverImpressions() {
        assertEquals(0.25, arm(1, 200, 50).ctr(), 1e-9);
        assertEquals(0.0, arm(1, 0, 0).ctr(), 1e-9, "零曝光 CTR 记 0");
    }
}
