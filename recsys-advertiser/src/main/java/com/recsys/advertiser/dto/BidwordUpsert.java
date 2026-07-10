package com.recsys.advertiser.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 竞价词新建/更新入参。{@code matchType} ∈ EXACT/PHRASE/BROAD;{@code bidMode} ∈ CPC/oCPC/oCPM。
 * 新建广告时可随广告一起带入一批竞价词。约束均 null-safe(部分更新友好)。
 */
public record BidwordUpsert(
        @Size(max = 200, message = "keyword 长度不能超过 200") String keyword,
        @Pattern(regexp = "EXACT|PHRASE|BROAD", message = "matchType 仅支持 EXACT/PHRASE/BROAD") String matchType,
        @Positive(message = "bid 必须为正") Double bid,
        @Pattern(regexp = "CPC|oCPC|oCPM", message = "bidMode 仅支持 CPC/oCPC/oCPM") String bidMode) {
}
