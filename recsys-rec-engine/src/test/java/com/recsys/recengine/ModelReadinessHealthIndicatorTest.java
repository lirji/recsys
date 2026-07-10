package com.recsys.recengine;

import com.recsys.rank.RankProperties;
import com.recsys.rank.RankRouter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link ModelReadinessHealthIndicator} 单测(E4)——readiness 门控:模型策略但模型未就绪 → DOWN。
 */
class ModelReadinessHealthIndicatorTest {

    private ModelReadinessHealthIndicator indicator(String strategy, boolean modelReady) {
        RankRouter router = mock(RankRouter.class);
        when(router.activeStrategyReady()).thenReturn(modelReady);
        RankProperties props = new RankProperties();
        props.setStrategy(strategy);
        return new ModelReadinessHealthIndicator(router, props);
    }

    @Test
    void modelStrategyReady_isUp() {
        Health h = indicator("din", true).health();
        assertEquals(Status.UP, h.getStatus());
        assertEquals("din", h.getDetails().get("rank.strategy"));
    }

    @Test
    void modelStrategyNotLoaded_isDown() {
        Health h = indicator("din", false).health();
        assertEquals(Status.DOWN, h.getStatus(), "部署了模型策略但模型没加载 → readiness DOWN,k8s 不导流");
    }

    @Test
    void ruleStrategy_alwaysUp() {
        Health h = indicator("v1", true).health();
        assertEquals(Status.UP, h.getStatus());
    }
}
