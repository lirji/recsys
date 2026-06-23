package com.recsys.advertiser.dto;

/** 广告创意视图。 */
public record CreativeView(long creativeId, long adId, String title, String landingUrl, String status) {
}
