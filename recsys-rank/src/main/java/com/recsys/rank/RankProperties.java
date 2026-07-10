package com.recsys.rank;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 排序配置。strategy=v1 走规则打分,onnx 走 LightGBM 模型,deepfm 走 DeepFM 深度模型,
 * mmoe 走 MMoE+ESMM 多目标模型,din 走 DIN 序列建模(多目标头)。
 */
@ConfigurationProperties(prefix = "recsys.rank")
public class RankProperties {

    /** v1=规则打分;onnx=LightGBM;deepfm=DeepFM;dcn=DCN v2 显式交叉;mmoe=MMoE多目标;din=DIN序列多目标。 */
    private String strategy = "v1";

    /** ONNX 模型路径(strategy=onnx 时用)。 */
    private String onnxModelPath = "classpath:model/model.onnx";

    /** DeepFM 模型路径 + 稀疏编码 schema/vocab(strategy=deepfm 时用,均为训练侧产出)。 */
    private String deepfmModelPath = "classpath:model/model_deepfm.onnx";
    private String rankSchemaPath = "classpath:model/rank_schema.json";
    private String categoryVocabPath = "classpath:model/category_vocab.json";

    /** DCN v2 显式交叉模型 + 稀疏 schema/vocab(strategy=dcn 时用,train_dcn.py 产出;
     *  在线契约与 DeepFM 完全一致——双输入 dense[N,5]+sparse[N,3]、输出 ctr[N,1]、共享编码器)。 */
    private String dcnModelPath = "classpath:model/model_dcn.onnx";
    private String dcnSchemaPath = "classpath:model/dcn_schema.json";
    private String dcnCategoryVocabPath = "classpath:model/dcn_category_vocab.json";

    /** MMoE 多目标模型 + 稀疏 schema/vocab(strategy=mmoe 时用,train_mmoe.py 产出)。 */
    private String mmoeModelPath = "classpath:model/model_mmoe.onnx";
    private String mmoeSchemaPath = "classpath:model/mmoe_schema.json";
    private String mmoeCategoryVocabPath = "classpath:model/mmoe_category_vocab.json";

    /** DIN 序列模型 + 稀疏 schema/vocab(strategy=din 时用,train_din.py 产出)。 */
    private String dinModelPath = "classpath:model/model_din.onnx";
    private String dinSchemaPath = "classpath:model/din_schema.json";
    private String dinCategoryVocabPath = "classpath:model/din_category_vocab.json";
    /**
     * DIN 在线行为序列走 Redis(R2):在线优先读 Redis ZSet {@code rt:user:seq:{id}},未命中回退 DB
     * 并 cache-aside 回填 —— 把"每请求直连 Postgres 查序列"降为"每用户每 TTL 至多一次 DB"。
     * 关掉则退回原直连 DB 行为。
     */
    private boolean dinSeqRedisEnabled = true;
    /** DIN 序列 Redis cache-aside 回填的 TTL(秒);到期重建,避免陈旧序列长期驻留。 */
    private long dinSeqTtlSeconds = 3600;

    /** PLE 多目标模型 + 稀疏 schema/vocab(strategy=ple 时用,train_ple.py 产出;与 MMoE 同在线契约)。 */
    private String pleModelPath = "classpath:model/model_ple.onnx";
    private String pleSchemaPath = "classpath:model/ple_schema.json";
    private String pleCategoryVocabPath = "classpath:model/ple_category_vocab.json";

    /** DIEN 兴趣演化模型 + 稀疏 schema/vocab(strategy=dien 时用,train_dien.py 产出;
     *  在线 4 输入/2 输出契约与 DIN 完全一致,共用 SequenceEncoder + R2 的 rt:user:seq)。 */
    private String dienModelPath = "classpath:model/model_dien.onnx";
    private String dienSchemaPath = "classpath:model/dien_schema.json";
    private String dienCategoryVocabPath = "classpath:model/dien_category_vocab.json";

    /** SIM 长序列模型 + 稀疏 schema/vocab(strategy=sim 时用,train_sim.py 产出;ESU 与 DIN 同架构/契约)。 */
    private String simModelPath = "classpath:model/model_sim.onnx";
    private String simSchemaPath = "classpath:model/sim_schema.json";
    private String simCategoryVocabPath = "classpath:model/sim_category_vocab.json";
    /** SIM 的 GSU 检索基:每请求拉用户近 longHistory 条正反馈(带类目)供类目硬检索;ESU 长度取自 schema seq_len。 */
    private int simLongHistory = 500;

    /** 多目标融合:finalScore = pCTR · (cvrBias + cvrWeight · pCVR)。mmoe/din 共用。 */
    private final MultiTask multiTask = new MultiTask();

