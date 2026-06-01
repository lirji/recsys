package com.recsys.recengine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recsys.common.constant.RedisKeys;
import com.recsys.common.dto.RecommendResponse;
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

    public RecommendResponse get(long userId, String scene) {
        try {
            String json = redis.opsForValue().get(key(userId, scene));
            if (json == null) {
                return null;
            }
            return mapper.readValue(json, RecommendResponse.class);
        } catch (Exception e) {
            log.debug("读结果缓存失败(忽略): {}", e.getMessage());
            return null;
        }
    }

    public void put(long userId, String scene, RecommendResponse resp) {
        try {
            String json = mapper.writeValueAsString(resp);
            redis.opsForValue().set(key(userId, scene), json,
                    Duration.ofSeconds(props.getCache().getRecTtlSeconds()));
        } catch (Exception e) {
            log.debug("写结果缓存失败(忽略): {}", e.getMessage());
        }
    }
}
