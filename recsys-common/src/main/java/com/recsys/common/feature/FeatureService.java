package com.recsys.common.feature;

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
}
