package com.recsys.advertiser.catalog;

import com.recsys.advertiser.catalog.CatalogFragments.*;
import com.recsys.advertiser.dto.*;
import com.recsys.advertiser.persistence.mapper.primary.*;
import com.recsys.advertiser.persistence.mapper.primary.CatalogBusinessMapper.*;
import com.recsys.advertiser.persistence.model.GeneratedId;
import java.util.*;
import java.util.function.Supplier;
import static com.recsys.advertiser.catalog.CatalogProtocol.*;
import static com.recsys.advertiser.catalog.CatalogCommandRepository.affected;

/** 单物理步骤的真实业务处理；每个callback只访问明确owner库或ad子库，不调用远程依赖。 */
public final class CatalogBusinessSteps {
    private final CatalogBusinessMapper business;
    private final CatalogLocalMapper local;
    private final CatalogStepRepository steps;
    private final CatalogCommandRepository commands;
    private final CatalogOutboxRepository outbox;
    private final CatalogJson json;
    private final CatalogSnapshotCodec codec;
    private final CatalogSnapshotAssembler assembler=new CatalogSnapshotAssembler();

    public CatalogBusinessSteps(CatalogBusinessMapper business,CatalogLocalMapper local,CatalogStepRepository steps,
                                CatalogCommandRepository commands,CatalogOutboxRepository outbox,CatalogJson json,CatalogSnapshotCodec codec) {
        this.business=business;this.local=local;this.steps=steps;this.commands=commands;this.outbox=outbox;this.json=json;this.codec=codec;
    }

    /** 持久owner占有下、尚无业务效果时验证跨库引用；避免一个非法子资源请求留下业务半结果。 */
    public void validate(Request request,long owner) {
        var m=request.mutation();
        if(m.ad()!=null && m.ad().itemId()!=null && !business.itemExists(m.ad().itemId()))
            throw com.recsys.advertiser.AdvertiserRules.badRequest("关联item不存在");
        if(m.kind()==Kind.AD_CREATE && m.ad()!=null && m.ad().bidwords()!=null) {
            if(m.ad().bidwords().size()>1024)throw failure(Failure.SNAPSHOT_LIMIT);
            for(var word:m.ad().bidwords())CatalogBusinessPolicy.bidword(1,1,null,word);
        }
        long ad=request.adId()==null?0:request.adId();
        switch(m.kind()) {
            case BIDWORD_CREATE -> CatalogBusinessPolicy.bidword(ad,1,null,m.bidword());
            case BIDWORD_UPDATE -> CatalogBusinessPolicy.bidword(ad,m.resourceId(),required(business.bidword(ad,m.resourceId())),m.bidword());
            case BIDWORD_DELETE -> required(business.bidword(ad,m.resourceId()));
            case CREATIVE_CREATE -> CatalogBusinessPolicy.creative(ad,1,null,m.creative());
            case CREATIVE_UPDATE -> CatalogBusinessPolicy.creative(ad,m.resourceId(),required(business.creative(ad,m.resourceId())),m.creative());
            case CREATIVE_DELETE -> required(business.creative(ad,m.resourceId()));
            default -> { }
        }
    }

    /** 参数拒绝前核实首步是否实际已提交，提交未知不能按异常猜测回滚。 */
    public boolean ownerApplied(CommandRow command) {
        var row=local.step((int)(command.advertiserId()%2),command.key().stepKey("PREPARE"));
        return row!=null && row.applied();
    }

