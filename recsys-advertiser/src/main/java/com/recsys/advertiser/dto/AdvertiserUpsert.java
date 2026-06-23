package com.recsys.advertiser.dto;

/**
 * 广告主新建/更新入参。{@code status} 仅允许 active / paused(over_budget 由在线 pacing 自动置位,管理面不直接设)。
 * 字段为 null 表示更新时不改该列(部分更新)。
 */
public record AdvertiserUpsert(String name, Double dailyBudget, String status) {
}
