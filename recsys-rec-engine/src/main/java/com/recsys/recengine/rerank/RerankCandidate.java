package com.recsys.recengine.rerank;

/**
 * 进入重排的候选:itemId + 融合后(召回分+排序分)的相关性分,已按 score 降序传入。
 */
public record RerankCandidate(long itemId, double score) {
}
