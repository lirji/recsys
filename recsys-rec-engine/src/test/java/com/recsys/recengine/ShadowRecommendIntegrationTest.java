package com.recsys.recengine;

import com.recsys.common.dto.RecommendResponse;
import com.recsys.rank.RankRouter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 复用真实PG/Redis推荐fixture，在不响应中断的影子模型拥堵时测主HTTP及曝光落库。 */
@Tag("recall-resilience")
@SpringBootTest(properties = {"recsys.shadow.threads=1", "recsys.shadow.queue-capacity=1", "recsys.shadow.timeout-ms=150"})
@Testcontainers
class ShadowRecommendIntegrationTest extends RecommendIntegrationTest {
    @SpyBean RankRouter router;
    @Autowired ShadowRankRunner shadow;
    @Autowired RecEngineProperties properties;
    @Autowired MeterRegistry meters;
    @AfterEach void disableShadow() { properties.getShadow().setEnabled(false); }

    @Test void saturatedShadowStillAllowsRealRecommendationAndExposureWrites() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        doAnswer(inv -> {
            entered.countDown(); boolean interrupted = false;
            while (release.getCount() > 0) try { release.await(); } catch (InterruptedException expected) { interrupted = true; }
            if (interrupted) Thread.currentThread().interrupt(); return List.of();
        }).when(router).rank(anyLong(), anyList(), anyString(), eq("deepfm"));
        var cfg = properties.getShadow(); cfg.setStrategy("deepfm"); cfg.setSampleRate(1); cfg.setEnabled(true);
        try {
            shadow.maybeShadow(-1, List.of(1L), "test", "v1", List.of());
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            for (long user = -202; user < -1; user++) shadow.maybeShadow(user, List.of(1L), "test", "v1", List.of());
            var latencies = new ArrayList<Long>(); int expectedExposures = 0;
            for (int i = 0; i < 50; i++) {
                long begin = System.nanoTime();
                var result = mockMvc.perform(get("/api/recommend").param("userId", String.valueOf(1000 + i)).param("size", "5"))
                        .andExpect(status().isOk()).andReturn();
                var response = objectMapper.readValue(result.getResponse().getContentAsString(), RecommendResponse.class);
                assertFalse(response.items().isEmpty()); expectedExposures += response.items().size();
                latencies.add(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin));
            }
            latencies.sort(Long::compare); assertTrue(latencies.get(47) < 1000, "隔离测试主HTTP P95应小于1秒调度余量");
            assertEquals(1, meters.get("recsys.executor.active").tag("pool", "shadow-rank").gauge().value());
            assertTrue(meters.get("recsys.executor.queued").tag("pool", "shadow-rank").gauge().value() <= 1);
            assertTrue(meters.get("recsys.rank.shadow.tasks").tag("outcome", "saturated").counter().count() > 0);
            int expected = expectedExposures;
            ShadowResourceTest.await(() -> jdbc.queryForObject("SELECT count(*) FROM user_behavior WHERE user_id BETWEEN 1000 AND 1049 AND action='IMPRESSION'", Integer.class) == expected);
            org.slf4j.LoggerFactory.getLogger(getClass()).info("C21 mixed HTTP requests=50 shadowFlood=201 primaryP95ms={} primaryP99ms={} persistedExposures={} shadowActive=1 queue<=1",
                    latencies.get(47), latencies.get(49), expected);
        } finally {
            cfg.setEnabled(false); release.countDown();
            ShadowResourceTest.await(() -> meters.get("recsys.rank.shadow.inflight").gauge().value() == 0);
        }
    }
}
