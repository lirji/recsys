package com.recsys.common.ad;

/**
 * 广告召回通道(docs/05 §4.2)。query→ad,与推荐的 user→item 召回区分。
 *
 * <p>优先级(主来源标记,数字小优先)用于多路命中同一广告时取主路。
 */
public enum AdChannel {
    /** query 词项精确命中竞价词 keyword(EXACT 匹配类型)。 */
    KW_EXACT(0),
    /** query 改写/同义词命中竞价词(BROAD/PHRASE 匹配类型)。 */
    KW_BROAD(1),
    /** query 向量 → ad_embedding 余弦 ANN(embedding 不可用时降级空)。 */
    SEMANTIC_AD(2),
    /** 高质量/高出价广告兜底(永远在线,保填充率)。 */
    HOT_AD(3);

    private final int priority;

    AdChannel(int priority) {
        this.priority = priority;
    }

    public int priority() {
        return priority;
    }
}
