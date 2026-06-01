package com.recsys.rank;

import com.recsys.common.rank.RankService;
import com.recsys.common.rank.RankedItem;
import io.micrometer.core.instrument.MeterRegistry;
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
 *   <li>strategy=onnx 且 LightGBM 模型就绪 → 走 {@link OnnxRankService};</li>
 *   <li>strategy=deepfm 且 DeepFM 模型就绪 → 走 {@link DeepFmRankService};</li>
 *   <li>否则(strategy=v1 / 模型未就绪 / 模型返回空)→ 回退 {@link RuleRankService}。</li>
 * </ul>
 * Onnx/DeepFm 服务仅在对应 strategy 下存在(条件 Bean),故用 ObjectProvider 可选注入。
 *
 * <p>观测:每次排序打 {@code recsys.rank} 计数,tag {@code requested}(请求策略)/
 * {@code served}(实际执行)/{@code reason}。模型回退率 =
 * {@code recsys_rank_total{requested="onnx",served="rule"} / recsys_rank_total{requested="onnx"}}。
 * MeterRegistry 经 ObjectProvider 可选注入:在线由 rec-engine 提供;offline(eval 作业)无注册表时不打点。
 */
@Service
@Primary
public class RankRouter implements RankService {

    private static final Logger log = LoggerFactory.getLogger(RankRouter.class);

    private final RuleRankService rule;
    private final ObjectProvider<OnnxRankService> onnxProvider;
    private final ObjectProvider<DeepFmRankService> deepfmProvider;
    private final ObjectProvider<MmoeRankService> mmoeProvider;
    private final ObjectProvider<DinRankService> dinProvider;
    private final ObjectProvider<MeterRegistry> meterRegistryProvider;
    private final RankProperties props;

    public RankRouter(RuleRankService rule,
                      ObjectProvider<OnnxRankService> onnxProvider,
                      ObjectProvider<DeepFmRankService> deepfmProvider,
                      ObjectProvider<MmoeRankService> mmoeProvider,
                      ObjectProvider<DinRankService> dinProvider,
                      ObjectProvider<MeterRegistry> meterRegistryProvider,
                      RankProperties props) {
        this.rule = rule;
        this.onnxProvider = onnxProvider;
        this.deepfmProvider = deepfmProvider;
        this.mmoeProvider = mmoeProvider;
        this.dinProvider = dinProvider;
        this.meterRegistryProvider = meterRegistryProvider;
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
        String requested = strategy == null ? "v1" : strategy.toLowerCase();

        if ("onnx".equals(requested)) {
            OnnxRankService onnx = onnxProvider.getIfAvailable();
            if (onnx == null || !onnx.isReady()) {
                return served(rule.rank(userId, candidateItemIds, scene), requested, "rule", "not_ready");
            }
            List<RankedItem> r = onnx.rank(userId, candidateItemIds, scene);
            if (!r.isEmpty()) {
                return served(r, requested, "onnx", "ok");
            }
            log.debug("ONNX 返回空,回退规则排序 user={}", userId);
            return served(rule.rank(userId, candidateItemIds, scene), requested, "rule", "empty");
        } else if ("deepfm".equals(requested)) {
            DeepFmRankService deepfm = deepfmProvider.getIfAvailable();
            if (deepfm == null || !deepfm.isReady()) {
                return served(rule.rank(userId, candidateItemIds, scene), requested, "rule", "not_ready");
            }
            List<RankedItem> r = deepfm.rank(userId, candidateItemIds, scene);
            if (!r.isEmpty()) {
                return served(r, requested, "deepfm", "ok");
            }
            log.debug("DeepFM 返回空,回退规则排序 user={}", userId);
            return served(rule.rank(userId, candidateItemIds, scene), requested, "rule", "empty");
        } else if ("mmoe".equals(requested)) {
            MmoeRankService mmoe = mmoeProvider.getIfAvailable();
            if (mmoe == null || !mmoe.isReady()) {
                return served(rule.rank(userId, candidateItemIds, scene), requested, "rule", "not_ready");
            }
            List<RankedItem> r = mmoe.rank(userId, candidateItemIds, scene);
            if (!r.isEmpty()) {
                return served(r, requested, "mmoe", "ok");
            }
            log.debug("MMoE 返回空,回退规则排序 user={}", userId);
            return served(rule.rank(userId, candidateItemIds, scene), requested, "rule", "empty");
        } else if ("din".equals(requested)) {
            DinRankService din = dinProvider.getIfAvailable();
            if (din == null || !din.isReady()) {
                return served(rule.rank(userId, candidateItemIds, scene), requested, "rule", "not_ready");
            }
            List<RankedItem> r = din.rank(userId, candidateItemIds, scene);
            if (!r.isEmpty()) {
                return served(r, requested, "din", "ok");
            }
            log.debug("DIN 返回空,回退规则排序 user={}", userId);
            return served(rule.rank(userId, candidateItemIds, scene), requested, "rule", "empty");
        }
        // 请求即规则排序(v1 / 未知策略):served=rule 但不是回退,reason=ok
        return served(rule.rank(userId, candidateItemIds, scene), requested, "rule", "ok");
    }

    /** 打点后原样返回结果。注册表缺失(offline)时静默跳过。 */
    private List<RankedItem> served(List<RankedItem> result, String requested, String served, String reason) {
        MeterRegistry registry = meterRegistryProvider.getIfAvailable();
        if (registry != null) {
            registry.counter("recsys.rank",
                    "requested", requested, "served", served, "reason", reason).increment();
        }
        return result;
    }
}
