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
    private final Shadow shadow = new Shadow();

    public Rerank getRerank() {
        return rerank;
    }

    public Shadow getShadow() {
        return shadow;
    }

    /**
     * 排序影子流量(P5,{@code recsys.shadow.*}):按比例对部分请求<b>异步并行</b>跑一个影子排序策略,
     * 只对比打点(与主策略的 top-K 重合度、影子耗时),<b>不影响返回结果</b>。用于新模型上线前的
     * 在线灰度评估——与 A/B 的 rank 层(真实分流金丝雀)互补:影子零风险(不进用户结果)、可全量观测差异。
     */
    public static class Shadow {
        /** 是否启用影子排序。 */
        private boolean enabled = false;
        /** 影子策略名(v1/onnx/deepfm/mmoe/din/dien/ple);空或等于主策略则不跑。 */
        private String strategy = "";
        /** 采样比例 [0,1]:按 userId 确定性采样,同一用户稳定进出影子集,便于横比。 */
        private double sampleRate = 0.1;
        /** 对比的 top-K 重合度口径。 */
        private int k = 10;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getStrategy() {
            return strategy;
        }

        public void setStrategy(String strategy) {
            this.strategy = strategy;
        }

        public double getSampleRate() {
            return sampleRate;
        }

        public void setSampleRate(double sampleRate) {
            this.sampleRate = sampleRate;
        }

        public int getK() {
            return k;
        }

        public void setK(int k) {
            this.k = k;
        }
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

        /** 精排分数保序回归校准:把 rank 原始分映射成可比概率再进融合。 */
        private final Calibration calibration = new Calibration();

        /** 近线增量学习 FTRL-LR 信号:融合时加 ftrlWeight·pFtrl(user,item)。 */
        private final Ftrl ftrl = new Ftrl();

        /** R7 全量 contextual bandit(LinUCB/Thompson)探索信号:融合时加 banditWeight·探索加成。 */
        private final Bandit bandit = new Bandit();

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

        public Calibration getCalibration() {
            return calibration;
        }

        public Ftrl getFtrl() {
            return ftrl;
        }

        public Bandit getBandit() {
            return bandit;
        }

        /**
         * 近线增量学习 FTRL-LR 信号:在融合分上加 {@code weight · pFtrl(user,item)}(协同过滤味的近线学习分)。
         * 模型缺失时打分为 0,故默认开也不改行为(直到跑 train-ftrl)。weight=0 或 enabled=false 关闭。
         */
        public static class Ftrl {
            private boolean enabled = true;
            private double weight = 0.5;

            public boolean isEnabled() {
                return enabled;
            }

            public void setEnabled(boolean enabled) {
                this.enabled = enabled;
            }

            public double getWeight() {
                return weight;
            }

            public void setWeight(double weight) {
                this.weight = weight;
            }
        }

        /**
         * R7 全量 contextual bandit(LinUCB/Thompson)探索信号:在融合分上加
         * {@code weight · banditScore(x)}。x=排序稠密特征(与离线 bandit-stats 经 FeatureAssembler 同源)。
         * <ul>
         *   <li>{@code mode=linucb}:banditScore = θ̂ᵀx + alpha·√(xᵀA⁻¹x)(点估计 + 置信宽度);</li>
         *   <li>{@code mode=thompson}:banditScore = θ̃ᵀx(每请求采一次 θ̃~N(θ̂,alpha²A⁻¹))。</li>
         * </ul>
         * 模型缺失(未跑 bandit-stats)→ 打分 0,故即便开启也不改行为。默认 <b>关</b>(改变排序,需显式启用)。
         * weight/alpha 可经 recsys:tuning 热更;mode 走静态 yml(切换重启)。
         */
        public static class Bandit {
            private boolean enabled = false;
            private double weight = 0.3;
            private double alpha = 1.0;
            private String mode = "linucb";   // linucb | thompson

            public boolean isEnabled() {
                return enabled;
            }

            public void setEnabled(boolean enabled) {
                this.enabled = enabled;
            }

            public double getWeight() {
                return weight;
            }

            public void setWeight(double weight) {
                this.weight = weight;
            }

            public double getAlpha() {
                return alpha;
            }

            public void setAlpha(double alpha) {
                this.alpha = alpha;
            }

            public String getMode() {
                return mode;
            }

            public void setMode(String mode) {
                this.mode = mode;
            }
        }

        /**
         * 精排分数校准:isotonic 单调变换,不改单策略内排序,但让 rank 分成为可比概率,
         * 使 recall+rank 融合量纲一致、可解释。model = Redis {@code rec:calib:{model}} 的标识
         * (离线 RecCalibrateJob 写)。enabled=true 但表缺失时 calibrate() 返回原值,故默认开也不改行为。
         */
        public static class Calibration {
            private boolean enabled = true;
            private String model = "rec";

            public boolean isEnabled() {
                return enabled;
            }

            public void setEnabled(boolean enabled) {
                this.enabled = enabled;
            }

            public String getModel() {
                return model;
            }

            public void setModel(String model) {
                this.model = model;
            }
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
