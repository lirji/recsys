package com.recsys.rank;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 排序配置。strategy=v1 走规则打分,onnx 走 LightGBM 模型,deepfm 走 DeepFM 深度模型。
 */
@ConfigurationProperties(prefix = "recsys.rank")
public class RankProperties {

    /** v1=规则打分;onnx=LightGBM 模型;deepfm=DeepFM 深度模型。 */
    private String strategy = "v1";

    /** ONNX 模型路径(strategy=onnx 时用)。 */
    private String onnxModelPath = "classpath:model/model.onnx";

    /** DeepFM 模型路径 + 稀疏编码 schema/vocab(strategy=deepfm 时用,均为训练侧产出)。 */
    private String deepfmModelPath = "classpath:model/model_deepfm.onnx";
    private String rankSchemaPath = "classpath:model/rank_schema.json";
    private String categoryVocabPath = "classpath:model/category_vocab.json";

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

    public Weights getWeights() {
        return weights;
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
