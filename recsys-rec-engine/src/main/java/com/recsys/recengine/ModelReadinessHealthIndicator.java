package com.recsys.recengine;

import com.recsys.rank.RankProperties;
import com.recsys.rank.RankRouter;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * 模型就绪健康探针(E4)。纳入 k8s <b>readiness</b> 组(见 application.yml 的
 * {@code management.endpoint.health.group.readiness.include}):当部署配置了<b>模型排序策略</b>
 * (onnx/deepfm/mmoe/din/dien/ple)但对应 ONNX 模型没加载成功时,readiness 判 DOWN ——
 * k8s 因此不把流量导给这个"会全量退化为规则打分却无人察觉"的实例,滚动发布时坏模型不接管流量。
 *
 * <p>v1/规则策略无模型依赖 → 恒 UP。liveness 不受影响(进程活着即存活),只 readiness 被门控。
 */
@Component("modelReadiness")
public class ModelReadinessHealthIndicator implements HealthIndicator {

    private final RankRouter rankRouter;
    private final RankProperties props;

    public ModelReadinessHealthIndicator(RankRouter rankRouter, RankProperties props) {
        this.rankRouter = rankRouter;
        this.props = props;
    }

    @Override
    public Health health() {
        String strategy = props.getStrategy() == null ? "v1" : props.getStrategy();
        boolean ready = rankRouter.activeStrategyReady();
        Health.Builder b = ready ? Health.up() : Health.down();
        return b.withDetail("rank.strategy", strategy)
                .withDetail("rank.modelReady", ready)
                .build();
    }
}
