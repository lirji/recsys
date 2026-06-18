package com.recsys.common.ad;

import java.util.List;

/**
 * 搜索广告响应:{@code GET /api/search-ads} 的返回。
 *
 * @param userId    用户 ID
 * @param query     原始查询
 * @param requestId 本次请求 ID(点击/转化回传时带回,用于计费归因)
 * @param ads       竞得展示的广告(按位次升序)
 * @param traceId   链路追踪 ID
 */
public record SearchAdsResponse(long userId,
                                String query,
                                String requestId,
                                List<SponsoredAd> ads,
                                String traceId) {
}
