package com.recsys.common.query;

import java.util.List;

/**
 * 结构化查询:Query 理解层 {@link QueryUnderstandingService#parse} 的产出,
 * 是搜索/搜索广告链路从「一段原始文本」到「可供召回与排序消费的结构」的桥梁。
 *
 * <p>设计原则:**每一步都可降级**。向量化失败时 {@code embedding} 为 null;
 * 无库/无命中时 {@code intents} 为空;但 {@code raw}/{@code normalized}/{@code terms}
 * 总是有值。下游(SEMANTIC 召回读 query、TAG 召回读意图类目、相关性打分读 terms)
 * 据此各取所需,任一字段缺失都不应使链路崩溃。
 *
 * @param raw        原始查询串(用户输入,未处理)
 * @param normalized 归一化串(小写、去标点、压空格)——可作为伪 query 喂给向量化/语义召回
 * @param terms      分词 + 权重(去停用词、去重、截断后)
 * @param intents    意图类目(降序、过阈值);可能为空
 * @param rewrites   改写/扩展查询串(含归一化串本身);MVP 最小实现
 * @param embedding  查询向量(维度同 {@code EmbeddingClient.dimension()});不可用时为 null
 */
public record StructuredQuery(String raw,
                              String normalized,
                              List<TermWeight> terms,
                              List<CategoryScore> intents,
                              List<String> rewrites,
                              float[] embedding) {

    public StructuredQuery {
        terms = terms == null ? List.of() : terms;
        intents = intents == null ? List.of() : intents;
        rewrites = rewrites == null ? List.of() : rewrites;
    }

    /** 是否成功拿到查询向量(Gemini/BGE 可用时)。 */
    public boolean hasEmbedding() {
        return embedding != null && embedding.length > 0;
    }

    /** 置信度最高的意图类目;无意图时返回 null。 */
    public String topIntent() {
        return intents.isEmpty() ? null : intents.get(0).category();
    }
}
