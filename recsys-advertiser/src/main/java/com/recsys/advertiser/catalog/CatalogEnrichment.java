package com.recsys.advertiser.catalog;

import com.recsys.advertiser.persistence.mapper.derived.AdvertiserItemEmbeddingMapper;
import com.recsys.common.ad.VersionedAdCatalogEvent.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

/** 可选派生读取受单槽、零排队和一秒总等待约束；迟到结果丢弃，永不写回源或旧事件。 */
public final class CatalogEnrichment implements AutoCloseable {
    private final AdvertiserItemEmbeddingMapper vectors;
    private final StringRedisTemplate redis;
    private final ThreadPoolExecutor executor;
    public CatalogEnrichment(AdvertiserItemEmbeddingMapper vectors,StringRedisTemplate redis) {
        this.vectors=vectors;this.redis=redis;
        executor=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new SynchronousQueue<>(),r->{var t=new Thread(r,"catalog-enrichment");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    }
    /** 无行、非法数据和不可达均UNKNOWN；完整快照仍准确携带新的item关联。 */
    public Embedding read(long item) {
        Instant observed=Instant.now();
        return bounded(()->{
            var values=vectors.find(item);
            if(values.isEmpty())return unknown(item,observed,EmbeddingReason.MISSING);
            if(values.size()!=1)return unknown(item,observed,EmbeddingReason.INVALID);
            var value=values.getFirst();String text=value.embedding();
            if(text==null || text.length()>65536 || !text.startsWith("[") || !text.endsWith("]") || value.model()==null || value.model().isBlank())return unknown(item,observed,EmbeddingReason.INVALID);
            String[] fields=text.substring(1,text.length()-1).split(",");
            if(fields.length!=768)return unknown(item,observed,EmbeddingReason.INVALID);
            var vector=new ArrayList<Double>(768);
            double norm=0;
            for(String field:fields){double n;
                try{n=Double.parseDouble(field.trim());}catch(NumberFormatException invalid){return unknown(item,observed,EmbeddingReason.INVALID);}
                if(!Double.isFinite(n))return unknown(item,observed,EmbeddingReason.INVALID);vector.add(n);norm+=n*n;}
            if(!Double.isFinite(norm) || norm==0)return unknown(item,observed,EmbeddingReason.INVALID);
            return new Embedding(EmbeddingState.PRESENT,item,value.model(),vector,observed,EmbeddingReason.NONE);
        },unknown(item,observed,EmbeddingReason.UNAVAILABLE));
    }
    /** 沿用旧pacing读取语义，未知保留null；本调用与目录事务严格分离。 */
    public Double spent(long owner) {
        return bounded(()->{
            String key=com.recsys.common.constant.RedisKeys.adBudget(owner,LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd")));
            String value=redis.opsForValue().get(key);if(value==null)return null;
            long cents=Long.parseLong(value);return cents<0?null:java.math.BigDecimal.valueOf(cents,2).doubleValue();
        },null);
    }
    private <T>T bounded(Callable<T> read,T fallback) {
        Future<T> pending=null;
        try{long budget=Math.min(1000,CatalogCallBudget.remainingMillis());pending=executor.submit(read);return pending.get(budget,TimeUnit.MILLISECONDS);}
        catch(InterruptedException e){Thread.currentThread().interrupt();return fallback;}
        catch(ExecutionException | TimeoutException | RejectedExecutionException e){return fallback;}
        finally{if(pending!=null && !pending.isDone())pending.cancel(true);}
    }
    private static Embedding unknown(long item,Instant at,EmbeddingReason reason){return new Embedding(EmbeddingState.UNKNOWN,item,null,null,at,reason);}
    /** 不等待无界外部调用；实际驱动另配连接/socket超时以释放此唯一线程。 */
    @Override public void close(){executor.shutdownNow();}
}
