package com.recsys.advertiser.catalog;

import com.recsys.advertiser.persistence.mapper.primary.CatalogLocalMapper;
import com.recsys.advertiser.persistence.mapper.primary.CatalogLocalMapper.*;
import java.util.*;

/** 按已授权命令作用域查询两个物理库摘要；不返回原词、payload或actor。 */
public final class CatalogCommandQuery {
    private final CatalogLocalMapper mapper;
    public CatalogCommandQuery(CatalogLocalMapper mapper){this.mapper=mapper;}
    public record Page<T>(List<T> items,String nextCursor) { }
    public record Summary(Page<StepSummary> steps,Page<EventSummary> events) { }
    /** 每类合计最多100条，跨库游标始终带物理位置；不是跨库一致快照。 */
    public Summary summaries(CatalogProtocol.CommandKey key,String afterStep,String afterEvent,int limit) {
        if(limit<1 || limit>100)throw com.recsys.advertiser.AdvertiserRules.badRequest("分页数量必须在1至100之间");
        try {
            var steps=page(afterStep,limit,(shard,after,n)->mapper.stepSummaries(shard,key,after,n),StepSummary::stepKey);
            var events=page(afterEvent,limit,(shard,after,n)->mapper.eventSummaries(shard,key,after,n),EventSummary::outboxId);
            return new Summary(steps,events);
        } catch(org.springframework.dao.DataAccessException dependency){throw CatalogWriteRouter.publicFailure(CatalogProtocol.Failure.RESULT_UNKNOWN);}
    }
    private interface Read<T>{List<T> read(int shard,String after,int limit);}
    private <T>Page<T> page(String cursor,int limit,Read<T> read,java.util.function.Function<T,String> id) {
        int first=0;String after="";
        if(cursor!=null && !cursor.isBlank()) {
            if(cursor.length()>260 || cursor.length()<3 || cursor.charAt(1)!=':' || (cursor.charAt(0)!='0' && cursor.charAt(0)!='1'))
                throw com.recsys.advertiser.AdvertiserRules.badRequest("无效目录分页游标");
            first=cursor.charAt(0)-'0';after=cursor.substring(2);
        }
        var all=new ArrayList<T>();String next=null;
        for(int shard=first;shard<2;shard++) {
            var values=read.read(shard,shard==first?after:"",limit-all.size());all.addAll(values);
            if(all.size()==limit){next=shard+":"+id.apply(values.getLast());break;}
        }
        return new Page<>(List.copyOf(all),next);
    }
}
