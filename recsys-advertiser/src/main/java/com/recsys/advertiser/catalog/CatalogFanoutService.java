package com.recsys.advertiser.catalog;

import com.recsys.advertiser.catalog.CatalogFragments.*;
import com.recsys.advertiser.persistence.mapper.primary.*;
import com.recsys.advertiser.persistence.mapper.primary.CatalogBusinessMapper.Root;
import java.util.*;
import static com.recsys.advertiser.catalog.CatalogProtocol.*;
import static com.recsys.advertiser.catalog.CatalogCommandRepository.affected;

/** 广告主状态的持久有界fanout；单ad完成事实和根命令最终化严格分离，集合封闭期间不释放guard。 */
public final class CatalogFanoutService {
    private final CatalogLocalMapper local;
    private final CatalogTaskMapper tasks;
    private final CatalogBusinessMapper business;
    private final CatalogLocalTransactions tx;
    private final CatalogBusinessSteps steps;
    private final CatalogCommandRepository commands;
    private final CatalogOutboxRepository outbox;
    private final CatalogEnrichment enrichment;
    private final CatalogJson json;
    private final CatalogSnapshotCodec codec;
    private final CatalogSnapshotAssembler assembler=new CatalogSnapshotAssembler();

    /** 复用原单广告步骤与发布仓储，仅分离任务目标和根最终化。 */
    public CatalogFanoutService(CatalogLocalMapper local,CatalogTaskMapper tasks,CatalogBusinessMapper business,
            CatalogLocalTransactions tx,CatalogBusinessSteps steps,CatalogCommandRepository commands,CatalogOutboxRepository outbox,
            CatalogEnrichment enrichment,CatalogJson json,CatalogSnapshotCodec codec) {
        this.local=local;this.tasks=tasks;this.business=business;this.tx=tx;this.steps=steps;this.commands=commands;
        this.outbox=outbox;this.enrichment=enrichment;this.json=json;this.codec=codec;
    }
    /** 原根变化及每一页的游标独立持久；current只保存一个ad的有界完整片段。 */
    public record RootChange(Root before,Owner owner,boolean statusChanged,List<Effect> effects) { }
    public record Progress(RootChange root,long cursor,long count,Long target,Frozen current) { }
    public record Member(long ad,long version) { }
    public record TargetResult(String eventId,long version,String digest,List<Effect> effects) { }

