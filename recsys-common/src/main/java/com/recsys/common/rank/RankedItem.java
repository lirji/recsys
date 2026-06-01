package com.recsys.common.rank;

import java.util.Map;

/**
 * 排序后的单条结果。
 *
 * @param itemId          物品 ID
 * @param score           排序分(如 CTR 预估值)
 * @param featureSnapshot 本次打分使用的特征快照,用于调试与训练样本回流
 *                        (在线/离线特征一致性校验的依据)
 */
public record RankedItem(long itemId, double score, Map<String, Double> featureSnapshot) {
}