    /** 原owner片段与版本预留同库；创建/删除在此步骤发生，其余ad变化留到最终事件事务。 */
    public Owner owner(CommandRow command,Request request) {
        long owner=command.advertiserId();int shard=(int)(owner%2);var m=request.mutation();
        var plan=new ArrayList<Map<String,Object>>();
        if(m.kind()==Kind.ROOT_CREATE)plan.add(plan("advertiser","INSERT",owner));
        if(m.kind()==Kind.ROOT_UPDATE)plan.add(plan("advertiser","UPDATE",owner));
        if(m.kind()==Kind.AD_CREATE) {
            plan.add(Map.of("table","ad","operation","INSERT","generated",true));
            plan.add(Map.of("table","ad","operation","UPDATE","generated",true));
        }
        if(m.kind()==Kind.AD_DELETE)plan.add(plan("ad","DELETE",request.adId()));
        var step=new StepRequest(shard,command.key(),"PREPARE",owner,request.adId(),null,command.requestBytes(),json.text(plan));
        return execute(step,Owner.class,()->{
            var effects=new ArrayList<Effect>();Root root=business.root(owner);
            var guard=local.lockGuard(shard,owner);if(guard==null)throw failure(Failure.INCONSISTENT);
            long rootVersion=guard.advertiserVersion();
            if(m.kind()==Kind.ROOT_CREATE) {
                if(root!=null)throw failure(Failure.CONFLICT);
                root=CatalogBusinessPolicy.root(owner,null,m.advertiser());affected(business.insertRoot(root));
                effects.add(new Effect("advertiser","INSERT",owner));
            } else {
                required(root);
                if(m.kind()==Kind.ROOT_UPDATE) {
                    var desired=CatalogBusinessPolicy.root(owner,root,m.advertiser());
                    if(!desired.equals(root)) {
                        affected(local.advanceRootVersion(shard,owner,rootVersion));rootVersion++;
                        affected(business.updateRoot(desired));effects.add(new Effect("advertiser","UPDATE",owner));root=desired;
                    }
                }
            }
            if(m.kind()==Kind.ROOT_CREATE || m.kind()==Kind.ROOT_UPDATE)
                return new Owner(root,null,null,rootVersion,0,business.now(shard),effects);
            Ad before=null,desired;long version;
            if(m.kind()==Kind.AD_CREATE) {
                var draft=CatalogBusinessPolicy.ad(owner,0,null,m);var generated=new GeneratedId();
                affected(local.insertAd(generated,owner,draft.itemId(),draft.title(),draft.landingUrl(),draft.qualityScore(),draft.status(),draft.reviewStatus(),draft.reviewReason(),draft.optimizationType(),draft.targetCpa(),draft.audienceId()));
                long ad=generated.valueOrZero();if(ad<=0)throw failure(Failure.INCONSISTENT);
                effects.add(new Effect("ad","INSERT",ad));affected(local.bindStepAd(shard,step.stepKey(),ad));
                if(draft.landingUrl()==null) {
                    affected(local.setAdLanding(owner,ad,"https://example.com/ad/"+ad));effects.add(new Effect("ad","UPDATE",ad));
                }
                desired=required(business.ad(owner,ad));affected(business.createAggregate(shard,ad,owner));version=1;
            } else {
                before=required(business.ad(owner,request.adId()));
                var aggregate=business.lockAggregate(shard,before.adId());
                if(aggregate==null || aggregate.deleted() || aggregate.advertiserId()!=owner)throw failure(Failure.NOT_READY);
                affected(business.reserveVersion(shard,before.adId(),aggregate.reservedVersion()));version=aggregate.reservedVersion()+1;
                desired=CatalogBusinessPolicy.ad(owner,before.adId(),before,m);
            }
            var frozen=new Owner(root,before,desired,rootVersion,version,business.now(shard),effects);
            if(m.kind()==Kind.AD_DELETE) {
                affected(business.deleteAd(owner,desired.adId()));effects.add(new Effect("ad","DELETE",desired.adId()));
                var event=assembler.event(command.key(),frozen,null,null,true);outbox.append(command.key(),"PREPARE",event,codec.encode(event));
                affected(business.materialize(shard,desired.adId(),version,event.eventId(),true));
            }
            return frozen;
        },Owner::effects);
    }

    /** 子资源实际写入与两张完整子表的冻结快照同一个物理事务，原reviewStatus必须保留。 */
    public Children children(CommandRow command,Request request,Owner owner) {
        return children(command,request,owner,"CHILDREN");
    }

