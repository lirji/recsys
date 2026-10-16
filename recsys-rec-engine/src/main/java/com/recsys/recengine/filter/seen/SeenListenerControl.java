package com.recsys.recengine.filter.seen;

import com.recsys.recengine.filter.SeenItemsConsumer;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.SmartLifecycle;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.listener.*;
import java.util.Collection;

/** 所有启动/恢复入口核查全流；恢复一条记录不能释放另一条PAUSED义务。 */
public final class SeenListenerControl implements SmartLifecycle {
    private final ConcurrentMessageListenerContainer<byte[],byte[]> container;
    private final SeenRecoveryRepository repository;
    private final SeenOffsetInitializer offsets;
    private final boolean auto;
    /** 容器不自行启动，所有启动路径均经本控制器。 */
    public SeenListenerControl(ConcurrentKafkaListenerContainerFactory<byte[],byte[]> factory,SeenItemsConsumer listener,
                               SeenRecoveryRepository repository,SeenOffsetInitializer offsets,String topic,String group,boolean auto) {
        this.repository=repository;this.offsets=offsets;this.auto=auto;
        container=factory.createContainer(topic);container.setBeanName("seen-legacy");container.setAutoStartup(false);
        container.getContainerProperties().setGroupId(group);
        container.setupMessageListener((AcknowledgingConsumerAwareMessageListener<byte[],byte[]>)(record,ack,consumer)->listener.onEvent(record,ack,consumer,container));
        container.getContainerProperties().setConsumerRebalanceListener(new ConsumerAwareRebalanceListener() {
            @Override public void onPartitionsAssigned(Consumer<?,?> consumer,Collection<TopicPartition> partitions) {
                offsets.validate();if(repository.blocked("LEGACY"))container.pause();
            }
            // revoke/lost不flush未确认记录；旧调用必须依赖租约/fence与consumer线程确认。
        });
    }
    /** 运行开关只请求启动，不能绕过持久STOP或身份/位点检查。 */
    @Override public synchronized void start() {
        offsets.validate();
        var control=repository.control("LEGACY");
        if(control.stopReason()!=null)throw new SeenRecord.Failure(SeenRecord.Code.STREAM_STOPPED);
        if(repository.blocked("LEGACY"))container.pause();
        container.start();
    }
    /** 必须先显式恢复全部记录，才可恢复整个流。 */
    public synchronized void resume() {
        offsets.validate();
        if(repository.blocked("LEGACY"))throw new SeenRecord.Failure(SeenRecord.Code.STREAM_PAUSED);
        if(!container.isRunning())start();
        container.resume();
    }
    /** 只停止领取，未完成记录和租约保留供恢复。 */
    @Override public void stop() {container.stop();}
    /** 宿主关闭回调沿容器有界停机，不在finally确认消息。 */
    @Override public void stop(Runnable callback) {container.stop(callback);}
    /** running只表示消费生命周期，不表示历史集合完整。 */
    @Override public boolean isRunning() {return container.isRunning();}
    /** 默认关闭不会因有容器bean而开始消费。 */
    @Override public boolean isAutoStartup() {return auto;}
    /** 等依赖装配完再启动，关闭时先停消费者。 */
    @Override public int getPhase() {return Integer.MAX_VALUE-100;}
    /** 请求暂停也应对运维可见，不等待下一次poll才报状态。 */
    public boolean isPaused() {return container.isPauseRequested();}
    /** 分配信息用于技术验证，不作为处理完成水位。 */
    public Collection<TopicPartition> assigned() {return container.getAssignedPartitions();}
}
