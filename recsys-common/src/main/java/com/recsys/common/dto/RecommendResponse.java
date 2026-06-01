package com.recsys.common.dto;

import java.util.List;

/**
 * 推荐响应。对应架构文档 §5.1。
 *
 * @param userId  用户 ID
 * @param scene   场景
 * @param items   推荐结果列表(已排序、已截断)
 * @param traceId 全链路追踪 ID,便于排障与样本回流关联
 */
public record RecommendResponse(
        long userId,
        String scene,
        List<RecommendItem> items,
        String traceId) {
}
