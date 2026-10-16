package com.recsys.advertiser.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 单广告投放报表行(从 {@code ad_event} 聚合)。{@code spend} 为该广告已计费点击的 charged_price 之和;
 * ctr=clicks/impressions,cvr=conversions/clicks,ecpm=spend/impressions×1000。
 */
public record AdReportRow(
        long adId,
        String title,
        long impressions,
        long clicks,
        long conversions,
        double spend,
        double ctr,
        double cvr,
        double ecpm) {

    /** 兼容保留数字字段；浏览器以文本标识避免 Snowflake 精度丢失。 */
    @JsonProperty("adIdText")
    public String adIdText() { return Long.toString(adId); }
}
