package com.recsys.common.query;

/**
 * 一个查询词项及其权重。
 *
 * <p>权重由 query 侧的 IDF 查表提供；未启用、缺表或 OOV 时为中性值 1.0。
 * 稀有词的较高权重会透传给词法召回与意图相关性打分。
 *
 * @param term   归一化后的词(小写、去标点)
 * @param weight 词项权重，1.0 为中性值
 */
public record TermWeight(String term, double weight) {
}