    /** 调用方已领取父命令当前phase；每次最多一个物理步骤或一次有界enrichment。 */
    public void advance(CommandRow claim,Request input) {
        if(!Kind.ROOT_STATUS.name().equals(claim.kind()))throw failure(Failure.INCONSISTENT);
        if(PHASE_PREPARE.equals(claim.phase())){prepare(claim,input);return;}
        var p=json.decode(claim.frozenBytes(),Progress.class);
        switch(claim.phase()) {
            case "FANOUT_PAGE" -> page(claim,p);
            case "FANOUT_NEXT" -> nextTarget(claim,p);
            case "FANOUT_OWNER" -> {
                var target=target(claim,p);String step=step(p.target(),"OWNER");
                var request=new StepRequest(claim.shardId(),claim.key(),step,claim.advertiserId(),p.target(),target.expectedVersion()+1,
                        json.encode(new Member(target.targetId(),target.expectedVersion())),"[]");
                Owner owner=steps.execute(request,Owner.class,()->{
                    requireRoot(claim,p);var ad=business.ad(claim.advertiserId(),p.target());
                    var aggregate=business.lockAggregate(claim.shardId(),p.target());
                    if(ad==null || aggregate==null || aggregate.deleted() || aggregate.advertiserId()!=claim.advertiserId()
                            || aggregate.reservedVersion()!=target.expectedVersion())throw failure(Failure.INCONSISTENT);
                    affected(business.reserveVersion(claim.shardId(),p.target(),target.expectedVersion()));
                    return new Owner(p.root().owner().root(),ad,ad,p.root().owner().advertiserVersion(),target.expectedVersion()+1,
                            business.now(claim.shardId()),new ArrayList<>());
                },Owner::effects);
                advance(claim,"FANOUT_CHILDREN",new Progress(p.root(),p.cursor(),p.count(),p.target(),new Frozen(owner,null,null,null)));
            }
            case "FANOUT_CHILDREN" -> {
                var child=steps.children(claim,input,p.current().owner(),step(p.target(),"CHILDREN"));
                advance(claim,"FANOUT_ENRICH",new Progress(p.root(),p.cursor(),p.count(),p.target(),new Frozen(p.current().owner(),child,null,null)));
            }
            case "FANOUT_ENRICH" -> {
                var vector=enrichment.read(p.current().owner().desired().itemId());
                advance(claim,"FANOUT_FINAL",new Progress(p.root(),p.cursor(),p.count(),p.target(),new Frozen(p.current().owner(),p.current().children(),vector,null)));
            }
            case "FANOUT_FINAL" -> finishTarget(claim,p);
            case "ROOT_ENRICH" -> advance(claim,"ROOT_FINAL",new Progress(p.root(),p.cursor(),p.count(),null,
                    new Frozen(p.root().owner(),null,null,enrichment.spent(claim.advertiserId()))));
            case "ROOT_FINAL" -> {
                var count=tasks.counts(claim.key());
                if(!count.complete() || count.total()!=p.count())throw failure(Failure.INCONSISTENT);
                // 仅此根最终步骤调用A的finishLocal；每个ad最终步骤只完成自己的task item。
                steps.finish(claim,input,p.current());
            }
            default -> throw failure(Failure.INCONSISTENT);
        }
    }
    private void prepare(CommandRow claim,Request input) {
        var request=new StepRequest(claim.shardId(),claim.key(),"PREPARE",claim.advertiserId(),null,null,claim.requestBytes(),
                json.text(List.of(Map.of("table","advertiser","operation","UPDATE","id",claim.advertiserId()))));
        steps.execute(request,RootChange.class,()->{
            var old=business.root(claim.advertiserId());
            var guard=local.lockGuard(claim.shardId(),claim.advertiserId());
            if(guard==null)throw failure(Failure.INCONSISTENT);
            if(input.expectedAdvertiserVersion()!=null && input.expectedAdvertiserVersion()!=guard.advertiserVersion())throw failure(Failure.CONFLICT);
            var desired=CatalogBusinessPolicy.rootStatus(claim.advertiserId(),old,input.mutation().advertiser());
            long version=guard.advertiserVersion();var effects=new ArrayList<Effect>();
            if(!desired.equals(old)) {
                affected(local.advanceRootVersion(claim.shardId(),claim.advertiserId(),version));version++;
                affected(business.updateRoot(desired));effects.add(new Effect("advertiser","UPDATE",claim.advertiserId()));
            }
            var owner=new Owner(desired,null,null,version,0,business.now(claim.shardId()),effects);
            var root=new RootChange(old,owner,!old.status().equals(desired.status()),effects);
            String next=root.statusChanged()?"FANOUT_PAGE":"ROOT_ENRICH";
            // 根状态/version、原fact、step receipt和任务起点同本库事务提交，未知提交按原PREPARE查回。
            affected(local.advanceCommand(claim.key(),claim.fence(),next,next,json.encode(new Progress(root,0,0,null,null))));
            return root;
        },RootChange::effects);
    }
    private void page(CommandRow claim,Progress p) {
        tx.on(claim.shardId(),()->{
            lockParent(claim);requireRoot(claim,p);
            var members=new ArrayList<Member>();
            for(long ad:tasks.adPage(claim.advertiserId(),p.cursor())) {
                var aggregate=business.lockAggregate(claim.shardId(),ad);
                if(aggregate==null || aggregate.deleted() || aggregate.advertiserId()!=claim.advertiserId())throw failure(Failure.INCONSISTENT);
                members.add(new Member(ad,aggregate.reservedVersion()));
            }
            String digest=sha256(json.encode(members));long ordinal=p.count();
            for(var member:members) {
                UUID child=UUID.nameUUIDFromBytes(utf8(claim.key().stepKey("AD_"+member.ad())));
                affected(tasks.insert(claim.key(),new CatalogTaskMapper.Target(ordinal++,"FANOUT_AD",member.ad(),claim.advertiserId(),
                        member.version(),child,TaskState.READY.name(),null,null,digest,"ADMIT",1,0,null)));
            }
            long cursor=members.isEmpty()?p.cursor():members.getLast().ad();String phase=members.size()==100?"FANOUT_PAGE":"FANOUT_NEXT";
            affected(local.advanceCommand(claim.key(),claim.fence(),phase,phase,json.encode(new Progress(p.root(),cursor,ordinal,null,null))));
            return null;
        });
    }
    private void nextTarget(CommandRow claim,Progress p) {
        tx.on(claim.shardId(),()->{
            lockParent(claim);requireRoot(claim,p);var target=tasks.nextFanout(claim.key());
            String phase;Progress next;
            if(target==null) {
                var count=tasks.counts(claim.key());if(!count.complete() || count.total()!=p.count())throw failure(Failure.INCONSISTENT);
                phase="ROOT_ENRICH";next=new Progress(p.root(),p.cursor(),p.count(),null,null);
            } else {
                if(!TaskState.READY.name().equals(target.state()))throw failure(Failure.INCONSISTENT);
                affected(tasks.dispatchFanout(claim.key(),target.targetId()));phase="FANOUT_OWNER";
                next=new Progress(p.root(),p.cursor(),p.count(),target.targetId(),null);
            }
            affected(local.advanceCommand(claim.key(),claim.fence(),phase,phase,json.encode(next)));return null;
        });
    }
    private void finishTarget(CommandRow claim,Progress p) {
        var target=target(claim,p);var frozen=p.current();String phase=step(p.target(),"FINAL");
        var request=new StepRequest(claim.shardId(),claim.key(),phase,claim.advertiserId(),p.target(),target.expectedVersion()+1,json.encode(frozen),"[]");
        steps.execute(request,TargetResult.class,()->{
            requireRoot(claim,p);
            if(frozen.owner().version()!=target.expectedVersion()+1 || frozen.owner().desired().adId()!=p.target())throw failure(Failure.INCONSISTENT);
            var event=assembler.event(claim.key(),frozen.owner(),frozen.children(),frozen.embedding(),false);byte[] bytes=codec.encode(event);
            outbox.append(claim.key(),phase,event,bytes);
            affected(business.materialize(claim.shardId(),event.adId(),event.aggregateVersion(),event.eventId(),false));
            var result=new TargetResult(event.eventId(),event.aggregateVersion(),sha256(bytes),new ArrayList<>());
            affected(tasks.completeFanout(claim.key(),p.target(),json.encode(result)));
            affected(local.advanceCommand(claim.key(),claim.fence(),"FANOUT_NEXT","FANOUT_NEXT",json.encode(new Progress(p.root(),p.cursor(),p.count(),null,null))));
            return result;
        },TargetResult::effects);
    }
    private CatalogTaskMapper.Target target(CommandRow claim,Progress p) {
        if(p.target()==null)throw failure(Failure.INCONSISTENT);
        var target=tasks.target(claim.key(),"FANOUT_AD",p.target());
        if(target==null || target.targetOwnerId()!=claim.advertiserId().longValue() || target.expectedVersion()==null)throw failure(Failure.INCONSISTENT);
        return target;
    }
    private void requireRoot(CommandRow claim,Progress p) {
        var guard=local.lockGuard(claim.shardId(),claim.advertiserId());
        if(guard==null || guard.advertiserVersion()!=p.root().owner().advertiserVersion()
                || !p.root().owner().root().equals(business.root(claim.advertiserId())))throw failure(Failure.INCONSISTENT);
    }
    private void lockParent(CommandRow claim) {
        CatalogCommandRepository.requireActive(local.lockControl(claim.shardId()),claim.key());
        var guard=local.lockGuard(claim.shardId(),claim.advertiserId());
        if(guard==null || !claim.commandId().equals(guard.activeCommandId()) || !claim.key().scope().equals(guard.activeCommandScope())
                || !Integer.valueOf(claim.shardId()).equals(guard.activeCommandHome()))throw failure(Failure.FENCED);
        var current=local.lockCommand(claim.key());
        if(current==null || current.fence()!=claim.fence() || !State.RUNNING.name().equals(current.state()))throw failure(Failure.FENCED);
    }
    private void advance(CommandRow claim,String phase,Progress p){commands.advance(claim,phase,phase,json.encode(p));}
    private static String step(long ad,String phase){return "F_"+ad+"_"+phase;}
}
