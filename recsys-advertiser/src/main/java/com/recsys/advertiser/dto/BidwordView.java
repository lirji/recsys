package com.recsys.advertiser.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** 竞价词视图。 */
public record BidwordView(long id, long adId, String keyword, String matchType, double bid, String bidMode) {

    /** 兼容保留数字字段；浏览器以文本标识避免 Snowflake 精度丢失。 */
    @JsonProperty("idText")
    public String idText() { return Long.toString(id); }

    /** 兼容保留数字字段；浏览器以文本标识避免 Snowflake 精度丢失。 */
    @JsonProperty("adIdText")
    public String adIdText() { return Long.toString(adId); }
}
