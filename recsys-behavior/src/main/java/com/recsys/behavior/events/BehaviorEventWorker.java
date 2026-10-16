package com.recsys.behavior.events;

import com.recsys.behavior.events.BehaviorEventRepository.Destination;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import java.util.Map;

/** 一条流独占一个线程和producer；整体两流最多两个，不使用公共调度器。 */
public class BehaviorEventWorker implements AutoCloseable {
    private final ThreadPoolTaskScheduler scheduler=new ThreadPoolTaskScheduler();
    private final BehaviorKafkaPublisher publisher;
    private final BehaviorEventJob job;
    /** schema已由装配前置验证；连接安全属性沿宿主配置。 */
    public BehaviorEventWorker(BehaviorEventRepository repository, Destination destination, String topic,
                               Map<String,Object> producerProperties, MeterRegistry metrics) {
        BehaviorEventConfiguration.validateTopic(destination,topic);
        publisher=new BehaviorKafkaPublisher(new KafkaProducer<>(producerProperties),destination,topic);
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("behavior-outbox-"+destination.name().toLowerCase(java.util.Locale.ROOT)+"-");
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        scheduler.setAwaitTerminationSeconds(10);
        job=new BehaviorEventJob(repository,new BehaviorEventRelay(repository,publisher,new BehaviorEventCodec(),metrics,destination),scheduler,metrics,destination);
    }
    /** 初始化只启动一条固定延迟任务；失败时关闭已创建网络资源。 */
    public void start() {
        try { scheduler.initialize(); job.start(); }
        catch (RuntimeException ex) { close(); throw ex; }
    }
    /** 停新领、中断未决，租约保留；等待和producer关闭均有上限。 */
    @Override public void close() { job.stop(); scheduler.shutdown(); publisher.close(); }
}
