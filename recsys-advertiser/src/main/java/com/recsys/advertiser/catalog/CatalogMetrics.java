package com.recsys.advertiser.catalog;

import com.recsys.advertiser.persistence.mapper.primary.CatalogLocalMapper;
import io.micrometer.core.instrument.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** 原数据库预算/积压的低基数观测；采集超时保留旧值并标stale，不影响关键事件或清理历史。 */
public final class CatalogMetrics implements AutoCloseable {
    private final CatalogLocalMapper mapper;private final CatalogJson json;private final MeterRegistry registry;
    private final ScheduledThreadPoolExecutor scheduler;
    private final Map<String,AtomicLong> gauges=new HashMap<>();
    private static final Set<String> STATES=Set.of("ADMITTED","RUNNING","COMPLETED","PAUSED","REJECTED","READY","APPLIED","SENDING","PUBLISHED","STOPPED","OBSERVATION");
    public record Sample(String kind,String state,long total,long oldest,long expired,long unknown) { }
    public CatalogMetrics(CatalogLocalMapper mapper,CatalogJson json,MeterRegistry registry) {
        this.mapper=mapper;this.json=json;this.registry=registry;
        scheduler=new ScheduledThreadPoolExecutor(1,r->{var thread=new Thread(r,"catalog-metrics");thread.setDaemon(true);return thread;});
        scheduler.setRemoveOnCancelPolicy(true);scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    }
    /** 固定单线程、无额外任务排队，每次仅两库各一条有超时聚合。 */
    public void start(){scheduler.scheduleWithFixedDelay(this::observe,0,10,TimeUnit.SECONDS);}
    public synchronized void observe() {
        for(int shard=0;shard<2;shard++)try {
            var samples=json.decode(CatalogProtocol.utf8(mapper.statistics(shard)),Sample[].class);
            String prefix=shard+"/";gauges.forEach((key,value)->{if(key.startsWith(prefix))value.set(0);});
            for(var sample:samples) {
                String kind=allowed(sample.kind())?sample.kind():"UNKNOWN",state=STATES.contains(sample.state())?sample.state():"UNKNOWN";
                value(shard,kind,state,"records",sample.total());value(shard,kind,state,"oldest_seconds",sample.oldest());
                value(shard,kind,state,"expired_leases",sample.expired());value(shard,kind,state,"unknown_steps",sample.unknown());
            }
            value(shard,"CONTROL","OBSERVATION","stale",0);
        }catch(RuntimeException dependency){value(shard,"CONTROL","OBSERVATION","stale",1);}
    }
    private static boolean allowed(String kind) {
        if(Set.of("STEP","EVENT","CONTROL").contains(kind))return true;
        try{CatalogProtocol.Kind.valueOf(kind);return true;}catch(IllegalArgumentException invalid){return false;}
    }
    private void value(int shard,String kind,String state,String metric,long value) {
        String key=shard+"/"+kind+"/"+state+"/"+metric;
        var gauge=gauges.computeIfAbsent(key,k->{var number=new AtomicLong();registry.gauge("catalog_"+metric,Tags.of("shard",Integer.toString(shard),"kind",kind,"state",state),number);return number;});
        gauge.set(value);
    }
    /** 采集不是关键业务；退出不再调度，原Outbox/命令仍由持久协议保存。 */
    @Override public void close(){scheduler.shutdownNow();}
}
