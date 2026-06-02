package com.recsys.common.query;

/**
 * 一个查询词项及其权重。
 *
 * <p>MVP 阶段权重统一为 1.0(去停用词后等权);后续可接 IDF / term importance 模型,
 * 让稀有词权重更高,作为召回与相关性打分的输入。
 *
 * @param term   归一化后的词(小写、去标点)
 * @param weight 词项权重(MVP 恒为 1.0)
 */
public record TermWeight(String term, double weight) {
}
