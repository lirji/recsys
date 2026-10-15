package com.recsys.recengine.feed;

import com.recsys.common.ad.billing.CpmPrice;
import com.recsys.common.ad.delivery.SandboxDelivery;
import com.recsys.common.exposure.*;
import com.recsys.recengine.exposure.NaturalDelivery;
import java.util.*;

/** engine 自有最终混排聚合；自然资格与广告候选在任何准备副作用前固定。 */
public final class FeedDelivery {
    public static final int MAX_SNAPSHOT_BYTES=4194304;
    private FeedDelivery() {}
    public enum State { PREPARING, COMPLETE }
    public enum EntryKind { NATURAL, AD }
    public enum AdDisposition { NOT_REQUESTED, CANDIDATES_AVAILABLE, NO_ELIGIBLE_ADS, CANDIDATES_UNAVAILABLE }

    /** 终端只能指定原搜索意图，位置、来源、政策和价格全部由服务端决定。 */
    public record Request(UUID operationId,String subject,long userId,int size,String scene,String query) {
        public Request {
            SandboxDelivery.requireIdentity(operationId,subject,userId);
            if(size<1 || size>NaturalQualification.MAX_SLOTS || scene==null || scene.isBlank() || scene.length()>100
                    || query!=null && (query.isBlank() || query.length()>500)) throw new IllegalArgumentException("feed 请求非法");
        }
        @Override public String toString() { return "FeedDelivery.Request[operationId="+operationId+"]"; }
    }
    /** 每个操作冻结混排和广告实验参数；恢复不能读取已变化的线上配置。 */
    public record Settings(boolean adsEnabled,List<Integer> adSlots,int maxAds,String adBucket,double reserve,
                           NaturalQualification.Policy policy) {
        public Settings {
            adSlots=List.copyOf(adSlots); Objects.requireNonNull(policy); CpmPrice.decimal(reserve);
            if(maxAds<0 || maxAds>SandboxDelivery.MAX_SLOTS || adSlots.size()>SandboxDelivery.MAX_SLOTS
                    || new HashSet<>(adSlots).size()!=adSlots.size() || adBucket==null || adBucket.length()>200) {
                throw new IllegalArgumentException("feed 混排配置非法");
            }
            adSlots.forEach(SandboxDelivery::requireSlot);
        }
    }
    public record Operation(Request request,Settings settings,State state,long fence,Plan plan) {}
    public record Plan(String bucket,String traceId,AdDisposition adDisposition,List<Entry> entries) {
        public Plan {
            if(bucket==null || bucket.length()>2000 || traceId!=null && traceId.length()>2000 || adDisposition==null) {
                throw new IllegalArgumentException("feed 候选上下文非法");
            }
            entries=List.copyOf(entries);
            var itemIds=new HashSet<Long>(); var childIds=new HashSet<UUID>(); var qualifications=new HashSet<String>();
            for(int i=0;i<entries.size();i++) {
                var e=entries.get(i);
                if(e.slot()!=i+1 || !itemIds.add(e.itemId()) || e.candidate()!=null && !childIds.add(e.candidate().childId())
                        || e.natural()!=null && !qualifications.add(e.natural().qualificationId())) {
                    throw new IllegalArgumentException("feed 最终身份或位置重复");
                }
            }
        }
        /** 全自然立即完成；未知广告必须保留可恢复状态。 */
        public boolean complete() { return entries.stream().allMatch(e->e.candidate()==null || e.preparation()!=null); }
        @Override public String toString() { return "FeedDelivery.Plan[entries="+entries.size()+"]"; }
    }
    /** natural 与 candidate 互斥；广告 preparation 只能从未知变为原子操作的原结果。 */
    public record Entry(int slot,NaturalDelivery.Entry natural,SandboxDelivery.Candidate candidate,SandboxDelivery.Prepared preparation) {
        public Entry {
            if(slot<1 || slot>NaturalQualification.MAX_SLOTS || (natural==null)==(candidate==null)
                    || natural!=null && (natural.slot()!=slot || preparation!=null)) throw new IllegalArgumentException("feed 条目非法");
            if(candidate!=null) {
                SandboxDelivery.requireSlot(slot);
                if(preparation!=null && !candidate.childId().equals(preparation.childId())) throw new IllegalArgumentException("feed 子结果身份不匹配");
            }
        }
        public long itemId() { return natural==null?candidate.ad().itemId():Long.parseLong(natural.itemId()); }
        @Override public String toString() { return "FeedDelivery.Entry[slot="+slot+"]"; }
    }
    public record View(String operationId,String trafficClass,State state,String userId,int size,String scene,String query,
                       NaturalQualification.Policy policy,String bucket,String traceId,NaturalDelivery.ModelLineage modelLineage,
                       AdDisposition adDisposition,List<ViewEntry> items) {
        public View { items=List.copyOf(items); }
    }
    public record ViewEntry(int slot,EntryKind kind,NaturalDelivery.Entry natural,AdView ad) {}

    public record AdView(int slot,String childId,String adId,String itemId,String advertiserId,String creativeId,
                         String title,SandboxDelivery.Prepared preparation) {}

    /** 对外只给可展示快照，候选 offer 证明和认证主体从不进入 HTTP。 */
    public static View view(Operation operation) {
        var r=operation.request(); var plan=operation.plan();
        var items=plan==null?List.<ViewEntry>of():plan.entries().stream().map(e->{
            if(e.natural()!=null) return new ViewEntry(e.slot(),EntryKind.NATURAL,e.natural(),null);
            var a=e.candidate().ad();
            return new ViewEntry(e.slot(),EntryKind.AD,null,new AdView(e.slot(),e.candidate().childId().toString(),
                    Long.toString(a.adId()),Long.toString(a.itemId()),Long.toString(a.advertiserId()),Long.toString(a.creativeId()),a.title(),e.preparation()));
        }).toList();
        return new View(r.operationId().toString(),NaturalQualification.Traffic.SANDBOX.name(),operation.state(),Long.toString(r.userId()),r.size(),r.scene(),r.query(),
                operation.settings().policy(),plan==null?"":plan.bucket(),plan==null?"":plan.traceId(),NaturalDelivery.ModelLineage.UNVERIFIED,
                plan==null?null:plan.adDisposition(),items);
    }
}
