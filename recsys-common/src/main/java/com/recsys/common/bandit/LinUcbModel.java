package com.recsys.common.bandit;

import java.util.Random;

/**
 * 线性上下文老虎机(LinUCB / Thompson)的**纯数学**充分统计模型(无 Spring/IO,单一真源)。
 *
 * <p>R7 全量 contextual bandit 的数学内核:维护共享岭回归充分统计
 * {@code A = λI + Σ x·xᵀ}(d×d)与 {@code b = Σ reward·x}(d),据此在线求
 * <ul>
 *   <li>点估计均值 {@code θ̂ = A⁻¹·b}(线性 CTR 味信号,补排序 exploit);</li>
 *   <li>LinUCB 置信宽度 {@code √(xᵀA⁻¹x)}(不确定性,驱动探索);</li>
 *   <li>Thompson 采样 {@code θ̃ = θ̂ + α·L·z}(L=cholesky(A⁻¹),z~N(0,I)),用采样方差探索。</li>
 * </ul>
 * 上下文 {@code x} 维度极小(=排序稠密特征数,默认 5),故 A⁻¹/Cholesky 的 O(d³) 可忽略。
 *
 * <p><b>纯数值、只吃/吐 {@code double[]}</b>:不依赖 {@code FeatureAssembler} 或 JSON —— x 向量的
 * 组装与序列化(经 {@link BanditModelDto})留在上层(离线 {@code bandit-stats} / 在线 {@code BanditScorer}),
 * 保持 recsys-common 基座干净。序列化往返即"离线攒统计 → 在线服务/增量续训"的 warm-start 契约。
 */
public final class LinUcbModel {

    private final int d;
    private final double lambda;
    private final double[][] a;   // A = λI + Σ x xᵀ
    private final double[] b;     // b = Σ reward·x
    private long n;               // 累计样本数

    private LinUcbModel(int d, double lambda, double[][] a, double[] b, long n) {
        this.d = d;
        this.lambda = lambda;
        this.a = a;
        this.b = b;
        this.n = n;
    }

    /** 全新模型:{@code A = λ·I}(λ&gt;0 保证正定可逆),{@code b = 0},n=0。 */
    public static LinUcbModel create(int d, double lambda) {
        if (d <= 0) {
            throw new IllegalArgumentException("维度必须为正: " + d);
        }
        if (!(lambda > 0)) {
            throw new IllegalArgumentException("岭 λ 必须为正(保证 A 可逆): " + lambda);
        }
        double[][] a = new double[d][d];
        for (int i = 0; i < d; i++) {
            a[i][i] = lambda;
        }
        return new LinUcbModel(d, lambda, a, new double[d], 0);
    }

    /** 从已有充分统计重建(warm-start / 反序列化)。A、b 会被防御性拷贝。 */
    public static LinUcbModel of(double lambda, double[][] a, double[] b, long n) {
        int d = b.length;
        if (a.length != d) {
            throw new IllegalArgumentException("A 维度(" + a.length + ")与 b(" + d + ")不一致");
        }
        double[][] ac = new double[d][];
        for (int i = 0; i < d; i++) {
            if (a[i].length != d) {
                throw new IllegalArgumentException("A 非方阵");
            }
            ac[i] = a[i].clone();
        }
        return new LinUcbModel(d, lambda, ac, b.clone(), n);
    }

    /** 用一个观测 (x, reward) 更新充分统计:{@code A += x·xᵀ};{@code b += reward·x};n++。 */
    public void accumulate(double[] x, double reward) {
        if (x.length != d) {
            throw new IllegalArgumentException("x 维度(" + x.length + ")≠模型维度(" + d + ")");
        }
        for (int i = 0; i < d; i++) {
            double xi = x[i];
            b[i] += reward * xi;
            double[] ai = a[i];
            for (int j = 0; j < d; j++) {
                ai[j] += xi * x[j];
            }
        }
        n++;
    }

    public int dim() {
        return d;
    }

    public double getLambda() {
        return lambda;
    }

    public long getN() {
        return n;
    }

    /** 返回 A 的拷贝(供序列化)。 */
    public double[][] getA() {
        double[][] out = new double[d][];
        for (int i = 0; i < d; i++) {
            out[i] = a[i].clone();
        }
        return out;
    }

    /** 返回 b 的拷贝(供序列化)。 */
    public double[] getB() {
        return b.clone();
    }

    /** A 的逆(供在线预计算一次后复用);A 正定,失败(奇异)抛异常。 */
    public double[][] inverseA() {
        return invert(a);
    }

    /** 点估计 {@code θ̂ = A⁻¹·b};传入已算好的 A⁻¹ 避免重复求逆。 */
    public double[] theta(double[][] aInv) {
        return matVec(aInv, b);
    }

