package com.recsys.advertiser.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 广告新建/更新入参。
 *
 * <p>新建:{@code itemId} 必填(复用现有 item 的创意/embedding/特征);可选随广告带一批 {@code bidwords}。
 * {@code optimizationType} ∈ CPC/OCPC/CPM/OCPM/CPA;OCPC/OCPM/CPA 时 {@code targetCpa} 生效。
 * {@code audienceId} 绑定 Look-alike 人群包;新建时空=不定向,更新时 null=不改、0=清除定向。
 * 更新:为 null 的字段不改(部分更新);改 {@code itemId} 会重拷 {@code ad_embedding}。约束均 null-safe。
 */
public record AdUpsert(
        @Positive(message = "itemId 必须为正") Long itemId,
        @Size(max = 300, message = "title 长度不能超过 300") String title,
        @Size(max = 1000, message = "landingUrl 长度不能超过 1000") String landingUrl,
        @PositiveOrZero(message = "qualityScore 不能为负") Double qualityScore,
        @Pattern(regexp = "active|paused", message = "status 仅支持 active/paused") String status,
        @Pattern(regexp = "CPC|OCPC|CPM|OCPM|CPA", message = "optimizationType 仅支持 CPC/OCPC/CPM/OCPM/CPA") String optimizationType,
        @PositiveOrZero(message = "targetCpa 不能为负") Double targetCpa,
        @Positive(message = "audienceId 必须为正") Long audienceId,
        @Valid List<BidwordUpsert> bidwords) {
}
