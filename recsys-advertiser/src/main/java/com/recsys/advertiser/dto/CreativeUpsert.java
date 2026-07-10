package com.recsys.advertiser.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 广告创意新建/更新入参(DCO 一个广告多套创意,多臂老虎机在线择优,docs/05 §7 M7)。约束均 null-safe。 */
public record CreativeUpsert(
        @Size(max = 300, message = "title 长度不能超过 300") String title,
        @Size(max = 1000, message = "landingUrl 长度不能超过 1000") String landingUrl,
        @Pattern(regexp = "active|paused", message = "status 仅支持 active/paused") String status) {
}
