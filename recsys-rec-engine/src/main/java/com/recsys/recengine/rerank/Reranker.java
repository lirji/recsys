package com.recsys.recengine.rerank;

import com.recsys.common.dto.RecommendItem;
import com.recsys.content.Item;

import java.util.List;

/**
 * 重排策略契约。输入"已按相关性降序"的候选,产出最终展示列表(已截断到 size、带理由)。
 *
 * <p>多策略由 {@link RerankRouter} 按分层实验的 rerank 层选择;新增策略只需实现本接口
 * 并声明唯一 {@link #name()}。默认方法提供理由文案与组装工具,各实现共用。
 */
public interface Reranker {

    /** 策略名,与实验 params.strategy 对应(如 diversity / mmr / none)。 */
    String name();

    List<RecommendItem> rerank(List<RerankCandidate> fused, RerankInput in);

    /** 按召回来源生成可读理由。 */
    default String buildReason(Item item, String channel) {
        return switch (channel) {
            case "VECTOR" -> "与你的兴趣语义相近";
            case "SEMANTIC" -> "贴合你近期的兴趣";
            case "I2I" -> "看过相似内容的人也喜欢";
            case "SWING" -> "和你看过的强相关";
            case "U2U" -> "和你口味相似的用户也喜欢";
            case "TAG" -> "来自你偏好的类目";
            case "COLD" -> "新用户·为你探索";
            case "HOT" -> "热门推荐";
            default -> "为你推荐";
        };
    }

    /** 组装一条结果。 */
    default RecommendItem build(RerankCandidate c, RerankInput in) {
        Item item = in.itemMap().get(c.itemId());
        String channel = in.recallChannel().getOrDefault(c.itemId(), "unknown");
        return new RecommendItem(c.itemId(), round(c.score()), List.of(channel), buildReason(item, channel));
    }

    default double round(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
