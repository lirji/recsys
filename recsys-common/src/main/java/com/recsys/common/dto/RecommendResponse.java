package com.recsys.common.dto;

import java.util.List;

/**
 * 推荐响应。对应架构文档 §5.1。
 *
 * @param userId  用户 ID
 * @param scene   场景
 * @param items   推荐结果列表(已排序、已截断)
 * @param traceId 全链路追踪 ID,便于排障与样本回流关联
 * @param explain 链路 explain(仅 {@code explain=true} 请求填充,否则为 null);见 {@link RecommendExplain}
 */
public record RecommendResponse(
        long userId,
        String scene,
        List<RecommendItem> items,
        String traceId,
        RecommendExplain explain) {

    /**
     * 兼容旧调用:不带 explain(explain=null)。保留 4 参构造使既有构造点(编排层空返回/兜底/正常返回)不破。
     * explain=null 时序列化为 {@code "explain":null},对既有消费方(前端按可选字段读)向后兼容。
     */
    public RecommendResponse(long userId, String scene, List<RecommendItem> items, String traceId) {
        this(userId, scene, items, traceId, null);
    }
}
