package com.recsys.common.dto;

import com.recsys.common.constant.ActionType;

/**
 * 用户行为事件(埋点上报)。对应架构文档 §5.6 POST /api/behavior。
 * 这是反馈闭环与训练样本的数据源,字段需稳定。
 *
 * @param userId  用户 ID
 * @param itemId  物品 ID
 * @param action  行为类型
 * @param value   行为值(评分值、播放时长等;无则 0)
 * @param scene   发生场景
 * @param bucket  AB 实验分桶标记(用于分组指标对比),可为空
 * @param ts      事件时间戳(毫秒);上报时可由服务端补全
 */
public record BehaviorEvent(
        long userId,
        long itemId,
        ActionType action,
        double value,
        String scene,
        String bucket,
        long ts) {
}
