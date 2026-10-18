package com.recsys.recall.execution;

import io.micrometer.context.ContextRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import org.junit.jupiter.api.*;
import org.slf4j.MDC;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** 传播真实Observation而不是只复制TraceId字符串，事务资源不进入工作线程。 */
@Tag("recall-resilience")
class RecallContextTest {
    @Test void observationMdcAndNestedContextRestoreOnReuseAndExceptionWithoutTransactionResources() throws Exception {
        var registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new io.micrometer.observation.ObservationHandler<Observation.Context>() {
            public boolean supportsContext(Observation.Context context) { return true; }
        });
        var accessor = new ObservationThreadLocalAccessor(registry);
        ContextRegistry.getInstance().registerThreadLocalAccessor(accessor);
        var meters = new SimpleMeterRegistry(); var pool = RecallExecution.pool("context-test", 1, 1, meters);
        var observation = Observation.start("request", registry);
        Object key = new Object(); TransactionSynchronizationManager.bindResource(key, new Object()); MDC.put("userId", "first");
        try (var scope = observation.openScope()) {
            var future = pool.submit(RecallExecution.contextual(() -> {
                assertSame(observation, registry.getCurrentObservation()); assertEquals("first", MDC.get("userId"));
                assertFalse(TransactionSynchronizationManager.hasResource(key));
                var nested = RecallExecution.contextual(() -> { MDC.put("userId", "nested-change"); throw new IllegalArgumentException(); });
                assertThrows(IllegalArgumentException.class, nested::call); assertEquals("first", MDC.get("userId"));
                return 1;
            }));
            assertEquals(1, future.get(2, TimeUnit.SECONDS)); assertEquals("first", MDC.get("userId"));
        } finally {
            observation.stop(); TransactionSynchronizationManager.unbindResource(key); MDC.clear();
        }
        try {
            pool.submit(() -> { assertNull(MDC.get("userId")); assertNull(registry.getCurrentObservation()); }).get(2, TimeUnit.SECONDS);
            var empty = pool.submit(RecallExecution.contextual(() -> { assertNull(MDC.get("userId")); assertNull(registry.getCurrentObservation()); return 2; }));
            assertEquals(2, empty.get(2, TimeUnit.SECONDS));
        } finally { RecallExecution.stop(pool); meters.close(); ContextRegistry.getInstance().removeThreadLocalAccessor((String) accessor.key()); }
    }
}
