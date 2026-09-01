package com.recsys.common.dto;

import com.recsys.common.constant.ActionType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 用户行为事件(埋点上报)。对应架构文档 §5.6 POST /api/behavior。
 * 这是反馈闭环与训练样本的数据源,字段需稳定。校验仅在上报入口 @Valid 时生效,不影响离线读取。
 *
 * @param userId  用户 ID
 * @param itemId  物品 ID
 * @param action  行为类型
 * @param value   行为值(评分值、播放时长等;无则 0)
 * @param scene   发生场景
 * @param bucket  AB 实验分桶标记(用于分组指标对比),可为空
 * @param ts      事件时间戳(毫秒);上报时可由服务端补全
 * @param exposureId 服务端推荐结果返回的曝光 ID；CLICK 用它做并发幂等与精确归因
 */
public record BehaviorEvent(
        @Positive(message = "userId 必须为正") long userId,
        @Positive(message = "itemId 必须为正") long itemId,
        @NotNull(message = "action 不能为空") ActionType action,
        double value,
        String scene,
        String bucket,
        long ts,
        @Size(max = 128, message = "exposureId 最长 128 字符") String exposureId) {

    /** 兼容旧客户端/旧测试；缺 exposureId 时服务端尝试按 user-item 最近曝光补齐。 */
    public BehaviorEvent(long userId, long itemId, ActionType action, double value,
                         String scene, String bucket, long ts) {
        this(userId, itemId, action, value, scene, bucket, ts, null);
    }
}