    /** fanout每个ad有独立且稳定的本地step；复用原快照规则，不复用单广告最终化。 */
    public Children children(CommandRow command,Request request,Owner owner,String stepId) {
        var m=request.mutation();long ad=owner.desired().adId();int shard=(int)(ad%2);var plan=new ArrayList<Map<String,Object>>();
        switch(m.kind()) {
            case AD_CREATE, BIDWORD_CREATE -> plan.add(Map.of("table","bidword","operation","INSERT","generated",true));
            case BIDWORD_UPDATE -> plan.add(plan("bidword","UPDATE",m.resourceId()));
            case BIDWORD_DELETE -> plan.add(plan("bidword","DELETE",m.resourceId()));
            case CREATIVE_CREATE -> plan.add(Map.of("table","ad_creative","operation","INSERT","generated",true));
            case CREATIVE_UPDATE -> plan.add(plan("ad_creative","UPDATE",m.resourceId()));
            case CREATIVE_DELETE -> plan.add(plan("ad_creative","DELETE",m.resourceId()));
            case AD_DELETE -> {plan.add(Map.of("table","bidword","operation","DELETE","allChildren",true));plan.add(Map.of("table","ad_creative","operation","DELETE","allChildren",true));}
            default -> { }
        }
        var step=new StepRequest(shard,command.key(),stepId,command.advertiserId(),ad,owner.version(),json.encode(new Frozen(owner,null,null,null)),json.text(plan));
        return execute(step,Children.class,()->{
            var effects=new ArrayList<Effect>();Object response=null;
            if(m.kind()==Kind.AD_CREATE && m.ad().bidwords()!=null)
                for(var input:m.ad().bidwords())insertWord(ad,input,effects);
            if(m.kind()==Kind.BIDWORD_CREATE)response=insertWord(ad,m.bidword(),effects);
            if(m.kind()==Kind.BIDWORD_UPDATE) {
                var old=required(business.bidword(ad,m.resourceId()));var word=CatalogBusinessPolicy.bidword(ad,m.resourceId(),old,m.bidword());
                affected(business.updateBidword(word));effects.add(new Effect("bidword","UPDATE",word.id()));response=word;
            }
            if(m.kind()==Kind.BIDWORD_DELETE) {
                affected(business.deleteBidword(ad,m.resourceId()));effects.add(new Effect("bidword","DELETE",m.resourceId()));response=Map.of("ok",true,"bidwordId",m.resourceId());
            }
            if(m.kind()==Kind.CREATIVE_CREATE) {
                var draft=CatalogBusinessPolicy.creative(ad,0,null,m.creative());var generated=new GeneratedId();
                affected(local.insertCreative(generated,ad,draft.title(),draft.landingUrl(),draft.status()));long id=generated.valueOrZero();
                effects.add(new Effect("ad_creative","INSERT",id));response=creativeView(required(business.creative(ad,id)));
            }
            if(m.kind()==Kind.CREATIVE_UPDATE) {
                var old=required(business.creative(ad,m.resourceId()));var next=CatalogBusinessPolicy.creative(ad,m.resourceId(),old,m.creative());
                affected(business.updateCreative(next));effects.add(new Effect("ad_creative","UPDATE",m.resourceId()));response=creativeView(next);
            }
            if(m.kind()==Kind.CREATIVE_DELETE) {
                affected(business.deleteCreative(ad,m.resourceId()));effects.add(new Effect("ad_creative","DELETE",m.resourceId()));response=Map.of("ok",true,"creativeId",m.resourceId());
            }
            if(m.kind()==Kind.AD_DELETE) {
                var words=words(ad);var creatives=creatives(ad);
                int nw=business.deleteBidwords(ad),nc=business.deleteCreatives(ad);
                if(nw!=words.size() || nc!=creatives.size())throw failure(Failure.INCONSISTENT);
                words.forEach(w->effects.add(new Effect("bidword","DELETE",w.id())));creatives.forEach(c->effects.add(new Effect("ad_creative","DELETE",c.creativeId())));
                return new Children(List.of(),List.of(),json.tree(Map.of("ok",true,"adId",ad)),effects);
            }
            return new Children(words(ad),creatives(ad),response==null?null:json.tree(response),effects);
        },Children::effects);
    }

