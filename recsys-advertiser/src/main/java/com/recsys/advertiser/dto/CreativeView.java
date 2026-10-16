package com.recsys.advertiser.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** 广告创意视图。 */
public record CreativeView(long creativeId, long adId, String title, String landingUrl, String status) {

    /** 兼容保留数字字段；浏览器以文本标识避免 Snowflake 精度丢失。 */
    @JsonProperty("creativeIdText")
    public String creativeIdText() { return Long.toString(creativeId); }

    /** 兼容保留数字字段；浏览器以文本标识避免 Snowflake 精度丢失。 */
    @JsonProperty("adIdText")
    public String adIdText() { return Long.toString(adId); }
}
