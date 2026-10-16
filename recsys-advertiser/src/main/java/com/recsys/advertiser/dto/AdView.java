package com.recsys.advertiser.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

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
        Long audienceId,
        boolean hasEmbedding,
        List<BidwordView> bidwords,
        List<CreativeView> creatives,
        String reviewStatus,
        String reviewReason) {

    /** 兼容保留数字字段；浏览器以文本标识避免 Snowflake 精度丢失。 */
    @JsonProperty("adIdText")
    public String adIdText() { return Long.toString(adId); }

    /** 兼容保留数字字段；浏览器以文本标识避免 Snowflake 精度丢失。 */
    @JsonProperty("advertiserIdText")
    public String advertiserIdText() { return Long.toString(advertiserId); }

    /** 兼容保留数字字段；浏览器以文本标识避免 Snowflake 精度丢失。 */
    @JsonProperty("itemIdText")
    public String itemIdText() { return Long.toString(itemId); }

    /** 兼容保留数字字段；浏览器以文本标识避免 Snowflake 精度丢失。 */
    @JsonProperty("audienceIdText")
    public String audienceIdText() { return audienceId == null ? null : Long.toString(audienceId); }
}
