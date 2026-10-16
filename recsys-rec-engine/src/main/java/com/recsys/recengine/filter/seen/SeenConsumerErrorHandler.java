package com.recsys.recengine.filter.seen;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.listener.CommonContainerStoppingErrorHandler;
import org.springframework.kafka.listener.MessageListenerContainer;
import java.util.List;
import java.util.concurrent.*;

/** 无recoverer、无失败ACK；日志只携带固定码，停止线程与队列均有界。 */
public final class SeenConsumerErrorHandler extends CommonContainerStoppingErrorHandler implements AutoCloseable {
    private final SeenRecoveryRepository repository;
    private final ThreadPoolExecutor executor;
    private final java.util.Map<String,SeenStream> groups;
    /** 独立单线程及容量3队列隔离停止动作，不占公共异步执行器。 */
    public SeenConsumerErrorHandler(SeenRecoveryRepository repository) {this(repository,executor(),null);}
    /** 三流共享停止队列；只接受启动时核验过的group→stream映射。 */
    public SeenConsumerErrorHandler(SeenRecoveryRepository repository,java.util.Map<String,SeenStream> groups) {this(repository,executor(),java.util.Map.copyOf(groups));}
    private SeenConsumerErrorHandler(SeenRecoveryRepository repository,ThreadPoolExecutor executor,java.util.Map<String,SeenStream> groups) {super(executor);this.repository=repository;this.executor=executor;this.groups=groups;}
    private static ThreadPoolExecutor executor() {
        return new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(3),task->{
            Thread thread=new Thread(task,"seen-container-stop");thread.setDaemon(true);return thread;
        },new ThreadPoolExecutor.AbortPolicy());
    }
    /** 此handler永不确认失败消息，不接受外部放宽。 */
    @Override public boolean isAckAfterHandle() {return false;}
    @Override public void setAckAfterHandle(boolean ack) {if(ack)throw new IllegalArgumentException("Seen禁止失败ACK");}
    @Override public void handleRemaining(Exception exception,List<ConsumerRecord<?,?>> records,Consumer<?,?> consumer,MessageListenerContainer container) {
        stop(exception,consumer,container);
    }
    @Override public void handleOtherException(Exception exception,Consumer<?,?> consumer,MessageListenerContainer container,boolean batch) {
        stop(exception,consumer,container);
    }
    private void stop(Exception exception,Consumer<?,?> consumer,MessageListenerContainer container) {
        Throwable classified=exception;while(!(classified instanceof SeenRecord.Failure) && classified.getCause()!=null)classified=classified.getCause();
        var code=classified instanceof SeenRecord.Failure f?f.code():SeenRecord.Code.INTERNAL_ERROR;
        SeenStream identity=groups==null?SeenStream.LEGACY:groups.get(container.getContainerProperties().getGroupId());
        if(identity!=null)io.micrometer.core.instrument.Metrics.counter("recsys.seen.stops","stream",identity.name(),"reason",code.name()).increment();
        try {if(identity!=null)repository.stop(identity.name(),code);}catch(RuntimeException unavailable){org.slf4j.LoggerFactory.getLogger(SeenConsumerErrorHandler.class).warn("Seen停止标记未确认，仍继续停止容器");}
        try {super.handleOtherException(new SeenRecord.Failure(code),consumer,container,false);}
        catch(RejectedExecutionException saturated) {container.stopAbnormally(()->{});throw new SeenRecord.Failure(code);}
    }
    /** 关闭时不丢弃待执行停止任务。 */
    @Override public void close() {executor.shutdown();}
    /** 暴露固定资源预算供验收，不允许调用方扩大队列。 */
    public int queueCapacity() {return executor.getQueue().size()+executor.getQueue().remainingCapacity();}
}
