package com.recsys.common.recall;

/**
 * 单条召回结果。
 *
 * @param itemId      物品 ID
 * @param recallScore 召回分(各路量纲不同,合并时不可直接相加,交由排序层统一打分)
 * @param channel     来自哪一路召回
 */
public record RecallItem(long itemId, double recallScore, RecallChannel channel) {
}
