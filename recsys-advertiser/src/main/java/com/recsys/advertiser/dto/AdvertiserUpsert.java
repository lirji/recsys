package com.recsys.advertiser.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * 广告主新建/更新入参。{@code status} 仅允许 active / paused(over_budget 由在线 pacing 自动置位,管理面不直接设)。
 * 字段为 null 表示更新时不改该列(部分更新),故用 null-safe 约束(仅在非空时校验)。
 */
public record AdvertiserUpsert(
        @Size(max = 200, message = "name 长度不能超过 200") String name,
        @PositiveOrZero(message = "dailyBudget 不能为负") Double dailyBudget,
        @Pattern(regexp = "active|paused", message = "status 仅支持 active/paused") String status) {
}
