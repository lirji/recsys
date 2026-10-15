package com.recsys.ad.billing;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.*;
import org.springframework.scheduling.TaskScheduler;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 只验证调度与重试资源边界，资金/投影事务已有真实数据库和 broker 证据。 */
@Tag("money-chain")
class BillingEventWorkerTest {
    @Test void relayTickIsBoundedAndDatabaseFailureBacksOff() {
        var repo=mock(BillingOutboxRepository.class);var relay=mock(BillingOutboxRelay.class);
        when(repo.backlog()).thenReturn(new BillingOutboxRepository.Backlog(1,0,3));
        when(relay.relayOne()).thenReturn(true);
        var metrics=new SimpleMeterRegistry();
        var job=new BillingOutboxJob(repo,relay,mock(TaskScheduler.class),metrics);
        job.tick();verify(relay,times(10)).relayOne();
        when(relay.relayOne()).thenThrow(new IllegalStateException("db"));
        job.tick();job.tick();
        verify(relay,times(11)).relayOne();
        assertEquals(1,metrics.counter("ad.billing.outbox.worker.failures").count());
    }
    @Test void repeatedProjectionFailureStopsWithoutCommittingOffset() throws Exception {
        @SuppressWarnings("unchecked") Consumer<String,String> kafka=mock(Consumer.class);
        var record=new ConsumerRecord<>("topic",0,0,"key","payload");
        when(kafka.poll(any(Duration.class))).thenReturn(new ConsumerRecords<>(Map.of(new TopicPartition("topic",0),List.of(record))));
        var projection=mock(BillingProjectionService.class);
        when(projection.apply(anyString(),anyString())).thenThrow(new IllegalStateException("db"));
        var metrics=new SimpleMeterRegistry();
        var worker=new BillingProjectionConsumer(()->kafka,projection,"topic",metrics);
        worker.start();
        try {
            verify(projection,timeout(6000).times(3)).apply("key","payload");
            verify(kafka,timeout(2000)).close(Duration.ofSeconds(2));
            verify(kafka,never()).commitSync(anyMap(),any(Duration.class));
            assertFalse(worker.isRunning());
        } finally {worker.stop();}
    }
    @Test void shutdownWakesPollAndClosesWithinBoundedWait() throws Exception {
        @SuppressWarnings("unchecked") Consumer<String,String> kafka=mock(Consumer.class);
        var wakeup=new java.util.concurrent.CountDownLatch(1);
        when(kafka.poll(any(Duration.class))).thenAnswer(i->{wakeup.await(2,java.util.concurrent.TimeUnit.SECONDS);return ConsumerRecords.empty();});
        doAnswer(i->{wakeup.countDown();return null;}).when(kafka).wakeup();
        var worker=new BillingProjectionConsumer(()->kafka,mock(BillingProjectionService.class),"topic",new SimpleMeterRegistry());
        worker.start();verify(kafka,timeout(2000)).poll(any(Duration.class));
        worker.stop();verify(kafka).wakeup();verify(kafka).close(Duration.ofSeconds(2));
        assertFalse(worker.isRunning());
    }
}
