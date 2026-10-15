package com.recsys.recall.persistence.model;

import com.recsys.common.recall.RecallChannel;
import com.recsys.common.recall.RecallItem;

/** 数据库只返回相似度，通道身份仍由调用的召回器决定。 */
public record VectorMatch(long itemId, double score) {

    /** 在持久层边界转换为原召回结果，不在SQL中硬编码通道名。 */
    public RecallItem toRecallItem(RecallChannel channel) {
        return new RecallItem(itemId, score, channel);
    }
}
