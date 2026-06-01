package com.recsys.recengine.rerank;

import com.recsys.common.dto.RecommendItem;
import com.recsys.content.Item;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 多样性重排(strategy=diversity):限制同一类目在结果中的出现次数,打散类目。
 *
 * <p>原编排内联逻辑迁入。{@code maxSameCategory} 由 rerank 层实验参数覆盖(冷启动会传更小值
 * 以增强探索)。若打散后不足 size(候选类目高度集中),放宽补齐保证条数。
 */
@Component
public class DiversityReranker implements Reranker {

    private final int defaultMaxSameCategory;

    public DiversityReranker(com.recsys.recengine.RecEngineProperties props) {
        this.defaultMaxSameCategory = props.getRerank().getMaxSameCategory();
    }

    @Override
    public String name() {
        return "diversity";
    }

    @Override
    public List<RecommendItem> rerank(List<RerankCandidate> fused, RerankInput in) {
        int maxSameCat = in.intParam("maxSameCategory", defaultMaxSameCategory);
        int size = in.size();
        Map<String, Integer> catCount = new HashMap<>();
        List<RecommendItem> out = new ArrayList<>(size);

        for (RerankCandidate c : fused) {
            if (out.size() >= size) {
                break;
            }
            Item item = in.itemMap().get(c.itemId());
            String cat = item != null && item.category() != null ? item.category() : "Unknown";
            int seen = catCount.getOrDefault(cat, 0);
            if (seen >= maxSameCat) {
                continue; // 类目超额,打散跳过
            }
            catCount.put(cat, seen + 1);
            out.add(build(c, in));
        }

        // 打散后不足 size,放宽补齐
        if (out.size() < size) {
            for (RerankCandidate c : fused) {
                if (out.size() >= size) {
                    break;
                }
                boolean already = out.stream().anyMatch(o -> o.itemId() == c.itemId());
                if (already) {
                    continue;
                }
                out.add(build(c, in));
            }
        }
        return out;
    }
}
