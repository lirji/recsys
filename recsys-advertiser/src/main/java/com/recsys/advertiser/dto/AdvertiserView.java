package com.recsys.advertiser.dto;

/**
 * 广告主视图。{@code spentToday}/{@code remainingBudget} 来自 Redis 当日已耗预算
 * {@code ad:budget:{advertiserId}:{yyyymmdd}} 的整数分计数，响应统一为元。
 * 计数缺失、非法或 Redis 不可用时两者为 null；旧计数估计不能作为持久化账本余额。
 */
public record AdvertiserView(
        long advertiserId,
        String name,
        double dailyBudget,
        String status,
        Double spentToday,
        Double remainingBudget) {
}
