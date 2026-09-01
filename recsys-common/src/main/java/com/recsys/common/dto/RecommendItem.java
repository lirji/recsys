package com.recsys.common.dto;

import java.util.List;

/**
 * 单条推荐结果(对外返回)。
 *
 * @param itemId     物品 ID
 * @param score      最终排序分(精排/重排后的分数)
 * @param recallFrom 命中的召回路列表(如 ["vector","i2i"]),用于解释与调试
 * @param reason     可读的推荐理由(如 "和你看过的X相似"),可为空
 * @param exposureId 本次实际交付的曝光 ID；每次响应重新生成，点击上报时原样带回
 */
public record RecommendItem(
        long itemId,
        double score,
        List<String> recallFrom,
        String reason,
        String exposureId) {

    /** 兼容排序/重排内部旧构造；曝光 ID 由编排交付阶段补齐。 */
    public RecommendItem(long itemId, double score, List<String> recallFrom, String reason) {
        this(itemId, score, recallFrom, reason, null);
    }

    /** 返回内容相同、仅交付身份不同的新对象。 */
    public RecommendItem withExposureId(String id) {
        return new RecommendItem(itemId, score, recallFrom, reason, id);
    }
}
