package com.recsys.rank;

import com.recsys.common.rank.RankService;
import com.recsys.common.rank.RankedItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 排序策略路由(@Primary,编排层注入的就是它)。
 *
 * <p>按 {@code recsys.rank.strategy} 选择实现,并实现优雅降级:
 * <ul>
 *   <li>strategy=onnx 且 ONNX 模型就绪 → 走 {@link OnnxRankService};</li>
 *   <li>否则(strategy=v1 / 模型未就绪 / onnx 返回空)→ 回退 {@link RuleRankService}。</li>
 * </ul>
 * OnnxRankService 仅在 strategy=onnx 时存在(条件 Bean),故用 ObjectProvider 可选注入。
 */
@Service
@Primary
public class RankRouter implements RankService {

    private static final Logger log = LoggerFactory.getLogger(RankRouter.class);

    private final RuleRankService rule;
    private final ObjectProvider<OnnxRankService> onnxProvider;
    private final RankProperties props;

    public RankRouter(RuleRankService rule,
                      ObjectProvider<OnnxRankService> onnxProvider,
                      RankProperties props) {
        this.rule = rule;
        this.onnxProvider = onnxProvider;
        this.props = props;
    }

    @Override
    public List<RankedItem> rank(long userId, List<Long> candidateItemIds, String scene) {
        return rank(userId, candidateItemIds, scene, null);
    }

    /**
     * 带每请求策略覆盖的排序(供分层 A/B 的 rank 层使用)。
     *
     * @param strategyOverride 本次请求选用的策略(如 v1 / onnx);null/空 → 回落全局 {@code recsys.rank.strategy}
     */
    public List<RankedItem> rank(long userId, List<Long> candidateItemIds, String scene, String strategyOverride) {
        String strategy = (strategyOverride == null || strategyOverride.isBlank())
                ? props.getStrategy() : strategyOverride;
        if ("onnx".equalsIgnoreCase(strategy)) {
            OnnxRankService onnx = onnxProvider.getIfAvailable();
            if (onnx != null && onnx.isReady()) {
                List<RankedItem> r = onnx.rank(userId, candidateItemIds, scene);
                if (!r.isEmpty()) {
                    return r;
                }
                log.debug("ONNX 返回空,回退规则排序 user={}", userId);
            }
        }
        return rule.rank(userId, candidateItemIds, scene);
    }
}
