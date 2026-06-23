package com.recsys.advertiser.dto;

import java.util.List;

/**
 * 广告新建/更新入参。
 *
 * <p>新建:{@code itemId} 必填(复用现有 item 的创意/embedding/特征);可选随广告带一批 {@code bidwords}。
 * {@code optimizationType} ∈ CPC/OCPC,OCPC 时 {@code targetCpa} 生效(广告主只给目标转化成本,在线 oCPC 自动出价)。
 * 更新:为 null 的字段不改(部分更新);改 {@code itemId} 会重拷 {@code ad_embedding}。
 */
public record AdUpsert(
        Long itemId,
        String title,
        String landingUrl,
        Double qualityScore,
        String status,
        String optimizationType,
        Double targetCpa,
        List<BidwordUpsert> bidwords) {
}