    // ---------- 静态数值工具(在线/离线共用) ----------

    /** 内积 aᵀb。 */
    public static double dot(double[] a, double[] b) {
        double s = 0;
        for (int i = 0; i < a.length; i++) {
            s += a[i] * b[i];
        }
        return s;
    }

    /** 二次型 {@code xᵀ·M·x}(M=A⁻¹ 时即 LinUCB 方差,≥0;负值截 0 防浮点噪声)。 */
    public static double quadForm(double[] x, double[][] m) {
        double s = 0;
        for (int i = 0; i < x.length; i++) {
            double row = 0;
            double[] mi = m[i];
            for (int j = 0; j < x.length; j++) {
                row += mi[j] * x[j];
            }
            s += x[i] * row;
        }
        return Math.max(0.0, s);
    }

    /** M·v。 */
    public static double[] matVec(double[][] m, double[] v) {
        int rows = m.length;
        double[] out = new double[rows];
        for (int i = 0; i < rows; i++) {
            double s = 0;
            double[] mi = m[i];
            for (int j = 0; j < v.length; j++) {
                s += mi[j] * v[j];
            }
            out[i] = s;
        }
        return out;
    }

    /**
     * Thompson 采样 {@code θ̃ = θ̂ + α·L·z},L=cholesky(A⁻¹)(下三角,L·Lᵀ=A⁻¹),z~N(0,I)。
     * 于是 Cov(θ̃)=α²·A⁻¹(采样方差随 α² 缩放),供每请求采一次做探索。
     *
     * @param thetaHat 点估计 θ̂
     * @param aInv     A⁻¹(正定)
     * @param alpha    采样标准差倍数(&gt;0)
     * @param rng      随机源(测试可传定种)
     */
    public static double[] sampleTheta(double[] thetaHat, double[][] aInv, double alpha, Random rng) {
        int d = thetaHat.length;
        double[][] l = cholesky(aInv);
        double[] z = new double[d];
        for (int i = 0; i < d; i++) {
            z[i] = rng.nextGaussian();
        }
        double[] out = thetaHat.clone();
        for (int i = 0; i < d; i++) {
            double lz = 0;
            for (int j = 0; j <= i; j++) {      // L 下三角
                lz += l[i][j] * z[j];
            }
            out[i] += alpha * lz;
        }
        return out;
    }

    /** 小矩阵高斯-约当求逆(带部分主元);奇异则抛异常。 */
    public static double[][] invert(double[][] m) {
        int d = m.length;
        double[][] aug = new double[d][2 * d];
        for (int i = 0; i < d; i++) {
            System.arraycopy(m[i], 0, aug[i], 0, d);
            aug[i][d + i] = 1.0;
        }
        for (int col = 0; col < d; col++) {
            int piv = col;
            for (int r = col + 1; r < d; r++) {
                if (Math.abs(aug[r][col]) > Math.abs(aug[piv][col])) {
                    piv = r;
                }
            }
            if (Math.abs(aug[piv][col]) < 1e-12) {
                throw new ArithmeticException("矩阵奇异,不可逆(col=" + col + ")");
            }
            double[] tmp = aug[col];
            aug[col] = aug[piv];
            aug[piv] = tmp;
            double pivVal = aug[col][col];
            for (int j = 0; j < 2 * d; j++) {
                aug[col][j] /= pivVal;
            }
            for (int r = 0; r < d; r++) {
                if (r == col) {
                    continue;
                }
                double factor = aug[r][col];
                if (factor == 0) {
                    continue;
                }
                for (int j = 0; j < 2 * d; j++) {
                    aug[r][j] -= factor * aug[col][j];
                }
            }
        }
        double[][] inv = new double[d][d];
        for (int i = 0; i < d; i++) {
            System.arraycopy(aug[i], d, inv[i], 0, d);
        }
        return inv;
    }

    /** 正定矩阵的 Cholesky 分解,返回下三角 L(L·Lᵀ=M);非正定则抛异常。 */
    public static double[][] cholesky(double[][] m) {
        int d = m.length;
        double[][] l = new double[d][d];
        for (int i = 0; i < d; i++) {
            for (int j = 0; j <= i; j++) {
                double sum = m[i][j];
                for (int k = 0; k < j; k++) {
                    sum -= l[i][k] * l[j][k];
                }
                if (i == j) {
                    if (sum <= 0) {
                        throw new ArithmeticException("矩阵非正定,Cholesky 失败(i=" + i + ")");
                    }
                    l[i][j] = Math.sqrt(sum);
                } else {
                    l[i][j] = sum / l[j][j];
                }
            }
        }
        return l;
    }
}
