package com.recsys.offline;

import java.util.HashMap;
import java.util.Map;

/**
 * FTRL-Proximal 在线逻辑回归(McMahan 2013),稀疏状态、逐坐标自适应学习率 + L1/L2 正则。
 *
 * <p>每个坐标维护 (z, n):{@code w = -(z - sign(z)·λ1) / ((β + √n)/α + λ2)},当 {@code |z| ≤ λ1} 时 w=0
 * (L1 产生稀疏解,故服务只需存非零权重)。仅当前样本命中的坐标被更新,天然稀疏 → 用 HashMap 存,
 * 状态规模 ≈ 见过的 (user/item/交叉) 数,可 JSON 序列化到 Redis 支持近线增量续训。
 *
 * <p>纯算法、无框架依赖,便于单测;供 {@link FtrlTrainJob} 近线增量训练协同过滤味的轻量 CTR 模型。
 */
final class FtrlProximal {

    private final double alpha;
    private final double beta;
    private final double l1;
    private final double l2;

    private final Map<Integer, double[]> zn = new HashMap<>();   // idx -> [z, n]
    private final double[] biasZn = new double[]{0.0, 0.0};       // 偏置项单独一坐标

    FtrlProximal(double alpha, double beta, double l1, double l2) {
        this.alpha = alpha;
        this.beta = beta;
        this.l1 = l1;
        this.l2 = l2;
    }

    /** 由 (z,n) 惰性算权重;|z|≤l1 → 0(稀疏)。 */
    private double weight(double[] s) {
        double z = s[0];
        if (Math.abs(z) <= l1) {
            return 0.0;
        }
        return -(z - Math.signum(z) * l1) / ((beta + Math.sqrt(s[1])) / alpha + l2);
    }

    /** 预测正反馈概率 p = sigmoid(bias + Σ w[feat])。 */
    double predict(int[] feats) {
        double wtx = weight(biasZn);
        for (int i : feats) {
            double[] s = zn.get(i);
            if (s != null) {
                wtx += weight(s);
            }
        }
        return 1.0 / (1.0 + Math.exp(-clip(wtx)));
    }

    /** 用一个样本 (feats, label∈{0,1}) 做一次 FTRL 更新。 */
    void update(int[] feats, double label) {
        double p = predict(feats);
        double g = p - label;                 // 逻辑回归梯度(对每个命中坐标相同,值=1)
        updateCoord(biasZn, g);
        for (int i : feats) {
            updateCoord(zn.computeIfAbsent(i, k -> new double[2]), g);
        }
    }

    private void updateCoord(double[] s, double g) {
        double n = s[1];
        double sigma = (Math.sqrt(n + g * g) - Math.sqrt(n)) / alpha;
        double w = weight(s);
        s[0] += g - sigma * w;                // z
        s[1] += g * g;                        // n
    }

    private static double clip(double v) {
        return v > 35 ? 35 : (v < -35 ? -35 : v);
    }

    // ---------- 状态 / 权重 序列化(供 Redis 近线续训与在线服务) ----------

    /** 训练状态(z,n),供增量续训 warm-start。 */
    Map<Integer, double[]> stateCoords() {
        return zn;
    }

    double[] biasState() {
        return biasZn;
    }

    /** 载入上次状态(增量续训)。 */
    void loadState(double[] bias, Map<Integer, double[]> coords) {
        biasZn[0] = bias[0];
        biasZn[1] = bias[1];
        zn.clear();
        zn.putAll(coords);
    }

    /** 导出服务用非零权重:idx -> weight(w=0 的坐标略去,得稀疏模型)。 */
    Map<Integer, Double> serveWeights() {
        Map<Integer, Double> out = new HashMap<>();
        for (var e : zn.entrySet()) {
            double w = weight(e.getValue());
            if (w != 0.0) {
                out.put(e.getKey(), w);
            }
        }
        return out;
    }

    double serveBias() {
        return weight(biasZn);
    }
}
