package com.recsys.advertiser.dto;

/**
 * 广告主视图。{@code spentToday}/{@code remainingBudget} 来自 Redis 当日已耗预算
 * {@code ad:budget:{advertiserId}:{yyyymmdd}}(与在线 pacing 同源),Redis 不可用时为 0 / 日预算。
 */
public record AdvertiserView(
        long advertiserId,
        String name,
        double dailyBudget,
        String status,
        double spentToday,
        double remainingBudget) {
}
