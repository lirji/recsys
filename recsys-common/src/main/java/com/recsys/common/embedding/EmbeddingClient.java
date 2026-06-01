package com.recsys.common.embedding;

/**
 * 向量化客户端契约(Track A 实现)。
 * 默认实现 GeminiEmbeddingClient(联网,带缓存/批量/限流重试);
 * 降级实现 LocalBgeEmbeddingClient(本地 ONNX,CPU)。
 *
 * 维度约束:所有实现必须返回 {@link #dimension()} 固定维度,全库一致;
 * 换模型 = 换实现类 + 全量重灌向量,并更新 item_embedding.model 标记。
 */
public interface EmbeddingClient {

    /** 文本向量化。 */
    float[] embedText(String text);

    /**
     * 图片向量化(多模态,可选)。未实现的客户端可抛 UnsupportedOperationException。
     */
    default float[] embedImage(byte[] image) {
        throw new UnsupportedOperationException("multimodal embedding not supported by this client");
    }

    /** 向量维度(如 768)。 */
    int dimension();

    /** 模型标识,写入 item_embedding.model,便于换模型与灰度。 */
    String modelName();
}
