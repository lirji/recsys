package com.recsys.recengine;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 编排层配置:重排 + 缓存 + 召回/排序融合权重。
 */
@ConfigurationProperties(prefix = "recsys")
public class RecEngineProperties {

    private final Rerank rerank = new Rerank();
    private final Cache cache = new Cache();
    private final Fusion fusion = new Fusion();

    public Rerank getRerank() {
        return rerank;
    }

    public Cache getCache() {
        return cache;
    }

    public Fusion getFusion() {
        return fusion;
    }

    public static class Rerank {
        /** 同一类目在结果中最多连续/总计出现次数。 */
        private int maxSameCategory = 3;

        public int getMaxSameCategory() {
            return maxSameCategory;
        }

        public void setMaxSameCategory(int maxSameCategory) {
            this.maxSameCategory = maxSameCategory;
        }
    }

    public static class Cache {
        private long recTtlSeconds = 300;

        public long getRecTtlSeconds() {
            return recTtlSeconds;
        }

        public void setRecTtlSeconds(long recTtlSeconds) {
            this.recTtlSeconds = recTtlSeconds;
        }
    }

    /** M1 阶段特征稀疏,最终分 = recallWeight*召回分 + rankWeight*排序分。 */
    public static class Fusion {
        private double recallWeight = 1.0;
        private double rankWeight = 1.0;

        public double getRecallWeight() {
            return recallWeight;
        }

        public void setRecallWeight(double recallWeight) {
            this.recallWeight = recallWeight;
        }

        public double getRankWeight() {
            return rankWeight;
        }

        public void setRankWeight(double rankWeight) {
            this.rankWeight = rankWeight;
        }
    }
}
