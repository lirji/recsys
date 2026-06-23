package com.recsys.advertiser.dto;

/** 竞价词视图。 */
public record BidwordView(long id, long adId, String keyword, String matchType, double bid, String bidMode) {
}
