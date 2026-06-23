package com.recsys.advertiser.dto;

/** 广告创意新建/更新入参(DCO 一个广告多套创意,多臂老虎机在线择优,docs/05 §7 M7)。 */
public record CreativeUpsert(String title, String landingUrl, String status) {
}
