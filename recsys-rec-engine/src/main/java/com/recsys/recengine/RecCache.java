package com.recsys.recengine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recsys.common.constant.RedisKeys;
import com.recsys.common.dto.RecommendResponse;
import com.recsys.common.dto.RecommendItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 推荐结果缓存(cache:rec:{userId},按 scene 区分)。短 TTL,失败不影响主流程。
 */
@Component
public class RecCache {

    private static final Logger log = LoggerFactory.getLogger(RecCache.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final RecEngineProperties props;

    public RecCache(StringRedisTemplate redis, ObjectMapper mapper, RecEngineProperties props) {
        this.redis = redis;
        this.mapper = mapper;
        this.props = props;
    }

    private String key(long userId, String scene) {
        return RedisKeys.recCache(userId) + ":" + scene;
    }

    public CacheEntry get(long userId, String scene) {
        try {
            String json = redis.opsForValue().get(key(userId, scene));
            if (json == null) {
                return null;
            }
            return mapper.readValue(json, CacheEntry.class);
        } catch (Exception e) {
            log.debug("读结果缓存失败(忽略): {}", e.getMessage());
            return null;
        }
    }

    public void put(long userId, String scene, RecommendResponse resp, String bucketTag) {
        try {
            // 缓存只保存候选内容，不保存一次性交付身份；每次命中必须重新生成 exposureId 并记曝光。
            var cachedItems = resp.items().stream()
                    .map(i -> new RecommendItem(i.itemId(), i.score(), i.recallFrom(), i.reason()))
                    .toList();
            RecommendResponse cached = new RecommendResponse(
                    resp.userId(), resp.scene(), cachedItems, "cached", resp.explain());
            String json = mapper.writeValueAsString(new CacheEntry(cached, bucketTag));
            redis.opsForValue().set(key(userId, scene), json,
                    Duration.ofSeconds(props.getCache().getRecTtlSeconds()));
        } catch (Exception e) {
            log.debug("写结果缓存失败(忽略): {}", e.getMessage());
        }
    }

    /** 缓存内部契约；bucketTag 不暴露到外部 RecommendResponse。 */
    public record CacheEntry(RecommendResponse response, String bucketTag) {
    }
}
