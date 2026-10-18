package com.recsys.recall;

import com.recsys.common.recall.*;
import com.recsys.recall.channel.*;
import com.recsys.recall.execution.RecallExecution;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 以不响应中断的慢调用验证真实资源占用，不能仅断言Future已cancel。 */
@Tag("recall-resilience")
class RecallResourceTest {
    RecallProperties properties;
    SimpleMeterRegistry meters;
    RecallExecution execution;
    CountDownLatch release;
    @BeforeEach void setup() {
        properties = new RecallProperties(); var p = properties.getParallel();
        p.setPoolSize(2); p.setQueueCapacity(1); p.setFastPoolSize(1); p.setFastQueueCapacity(1);
        p.setPrefetchPoolSize(1); p.setPrefetchQueueCapacity(1); p.setMaxRequests(4);
        p.setTimeoutMs(80); p.setTotalTimeoutMs(80); p.setPrefetchTimeoutMs(20);
        meters = new SimpleMeterRegistry(); execution = new RecallExecution(properties, meters); release = new CountDownLatch(1);
    }
    @AfterEach void close() { release.countDown(); execution.close(); meters.close(); }
    ChannelRecaller channel(RecallChannel name, Function<RecallContext, List<RecallItem>> call) {
        return new ChannelRecaller() {
            public RecallChannel channel() { return name; }
            public List<RecallItem> recall(RecallContext ctx) { return call.apply(ctx); }
        };
    }
    List<RecallItem> slow(RecallContext ctx) {
        boolean interrupted = false;
        while (release.getCount() > 0) try { release.await(); } catch (InterruptedException ignored) { interrupted = true; }
        if (interrupted) Thread.currentThread().interrupt();
        return List.of(new RecallItem(99, 1, RecallChannel.VECTOR));
    }
    MultiChannelRecallService service(List<ChannelRecaller> channels, RecentPositiveItemsSource seeds) {
        return new MultiChannelRecallService(channels, properties, seeds, mock(BehaviorSequenceSource.class), execution);
    }
    @Test void oneDeadlinePreservesReadyHotAndHotUserCannotRefillCancelledButRunningSlots() throws Exception {
        var slowCalls = new AtomicInteger();
        var service = service(List.of(channel(RecallChannel.VECTOR, ctx -> { slowCalls.incrementAndGet(); return slow(ctx); }),
                channel(RecallChannel.SEMANTIC, this::slow), channel(RecallChannel.GRAPH, this::slow),
                channel(RecallChannel.HOT, ctx -> List.of(new RecallItem(7, 2, RecallChannel.HOT)))), mock(RecentPositiveItemsSource.class));
        long begin = System.nanoTime(); var result = service.recall(new RecallContext(1, 10, "load"));
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin) < 250, "等待不能按3条慢路逐项累加");
        assertEquals(List.of(7L), result.stream().map(RecallItem::itemId).toList());
        assertEquals(1, execution.activeRequests()); assertEquals(2, execution.slow.getActiveCount());
        assertEquals(0, execution.slow.getQueue().size(), "被取消的队列任务应真实移除");
        for (int i = 0; i < 100; i++) assertTrue(service.recall(new RecallContext(1, 10, "load")).isEmpty());
        assertEquals(1, slowCalls.get());
        var latencies = new ArrayList<Long>();
        for (int i = 2; i < 202; i++) {
            long started = System.nanoTime();
            assertEquals(7L, service.recall(new RecallContext(i, 10, "normal", List.of(RecallChannel.HOT), Map.of())).getFirst().itemId());
            latencies.add(TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - started));
        }
        latencies.sort(Long::compare);
        assertTrue(latencies.get(197) < 200_000);
        assertEquals(100, meters.counter("recsys.recall.execution", "outcome", "user_rejected").count());
        org.slf4j.LoggerFactory.getLogger(getClass()).info("C20 mixed-load normal=200 hotspotRejected=100 p95us={} p99us={} slowActive={} queue={}",
                latencies.get(189), latencies.get(197), execution.slow.getActiveCount(), execution.slow.getQueue().size());
        release.countDown(); await(() -> execution.activeRequests() == 0);
        assertFalse(service.recall(new RecallContext(1, 10, "normal", List.of(RecallChannel.HOT), Map.of())).isEmpty());
    }
    @Test void prefetchTimeoutSuppliesEmptySeedAndDoesNotTriggerChannelDatabaseFanout() {
        var source = mock(RecentPositiveItemsSource.class);
        when(source.recentPositiveItems(anyLong(), anyInt())).thenAnswer(invocation -> { slow(null); return List.of(42L); });
        var service = service(List.of(channel(RecallChannel.I2I, ctx -> {
            assertEquals(List.of(), ctx.recentPositiveItems()); return List.of(new RecallItem(8, 1, RecallChannel.I2I));
        })), source);
        assertEquals(8L, service.recall(new RecallContext(1, 10, "test")).getFirst().itemId());
        verify(source, times(1)).recentPositiveItems(anyLong(), anyInt());
        assertEquals(1, execution.activeRequests());
    }
    @Test void serialModeStillHasOneDeadlineAndInterruptIsPreserved() throws Exception {
        properties.getParallel().setEnabled(false);
        var service = service(List.of(channel(RecallChannel.VECTOR, this::slow), channel(RecallChannel.HOT, ctx -> List.of())), mock(RecentPositiveItemsSource.class));
        long begin = System.nanoTime(); assertTrue(service.recall(new RecallContext(1, 10, "test")).isEmpty());
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin) < 250);
        AtomicBoolean interrupted = new AtomicBoolean();
        var worker = new Thread(() -> { Thread.currentThread().interrupt(); service.recall(new RecallContext(2, 10, "test")); interrupted.set(Thread.currentThread().isInterrupted()); });
        worker.start(); worker.join(1000); assertFalse(worker.isAlive()); assertTrue(interrupted.get());
    }
    @Test void fullAdmissionAndQueueRejectExplicitlyThenRecover() throws Exception {
        var requests = new ArrayList<RecallExecution.Request>();
        for (int i = 0; i < 4; i++) { var request = execution.admit(i); assertNotNull(request); requests.add(request); }
        assertNull(execution.admit(10));
        // activeCount包含beforeExecute；只有业务实际进入后，取消才应保留在途槽。
        var entered = new CountDownLatch(1);
        var first = execution.submit(execution.fast, requests.getFirst(), System.nanoTime() + TimeUnit.SECONDS.toNanos(10), () -> { entered.countDown(); return slow(null); });
        assertTrue(entered.await(1, TimeUnit.SECONDS));
        var queued = execution.submit(execution.fast, requests.get(1), System.nanoTime() + TimeUnit.SECONDS.toNanos(10), List::of);
        assertNull(execution.submit(execution.fast, requests.get(2), System.nanoTime() + TimeUnit.SECONDS.toNanos(10), List::of));
        queued.cancel(true); assertEquals(0, execution.fast.getQueue().size());
        first.cancel(true); requests.forEach(RecallExecution.Request::close); assertEquals(1, execution.activeRequests());
        release.countDown(); await(() -> execution.activeRequests() == 0);
    }
    @Test void completedChannelsRetainNormalizationRrfAndSourcePriority() {
        // 本例验证成功融合；饱和拒绝由独立用例验证，给两条成功通道足够队列容量。
        execution.close(); properties.getParallel().setFastQueueCapacity(2);
        execution = new RecallExecution(properties, meters);
        var service = service(List.of(
                channel(RecallChannel.HOT, ctx -> List.of(new RecallItem(7, 100, RecallChannel.HOT), new RecallItem(8, 50, RecallChannel.HOT))),
                channel(RecallChannel.TAG, ctx -> List.of(new RecallItem(8, 4, RecallChannel.TAG)))), mock(RecentPositiveItemsSource.class));
        var normal = service.recall(new RecallContext(11, 10, "normal"));
        assertEquals(List.of(7L, 8L), normal.stream().map(RecallItem::itemId).toList());
        assertEquals(1, normal.get(0).recallScore()); assertEquals(1, normal.get(1).recallScore());
        assertEquals(RecallChannel.TAG, normal.get(1).channel());
        assertEquals(List.of(RecallChannel.TAG, RecallChannel.HOT), normal.get(1).channels());
        var rrf = service.recall(new RecallContext(12, 10, "search", List.of(), Map.of("recall-fusion", "rrf")));
        assertTrue(rrf.get(0).recallScore() < rrf.get(1).recallScore()); assertEquals(1, rrf.get(1).recallScore());
    }

    private static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean()) { assertTrue(System.nanoTime() < end); Thread.sleep(5); }
    }
}
