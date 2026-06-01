package com.recsys.feature;

import com.recsys.common.constant.RedisKeys;
import com.recsys.common.feature.FeatureService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * 基于 Redis Hash 的在线特征服务。
 * 离线作业把特征写入 feat:user:{id} / feat:item:{id},在线直接读。
 * 无数据时返回空 map(排序层据此降级)。
 *
 * 注意(docs/03 §6):离线写特征必须复用相同的特征名,保证在线/离线一致。
 */
@Service
public class RedisFeatureService implements FeatureService {

    private static final Logger log = LoggerFactory.getLogger(RedisFeatureService.class);

    private final StringRedisTemplate redis;

    public RedisFeatureService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public Map<String, Double> userFeatures(long userId) {
        return readNumericHash(RedisKeys.userFeature(userId));
    }

    @Override
    public Map<String, Double> itemFeatures(long itemId) {
        return readNumericHash(RedisKeys.itemFeature(itemId));
    }

    /** 写入特征(供离线作业复用,保证 key/字段一致)。 */
    public void writeUserFeatures(long userId, Map<String, Double> features) {
        writeNumericHash(RedisKeys.userFeature(userId), features);
    }

    public void writeItemFeatures(long itemId, Map<String, Double> features) {
        writeNumericHash(RedisKeys.itemFeature(itemId), features);
    }

    private Map<String, Double> readNumericHash(String key) {
        Map<String, Double> out = new HashMap<>();
        try {
            Map<Object, Object> raw = redis.opsForHash().entries(key);
            for (var e : raw.entrySet()) {
                try {
                    out.put(String.valueOf(e.getKey()), Double.parseDouble(String.valueOf(e.getValue())));
                } catch (NumberFormatException ignore) {
                    // 跳过非数值特征
                }
            }
        } catch (Exception e) {
            log.debug("读特征失败 {}: {}", key, e.getMessage());
        }
        return out;
    }

    private void writeNumericHash(String key, Map<String, Double> features) {
        if (features == null || features.isEmpty()) {
            return;
        }
        Map<String, String> str = new HashMap<>();
        features.forEach((k, v) -> str.put(k, String.valueOf(v)));
        redis.opsForHash().putAll(key, str);
    }
}
