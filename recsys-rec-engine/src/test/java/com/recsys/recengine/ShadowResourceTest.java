package com.recsys.recengine;

import com.recsys.common.rank.RankedItem;
import com.recsys.rank.RankRouter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.context.ContextRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import org.junit.jupiter.api.*;
import org.slf4j.MDC;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真实worker/排队/定时取消与上下文；模拟不响应中断的模型，不能用Future状态冒充释放。 */
@Tag("recall-resilience")
class ShadowResourceTest {
    final RankRouter router = mock(RankRouter.class);
    final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    final RecEngineProperties properties = new RecEngineProperties();
    final CountDownLatch release = new CountDownLatch(1);
    ShadowRankRunner runner;
    @BeforeEach void config() {
        var c = properties.getShadow(); c.setEnabled(true); c.setStrategy("deepfm"); c.setSampleRate(1);
        c.setThreads(1); c.setQueueCapacity(1); c.setTimeoutMs(150);
    }
    @AfterEach void close() { release.countDown(); if (runner != null) runner.shutdown(); meters.close(); MDC.clear(); }
    void start() { runner = new ShadowRankRunner(router, meters, properties); }
    void submit(long id) { runner.maybeShadow(id, List.of(7L), "test", "v1", List.of(new RankedItem(7, 1, Map.of()))); }
    double count(String result) { var c = meters.find("recsys.rank.shadow.tasks").tag("outcome", result).counter(); return c == null ? 0 : c.count(); }
    void block() {
        boolean interrupted = false;
        while (release.getCount() > 0) try { release.await(); } catch (InterruptedException expected) { interrupted = true; }
        if (interrupted) Thread.currentThread().interrupt();
    }
    @Test void saturationAndTimeoutRetainActualWorkerRemoveQueueAndNeverPublishLateSuccess() throws Exception {
        var entered = new CountDownLatch(1); var calls = new AtomicInteger();
        when(router.rank(anyLong(), anyList(), anyString(), anyString())).thenAnswer(inv -> { calls.incrementAndGet(); entered.countDown(); block(); return List.of(); });
        start(); submit(1); assertTrue(entered.await(1, TimeUnit.SECONDS)); submit(2);
        long begin = System.nanoTime(); for (int i = 3; i < 203; i++) submit(i);
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin) < 250);
        assertEquals(200, count("saturated"));
        await(() -> count("timeout") == 2);
        assertEquals(1, meters.get("recsys.rank.shadow.inflight").gauge().value());
        assertEquals(1, meters.get("recsys.executor.active").tag("pool", "shadow-rank").gauge().value());
        assertEquals(0, meters.get("recsys.executor.queued").tag("pool", "shadow-rank").gauge().value());
        submit(1); assertEquals(1, count("user_busy")); assertEquals(1, calls.get());
        release.countDown(); await(() -> meters.get("recsys.rank.shadow.inflight").gauge().value() == 0);
        assertEquals(0, count("success")); submit(3); await(() -> count("success") == 1);
    }
    @Test void queuedInputSnapshotAndObservationMdcAreRestoredAcrossWorkers() throws Exception {
        var entered = new CountDownLatch(1); var seen = new AtomicReference<List<Long>>();
        var observationRegistry = ObservationRegistry.create();
        observationRegistry.observationConfig().observationHandler(new ObservationHandler<Observation.Context>() {
            public boolean supportsContext(Observation.Context context) { return true; }
        });
        var accessor = new ObservationThreadLocalAccessor(observationRegistry);
        ContextRegistry.getInstance().registerThreadLocalAccessor(accessor);
        Observation observation = Observation.start("shadow-test", observationRegistry);
        try {
            when(router.rank(anyLong(), anyList(), anyString(), anyString())).thenAnswer(inv -> {
                long user = inv.getArgument(0);
                if (user == 1) { entered.countDown(); block(); }
                if (user == 2) {
                    assertEquals("captured", MDC.get("request")); assertSame(observation, observationRegistry.getCurrentObservation());
                    seen.set(inv.getArgument(1));
                }
                if (user == 3) { assertNull(MDC.get("request")); assertNull(observationRegistry.getCurrentObservation()); }
                return List.of(new RankedItem(7, 1, Map.of()));
            });
            properties.getShadow().setTimeoutMs(1000); start(); submit(1); assertTrue(entered.await(1, TimeUnit.SECONDS));
            var candidates = new ArrayList<>(List.of(7L));
            try (var scope = observation.openScope()) {
                MDC.put("request", "captured"); runner.maybeShadow(2, candidates, "test", "v1", List.of());
            }
            MDC.clear(); candidates.set(0, 99L); release.countDown();
            await(() -> count("success") == 2); assertEquals(List.of(7L), seen.get());
            submit(3); await(() -> count("success") == 3);
        } finally { observation.stop(); ContextRegistry.getInstance().removeThreadLocalAccessor((String) accessor.key()); }
    }
    @Test void disabledSamplingInputLimitsAndFailureDoNotExecuteOnCaller() throws Exception {
        start(); properties.getShadow().setEnabled(false); submit(1);
        properties.getShadow().setEnabled(true); properties.getShadow().setSampleRate(0); submit(1);
        properties.getShadow().setSampleRate(1); properties.getShadow().setStrategy("v1"); submit(1);
        verifyNoInteractions(router); properties.getShadow().setStrategy("deepfm");
        runner.maybeShadow(1, Collections.nCopies(513, 7L), "test", "v1", List.of()); assertEquals(1, count("oversize"));
        runner.maybeShadow(1, Arrays.asList(7L, null), "test", "v1", List.of()); assertEquals(1, count("invalid_input"));
        when(router.rank(anyLong(), anyList(), anyString(), anyString())).thenAnswer(inv -> {
            assertTrue(Thread.currentThread().getName().startsWith("shadow-rank-")); throw new IllegalStateException("model failure");
        });
        submit(1); await(() -> count("failed") == 1); assertEquals(0, count("success"));
    }
    @Test void shutdownCancelsQueuedWorkWithoutForcingActiveSlotRelease() throws Exception {
        var entered = new CountDownLatch(1);
        when(router.rank(anyLong(), anyList(), anyString(), anyString())).thenAnswer(inv -> { entered.countDown(); block(); return List.of(); });
        properties.getShadow().setTimeoutMs(1000); start(); submit(1); assertTrue(entered.await(1, TimeUnit.SECONDS)); submit(2);
        runner.shutdown(); assertEquals(2, count("closed")); submit(3); assertEquals(3, count("closed"));
        assertEquals(1, meters.get("recsys.rank.shadow.inflight").gauge().value());
        release.countDown(); await(() -> meters.get("recsys.rank.shadow.inflight").gauge().value() == 0);
        verify(router, times(1)).rank(anyLong(), anyList(), anyString(), anyString());
    }
    @Test void invalidBudgetsFailAtConstruction() {
        properties.getShadow().setThreads(3);
        assertThrows(IllegalArgumentException.class, () -> new ShadowRankRunner(router, meters, properties));
        properties.getShadow().setThreads(1); properties.getShadow().setSampleRate(Double.NaN);
        assertThrows(IllegalArgumentException.class, () -> new ShadowRankRunner(router, meters, properties));
        properties.getShadow().setSampleRate(1); properties.getShadow().setK(0);
        assertThrows(IllegalArgumentException.class, () -> new ShadowRankRunner(router, meters, properties));
    }
    static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean()) { assertTrue(System.nanoTime() < until); Thread.sleep(5); }
    }
}
