package com.recsys.rank;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 排序配置。strategy=v1 走规则打分,onnx 走 LightGBM 模型,deepfm 走 DeepFM 深度模型,
 * mmoe 走 MMoE+ESMM 多目标模型,din 走 DIN 序列建模(多目标头)。
 */
@ConfigurationProperties(prefix = "recsys.rank")
public class RankProperties {

    /** v1=规则打分;onnx=LightGBM;deepfm=DeepFM;mmoe=MMoE多目标;din=DIN序列多目标。 */
    private String strategy = "v1";

    /** ONNX 模型路径(strategy=onnx 时用)。 */
    private String onnxModelPath = "classpath:model/model.onnx";

    /** DeepFM 模型路径 + 稀疏编码 schema/vocab(strategy=deepfm 时用,均为训练侧产出)。 */
    private String deepfmModelPath = "classpath:model/model_deepfm.onnx";
    private String rankSchemaPath = "classpath:model/rank_schema.json";
    private String categoryVocabPath = "classpath:model/category_vocab.json";

    /** MMoE 多目标模型 + 稀疏 schema/vocab(strategy=mmoe 时用,train_mmoe.py 产出)。 */
    private String mmoeModelPath = "classpath:model/model_mmoe.onnx";
    private String mmoeSchemaPath = "classpath:model/mmoe_schema.json";
    private String mmoeCategoryVocabPath = "classpath:model/mmoe_category_vocab.json";

    /** DIN 序列模型 + 稀疏 schema/vocab(strategy=din 时用,train_din.py 产出)。 */
    private String dinModelPath = "classpath:model/model_din.onnx";
    private String dinSchemaPath = "classpath:model/din_schema.json";
    private String dinCategoryVocabPath = "classpath:model/din_category_vocab.json";

    /** 多目标融合:finalScore = pCTR · (cvrBias + cvrWeight · pCVR)。mmoe/din 共用。 */
    private final MultiTask multiTask = new MultiTask();

    private final Weights weights = new Weights();

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

    public MultiTask getMultiTask() {
        return multiTask;
    }

    public Weights getWeights() {
        return weights;
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