    private final Weights weights = new Weights();

    /** 粗排:精排前的轻量打分 + 截断(补齐 召回→粗排→精排 漏斗)。 */
    private final PreRank preRank = new PreRank();

    public String getStrategy() {
        return strategy;
    }

    public void setStrategy(String strategy) {
        this.strategy = strategy;
    }

    public String getOnnxModelPath() {
        return onnxModelPath;
    }

    public void setOnnxModelPath(String onnxModelPath) {
        this.onnxModelPath = onnxModelPath;
    }

    public String getDeepfmModelPath() {
        return deepfmModelPath;
    }

    public void setDeepfmModelPath(String deepfmModelPath) {
        this.deepfmModelPath = deepfmModelPath;
    }

    public String getRankSchemaPath() {
        return rankSchemaPath;
    }

    public void setRankSchemaPath(String rankSchemaPath) {
        this.rankSchemaPath = rankSchemaPath;
    }

    public String getCategoryVocabPath() {
        return categoryVocabPath;
    }

    public void setCategoryVocabPath(String categoryVocabPath) {
        this.categoryVocabPath = categoryVocabPath;
    }

    public String getDcnModelPath() {
        return dcnModelPath;
    }

    public void setDcnModelPath(String dcnModelPath) {
        this.dcnModelPath = dcnModelPath;
    }

    public String getDcnSchemaPath() {
        return dcnSchemaPath;
    }

    public void setDcnSchemaPath(String dcnSchemaPath) {
        this.dcnSchemaPath = dcnSchemaPath;
    }

    public String getDcnCategoryVocabPath() {
        return dcnCategoryVocabPath;
    }

    public void setDcnCategoryVocabPath(String dcnCategoryVocabPath) {
        this.dcnCategoryVocabPath = dcnCategoryVocabPath;
    }

    public String getMmoeModelPath() {
        return mmoeModelPath;
    }

    public void setMmoeModelPath(String mmoeModelPath) {
        this.mmoeModelPath = mmoeModelPath;
    }

    public String getMmoeSchemaPath() {
        return mmoeSchemaPath;
    }

    public void setMmoeSchemaPath(String mmoeSchemaPath) {
        this.mmoeSchemaPath = mmoeSchemaPath;
    }

    public String getMmoeCategoryVocabPath() {
        return mmoeCategoryVocabPath;
    }

    public void setMmoeCategoryVocabPath(String mmoeCategoryVocabPath) {
        this.mmoeCategoryVocabPath = mmoeCategoryVocabPath;
    }

    public String getDinModelPath() {
        return dinModelPath;
    }

    public void setDinModelPath(String dinModelPath) {
        this.dinModelPath = dinModelPath;
    }

    public String getDinSchemaPath() {
        return dinSchemaPath;
    }

    public void setDinSchemaPath(String dinSchemaPath) {
        this.dinSchemaPath = dinSchemaPath;
    }

    public String getDinCategoryVocabPath() {
        return dinCategoryVocabPath;
    }

    public void setDinCategoryVocabPath(String dinCategoryVocabPath) {
        this.dinCategoryVocabPath = dinCategoryVocabPath;
    }

    public boolean isDinSeqRedisEnabled() {
        return dinSeqRedisEnabled;
    }

    public void setDinSeqRedisEnabled(boolean dinSeqRedisEnabled) {
        this.dinSeqRedisEnabled = dinSeqRedisEnabled;
    }

    public long getDinSeqTtlSeconds() {
        return dinSeqTtlSeconds;
    }

    public void setDinSeqTtlSeconds(long dinSeqTtlSeconds) {
        this.dinSeqTtlSeconds = dinSeqTtlSeconds;
    }

    public String getDienModelPath() {
        return dienModelPath;
    }

    public void setDienModelPath(String dienModelPath) {
        this.dienModelPath = dienModelPath;
    }

    public String getDienSchemaPath() {
        return dienSchemaPath;
    }

    public void setDienSchemaPath(String dienSchemaPath) {
        this.dienSchemaPath = dienSchemaPath;
    }

    public String getDienCategoryVocabPath() {
        return dienCategoryVocabPath;
    }

    public void setDienCategoryVocabPath(String dienCategoryVocabPath) {
        this.dienCategoryVocabPath = dienCategoryVocabPath;
    }

    public String getSimModelPath() {
        return simModelPath;
    }

    public void setSimModelPath(String simModelPath) {
        this.simModelPath = simModelPath;
    }

    public String getSimSchemaPath() {
        return simSchemaPath;
    }

    public void setSimSchemaPath(String simSchemaPath) {
        this.simSchemaPath = simSchemaPath;
    }

    public String getSimCategoryVocabPath() {
        return simCategoryVocabPath;
    }

