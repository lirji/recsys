package com.recsys.common.ad;

import java.util.List;

/**
 * 混排信息流响应:{@code GET /api/feed} 的返回 —— 自然推荐 + 赞助广告按 Ad Load 规则混排(docs/05 §4.8)。
 *
 * @param userId    用户 ID
 * @param query     原始查询(可空,纯推荐场景)
 * @param requestId 广告计费归因 ID(点击/转化回传带回;无广告时为空)
 * @param entries   混排后的信息流(按 position 升序,含自然结果与广告)
 * @param traceId   链路追踪 ID
 */
public record BlendedFeedResponse(long userId,
                                  String query,
                                  String requestId,
                                  List<FeedEntry> entries,
                                  String traceId) {
}
