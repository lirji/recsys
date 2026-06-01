package com.recsys.recengine.rerank;

import com.recsys.common.dto.RecommendItem;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 不打散重排(strategy=none):严格按相关性截断到 size。作为对照组 / 关闭多样性时使用。
 */
@Component
public class NoopReranker implements Reranker {

    @Override
    public String name() {
        return "none";
    }

    @Override
    public List<RecommendItem> rerank(List<RerankCandidate> fused, RerankInput in) {
        int size = Math.min(in.size(), fused.size());
        List<RecommendItem> out = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            out.add(build(fused.get(i), in));
        }
        return out;
    }
}
