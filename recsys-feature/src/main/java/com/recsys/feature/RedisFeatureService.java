package com.recsys.feature;

import com.recsys.common.constant.RedisKeys;
import com.recsys.common.feature.FeatureService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
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

    /**
     * 批量读物品特征:一次 Redis pipeline 把所有候选的 feat hash 拉回,消除排序候选循环里逐个 HGETALL
     * 的 N 次往返(N+1)。任一异常退回逐个读(不影响正确性,只是慢)。
     */
    @Override
    public Map<Long, Map<String, Double>> itemFeatures(Collection<Long> itemIds) {
        Map<Long, Map<String, Double>> out = new HashMap<>();
        if (itemIds == null || itemIds.isEmpty()) {
            return out;
        }
        // 去重保序,pipeline 结果按此顺序对齐回填
        List<Long> ids = new ArrayList<>(new LinkedHashSet<>(itemIds));
        List<Object> raw;
        try {
            raw = redis.executePipelined((RedisCallback<Object>) connection -> {
                for (Long id : ids) {
                    connection.hGetAll(RedisKeys.itemFeature(id).getBytes(StandardCharsets.UTF_8));
                }
                return null;   // pipeline 回调必须返回 null
            });
        } catch (Exception e) {
            log.debug("批量读特征失败,回退逐个读: {}", e.getMessage());
            for (Long id : ids) {
                out.put(id, readNumericHash(RedisKeys.itemFeature(id)));
            }
            return out;
        }
        for (int i = 0; i < ids.size(); i++) {
            Map<String, Double> feats = new HashMap<>();
            Object r = i < raw.size() ? raw.get(i) : null;
            if (r instanceof Map<?, ?> m) {
                for (var e : m.entrySet()) {
                    try {
                        feats.put(String.valueOf(e.getKey()), Double.parseDouble(String.valueOf(e.getValue())));
                    } catch (NumberFormatException ignore) {
                        // 跳过非数值特征
                    }
                }
            }
            out.put(ids.get(i), feats);
        }
        return out;
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
