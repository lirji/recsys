package com.recsys.common.query;

/**
 * 查询的一个意图类目及其置信分。
 *
 * <p>类目落在现有 {@code item.category}(MovieLens 的 19 个 genre)体系上,
 * 由 {@link QueryUnderstandingService} 通过「genre 名直接命中 + 标题投票」算出,
 * 分数已归一化([0,1],同一查询下各意图分之和不强制为 1)。
 *
 * @param category 类目名(与 {@code item.category} 同口径)
 * @param score    置信分,降序排列、过阈值后保留
 */
public record CategoryScore(String category, double score) {
}
