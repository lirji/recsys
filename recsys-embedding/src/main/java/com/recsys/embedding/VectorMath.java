package com.recsys.embedding;

/**
 * 向量工具。gemini-embedding-001 降到 768 维后向量未归一化,
 * 入库前必须 L2 归一化,否则余弦相似度计算不正确(见 docs/03 §2)。
 */
public final class VectorMath {

    private VectorMath() {
    }

    /** L2 归一化(原地不修改,返回新数组)。模长为 0 时原样返回。 */
    public static float[] l2Normalize(float[] v) {
        double sumSq = 0;
        for (float x : v) {
            sumSq += (double) x * x;
        }
        double norm = Math.sqrt(sumSq);
        if (norm == 0) {
            return v.clone();
        }
        float[] out = new float[v.length];
        for (int i = 0; i < v.length; i++) {
            out[i] = (float) (v[i] / norm);
        }
        return out;
    }
}
