package com.recsys.advertiser.dto;

/**
 * 竞价词新建/更新入参。{@code matchType} ∈ EXACT/PHRASE/BROAD;{@code bidMode} ∈ CPC/oCPC/oCPM。
 * 新建广告时可随广告一起带入一批竞价词。
 */
public record BidwordUpsert(String keyword, String matchType, Double bid, String bidMode) {
}
