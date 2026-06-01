package com.recsys.embedding;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * 向量化模块装配。RestClient 供 GeminiEmbeddingClient 调用 REST API。
 */
@Configuration
@EnableConfigurationProperties(EmbeddingProperties.class)
public class EmbeddingConfig {

    @Bean
    public RestClient embeddingRestClient() {
        return RestClient.builder().build();
    }
}