    public void setSimCategoryVocabPath(String simCategoryVocabPath) {
        this.simCategoryVocabPath = simCategoryVocabPath;
    }

    public int getSimLongHistory() {
        return simLongHistory;
    }

    public void setSimLongHistory(int simLongHistory) {
        this.simLongHistory = simLongHistory;
    }

    public String getPleModelPath() {
        return pleModelPath;
    }

    public void setPleModelPath(String pleModelPath) {
        this.pleModelPath = pleModelPath;
    }

    public String getPleSchemaPath() {
        return pleSchemaPath;
    }

    public void setPleSchemaPath(String pleSchemaPath) {
        this.pleSchemaPath = pleSchemaPath;
    }

    public String getPleCategoryVocabPath() {
        return pleCategoryVocabPath;
    }

    public void setPleCategoryVocabPath(String pleCategoryVocabPath) {
        this.pleCategoryVocabPath = pleCategoryVocabPath;
    }

    public MultiTask getMultiTask() {
        return multiTask;
    }

    public Weights getWeights() {
        return weights;
    }

    public PreRank getPreRank() {
        return preRank;
    }

    /**
     * 粗排配置:候选数 > limit 时,用轻量线性打分砍到 top-limit 再进精排;≤ limit 直接放行。
     * 打分 = recallWeight·归一化召回分 + popWeight·item_pop_norm + affinityWeight·user_cat_affinity。
     */
    public static class PreRank {
        /** 是否启用粗排;false=候选全量进精排(旧行为)。 */
        private boolean enabled = true;
        /** 精排前保留的候选上限(粗排截断后的 top-K)。 */
        private int limit = 50;
        /** 归一化召回分权重(召回置信度)。 */
        private double recallWeight = 1.0;
        /** 物品热度权重。 */
        private double popWeight = 0.3;
        /** 用户-类目亲和度权重(交叉特征)。 */
        private double affinityWeight = 0.5;
        /**
         * 粗排模式(R6):linear=纯线性(旧行为);two-tower=在线性上叠加双塔学习分
         * (复用已训 user 塔 + item_tower_embedding,经 {@code TowerScorer})。双塔未就绪自动退线性。
         */
        private String mode = "linear";
        /** 双塔学习分权重(mode=two-tower 时生效;越大越以学习型双塔信号主导粗排)。 */
        private double towerWeight = 2.0;

        public boolean isEnabled() {
            return enabled;
        }

        public String getMode() {
            return mode;
        }

        public void setMode(String mode) {
            this.mode = mode;
        }

        public double getTowerWeight() {
            return towerWeight;
        }

        public void setTowerWeight(double towerWeight) {
            this.towerWeight = towerWeight;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getLimit() {
            return limit;
        }

        public void setLimit(int limit) {
            this.limit = limit;
        }

        public double getRecallWeight() {
            return recallWeight;
        }

        public void setRecallWeight(double recallWeight) {
            this.recallWeight = recallWeight;
        }

        public double getPopWeight() {
            return popWeight;
        }

        public void setPopWeight(double popWeight) {
            this.popWeight = popWeight;
        }

        public double getAffinityWeight() {
            return affinityWeight;
        }

        public void setAffinityWeight(double affinityWeight) {
            this.affinityWeight = affinityWeight;
        }
    }

    /** 多目标融合权重:finalScore = pCTR · (cvrBias + cvrWeight · pCVR)。 */
    public static class MultiTask {
        /** CVR 偏置:让纯高 CTR(低满意度)物品也能保留一定分,避免完全被 CVR 主导。 */
        private double cvrBias = 0.1;
        /** CVR 权重。cvrBias=0、cvrWeight=1 时退化为 ESMM 的 pCTCVR=pCTR·pCVR。 */
        private double cvrWeight = 1.0;

        public double getCvrBias() {
            return cvrBias;
        }

        public void setCvrBias(double cvrBias) {
            this.cvrBias = cvrBias;
        }

        public double getCvrWeight() {
            return cvrWeight;
        }

        public void setCvrWeight(double cvrWeight) {
            this.cvrWeight = cvrWeight;
        }
    }

    /** v1 规则打分权重。 */
    public static class Weights {
        private double recall = 1.0;
        private double popularity = 0.3;
        private double profileMatch = 0.5;

        public double getRecall() {
            return recall;
        }

        public void setRecall(double recall) {
            this.recall = recall;
        }

        public double getPopularity() {
            return popularity;
        }

        public void setPopularity(double popularity) {
            this.popularity = popularity;
        }

        public double getProfileMatch() {
            return profileMatch;
        }

        public void setProfileMatch(double profileMatch) {
            this.profileMatch = profileMatch;
        }
    }
}
