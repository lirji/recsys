package com.recsys.console.user;

import java.util.List;
import java.util.Map;

/**
 * 用户 360 视图:静态画像(app_user)+ 行为聚合(user_behavior)+ 向量存在性(user_embedding)+ 在线实时态(Redis)。
 * 全部只读聚合;DB / Redis 任一不可用时对应部分优雅降级(exists=false / realtime.available=false)。
 *
 * @param userId         用户 ID
 * @param exists         是否有画像行或任何行为(否则视为未知用户)
 * @param profileJson    app_user.profile 原文(JSONB → text),由前端解析
 * @param profileUpdatedAt 画像更新时刻(epoch ms),无则 null
 * @param hasEmbedding   是否已有 user_embedding 向量(供召回/个性化)
 * @param stats          行为聚合统计
 * @param recentBehavior 近期行为(倒序,含位次/分桶,供闭环/PAL 排障)
 * @param realtime       在线实时态(Redis:实时类目偏好 / 行为序列 / 已看数 / 结果缓存)
 */
public record UserProfileView(
        long userId,
        boolean exists,
        String profileJson,
        Long profileUpdatedAt,
        boolean hasEmbedding,
        Stats stats,
        List<BehaviorRow> recentBehavior,
        Realtime realtime) {

    /**
     * @param totalInteractions  行为总条数
     * @param actionCounts       各 action 计数(impression/click/like/...)
     * @param distinctCategories 交互过的不同类目数(join item)
     * @param bucketsSeen        命中过的 A/B 分桶
     */
    public record Stats(
            long totalInteractions,
            Map<String, Long> actionCounts,
            int distinctCategories,
            List<String> bucketsSeen) {
    }

    /** 单条行为(user_behavior 投影)。 */
    public record BehaviorRow(
            long itemId,
            String action,
            long ts,
            String bucket,
            Integer position,
            String scene,
            Double value) {
    }

    /**
     * 在线实时态(Redis)。
     *
     * @param available       Redis 是否可达(否则以下字段为降级空值)
     * @param rtCategoryPrefs 实时类目偏好(rt:user Hash,category → 近窗计数)
     * @param recentSeqItems  实时行为序列近端 item(rt:user:seq ZSet 逆序)
     * @param recCached       是否有推荐结果缓存(cache:rec)
     * @param seenCount       已正反馈物品数(seen Set 大小)
     */
    public record Realtime(
            boolean available,
            Map<String, Long> rtCategoryPrefs,
            List<Long> recentSeqItems,
            boolean recCached,
            long seenCount) {
    }
}
