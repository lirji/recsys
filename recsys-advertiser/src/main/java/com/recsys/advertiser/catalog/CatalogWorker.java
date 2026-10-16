package com.recsys.advertiser.catalog;

import com.recsys.advertiser.persistence.mapper.primary.CatalogLocalMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.UUID;
import java.util.concurrent.*;

/** 两分片各一个固定串行槽，候选和每次推进有界；没有公共线程池、递归重试或启动时重置预算。 */
public final class CatalogWorker implements AutoCloseable {
    private static final Logger LOG=LoggerFactory.getLogger(CatalogWorker.class);
    private final ScheduledThreadPoolExecutor executor;
    private final CatalogLocalMapper mapper;
    private final CatalogCommandService commands;
    private final CatalogStepRepository steps;
    private final CatalogRelay relay;
    private final UUID source;
    public CatalogWorker(CatalogLocalMapper mapper,CatalogCommandService commands,CatalogStepRepository steps,CatalogRelay relay,UUID source) {
        this.mapper=mapper;this.commands=commands;this.steps=steps;this.relay=relay;this.source=source;
        executor=new ScheduledThreadPoolExecutor(2,r->{var t=new Thread(r,"catalog-shard-worker");t.setDaemon(true);return t;});
        executor.setRemoveOnCancelPolicy(true);executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
    }
    /** 仅显式worker/relay开关装配调用；每个周期任务同一分片不会重叠执行。 */
    public void start() {for(int shard=0;shard<2;shard++){int target=shard;executor.scheduleWithFixedDelay(()->tick(target),0,500,TimeUnit.MILLISECONDS);}}
    /** 单轮只推进最多十个原命令的一步及有限事件，不因某owner故障饿死其他候选。 */
    public void tick(int shard) {
        try {
            // 命令耗尽后不再进入业务advance，故本地step清算必须有独立的有界维护入口。
            if(commands!=null)steps.pauseExpired(shard,source);
            if(commands!=null)for(var row:mapper.candidates(shard,source)) {
                if(Thread.currentThread().isInterrupted())return;
                try{commands.advance(row.key());}catch(RuntimeException error){LOG.warn("catalog command pending shard={} phase={} type={}",shard,row.phase(),safe(error));}
            }
            if(relay!=null && !Thread.currentThread().isInterrupted())relay.advance(shard);
        } catch(RuntimeException error){LOG.warn("catalog worker pending shard={} type={}",shard,safe(error));}
    }
    private static String safe(Exception error){return error instanceof CatalogProtocol.CatalogException c?c.failure().name():"DEPENDENCY_UNKNOWN";}
    /** 退出停止领取并有界等待；在途未知保留数据库原lease/fence供原命令接管。 */
    @Override public void close() {
        executor.shutdown();try{if(!executor.awaitTermination(5,TimeUnit.SECONDS))executor.shutdownNow();}
        catch(InterruptedException e){executor.shutdownNow();Thread.currentThread().interrupt();}
    }
}