    /** 全部原片段核实后在owner同事务写最终源状态、完整事件、原响应及释放占有。 */
    public byte[] finish(CommandRow claim,Request request,Frozen frozen) {
        var m=request.mutation();var owner=frozen.owner();boolean root=owner.desired()==null;
        int shard=(int)(claim.advertiserId()%2);var plan=new ArrayList<Map<String,Object>>();
        boolean update=EnumSet.of(Kind.AD_UPDATE,Kind.AD_STATUS,Kind.AD_REVIEW,Kind.AD_SUBMIT_REVIEW).contains(m.kind());
        if(update)plan.add(plan("ad","UPDATE",owner.desired().adId()));
        var step=new StepRequest(shard,claim.key(),"FINAL",claim.advertiserId(),root?null:owner.desired().adId(),root?null:owner.version(),json.encode(frozen),json.text(plan));
        var result=execute(step,Final.class,()->{
            var effects=new ArrayList<Effect>();Object response;
            if(root) {
                var r=owner.root();Double spent=frozen.spent();
                response=new AdvertiserView(r.advertiserId(),r.name(),r.dailyBudget(),r.status(),spent,spent==null?null:Math.round(Math.max(0,r.dailyBudget()-spent)*100)/100.0);
            } else {
                if(update){affected(business.updateAd(owner.desired()));effects.add(new Effect("ad","UPDATE",owner.desired().adId()));}
                if(m.kind()!=Kind.AD_DELETE) {
                    var event=assembler.event(claim.key(),owner,frozen.children(),frozen.embedding(),false);
                    outbox.append(claim.key(),"FINAL",event,codec.encode(event));affected(business.materialize(shard,event.adId(),event.aggregateVersion(),event.eventId(),false));
                }
                // JsonNode字段经过持久化回读可能是NullNode，不能将它误当已冻结的成功响应。
                response=frozen.children().response()!=null && !frozen.children().response().isNull()?frozen.children().response():assembler.view(frozen);
            }
            byte[] original=json.encode(response);
            if(m.kind()==Kind.ROOT_CREATE)affected(local.releaseGuard(shard,claim.advertiserId(),claim.key()));
            else commands.finishLocal(claim,original);
            return new Final(json.tree(response),effects);
        },Final::effects);
        return json.encode(result.response());
    }

    private BidwordView insertWord(long ad,BidwordUpsert input,List<Effect> effects) {
        var value=CatalogBusinessPolicy.bidword(ad,0,null,input);var generated=new GeneratedId();
        affected(local.insertBidword(generated,ad,value.keyword(),value.matchType(),value.bid(),value.bidMode()));
        long id=generated.valueOrZero();effects.add(new Effect("bidword","INSERT",id));return required(business.bidword(ad,id));
    }
    List<BidwordView> words(long ad) {
        var all=new ArrayList<BidwordView>();long after=0;
        for(int page=0;page<11;page++) {
            var values=business.bidwords(ad,after);all.addAll(values);if(all.size()>1024)throw failure(Failure.SNAPSHOT_LIMIT);
            if(values.size()<100)return List.copyOf(all);after=values.getLast().id();
        }
        throw failure(Failure.SNAPSHOT_LIMIT);
    }
    List<Creative> creatives(long ad) {
        var all=new ArrayList<Creative>();long after=0;
        for(int page=0;page<3;page++) {
            var values=business.creatives(ad,after);all.addAll(values);if(all.size()>256)throw failure(Failure.SNAPSHOT_LIMIT);
            if(values.size()<100)return List.copyOf(all);after=values.getLast().creativeId();
        }
        throw failure(Failure.SNAPSHOT_LIMIT);
    }
    <T>T execute(StepRequest request,Class<T> type,Supplier<T> mutation,java.util.function.Function<T,List<Effect>> effects) {
        var row=steps.register(request);
        if(!row.applied()) {
            var claim=steps.claim(request);if(claim==null)throw failure(Failure.RESULT_UNKNOWN);
            try {row=steps.apply(request,claim,()->{var value=mutation.get();
                var expected=effects.apply(value);
                var actual=Arrays.asList(json.decode(utf8(local.step(request.shard(),request.stepKey()).effects()),Effect[].class));
                // 批量DELETE的物理行顺序未承诺；核对效果多重集，再保留PG实际记录顺序。
                var counts=java.util.stream.Collectors.groupingBy(java.util.function.Function.identity(),java.util.stream.Collectors.counting());
                if(!expected.stream().collect(counts).equals(actual.stream().collect(counts)))throw failure(Failure.INCONSISTENT);
                expected.clear();expected.addAll(actual);
                return new CatalogStepRepository.LocalResult(json.encode(value),json.text(expected));});}
            catch(RuntimeException error){steps.failed(claim,Failure.RESULT_UNKNOWN);throw error;}
        }
        return json.decode(row.resultBytes(),type);
    }
    private static Map<String,Object> plan(String table,String operation,long id){return Map.of("table",table,"operation",operation,"id",id);}
    private static CreativeView creativeView(Creative c){return new CreativeView(c.creativeId(),c.adId(),c.title(),c.landingUrl(),c.status());}
    private static <T>T required(T value){if(value==null)throw failure(Failure.NOT_FOUND);return value;}
}
