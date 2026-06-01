package com.recsys.embedding;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 向量化配置。对应 application.yml 的 recsys.embedding.*
 */
@ConfigurationProperties(prefix = "recsys.embedding")
public class EmbeddingProperties {

    /** 统一向量维度(全库一致)。 */
    private int dimension = 768;

    /** 提供方:gemini(默认)/ local。 */
    private String provider = "gemini";

    private final Gemini gemini = new Gemini();

    public int getDimension() {
        return dimension;
    }

    public void setDimension(int dimension) {
        this.dimension = dimension;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public Gemini getGemini() {
        return gemini;
    }

    public static class Gemini {
        /** API Key(来自环境变量 GEMINI_API_KEY)。 */
        private String apiKey = "";
        /** 模型名。 */
        private String model = "gemini-embedding-001";
        /** 输出维度(gemini-embedding-001 默认 3072,降到 768 需 L2 归一化)。 */
        private int outputDimensionality = 768;
        /** REST 基址。 */
        private String baseUrl = "https://generativelanguage.googleapis.com/v1beta";
        /** 失败重试次数。 */
        private int maxRetries = 3;
        /** 缓存 TTL(秒);<=0 表示不过期。 */
        private long cacheTtlSeconds = 0;

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public int getOutputDimensionality() {
            return outputDimensionality;
        }

        public void setOutputDimensionality(int outputDimensionality) {
            this.outputDimensionality = outputDimensionality;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public int getMaxRetries() {
            return maxRetries;
        }

        public void setMaxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
        }

        public long getCacheTtlSeconds() {
            return cacheTtlSeconds;
        }

        public void setCacheTtlSeconds(long cacheTtlSeconds) {
            this.cacheTtlSeconds = cacheTtlSeconds;
        }
    }
}
