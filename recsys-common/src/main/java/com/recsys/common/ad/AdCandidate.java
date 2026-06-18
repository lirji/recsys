package com.recsys.common.ad;

/**
 * 广告召回候选:广告召回层 {@link AdRecallService#recall} 的产出,排序/竞价层的输入。
 *
 * @param adId         广告 ID
 * @param itemId       关联物品 ID(复用其 embedding/特征/类目做 pCTR 预估)
 * @param advertiserId 广告主 ID(预算 pacing / 计费归因)
 * @param bidwordId    命中的竞价词 ID(GSP 计费需回溯到具体词;语义/兜底路为 0)
 * @param recallScore  召回分(KW 路=出价,SEMANTIC 路=余弦相似度;仅用于召回内排序,不进竞价)
 * @param channel      主召回路
 * @param bid          出价(元,来自命中竞价词;HOT/SEMANTIC 兜底取广告默认出价)
 * @param quality      质量度(来自 ad.quality_score)
 */
public record AdCandidate(long adId,
                          long itemId,
                          long advertiserId,
                          long bidwordId,
                          double recallScore,
                          AdChannel channel,
                          double bid,
                          double quality) {
}
