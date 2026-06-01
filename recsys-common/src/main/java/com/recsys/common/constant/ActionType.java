package com.recsys.common.constant;

/**
 * 行为类型。impression(曝光) 用于构造负样本,是 CTR 训练的关键。
 */
public enum ActionType {
    IMPRESSION,   // 曝光(展示未必点击)—— 负样本来源
    CLICK,        // 点击 —— 正样本
    LIKE,         // 点赞/收藏
    PLAY,         // 播放(value=时长)
    RATING        // 评分(value=分值)
}
