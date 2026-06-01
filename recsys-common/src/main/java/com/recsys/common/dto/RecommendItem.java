package com.recsys.common.dto;

import java.util.List;

/**
 * 单条推荐结果(对外返回)。
 *
 * @param itemId     物品 ID
 * @param score      最终排序分(精排/重排后的分数)
 * @param recallFrom 命中的召回路列表(如 ["vector","i2i"]),用于解释与调试
 * @param reason     可读的推荐理由(如 "和你看过的X相似"),可为空
 */
public record RecommendItem(
        long itemId,
        double score,
        List<String> recallFrom,
        String reason) {
}
