package com.recsys.recengine;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 编排层配置:重排 + 缓存 + 召回/排序融合权重。
 */
@ConfigurationProperties(prefix = "recsys")
public class RecEngineProperties {

    private final Rerank rerank = new Rerank();
    private final Cache cache = new Cache();
    private final Fusion fusion = new Fusion();
    private final ColdStart coldStart = new ColdStart();
    private final Filter filter = new Filter();

    public Rerank getRerank() {
        return rerank;
    }

    public Filter getFilter() {
        return filter;
    }

    /** 召回后过滤(对应 recsys.filter.*)。 */
    public static class Filter {
        /** 是否从召回结果中剔除用户已正反馈过的物品(已看过滤)。 */
        private boolean seenEnabled = true;

        public boolean isSeenEnabled() {
            return seenEnabled;
        }

        public void setSeenEnabled(boolean seenEnabled) {
            this.seenEnabled = seenEnabled;
        }
    }

    public Cache getCache() {
        return cache;
    }

    public Fusion getFusion() {
        return fusion;
    }

    public ColdStart getColdStart() {
        return coldStart;
    }

    /** 冷启动判定与探索参数(对应 recsys.cold-start.*)。 */
    public static class ColdStart {
        /** 行为数 < 该阈值且无用户向量,判为冷启动用户。 */
        private int minBehaviors = 5;
        /** 冷启动时重排允许的同类目上限(更小=更强探索)。 */
        private int rerankMaxSameCategory = 1;

        public int getMinBehaviors() {
            return minBehaviors;
        }

        public void setMinBehaviors(int minBehaviors) {
            this.minBehaviors = minBehaviors;
        }

        public int getRerankMaxSameCategory() {
            return rerankMaxSameCategory;
        }

        public void setRerankMaxSameCategory(int rerankMaxSameCategory) {
            this.rerankMaxSameCategory = rerankMaxSameCategory;
        }
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

    /** M1 阶段特征稀疏,最终分 = (recallWeight*召回分 + rankWeight*排序分) * channelBoost。 */
    public static class Fusion {
        private double recallWeight = 1.0;
        private double rankWeight = 1.0;
        /**
         * 召回路融合加权:channel 名(大写,同 RecallChannel.name())→ 乘子,缺省 1.0。
         * 物品被多路命中时取其各路 boost 的最大值(取最有利信号,不过度叠加)。
         * 用途:把 TAG(已叠加实时类目偏好 rt:user)这类"用户当下/长期兴趣"信号在最终分上抬升,
         * 避免被 HOT/CF 的高热度物品在全局归一化中压过;也让冷启动 onboarding 的类目更突出。
         */
        private Map<String, Double> channelBoost = new HashMap<>();

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

        public Map<String, Double> getChannelBoost() {
            return channelBoost;
        }

        public void setChannelBoost(Map<String, Double> channelBoost) {
            this.channelBoost = channelBoost;
        }

        /** 物品命中多路时,取各路 boost 的最大值(缺省路按 1.0)。空/无命中返回 1.0。 */
        public double boostFor(List<String> channels) {
            if (channels == null || channels.isEmpty() || channelBoost.isEmpty()) {
                return 1.0;
            }
            double boost = 1.0;
            for (String c : channels) {
                Double b = channelBoost.get(c);
                if (b != null && b > boost) {
                    boost = b;
                }
            }
            return boost;
        }
    }
}
