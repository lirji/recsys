package com.recsys.common.bandit;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LinUcbModel} 单测(R7)——锁死 contextual bandit 数学内核:求逆/Cholesky 正确、
 * 探索方向不确定性随曝光下降、θ 恢复线性 reward、Thompson 采样分布、以及 warm-start 序列化往返。
 */
class LinUcbModelTest {

    private static final double EPS = 1e-9;

    @Test
    void invert_knownMatrix() {
        double[][] m = {{4, 3}, {6, 3}};   // det = -6
        double[][] inv = LinUcbModel.invert(m);
        // M·M⁻¹ = I
        double[][] p = mul(m, inv);
        assertEquals(1.0, p[0][0], 1e-9);
        assertEquals(0.0, p[0][1], 1e-9);
        assertEquals(0.0, p[1][0], 1e-9);
        assertEquals(1.0, p[1][1], 1e-9);
    }

    @Test
    void invert_singularThrows() {
        double[][] m = {{1, 2}, {2, 4}};   // 奇异
        assertThrows(ArithmeticException.class, () -> LinUcbModel.invert(m));
    }

    @Test
    void cholesky_reconstructsMatrix() {
        double[][] m = {{4, 2}, {2, 3}};   // 正定
        double[][] l = LinUcbModel.cholesky(m);
        // L·Lᵀ = M
        double[][] llt = mul(l, transpose(l));
        assertEquals(4.0, llt[0][0], 1e-9);
        assertEquals(2.0, llt[1][0], 1e-9);
        assertEquals(3.0, llt[1][1], 1e-9);
    }

    @Test
    void uncertaintyShrinksInExploredDirection() {
        LinUcbModel m = LinUcbModel.create(2, 1.0);
        double[] explored = {1.0, 0.0};
        double[] novel = {0.0, 1.0};
        double beforeE = LinUcbModel.quadForm(explored, m.inverseA());
        // 沿 explored 方向反复观测
        for (int i = 0; i < 50; i++) {
            m.accumulate(explored, 1.0);
        }
        double afterE = LinUcbModel.quadForm(explored, m.inverseA());
        double afterNovel = LinUcbModel.quadForm(novel, m.inverseA());
        assertTrue(afterE < beforeE, "已探索方向的不确定性应下降: " + afterE + " < " + beforeE);
        assertTrue(afterNovel > afterE, "未探索方向不确定性更大(值得探索): " + afterNovel + " > " + afterE);
        assertEquals(50, m.getN());
    }

    @Test
    void thetaRecoversLinearReward() {
        // 构造 reward = w·x 的样本,θ̂ 方向应与 w 一致
        double[] w = {2.0, -1.0};
        LinUcbModel m = LinUcbModel.create(2, 0.01);
        Random rng = new Random(1);
        for (int i = 0; i < 500; i++) {
            double[] x = {rng.nextGaussian(), rng.nextGaussian()};
            m.accumulate(x, LinUcbModel.dot(w, x));
        }
        double[] theta = m.theta(m.inverseA());
        assertEquals(2.0, theta[0], 0.1, "θ̂[0] 应≈2");
        assertEquals(-1.0, theta[1], 0.1, "θ̂[1] 应≈-1");
    }

    @Test
    void thompson_deterministicWithSeed_andCentersOnThetaHat() {
        LinUcbModel m = LinUcbModel.create(2, 1.0);
        for (int i = 0; i < 20; i++) {
            m.accumulate(new double[]{1, 0}, 1.0);
            m.accumulate(new double[]{0, 1}, 0.0);
        }
        double[][] aInv = m.inverseA();
        double[] thetaHat = m.theta(aInv);
        // 固定 seed → 可复现
        double[] s1 = LinUcbModel.sampleTheta(thetaHat, aInv, 1.0, new Random(42));
        double[] s2 = LinUcbModel.sampleTheta(thetaHat, aInv, 1.0, new Random(42));
        assertEquals(s1[0], s2[0], EPS);
        assertEquals(s1[1], s2[1], EPS);
        // 大量采样均值 ≈ θ̂
        Random rng = new Random(7);
        double[] mean = new double[2];
        int reps = 20000;
        for (int i = 0; i < reps; i++) {
            double[] s = LinUcbModel.sampleTheta(thetaHat, aInv, 1.0, rng);
            mean[0] += s[0];
            mean[1] += s[1];
        }
        mean[0] /= reps;
        mean[1] /= reps;
        assertEquals(thetaHat[0], mean[0], 0.03, "Thompson 采样均值应≈θ̂");
        assertEquals(thetaHat[1], mean[1], 0.03);
    }

