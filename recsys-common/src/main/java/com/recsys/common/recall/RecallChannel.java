package com.recsys.common.recall;

/**
 * 召回路标识。与架构文档 §3 多路召回对应。
 */
public enum RecallChannel {
    VECTOR,   // 向量召回(pgvector 语义最近邻)
    I2I,      // ItemCF 协同召回(物品共现)
    HOT,      // 热门召回(兜底 / 冷启动)
    TAG,      // 标签/类目召回(用户画像偏好)
    U2U,      // UserCF 协同召回(相似用户的正反馈,离线物化)
    SWING,    // Swing i2i 协同召回(抗热门的物品相似)
    SEMANTIC, // 语义 query 召回(query/伪 query → embedding → 向量检索)
    COLD,     // 冷启动类目探索召回(跨类目铺开,最大化覆盖)
    TWO_TOWER, // 双塔召回(离线学 item 向量 + 在线 ONNX 算 user 向量 → ANN,学的是"行为相似")
    GENERATIVE, // 生成式召回(RQ-VAE 语义 ID:离线把 item 量化成 codeword 元组,在线按用户近期 item 的语义 ID 前缀检索同簇)
    LEXICAL,   // 词法召回(Postgres 全文检索 BM25/ts_rank,query→item 标题倒排);与 SEMANTIC 向量路混合(RRF)
    TIGER      // 完整 TIGER 生成式召回(decoder-only Transformer 自回归 beam search 生成下一个 item 的语义 ID)
}
