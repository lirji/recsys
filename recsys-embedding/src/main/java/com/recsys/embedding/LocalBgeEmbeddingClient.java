package com.recsys.embedding;

import com.recsys.common.embedding.EmbeddingClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 降级实现:本地 BGE(ONNX,CPU)。断网/超额时兜底。
 * 仅当 recsys.embedding.provider=local 时装配。
 *
 * TODO(Track A 进阶): 接入 BGE-small-zh 的 ONNX 模型 + 分词,目前为占位 stub。
 */
@Component
@ConditionalOnProperty(prefix = "recsys.embedding", name = "provider", havingValue = "local")
public class LocalBgeEmbeddingClient implements EmbeddingClient {

    private final EmbeddingProperties props;

    public LocalBgeEmbeddingClient(EmbeddingProperties props) {
        this.props = props;
    }

    @Override
    public float[] embedText(String text) {
        throw new UnsupportedOperationException("本地 BGE 向量化尚未实现,请使用 provider=gemini");
    }

    @Override
    public int dimension() {
        return props.getDimension();
    }

    @Override
    public String modelName() {
        return "bge-small-zh-local";
    }
}