    @Test
    void thompson_alphaScalesSpread() {
        LinUcbModel m = LinUcbModel.create(2, 1.0);
        for (int i = 0; i < 10; i++) {
            m.accumulate(new double[]{1, 0}, 1.0);
        }
        double[][] aInv = m.inverseA();
        double[] th = m.theta(aInv);
        double spreadSmall = spread(th, aInv, 0.5);
        double spreadLarge = spread(th, aInv, 2.0);
        assertTrue(spreadLarge > spreadSmall, "α 越大采样越发散: " + spreadLarge + " > " + spreadSmall);
    }

    @Test
    void dtoRoundtrip_reconstructsIdentically() {
        LinUcbModel m = LinUcbModel.create(3, 1.0);
        Random rng = new Random(5);
        for (int i = 0; i < 100; i++) {
            double[] x = {rng.nextGaussian(), rng.nextGaussian(), rng.nextGaussian()};
            m.accumulate(x, rng.nextBoolean() ? 1.0 : 0.0);
        }
        // 经 DTO 往返(warm-start / 在线服务契约;JSON 串由持 Jackson 的 app 模块 + E2E 覆盖)
        BanditModelDto dto = BanditModelDto.from(List.of("f0", "f1", "f2"), m);
        LinUcbModel m2 = dto.toModel();
        // θ̂ 与 bonus 逐位一致
        double[] x = {0.3, -0.7, 1.1};
        assertEquals(m.getN(), m2.getN());
        double[] t1 = m.theta(m.inverseA());
        double[] t2 = m2.theta(m2.inverseA());
        assertEquals(t1[0], t2[0], 1e-9);
        assertEquals(t1[1], t2[1], 1e-9);
        assertEquals(t1[2], t2[2], 1e-9);
        assertEquals(LinUcbModel.quadForm(x, m.inverseA()),
                LinUcbModel.quadForm(x, m2.inverseA()), 1e-9);
        assertEquals(List.of("f0", "f1", "f2"), dto.order());
        // 防御性拷贝:改原模型不影响已取出的 DTO 数组
        m.accumulate(x, 1.0);
        assertEquals(100, m2.getN(), "toModel 应为独立副本");
    }

    @Test
    void create_rejectsBadArgs() {
        assertThrows(IllegalArgumentException.class, () -> LinUcbModel.create(0, 1.0));
        assertThrows(IllegalArgumentException.class, () -> LinUcbModel.create(3, 0.0));
    }

    // ---- 小工具 ----

    private static double spread(double[] thetaHat, double[][] aInv, double alpha) {
        Random rng = new Random(11);
        double var = 0;
        int reps = 5000;
        for (int i = 0; i < reps; i++) {
            double[] s = LinUcbModel.sampleTheta(thetaHat, aInv, alpha, rng);
            double dx = s[0] - thetaHat[0];
            var += dx * dx;
        }
        return var / reps;
    }

    private static double[][] mul(double[][] a, double[][] b) {
        int n = a.length, m = b[0].length, k = b.length;
        double[][] c = new double[n][m];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < m; j++) {
                double s = 0;
                for (int t = 0; t < k; t++) {
                    s += a[i][t] * b[t][j];
                }
                c[i][j] = s;
            }
        }
        return c;
    }

    private static double[][] transpose(double[][] a) {
        int n = a.length, m = a[0].length;
        double[][] t = new double[m][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < m; j++) {
                t[j][i] = a[i][j];
            }
        }
        return t;
    }
}
