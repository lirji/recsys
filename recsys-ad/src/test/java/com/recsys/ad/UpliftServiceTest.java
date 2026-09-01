package com.recsys.ad;

import com.recsys.common.ad.AdCandidate;
import com.recsys.common.ad.AdChannel;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A7 Java 在线契约：实际 TARNet ONNX 的双头输出与 adId 映射。 */
class UpliftServiceTest {

    @Test
    void actualTarnetModelProducesBoundedPotentialOutcomes() {
        AdProperties props = new AdProperties();
        props.getUplift().setScoringEnabled(true);
        UpliftService service = new UpliftService(props, new RelevanceGate(props));
        service.load();
        Assumptions.assumeTrue(service.isReady(),
                "需先运行 train_uplift.py 生成被 gitignore 的 model_uplift.onnx");
        try {
            List<AdCandidate> candidates = List.of(
                    new AdCandidate(101L, 11L, 1001L, 0L, 1.0,
                            AdChannel.HOT_AD, 2.5, 0.9),
                    new AdCandidate(102L, 12L, 1002L, 0L, 1.0,
                            AdChannel.HOT_AD, 1.5, 0.5));

            Map<Long, UpliftEstimate> estimates = service.estimate(7L, candidates,
                    Map.of(11L, 0.2, 12L, 0.1), Map.of(11L, 0.04, 12L, 0.01));

            assertEquals(2, estimates.size());
            for (UpliftEstimate estimate : estimates.values()) {
                assertTrue(estimate.mu0() >= 0.0 && estimate.mu0() <= 1.0);
                assertTrue(estimate.mu1() >= 0.0 && estimate.mu1() <= 1.0);
                assertEquals(estimate.mu1() - estimate.mu0(), estimate.delta(), 1e-12);
                assertEquals("a7-20260901", estimate.modelVersion());
            }
        } finally {
            service.close();
        }
    }

    @Test
    void scoringSwitchKeepsBaselinePathUntouched() {
        AdProperties props = new AdProperties();
        UpliftService service = new UpliftService(props, new RelevanceGate(props));
        assertTrue(service.estimate(1L, List.of(
                new AdCandidate(1L, 1L, 1L, 0L, 1.0, AdChannel.HOT_AD, 1.0, 1.0)),
                Map.of(), Map.of()).isEmpty());
    }
}
