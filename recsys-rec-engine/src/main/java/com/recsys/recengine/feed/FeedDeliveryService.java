package com.recsys.recengine.feed;

import com.recsys.ad.*;
import com.recsys.common.ad.delivery.SandboxDelivery.*;
import com.recsys.common.dto.*;
import com.recsys.common.exposure.*;
import com.recsys.common.query.QueryUnderstandingService;
import com.recsys.recengine.RecommendOrchestrator;
import com.recsys.recengine.ad.AdServingGateway;
import com.recsys.recengine.content.ContentGateway;
import com.recsys.recengine.experiment.ExperimentService;
import com.recsys.recengine.exposure.NaturalDelivery;
import com.recsys.recengine.feed.FeedDelivery.*;
import java.util.*;

/** 最终混排用例；广告未知可以保留自然结果，但不能重排或新建在途子操作。 */
public class FeedDeliveryService {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(FeedDeliveryService.class);
    private final FeedDeliveryRepository repository;
    private final RecommendOrchestrator recommender;
    private final ContentGateway content;
    private final QueryUnderstandingService queries;
    private final ExperimentService experiments;
    private final AdServingGateway ads;
    private final NaturalQualificationToken tokens;
    private final Settings defaults;
    private final boolean admissionEnabled;
    public FeedDeliveryService(FeedDeliveryRepository repository,RecommendOrchestrator recommender,ContentGateway content,
            QueryUnderstandingService queries,ExperimentService experiments,AdServingGateway ads,AdProperties properties,
            NaturalQualificationToken tokens,NaturalQualification.Policy policy,boolean admissionEnabled) {
        this.repository=repository; this.recommender=recommender; this.content=content; this.queries=queries;
        this.experiments=experiments; this.ads=ads; this.tokens=tokens; this.admissionEnabled=admissionEnabled;
        var load=properties.getAdLoad();
        defaults=new Settings(load.isEnabled(),load.getSlots(),load.getMaxAds(),"default",properties.getAuction().getReservePrice(),policy);
    }
    /** PUT 同 ID 恢复；暂停新建时仍可查询和完成既有混排操作。 */
    public Operation deliver(Request request) {
        if(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("feed 编排不能持有外层事务访问其他 owner");
        }
        var previous=repository.find(request.operationId(),request.subject());
        if(previous.isPresent()) {
            if(!previous.get().request().equals(request)) throw new NaturalReceipt.Conflict();
            if(previous.get().state()==State.COMPLETE) return previous.get();
        } else {
            if(!admissionEnabled) throw new IllegalStateException("新 feed 交付已暂停");
            repository.create(request,settings(request));
        }
        var claimed=repository.acquire(request.operationId(),request.subject());
        if(claimed.isEmpty()) return current(request);
        var operation=claimed.get();
        try {
            if(operation.plan()==null) {
                var plan=plan(operation);
                if(!repository.freeze(operation,plan)) return current(request);
                operation=current(request);
            }
            if(operation.fence()!=claimed.get().fence() || operation.state()==State.COMPLETE) return operation;
            for(int i=0;i<operation.plan().entries().size();i++) {
                var entry=operation.plan().entries().get(i);
                if(entry.candidate()==null || entry.preparation()!=null) continue;
                Prepared prepared;
                try {
                    prepared=ads.findSandboxPreparation(entry.candidate().childId(),request.subject()).orElseGet(()->
                            ads.prepareSandbox(new Prepare(request.operationId(),request.subject(),request.userId(),
                                    entry.candidate().childId(),entry.slot(),entry.candidate().offerToken())));
                } catch(RuntimeException ex) {
                    // 仅 RPC 未知可返回已持久化部分快照；数据库失败仍传播，不把未知写为预算拒绝。
                    LOG.warn("feed preparation unknown operationId={} childId={} failureType={}",request.operationId(),entry.candidate().childId(),ex.getClass().getSimpleName());
                    return current(request);
                }
                var entries=new ArrayList<>(operation.plan().entries());
                entries.set(i,new Entry(entry.slot(),null,entry.candidate(),prepared));
                var plan=new Plan(operation.plan().bucket(),operation.plan().traceId(),operation.plan().adDisposition(),entries);
                if(!repository.progress(operation,plan)) return current(request);
                operation=current(request);
                if(operation.fence()!=claimed.get().fence()) return operation;
            }
            return operation;
        } finally {
            repository.relinquish(request.operationId(),claimed.get().fence());
        }
    }
    /** 纯 GET 不触发候选计算或准备写入。 */
    public Optional<Operation> find(UUID id,String subject) { return repository.find(id,subject); }
    private Operation current(Request request) { return repository.find(request.operationId(),request.subject()).orElseThrow(); }
    private Settings settings(Request request) {
        var variant=request.query()!=null && defaults.adsEnabled()?experiments.adVariant(request.userId()):null;
        String bucket=variant==null?defaults.adBucket():variant.getName();
        double reserve=variant!=null && variant.getParams().containsKey("reserve-price")
                ?Double.parseDouble(variant.getParams().get("reserve-price")):defaults.reserve();
        return new Settings(defaults.adsEnabled(),defaults.adSlots(),defaults.maxAds(),bucket,reserve,defaults.policy());
    }
    private Plan plan(Operation operation) {
        var request=operation.request(); var settings=operation.settings();
        var candidates=recommender.naturalCandidates(new RecommendRequest(request.userId(),request.size(),request.scene(),request.query()));
        var organic=new LinkedHashMap<Long,RecommendItem>();
        for(var item:candidates.response().items()) {
            if(item.itemId()<=0) throw new IllegalStateException("自然候选身份非法");
            organic.putIfAbsent(item.itemId(),item); if(organic.size()==request.size()) break;
        }
        List<Candidate> advertising=List.of(); var disposition=AdDisposition.NOT_REQUESTED;
        if(request.query()!=null && settings.adsEnabled() && settings.maxAds()>0 && !organic.isEmpty()) {
            try {
                advertising=validated(ads.sandboxFeedCandidates(new Query(request.operationId(),request.subject(),request.userId(),
                        queries.parse(request.query(),request.userId()),settings.maxAds(),request.scene(),settings.adBucket(),settings.reserve())));
                disposition=advertising.isEmpty()?AdDisposition.NO_ELIGIBLE_ADS:AdDisposition.CANDIDATES_AVAILABLE;
            } catch(RuntimeException ex) {
                disposition=AdDisposition.CANDIDATES_UNAVAILABLE;
                LOG.warn("feed candidates unavailable operationId={} failureType={}",request.operationId(),ex.getClass().getSimpleName());
            }
        }
        var mixProps=new AdProperties(); var mix=mixProps.getAdLoad();
        mix.setEnabled(settings.adsEnabled()); mix.setSlots(settings.adSlots()); mix.setMaxAds(settings.maxAds());
        var mixed=new AdMixer(mixProps).mix(List.copyOf(organic.values()),advertising.stream().map(Candidate::ad).toList(),request.size());
        var naturalIds=mixed.stream().filter(e->!e.ad()).map(com.recsys.common.ad.FeedEntry::itemId).toList();
        var snapshots=naturalIds.isEmpty()?Map.<Long,com.recsys.content.Item>of():content.findByIds(naturalIds);
        var byAd=new HashMap<Long,Candidate>(); advertising.forEach(c->byAd.put(c.ad().adId(),c));
        var now=repository.databaseNow(); var entries=new ArrayList<Entry>();
        for(var item:mixed) {
            if(item.ad()) { entries.add(new Entry(item.position(),null,byAd.get(item.adId()),null)); continue; }
            var snapshot=snapshots.get(item.itemId());
            if(snapshot==null || snapshot.itemId()!=item.itemId()) throw new IllegalStateException("自然内容不可用");
            var display=new NaturalDelivery.Content(snapshot.title(),snapshot.category(),snapshot.description());
            var q=new NaturalQualification(request.operationId(),UUID.randomUUID(),request.subject(),request.userId(),item.itemId(),item.position(),
                    NaturalQualification.Kind.FEED,NaturalQualification.Traffic.SANDBOX,request.scene(),candidates.bucket(),display.hash(),settings.policy(),now,now.plusMillis(settings.policy().ttlMs()));
            var original=organic.get(item.itemId());
            entries.add(new Entry(item.position(),new NaturalDelivery.Entry(item.position(),Long.toString(item.itemId()),display.title(),display.category(),display.description(),
                    original.score(),original.recallFrom(),original.reason(),q.qualificationId().toString(),tokens.sign(q),now,q.expiresAt(),q.contentHash()),null,null));
        }
        return new Plan(candidates.bucket(),candidates.response().traceId(),disposition,entries);
    }
    private List<Candidate> validated(List<Candidate> input) {
        if(input.size()>com.recsys.common.ad.delivery.SandboxDelivery.MAX_SLOTS) throw new IllegalStateException("广告候选超限");
        var ads=new HashSet<Long>(); var items=new HashSet<Long>(); var children=new HashSet<UUID>(); var result=new ArrayList<Candidate>();
        for(var candidate:input) {
            var a=candidate.ad();
            if(a.adId()<=0 || a.itemId()<=0 || a.advertiserId()<=0 || a.creativeId()<0 || a.title()==null || a.title().isBlank() || a.title().length()>2000) {
                throw new IllegalStateException("广告候选身份或正文非法");
            }
            if(ads.add(a.adId()) && items.add(a.itemId()) && children.add(candidate.childId())) result.add(candidate);
        }
        return List.copyOf(result);
    }
}
