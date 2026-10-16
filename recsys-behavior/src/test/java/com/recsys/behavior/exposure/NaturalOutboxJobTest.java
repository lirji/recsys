package com.recsys.behavior.exposure;

import com.recsys.behavior.persistence.mapper.primary.NaturalOutboxMapper.Backlog;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.springframework.scheduling.TaskScheduler;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 只用替身验证 worker 资源预算；数据库与 broker 保证由真实集成测试验证。 */
@Tag("natural-exposure")
class NaturalOutboxJobTest {
    @Test void boundsBatchSamplingAndStopWithoutInventingZeroBacklog() {
        var repo = mock(NaturalOutboxRepository.class); var relay = mock(NaturalOutboxRelay.class);
        var scheduler = mock(TaskScheduler.class); var metrics = new SimpleMeterRegistry(); var clock = new AtomicLong(1);
        var job = new NaturalOutboxJob(repo, relay, scheduler, metrics, clock::get);
        when(relay.relayOne()).thenReturn(true); when(repo.backlog()).thenReturn(new Backlog(100, 2, 12));
        assertTrue(Double.isNaN(metrics.get("natural.events.outbox.pending").gauge().value()));
        job.start(); job.start(); job.tick();
        verify(scheduler, times(1)).scheduleWithFixedDelay(any(Runnable.class), eq(Duration.ofSeconds(1)));
        verify(relay, times(10)).relayOne(); verify(repo, times(1)).exhaust(); verify(repo, times(1)).backlog();
        job.tick(); verify(relay, times(20)).relayOne(); verify(repo, times(1)).backlog();
        assertEquals(100, metrics.get("natural.events.outbox.pending").gauge().value());
        job.stop(); job.tick(); verify(relay, times(20)).relayOne();
    }

    @Test void databaseFailureBacksOffAndStopsBatchAndObservationFailureIsUnknown() {
        var repo = mock(NaturalOutboxRepository.class); var relay = mock(NaturalOutboxRelay.class);
        var clock = new AtomicLong(1); var metrics = new SimpleMeterRegistry();
        var job = new NaturalOutboxJob(repo, relay, mock(TaskScheduler.class), metrics, clock::get);
        when(repo.exhaust()).thenThrow(new IllegalStateException("isolated fault")).thenReturn(0);
        job.start(); job.tick(); job.tick(); verify(repo, times(1)).exhaust(); verifyNoInteractions(relay);
        clock.addAndGet(Duration.ofSeconds(5).toNanos()); when(repo.backlog()).thenThrow(new IllegalStateException());
        job.tick(); assertTrue(Double.isNaN(metrics.get("natural.events.outbox.pending").gauge().value()));
        clock.addAndGet(Duration.ofSeconds(5).toNanos()); job.tick(); verify(repo, times(1)).backlog();
        assertEquals(2, metrics.get("natural.events.outbox.worker_failures").counter().count());
        job.stop();
    }

    @Test void stopDuringBatchPreventsAdditionalClaimsAndBackoffIsBounded() {
        var repo = mock(NaturalOutboxRepository.class); var relay = mock(NaturalOutboxRelay.class);
        var job = new NaturalOutboxJob(repo, relay, mock(TaskScheduler.class), new SimpleMeterRegistry());
        when(relay.relayOne()).thenAnswer(call -> { job.stop(); return true; });
        job.start(); job.tick(); verify(relay, times(1)).relayOne(); verify(repo, never()).backlog();
        for (int n = 1; n <= 20; n++) for (int i = 0; i < 100; i++) {
            long delay = NaturalOutboxRelay.retryDelay(n), cap = Math.min(60000, 1000L << Math.min(n - 1, 6));
            assertTrue(delay >= Math.max(1000, cap / 2) && delay <= cap);
        }
    }
}
