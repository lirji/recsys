package com.recsys.common.feature;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * 特征服务契约(Track C 实现)。
 * 提供在线特征读取;交叉特征在 rank 内部由 user×item 组合。
 * 离线特征写入工具同样应复用此处的特征命名,保证在线/离线一致。
 */
public interface FeatureService {

    /** 用户在线特征(读 Redis feat:user:{userId})。 */
    Map<String, Double> userFeatures(long userId);

    /** 物品在线特征(读 Redis feat:item:{itemId})。 */
    Map<String, Double> itemFeatures(long itemId);

    /**
     * 批量物品特征(itemId → 特征 map)。排序在候选循环里逐个取特征会产生 N 次 Redis 往返(N+1),
     * 用一次批量读取代替。默认实现逐个调用以兼容旧实现;Redis 实现应覆盖为单次 pipeline 批量读。
     */
    default Map<Long, Map<String, Double>> itemFeatures(Collection<Long> itemIds) {
        Map<Long, Map<String, Double>> out = new HashMap<>();
        if (itemIds == null) {
            return out;
        }
        for (Long id : itemIds) {
            if (id != null) {
                out.put(id, itemFeatures(id));
            }
        }
        return out;
    }
}
