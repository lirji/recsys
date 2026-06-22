package com.recsys.embedding;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 生成式 LLM 配置(recsys.llm.*)。当前实现为 Gemini generateContent REST。
 *
 * <p>默认 {@code enabled=false} + 空 key ⇒ {@code GeminiChatClient.isReady()=false},
 * 消费方(Query 理解层)跳过 LLM 走纯词法兜底,行为与接入前完全一致。
 */
@ConfigurationProperties(prefix = "recsys.llm")
public class LlmProperties {

    /** 总开关。关掉则不装配/不就绪,省一次外呼。 */
    private boolean enabled = false;

    /** 提供方(目前仅 gemini)。 */
    private String provider = "gemini";

    /** API Key(来自环境变量 GEMINI_API_KEY,与 embedding 共用)。 */
    private String apiKey = "";

    /** 模型名(轻量、便宜、低延迟的指令模型即可)。 */
    private String model = "gemini-2.0-flash";

    /** REST 基址。 */
    private String baseUrl = "https://generativelanguage.googleapis.com/v1beta";

    /** 采样温度(query 理解要稳定 → 低温)。 */
    private double temperature = 0.1;

    /** 单次生成最大 token。 */
    private int maxOutputTokens = 512;

    /** 失败重试次数。 */
    private int maxRetries = 2;

    /** 结果缓存 TTL(秒);<=0 表示不过期。query 理解结果可长缓存。 */
    private long cacheTtlSeconds = 86400;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

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

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public double getTemperature() {
        return temperature;
    }

    public void setTemperature(double temperature) {
        this.temperature = temperature;
    }

    public int getMaxOutputTokens() {
        return maxOutputTokens;
    }

    public void setMaxOutputTokens(int maxOutputTokens) {
        this.maxOutputTokens = maxOutputTokens;
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
