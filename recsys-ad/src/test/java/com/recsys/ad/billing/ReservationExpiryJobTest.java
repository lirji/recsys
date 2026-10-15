package com.recsys.ad.billing;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.TaskScheduler;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThat;

/** worker 的批次、退避与开关测试；真实竞争和回滚仍由 PG 测试证明。 */
@Tag("money-chain")
class ReservationExpiryJobTest {
    @Test
    void eachTickIsBoundedAndCountsOnlyCompletedExpirations() {
        var service = mock(ReservationService.class);
        when(service.expireOne()).thenReturn(true);
        var registry = new SimpleMeterRegistry();
        var job = new ReservationExpiryJob(service, registry, mock(TaskScheduler.class), Duration.ofSeconds(1), 3);
        job.tick();
        verify(service, times(3)).expireOne();
        assertEquals(3, registry.get("ad.billing.reservation.expired").counter().count());
    }

    @Test
    void failureStopsBatchAndBackoffPreventsImmediateDatabaseStorm() {
        var service = mock(ReservationService.class);
        when(service.expireOne()).thenThrow(new IllegalStateException("test database unavailable"));
        var registry = new SimpleMeterRegistry();
        var job = new ReservationExpiryJob(service, registry, mock(TaskScheduler.class), Duration.ofSeconds(1), 100);
        job.tick();
        job.tick();
        verify(service, times(1)).expireOne();
        assertEquals(1, registry.get("ad.billing.reservation.expiry.failures").counter().count());
        assertEquals(0, registry.get("ad.billing.reservation.expired").counter().count());
    }

    @Test
    void sandboxOffCreatesNoSchedulerOrReservationBeans() {
        new ApplicationContextRunner().withUserConfiguration(ReservationConfiguration.class).run(context ->
                assertThat(context).hasNotFailed().doesNotHaveBean(ReservationService.class)
                        .doesNotHaveBean(ReservationExpiryJob.class).doesNotHaveBean("adReservationScheduler"));
    }

    @Test
    void unboundedBatchAndInvalidIntervalAreRejected() {
        var config = new ReservationConfiguration();
        assertThrows(IllegalArgumentException.class, () -> config.reservationExpiryJob(null, null, null, 1, 100));
        assertThrows(IllegalArgumentException.class, () -> config.reservationExpiryJob(null, null, null, 1000, 101));
    }

    @Test
    void realSingleThreadSchedulerExecutesAndCanStop() throws Exception {
        var service = mock(ReservationService.class);
        var invoked = new java.util.concurrent.CountDownLatch(1);
        when(service.expireOne()).thenAnswer(call -> { invoked.countDown(); return false; });
        var scheduler = new ReservationConfiguration().adReservationScheduler();
        scheduler.initialize();
        var job = new ReservationExpiryJob(service, new SimpleMeterRegistry(), scheduler, Duration.ofSeconds(10), 1);
        try {
            job.start();
            assertTrue(invoked.await(2, java.util.concurrent.TimeUnit.SECONDS));
            job.stop();
            verify(service, times(1)).expireOne();
        } finally {
            job.stop();
            scheduler.shutdown();
        }
    }
}
