package com.recsys.recall.persistence.model;

/** 语义码行保留原NULL转零的读取契约，业务层负责前缀评分。 */
public record SemanticCode(long itemId, int c0, int c1, int c2) {

    /** 返回独立数组，避免不同候选共享可变码值。 */
    public int[] codes() {
        return new int[]{c0, c1, c2};
    }
}
