package com.recsys.common.ad;

import java.util.List;

/**
 * 混排信息流的一条:自然推荐结果或赞助广告(docs/05 §4.8)。前端按 {@code ad} 渲染"赞助"标识。
 *
 * @param ad         是否广告(true=赞助广告,false=自然推荐)
 * @param itemId     物品 ID(广告为其关联创意 item)
 * @param adId       广告 ID(自然结果为 0)
 * @param position   在信息流中的最终位次(1 基)
 * @param score      分数(自然=排序分;广告=eCPM,仅供调试)
 * @param reason     可读理由(广告为"赞助")
 * @param recallFrom 来源标记(广告为 ["AD"])
 */
public record FeedEntry(boolean ad,
                        long itemId,
                        long adId,
                        int position,
                        double score,
                        String reason,
                        List<String> recallFrom) {
}
