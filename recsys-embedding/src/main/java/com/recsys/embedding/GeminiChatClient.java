package com.recsys.embedding;

import com.recsys.common.constant.RedisKeys;
import com.recsys.common.llm.LlmClient;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * 生成式 LLM 实现:调用 Gemini {@code generateContent} REST API(单轮)。
 *
 * <p>与 {@link GeminiEmbeddingClient} 同源:复用 Gemini 基址 / key / RestClient 模式 +
 * Redis 缓存(llm:cache:{sha256(system|user)})省额度。仅当 {@code recsys.llm.enabled=true}
 * 时装配;{@link #isReady()} 还要求非空 key,否则消费方走纯词法兜底。
 *
 * <p>结果以纯文本返回;调用方(Query 理解层)在提示里约束"只返回 JSON"并自行解析。
 */
@Component
@ConditionalOnProperty(prefix = "recsys.llm", name = "enabled", havingValue = "true")
public class GeminiChatClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiChatClient.class);

    private final LlmProperties props;
    private final RestClient restClient;
    private final StringRedisTemplate redis;

    public GeminiChatClient(LlmProperties props,
                            RestClient llmRestClient,
                            StringRedisTemplate redis) {
        this.props = props;
        this.restClient = llmRestClient;
        this.redis = redis;
    }

    @Override
    public boolean isReady() {
        return props.isEnabled() && props.getApiKey() != null && !props.getApiKey().isBlank();
    }

    @Override
    public String modelName() {
        return props.getModel();
    }

    // 弹性护栏:generateContent 是在线 query 理解链路最前端的同步外呼,Gemini 慢/挂时即便有 read 超时
    // (llmRestClient 3s)也会每请求苦等 + 重试退避。熔断达阈值即快速失败(抛 CallNotPermittedException)——
    // 由 QueryUnderstandingServiceImpl.enrichWithLlm 的 catch 降级纯词法,半开态自动探活。范式同 gemini-embedding。
    @Override
    @CircuitBreaker(name = "gemini-llm")
    public String complete(String systemPrompt, String userPrompt) {
        if (!isReady()) {
            throw new IllegalStateException("LLM 未就绪(未配置 GEMINI_API_KEY 或 recsys.llm.enabled=false)");
        }
        if (userPrompt == null || userPrompt.isBlank()) {
            return "";
        }
        String cacheKey = RedisKeys.llmCache(sha256(props.getModel() + "|" + systemPrompt + "|" + userPrompt));
        String cached = readCache(cacheKey);
        if (cached != null) {
            return cached;
        }
        String out = callApiWithRetry(systemPrompt, userPrompt);
        writeCache(cacheKey, out);
        return out;
    }

    // ---- 内部 ----

    @SuppressWarnings("unchecked")
    private String callApiWithRetry(String systemPrompt, String userPrompt) {
        String url = props.getBaseUrl() + "/models/" + props.getModel()
                + ":generateContent?key=" + props.getApiKey();

        List<Map<String, Object>> contents = List.of(
                Map.of("role", "user", "parts", List.of(Map.of("text", userPrompt))));
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("contents", contents);
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            body.put("system_instruction", Map.of("parts", List.of(Map.of("text", systemPrompt))));
        }
        body.put("generationConfig", Map.of(
                "temperature", props.getTemperature(),
                "maxOutputTokens", props.getMaxOutputTokens(),
                // 强制 JSON 输出,免去 markdown 围栏解析
                "responseMimeType", "application/json"));

        RuntimeException last = null;
        for (int attempt = 1; attempt <= props.getMaxRetries(); attempt++) {
            try {
                Map<String, Object> resp = restClient.post()
                        .uri(url)
                        .header("Content-Type", "application/json")
                        .body(body)
                        .retrieve()
                        .body(Map.class);
                String text = extractText(resp);
                if (text == null || text.isBlank()) {
                    throw new IllegalStateException("Gemini generateContent 响应为空: " + resp);
                }
                return text;
            } catch (RuntimeException e) {
                last = e;
                log.warn("Gemini generateContent 调用失败(第 {}/{} 次): {}", attempt, props.getMaxRetries(), e.getMessage());
                sleepBackoff(attempt);
            }
        }
        throw new IllegalStateException("Gemini generateContent 重试 " + props.getMaxRetries() + " 次仍失败", last);
    }

    @SuppressWarnings("unchecked")
    private String extractText(Map<String, Object> resp) {
        if (resp == null) {
            return null;
        }
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) resp.get("candidates");
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        Map<String, Object> content = (Map<String, Object>) candidates.get(0).get("content");
        if (content == null) {
            return null;
        }
        List<Map<String, Object>> parts = (List<Map<String, Object>>) content.get("parts");
        if (parts == null || parts.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> part : parts) {
            Object t = part.get("text");
            if (t != null) {
                sb.append(t);
            }
        }
        return sb.toString();
    }

    private void sleepBackoff(int attempt) {
        try {
            Thread.sleep(200L * attempt);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private String readCache(String key) {
        try {
            return redis.opsForValue().get(key);
        } catch (RuntimeException e) {
            log.debug("读 LLM 缓存失败(忽略): {}", e.getMessage());
            return null;
        }
    }

    private void writeCache(String key, String value) {
        try {
            long ttl = props.getCacheTtlSeconds();
            if (ttl > 0) {
                redis.opsForValue().set(key, value, Duration.ofSeconds(ttl));
            } else {
                redis.opsForValue().set(key, value);
            }
        } catch (RuntimeException e) {
            log.debug("写 LLM 缓存失败(忽略): {}", e.getMessage());
        }
    }

    private static String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
