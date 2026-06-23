package com.recsys.advertiser.dto;

import java.util.List;

/**
 * 广告详情视图。{@code hasEmbedding} 反映 {@code ad_embedding} 是否就绪
 * (决定 SEMANTIC_AD / U2A 语义召回能否命中本广告)。
 */
public record AdView(
        long adId,
        long advertiserId,
        long itemId,
        String title,
        String landingUrl,
        double qualityScore,
        String status,
        String optimizationType,
        Double targetCpa,
        boolean hasEmbedding,
        List<BidwordView> bidwords,
        List<CreativeView> creatives) {
}
