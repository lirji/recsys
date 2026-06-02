package com.recsys.common.query;

/**
 * Query 理解层契约(由 {@code recsys-query} 模块实现)。
 *
 * <p>把一段原始查询文本解析为 {@link StructuredQuery}:归一化、分词+权重、
 * 意图类目识别、(可选)向量化、改写扩展。是搜索 / 搜索广告链路的入口,
 * 对应内容推荐里「userId 驱动」之外新增的「query 驱动」那一段(见 docs/05)。
 *
 * <p>实现必须对所有外部依赖(DB、EmbeddingClient)优雅降级:任一环节失败
 * 都应返回尽量完整的 {@link StructuredQuery},而非抛异常中断链路。
 */
public interface QueryUnderstandingService {

    /**
     * 解析查询。
     *
     * @param rawQuery 原始查询文本(可为 null/空 → 返回空结构)
     * @param userId   发起查询的用户(用于后续个性化;MVP 暂不使用,预留)
     * @return 结构化查询,永不为 null
     */
    StructuredQuery parse(String rawQuery, long userId);
}
