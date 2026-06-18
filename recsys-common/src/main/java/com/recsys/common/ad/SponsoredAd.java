package com.recsys.common.ad;

/**
 * 一条竞得展示位的广告:竞价排序 + GSP 拍卖后的最终产出,返回前端 + 写 ad_event 审计。
 *
 * <p>排序与计费分离(docs/05 §9.2):{@code ecpm} 决定"谁出现 + 排在哪",
 * {@code chargedPrice} 决定"收多少钱"(GSP 次高价)。{@code pctrCalibrated} 是进计费的
 * 校准后概率——未校准概率不得进计费(docs/05 §9.3)。
 *
 * @param adId           广告 ID
 * @param itemId         关联物品 ID
 * @param advertiserId   广告主 ID
 * @param bidwordId      命中竞价词 ID(计费回溯)
 * @param title          创意标题
 * @param channel        主召回路
 * @param bid            出价(元)
 * @param quality        质量度
 * @param relevance      query↔ad 相关性(过了 relevance-gate)
 * @param pctr           原始预估 CTR(排序模型输出)
 * @param pctrCalibrated 校准后 CTR(进 eCPM 与计费)
 * @param ecpm           eCPM = bid × pctrCalibrated × quality
 * @param chargedPrice   GSP 实际扣费(次高价;< reserve 时为 reserve)
 * @param position       广告位次(1 基)
 */
public record SponsoredAd(long adId,
                          long itemId,
                          long advertiserId,
                          long bidwordId,
                          String title,
                          AdChannel channel,
                          double bid,
                          double quality,
                          double relevance,
                          double pctr,
                          double pctrCalibrated,
                          double ecpm,
                          double chargedPrice,
                          int position) {
}
