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

    private final Local local = new Local();

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

    public Local getLocal() {
        return local;
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

    /**
     * 本地 BGE(ONNX,CPU)向量化配置。仅 provider=local 时生效。
     *
     * <p>模型与词表由 {@code recsys-embedding/train/export_bge_onnx.py} 离线导出,
     * 体积较大(bge-base ≈400MB)故不入 git——放在文件系统路径(默认 ~/.recsys/models/...),
     * 仅 vocab.txt 也随之导出。运行前先执行该脚本。
     */
    public static class Local {
        /** 默认模型目录:导出脚本的默认输出位置。 */
        private static final String DEFAULT_DIR =
                System.getProperty("user.home") + "/.recsys/models/bge-base-en-v1.5";

        /** ONNX 模型路径(输出 last_hidden_state[B,L,H])。 */
        private String modelPath = DEFAULT_DIR + "/model.onnx";
        /** BERT WordPiece 词表 vocab.txt 路径。 */
        private String vocabPath = DEFAULT_DIR + "/vocab.txt";
        /** 最大序列长度(含 [CLS]/[SEP]),超出截断。电影短文本 256 足够。 */
        private int maxSeqLen = 256;
        /** 池化方式:cls(BGE 默认,取 [CLS])或 mean(attention_mask 加权平均)。 */
        private String pooling = "cls";
        /** 是否小写(bge-*-en-v1.5 为 uncased)。 */
        private boolean lowercase = true;
        /**
         * 检索查询指令前缀(BGE s2p 非对称检索建议给 query 加;item 侧不加)。
         * 默认空——item/query 同空间对称比较,短文本足够;需要时可设
         * "Represent this sentence for searching relevant passages: "。
         */
        private String queryInstruction = "";

        public String getModelPath() {
            return modelPath;
        }

        public void setModelPath(String modelPath) {
            this.modelPath = modelPath;
        }

        public String getVocabPath() {
            return vocabPath;
        }

        public void setVocabPath(String vocabPath) {
            this.vocabPath = vocabPath;
        }

        public int getMaxSeqLen() {
            return maxSeqLen;
        }

        public void setMaxSeqLen(int maxSeqLen) {
            this.maxSeqLen = maxSeqLen;
        }

        public String getPooling() {
            return pooling;
        }

        public void setPooling(String pooling) {
            this.pooling = pooling;
        }

        public boolean isLowercase() {
            return lowercase;
        }

        public void setLowercase(boolean lowercase) {
            this.lowercase = lowercase;
        }

        public String getQueryInstruction() {
            return queryInstruction;
        }

        public void setQueryInstruction(String queryInstruction) {
            this.queryInstruction = queryInstruction;
        }
    }
}
