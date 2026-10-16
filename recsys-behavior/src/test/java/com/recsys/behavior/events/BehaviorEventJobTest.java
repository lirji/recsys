package com.recsys.behavior.events;

import com.recsys.behavior.persistence.mapper.primary.BehaviorEventMapper.Backlog;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.springframework.scheduling.TaskScheduler;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 只用替身验证 worker 资源预算；数据库与 broker 保证由真实集成测试验证。 */
@Tag("behavior-reliability")
class BehaviorEventJobTest {
    @Test void boundsBatchSamplingAndStopWithoutInventingZeroBacklog() {
        var repo = mock(BehaviorEventRepository.class); var relay = mock(BehaviorEventRelay.class);
        var scheduler = mock(TaskScheduler.class); var metrics = new SimpleMeterRegistry(); var clock = new AtomicLong(1);
        var job = new BehaviorEventJob(repo, relay, scheduler, metrics, BehaviorEventRepository.Destination.LIVE, clock::get);
        when(relay.relayOne()).thenReturn(true); when(repo.backlog(BehaviorEventRepository.Destination.LIVE)).thenReturn(new Backlog(100, 2, 12));
        assertTrue(Double.isNaN(metrics.get("behavior.events.outbox.pending").gauge().value()));
        job.start(); job.start(); job.tick();
        verify(scheduler, times(1)).scheduleWithFixedDelay(any(Runnable.class), eq(Duration.ofSeconds(1)));
        verify(relay, times(10)).relayOne(); verify(repo, times(1)).exhaust(BehaviorEventRepository.Destination.LIVE); verify(repo, times(1)).backlog(BehaviorEventRepository.Destination.LIVE);
        job.tick(); verify(relay, times(20)).relayOne(); verify(repo, times(1)).backlog(BehaviorEventRepository.Destination.LIVE);
        assertEquals(100, metrics.get("behavior.events.outbox.pending").gauge().value());
        job.stop(); job.tick(); verify(relay, times(20)).relayOne();
    }

    @Test void databaseFailureBacksOffAndStopsBatchAndObservationFailureIsUnknown() {
        var repo = mock(BehaviorEventRepository.class); var relay = mock(BehaviorEventRelay.class);
        var clock = new AtomicLong(1); var metrics = new SimpleMeterRegistry();
        var job = new BehaviorEventJob(repo, relay, mock(TaskScheduler.class), metrics, BehaviorEventRepository.Destination.LIVE, clock::get);
        when(repo.exhaust(BehaviorEventRepository.Destination.LIVE)).thenThrow(new IllegalStateException("isolated fault")).thenReturn(0);
        job.start(); job.tick(); job.tick(); verify(repo, times(1)).exhaust(BehaviorEventRepository.Destination.LIVE); verifyNoInteractions(relay);
        clock.addAndGet(Duration.ofSeconds(5).toNanos()); when(repo.backlog(BehaviorEventRepository.Destination.LIVE)).thenThrow(new IllegalStateException());
        job.tick(); assertTrue(Double.isNaN(metrics.get("behavior.events.outbox.pending").gauge().value()));
        clock.addAndGet(Duration.ofSeconds(5).toNanos()); job.tick(); verify(repo, times(1)).backlog(BehaviorEventRepository.Destination.LIVE);
        assertEquals(2, metrics.get("behavior.events.outbox.worker_failures").counter().count());
        job.stop();
    }

    @Test void stopDuringBatchPreventsAdditionalClaimsAndBackoffIsBounded() {
        var repo = mock(BehaviorEventRepository.class); var relay = mock(BehaviorEventRelay.class);
        var job = new BehaviorEventJob(repo, relay, mock(TaskScheduler.class), new SimpleMeterRegistry(), BehaviorEventRepository.Destination.LIVE);
        when(relay.relayOne()).thenAnswer(call -> { job.stop(); return true; });
        job.start(); job.tick(); verify(relay, times(1)).relayOne(); verify(repo, never()).backlog(BehaviorEventRepository.Destination.LIVE);
        for (int n = 1; n <= 20; n++) for (int i = 0; i < 100; i++) {
            long delay = BehaviorEventRelay.retryDelay(n), cap = Math.min(60000, 1000L << Math.min(n - 1, 6));
            assertTrue(delay >= Math.max(1000, cap / 2) && delay <= cap);
        }
    }
}
