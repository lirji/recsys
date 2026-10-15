package com.recsys.console.user;

import java.util.List;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Map;

/**
 * 用户 360 视图:静态画像(app_user)+ 行为聚合(user_behavior)+ 向量存在性(user_embedding)+ 在线实时态(Redis)。
 * 全部只读聚合；不可读部分通过errors标识，未知标量为null，其他成功数据保留。
 *
 * @param userId         用户 ID
 * @param exists         是否有画像行或任何行为；只有两项均确定缺失才false，无法判断时null
 * @param profileJson    app_user.profile 原文(JSONB → text),由前端解析
 * @param profileUpdatedAt 画像更新时刻(epoch ms),无则 null
 * @param hasEmbedding   是否已有 user_embedding 向量(供召回/个性化)
 * @param stats          行为聚合统计
 * @param recentBehavior 近期行为(倒序,含位次/分桶,供闭环/PAL 排障)
 * @param errors         不可读分区及受控错误码；列表/Map空值只有无对应错误时才代表真实为空
 * @param realtime       在线实时态(Redis:实时类目偏好 / 行为序列 / 已看数 / 结果缓存)
 */
public record UserProfileView(
        long userId,
        Boolean exists,
        String profileJson,
        Long profileUpdatedAt,
        Boolean hasEmbedding,
        Stats stats,
        List<BehaviorRow> recentBehavior,
        Realtime realtime,
        Map<Section, String> errors) {

    /**
     * @param totalInteractions  行为总条数
     * @param actionCounts       各 action 计数(impression/click/like/...)
     * @param distinctCategories 交互过的不同类目数(join item)
     * @param bucketsSeen        命中过的 A/B 分桶
     */
    public record Stats(
            Long totalInteractions,
            Map<String, Long> actionCounts,
            Integer distinctCategories,
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
            Boolean recCached,
            Long seenCount) {
    }

    /** 部分读取错误的稳定分区键，不用ordinal或Java字段名隐式生成协议。 */
    public enum Section {
        PROFILE("profile"), EMBEDDING("embedding"), ACTIONS("actions"), CATEGORIES("categories"),
        BUCKETS("buckets"), RECENT("recent"), REALTIME("realtime");
        private final String code;
        Section(String code) { this.code = code; }
        /** JSON对象键沿用显式分区编码，前端据此显示哪部分未知。 */
        @JsonValue public String code() { return code; }
    }
}
