package com.recsys.embedding;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * 向量化模块装配。RestClient 供 GeminiEmbeddingClient 调用 REST API。
 */
@Configuration
@EnableConfigurationProperties({EmbeddingProperties.class, LlmProperties.class})
public class EmbeddingConfig {

    /**
     * 在线路径调用外部 API 必须设超时:Gemini 挂起会无限阻塞搜索/query 主链路
     * (embedding→pgvector 是搜索主链路),降级 try/catch 只兜"失败"、兜不住"慢"。
     * connect 2s / read 3s,超时即抛异常 → 由调用方 catch 后降级本地 BGE。
     */
    private static final ClientHttpRequestFactorySettings HTTP_TIMEOUTS =
            ClientHttpRequestFactorySettings.DEFAULTS
                    .withConnectTimeout(Duration.ofSeconds(2))
                    .withReadTimeout(Duration.ofSeconds(3));

    @Bean
    public RestClient embeddingRestClient() {
        return RestClient.builder()
                .requestFactory(ClientHttpRequestFactories.get(HTTP_TIMEOUTS))
                .build();
    }

    /** 供 GeminiChatClient 调用 generateContent REST。 */
    @Bean
    public RestClient llmRestClient() {
        return RestClient.builder()
                .requestFactory(ClientHttpRequestFactories.get(HTTP_TIMEOUTS))
                .build();
    }
}
