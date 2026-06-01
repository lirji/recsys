package com.recsys.common.recall;

/**
 * 召回路标识。与架构文档 §3 多路召回对应。
 */
public enum RecallChannel {
    VECTOR,   // 向量召回(pgvector 语义最近邻)
    I2I,      // ItemCF / Swing 协同召回
    HOT,      // 热门召回(兜底 / 冷启动)
    TAG       // 标签/类目召回(用户画像偏好)
}
