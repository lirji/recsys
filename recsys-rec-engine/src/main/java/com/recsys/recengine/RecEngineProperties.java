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
    private final Search search = new Search();
    private final ColdStart coldStart = new ColdStart();
    private final Filter filter = new Filter();

    public Rerank getRerank() {
        return rerank;
    }

    public Search getSearch() {
        return search;
    }

    /**
     * 搜索场景(query 驱动)专用融合参数,覆盖默认 {@link Fusion} —— 让 query↔item 相关性主导,
     * 而非个性化热度/CTR。三处差异:
     * <ul>
     *   <li>召回分权重抬高、排序分权重压低:搜索以"和 query 多相关"为主,个性化 CTR 退为次序信号;</li>
     *   <li>{@code channelBoost} 抬升 SEMANTIC / TAG(意图)这两条 query 驱动路;</li>
     *   <li>{@code bypassColdStart}:冷用户带 query 时不走冷启动覆盖(query 已是明确意图,
     *       不该被强制类目探索 + 强多样性重排冲淡)。</li>
     * </ul>
     * 对应 {@code recsys.search.*}。
     */
    public static class Search {
        private boolean bypassColdStart = true;
        private double recallWeight = 2.0;
        private double rankWeight = 0.5;
        private Map<String, Double> channelBoost = new HashMap<>();
        /**
         * 个性化亲和度权重:搜索融合分 ×(1 + 权重·max(0,cos(user_emb,item_emb)))。
         * 0=关闭(纯相关性);默认 0.5 作温和加成(同等相关下偏向用户口味)。冷用户无向量自动无效。
         */
        private double personalizationWeight = 0.5;

        public boolean isBypassColdStart() {
            return bypassColdStart;
        }

        public double getPersonalizationWeight() {
            return personalizationWeight;
        }

        public void setPersonalizationWeight(double personalizationWeight) {
            this.personalizationWeight = personalizationWeight;
        }

        public void setBypassColdStart(boolean bypassColdStart) {
            this.bypassColdStart = bypassColdStart;
        }

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

        /** 流行度去偏:融合分乘 1/(1+item_pop_norm)^beta,系统性压低高热度、相对抬升长尾/语义。 */
        private final PopDebias popDebias = new PopDebias();

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

        public PopDebias getPopDebias() {
            return popDebias;
        }

        /**
         * 流行度去偏:对融合分乘惩罚因子 {@code 1/(1+item_pop_norm)^beta},pop_norm∈[0,1](越热越大)。
         * 系统性压低 HOT/CF 高热度物品、相对抬升语义/长尾召回,替代到处打补丁的 channel-boost。
         * 因子有界于 [1/2^beta, 1]、平滑、恒正,不会把任何物品清零。beta=0 或 enabled=false 关闭。
         */
        public static class PopDebias {
            private boolean enabled = true;
            /** 去偏强度:factor = 1/(1+pop_norm)^beta。beta 越大越压热度(0.5~1 常用)。 */
            private double beta = 0.5;

            public boolean isEnabled() {
                return enabled;
            }

            public void setEnabled(boolean enabled) {
                this.enabled = enabled;
            }

            public double getBeta() {
                return beta;
            }

            public void setBeta(double beta) {
                this.beta = beta;
            }
        }

        /** 物品命中多路时,取各路 boost 的最大值(缺省路按 1.0)。空/无命中返回 1.0。 */
        public double boostFor(List<String> channels) {
            return boostFor(channels, channelBoost);
        }

        /** 用给定 boost 表(默认 / 搜索场景各自一份)计算 channel 加成。 */
        public static double boostFor(List<String> channels, Map<String, Double> boostMap) {
            if (channels == null || channels.isEmpty() || boostMap == null || boostMap.isEmpty()) {
                return 1.0;
            }
            double boost = 1.0;
            for (String c : channels) {
                Double b = boostMap.get(c);
                if (b != null && b > boost) {
                    boost = b;
                }
            }
            return boost;
        }
    }
}
