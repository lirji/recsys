package com.recsys.offline;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DelayModel} 单元测试——延迟转化纠偏的数学契约(docs/05 §6,M6)。
 * 验证完成曲线、Horvitz–Thompson 权重、λ 的 MLE,以及"在大样本上 HT 估计无偏地补回删失转化"这一核心主张。
 */
class DelayModelTest {

    private static final double EPS = 1e-9;

    // ---- 完成曲线 c(e)=1−e^(−λe) ----

    @Test
    void completion_isZeroAtOrigin() {
        assertEquals(0.0, DelayModel.completion(0.5, 0.0), EPS);
        assertEquals(0.0, DelayModel.completion(0.5, -1.0), EPS, "负 elapsed 视为 0");
    }

    @Test
    void completion_matchesClosedForm() {
        double lambda = 0.354719;
        assertEquals(1 - Math.exp(-lambda * 3), DelayModel.completion(lambda, 3), EPS);
    }

    @Test
    void completion_isMonotonicAndApproachesOne() {
        double lambda = 0.4;
        double prev = -1;
        for (double e = 0; e <= 30; e += 0.5) {
            double c = DelayModel.completion(lambda, e);
            assertTrue(c >= prev, "完成曲线应随 elapsed 单调不减");
            assertTrue(c >= 0 && c <= 1, "完成度应在 [0,1]");
            prev = c;
        }
        assertTrue(DelayModel.completion(lambda, 100) > 0.999, "elapsed 足够大时趋近 1");
    }

    // ---- Horvitz–Thompson 权重 = 1/max(c, minCompletion) ----

    @Test
    void htWeight_isInverseCompletion() {
        double lambda = 0.4, e = 7;
        double expected = 1.0 / DelayModel.completion(lambda, e);
        assertEquals(expected, DelayModel.htWeight(lambda, e, 0.01), EPS);
    }

    @Test
    void htWeight_freshClickGetsLargeButCappedWeight() {
        // elapsed→0 时 c→0,若不设下限权重发散;minCompletion 把权重封顶在 1/minCompletion。
        double lambda = 0.4, minCompletion = 0.05;
        double w = DelayModel.htWeight(lambda, 1e-6, minCompletion);
        assertEquals(1.0 / minCompletion, w, EPS, "极近点击权重应被 minCompletion 封顶");
    }

    @Test
    void htWeight_oldClickApproachesOne() {
        // elapsed 远大于 1/λ 时几乎必已转化,权重→1(不需要补)。
        double w = DelayModel.htWeight(0.4, 60, 0.05);
        assertTrue(w > 1.0 && w < 1.001, "充分老的点击权重应≈1,实际=" + w);
    }

    // ---- λ 的 MLE ----

    @Test
    void lambdaFromMeanDays_isReciprocal() {
        assertEquals(0.25, DelayModel.lambdaFromMeanDays(4.0), EPS);
    }

    @Test
    void lambdaFromMeanDays_rejectsNonPositive() {
        assertThrows(IllegalArgumentException.class, () -> DelayModel.lambdaFromMeanDays(0.0));
        assertThrows(IllegalArgumentException.class, () -> DelayModel.lambdaFromMeanDays(-1.0));
        assertThrows(IllegalArgumentException.class,
                () -> DelayModel.lambdaFromMeanDays(Double.NaN));
    }

    // ---- 核心主张:HT 加权无偏地补回删失转化 ----

    @Test
    void htWeighting_recoversCensoredConversions() {
        // 构造一批"终将转化"的点击,真实延迟 ~ Exp(λ);观测时刻 now,点击 elapsed ~ U(0, horizon)。
        // 只有 delay <= elapsed 的转化已到达(其余被右删失)。对已到达者按 htWeight 加权求和,
        // 应近似无偏地还原"全部 N 个终值转化",而朴素计数会系统性偏低。
        double lambda = 0.4;          // 平均延迟 2.5 天
        double horizon = 20.0;        // 点击均匀分布在过去 20 天
        int n = 200_000;
        Random rnd = new Random(42);

        int arrived = 0;
        double weightedSum = 0;
        for (int i = 0; i < n; i++) {
            double elapsed = rnd.nextDouble() * horizon;
            double delay = -Math.log(1 - rnd.nextDouble()) / lambda;  // Exp(λ) 采样
            if (delay <= elapsed) {                                   // 已到达(未被删失)
                arrived++;
                weightedSum += DelayModel.htWeight(lambda, elapsed, 1e-6);
            }
        }

        // 朴素计数明显低估(被删失),而 HT 加权和应在真值 n 的 ±2% 内。
        assertTrue(arrived < 0.9 * n, "应有可观测删失,实际到达=" + arrived);
        double relErr = Math.abs(weightedSum - n) / n;
        assertTrue(relErr < 0.02,
                "HT 加权应无偏还原终值转化数:期望≈" + n + ",得" + Math.round(weightedSum)
                        + "(相对误差 " + relErr + "),朴素计数=" + arrived);
    }
}
