package com.recsys.ad.billing;

import io.micrometer.core.instrument.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.slf4j.*;
import java.time.Duration;
import java.util.*;
import java.util.function.Supplier;

/** 单线程手动确认；事务/位点窗口靠幂等恢复，毒消息或反复故障停止而非跳过。 */
public class BillingProjectionConsumer {
    private static final Logger log=LoggerFactory.getLogger(BillingProjectionConsumer.class);
    private static final int MAX_ATTEMPTS=3;
    private final Supplier<Consumer<String,String>> factory;
    private final BillingProjectionService projection;
    private final String topic;
    private final Counter applied,duplicates,failures;
    private volatile Consumer<String,String> consumer;
    private volatile boolean stopping,running;
    private Thread thread;
    public BillingProjectionConsumer(Supplier<Consumer<String,String>> factory,BillingProjectionService projection,String topic,MeterRegistry metrics) {
        this.factory=factory;this.projection=projection;this.topic=topic;
        applied=metrics.counter("ad.billing.projection.applied");duplicates=metrics.counter("ad.billing.projection.duplicate");
        failures=metrics.counter("ad.billing.projection.failures");
        metrics.gauge("ad.billing.projection.running",this,c->c.running?1:0);
    }
    /** 专用线程仅消费一个工作循环，不创建无界异步任务。 */
    public synchronized void start() {
        if(thread!=null) throw new IllegalStateException("消费者不可重复启动");
        thread=new Thread(this::run,"ad-billing-projection");thread.setDaemon(true);thread.start();
    }
    /** wakeup 是 Kafka 允许的跨线程动作；关闭和位点操作仍留在消费线程。 */
    public void stop() {
        stopping=true;
        Consumer<String,String> current=consumer;
        if(current!=null)current.wakeup();
        if(thread!=null)try { thread.join(10000); } catch(InterruptedException ex){Thread.currentThread().interrupt();}
    }
    public boolean isRunning() { return running; }

    private void run() {
        Consumer<String,String> owned=null;
        try {
            owned=factory.get();
            consumer=owned;
            if(stopping)return;
            owned.subscribe(List.of(topic));running=true;
            int pollFailures=0;
            while(!stopping) {
                ConsumerRecords<String,String> records;
                try { records=owned.poll(Duration.ofMillis(500));pollFailures=0; }
                catch(WakeupException ex) { throw ex; }
                catch(Exception ex) {
                    failures.increment();
                    if(++pollFailures>=MAX_ATTEMPTS)throw ex;
                    pause(pollFailures);continue;
                }
                for(var record:records) {
                    if(stopping)return;
                    boolean success=false;
                    for(int attempt=1;attempt<=MAX_ATTEMPTS && !stopping;attempt++) {
                        try { process(owned,record);success=true;break; }
                        catch(BillingEventCodec.InvalidEvent ex) {
                            log.error("sandbox projection invalid event topic={} partition={} offset={}",record.topic(),record.partition(),record.offset());
                            throw ex;
                        }
                        catch(Exception ex) {
                            failures.increment();
                            if(attempt==MAX_ATTEMPTS) {
                                log.error("sandbox projection retries exhausted topic={} partition={} offset={}",record.topic(),record.partition(),record.offset());
                                throw ex;
                            }
                            pause(attempt);
                        }
                    }
                    if(!success)return;
                }
            }
        } catch(WakeupException ex) {
            if(!stopping){failures.increment();log.error("sandbox projection unexpectedly woken; offsets retained");}
        } catch(Exception ex) {
            failures.increment();log.error("sandbox projection stopped; offsets retained type={}",ex.getClass().getSimpleName());
        } finally {
            running=false;consumer=null;
            if(owned!=null)try { owned.close(Duration.ofSeconds(2)); }
            catch(Exception ex) { log.warn("sandbox projection close failed type={}",ex.getClass().getSimpleName()); }
        }
    }

    /** 每条 offset+1 仅在本地事务返回后提交；后续失败不能确认尚未处理的记录。 */
    void process(Consumer<String,String> owned,ConsumerRecord<String,String> record) {
        if(!topic.equals(record.topic()))throw new BillingEventCodec.InvalidEvent();
        if(projection.apply(record.key(),record.value()))applied.increment();else duplicates.increment();
        owned.commitSync(Map.of(new TopicPartition(record.topic(),record.partition()),new OffsetAndMetadata(record.offset()+1)),Duration.ofSeconds(5));
    }
    private void pause(int seconds) throws InterruptedException {
        for(int i=0;i<seconds*10 && !stopping;i++)Thread.sleep(100);
    }
}
