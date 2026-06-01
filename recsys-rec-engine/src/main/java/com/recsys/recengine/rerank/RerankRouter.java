package com.recsys.recengine.rerank;

import com.recsys.common.dto.RecommendItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 重排路由:按分层实验 rerank 层选中的策略名分发到对应 {@link Reranker};未知策略回退 diversity。
 */
@Service
public class RerankRouter {

    private static final Logger log = LoggerFactory.getLogger(RerankRouter.class);
    private static final String FALLBACK = "diversity";

    private final Map<String, Reranker> byName;

    public RerankRouter(List<Reranker> rerankers) {
        this.byName = rerankers.stream().collect(Collectors.toMap(Reranker::name, Function.identity()));
    }

    public List<RecommendItem> rerank(String strategy, List<RerankCandidate> fused, RerankInput in) {
        Reranker r = byName.get(strategy);
        if (r == null) {
            if (strategy != null && !strategy.isBlank() && !FALLBACK.equals(strategy)) {
                log.debug("未知重排策略 {},回退 {}", strategy, FALLBACK);
            }
            r = byName.get(FALLBACK);
        }
        return r.rerank(fused, in);
    }
}
