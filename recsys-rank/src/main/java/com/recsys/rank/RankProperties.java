package com.recsys.rank;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 排序配置。strategy=v1 走规则打分,onnx 走模型(预留)。
 */
@ConfigurationProperties(prefix = "recsys.rank")
public class RankProperties {

    /** v1=规则打分;onnx=模型打分(预留)。 */
    private String strategy = "v1";

    /** ONNX 模型路径(strategy=onnx 时用)。 */
    private String onnxModelPath = "classpath:model/model.onnx";

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
