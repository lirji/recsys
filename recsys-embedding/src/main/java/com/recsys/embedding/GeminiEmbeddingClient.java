package com.recsys.embedding;

import com.recsys.common.constant.RedisKeys;
import com.recsys.common.embedding.EmbeddingClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * 默认向量化实现:调用 Gemini Embedding REST API。
 *
 * 关键点(见 docs/03 §2):
 * - gemini-embedding-001 默认 3072 维,通过 outputDimensionality 降到 768;
 *   降维后向量未归一化,这里统一做 L2 归一化再返回/入库。
 * - Redis 缓存(emb:cache:{sha256(text)})省 API 额度。
 * - 失败重试退避。
 *
 * 仅当 recsys.embedding.provider=gemini(默认)时装配。
 */
@Component
@ConditionalOnProperty(prefix = "recsys.embedding", name = "provider", havingValue = "gemini", matchIfMissing = true)
public class GeminiEmbeddingClient implements EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiEmbeddingClient.class);

    private final EmbeddingProperties props;
    private final RestClient restClient;
    private final StringRedisTemplate redis;

    public GeminiEmbeddingClient(EmbeddingProperties props,
                                 RestClient embeddingRestClient,
                                 StringRedisTemplate redis) {
        this.props = props;
        this.restClient = embeddingRestClient;
        this.redis = redis;
    }

    @Override
    public float[] embedText(String text) {
        if (text == null || text.isBlank()) {
            return new float[dimension()];
        }
        String cacheKey = RedisKeys.embCache(sha256(text));
        float[] cached = readCache(cacheKey);
        if (cached != null) {
            return cached;
        }
        float[] vec = callApiWithRetry(Map.of("parts", List.of(Map.of("text", text))));
        float[] normalized = VectorMath.l2Normalize(vec);
        writeCache(cacheKey, normalized);
        return normalized;
    }

    /**
     * 图片向量化(多模态):把图片以 inlineData(base64)喂 embedContent,同 {@link #dimension()} 维、L2 归一化。
     * 与文本走同一向量空间即可做「文本+图像」融合(见离线 backfill-multimodal)。缓存键前缀 img: 区分文本。
     * 注:需模型支持多模态 embedding;{@code gemini-embedding-001} 为纯文本,换多模态模型即生效,否则 API 报错、
     * 由上层作业优雅跳过(退回纯文本向量)。
     */
    @Override
    public float[] embedImage(byte[] image) {
        if (image == null || image.length == 0) {
            return new float[dimension()];
        }
        String cacheKey = RedisKeys.embCache("img:" + sha256(image));
        float[] cached = readCache(cacheKey);
        if (cached != null) {
            return cached;
        }
        String b64 = Base64.getEncoder().encodeToString(image);
        Map<String, Object> content = Map.of("parts", List.of(
                Map.of("inlineData", Map.of("mimeType", "image/jpeg", "data", b64))));
        float[] vec = callApiWithRetry(content);
        float[] normalized = VectorMath.l2Normalize(vec);
        writeCache(cacheKey, normalized);
        return normalized;
    }

    @Override
    public int dimension() {
        return props.getGemini().getOutputDimensionality();
    }

    @Override
    public String modelName() {
        return props.getGemini().getModel();
    }

    // ---- 内部 ----

    @SuppressWarnings("unchecked")
    private float[] callApiWithRetry(Map<String, Object> content) {
        var g = props.getGemini();
        String url = g.getBaseUrl() + "/models/" + g.getModel() + ":embedContent?key=" + g.getApiKey();
        Map<String, Object> body = Map.of(
                "model", "models/" + g.getModel(),
                "content", content,
                "outputDimensionality", g.getOutputDimensionality()
        );

        RuntimeException last = null;
        for (int attempt = 1; attempt <= g.getMaxRetries(); attempt++) {
            try {
                Map<String, Object> resp = restClient.post()
                        .uri(url)
                        .header("Content-Type", "application/json")
                        .body(body)
                        .retrieve()
                        .body(Map.class);
                if (resp == null || !resp.containsKey("embedding")) {
                    throw new IllegalStateException("Gemini 响应缺少 embedding: " + resp);
                }
                Map<String, Object> emb = (Map<String, Object>) resp.get("embedding");
                List<Number> values = (List<Number>) emb.get("values");
                float[] out = new float[values.size()];
                for (int i = 0; i < out.length; i++) {
                    out[i] = values.get(i).floatValue();
                }
                return out;
            } catch (HttpClientErrorException.TooManyRequests e) {
                // 配额耗尽:重试无意义,直接上抛由批量作业优雅停止
                throw new QuotaExhaustedException("Gemini 配额耗尽(429): " + e.getMessage());
            } catch (RuntimeException e) {
                last = e;
                log.warn("Gemini embedding 调用失败(第 {}/{} 次): {}", attempt, g.getMaxRetries(), e.getMessage());
                sleepBackoff(attempt);
            }
        }
        throw new IllegalStateException("Gemini embedding 重试 " + g.getMaxRetries() + " 次仍失败", last);
    }

    private void sleepBackoff(int attempt) {
        try {
            Thread.sleep(200L * attempt);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private float[] readCache(String key) {
        try {
            String s = redis.opsForValue().get(key);
            if (s == null || s.isEmpty()) {
                return null;
            }
            String[] parts = s.split(",");
            float[] v = new float[parts.length];
            for (int i = 0; i < parts.length; i++) {
                v[i] = Float.parseFloat(parts[i]);
            }
            return v;
        } catch (RuntimeException e) {
            log.debug("读向量缓存失败(忽略): {}", e.getMessage());
            return null;
        }
    }

    private void writeCache(String key, float[] v) {
        try {
            StringBuilder sb = new StringBuilder(v.length * 8);
            for (int i = 0; i < v.length; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(v[i]);
            }
            long ttl = props.getGemini().getCacheTtlSeconds();
            if (ttl > 0) {
                redis.opsForValue().set(key, sb.toString(), Duration.ofSeconds(ttl));
            } else {
                redis.opsForValue().set(key, sb.toString());
            }
        } catch (RuntimeException e) {
            log.debug("写向量缓存失败(忽略): {}", e.getMessage());
        }
    }

    private static String sha256(String text) {
        return sha256(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
