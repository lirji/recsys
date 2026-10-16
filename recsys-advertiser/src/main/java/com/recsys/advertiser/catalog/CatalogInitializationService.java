package com.recsys.advertiser.catalog;

import com.recsys.advertiser.catalog.CatalogFragments.*;
import com.recsys.advertiser.persistence.mapper.primary.*;
import com.recsys.advertiser.persistence.mapper.primary.CatalogInitializationMapper.*;
import com.recsys.common.ad.VersionedAdCatalogEvent;
import java.util.*;
import static com.recsys.advertiser.catalog.CatalogProtocol.*;
import static com.recsys.advertiser.catalog.CatalogCommandRepository.affected;

/** 内部受控初始化：先固定home持久占有，封闭两库后冻结存量；不会自动迁移或创建Kafka主题。 */
public final class CatalogInitializationService {
    /** 初始化逐页核验的物理对象集合，不能把child列表当owner清单。 */
    private enum InventoryKind { ROOT, AD, CHILD }
    private final CatalogLocalMapper local;
    private final CatalogInitializationMapper init;
    private final CatalogBusinessMapper business;
    private final CatalogLocalTransactions tx;
    private final CatalogBusinessSteps steps;
    private final CatalogOutboxRepository outbox;
    private final CatalogEnrichment enrichment;
    private final CatalogRuntimeBinding binding;
    private final CatalogJson json;
    private final CatalogSnapshotCodec codec;
    private final UUID source;
    private final String bootstrap;

    public CatalogInitializationService(CatalogLocalMapper local,CatalogInitializationMapper init,CatalogBusinessMapper business,
            CatalogLocalTransactions tx,CatalogBusinessSteps steps,CatalogOutboxRepository outbox,CatalogEnrichment enrichment,
            CatalogRuntimeBinding binding,CatalogJson json,CatalogSnapshotCodec codec,UUID source,String bootstrap) {
        this.local=local;this.init=init;this.business=business;this.tx=tx;this.steps=steps;this.outbox=outbox;
        this.enrichment=enrichment;this.binding=binding;this.json=json;this.codec=codec;this.source=source;this.bootstrap=bootstrap;
    }
    /** 身份、目标物理库和实例清单都是原请求，不接受重试时悄悄变更。 */
    public record Input(UUID sourceId,String topology,String topic,List<Integer> targets,Map<String,Integer> writerManifest) { }
    public record Progress(CatalogKafkaPublisher.Identity identity,int shard,long after,Target target,Frozen snapshot) { }

    /** 仅供持有维护窗口的内部owner调用；actor必须来自受信执行上下文而非公网请求体。 */
    public CommandRow initialize(UUID command,Input input,String actor,String reason) {
        if(!source.equals(input.sourceId()) || !TOPOLOGY.equals(input.topology()) || !VersionedAdCatalogEvent.TOPIC.equals(input.topic())
                || !List.of(0,1).equals(input.targets()))throw failure(Failure.CONFLICT);
        binding.verify(false);binding.verifyManifest(json.text(input.writerManifest()));
        var key=key(command);var submission=new Submission(key,Kind.INITIALIZE,json.encode(input),actor,reason);
        return tx.on(0,()->{
            var original=local.command(key);if(original!=null)return CatalogCommandRepository.same(submission,original);
            if(init.occupy(key)!=1)throw failure(Failure.CONFLICT);
            affected(local.insertCommand(submission,null));return local.command(key);
        });
    }
    /** 每次领取只完成一步或一页，崩溃后读取同命令；耗尽时保留原初始化占有。 */
    public CommandRow advance(UUID command) {
        var key=key(command);binding.verify(false);
        var claim=tx.on(0,()->{
            requireOwner(key);var row=local.lockCommand(key);if(row==null)throw failure(Failure.NOT_FOUND);
            if(row.completed() || State.PAUSED.name().equals(row.state()))return null;
            local.pauseSpentCommand(key);return local.claimCommand(key)==1?local.command(key):null;
        });
        if(claim==null)return local.command(key);
        try {advanceClaim(claim);}
        catch(RuntimeException error) {
            // 若最终home提交已经成功，读取原结果；否则仅记录有限预算，不释放初始化ID。
            var observed=local.command(key);if(observed!=null && observed.completed())return observed;
            tx.on(0,()->local.failCommand(key,claim.fence(),error instanceof CatalogException c?c.failure().name():Failure.RESULT_UNKNOWN.name()));
            throw error;
        }
        return local.command(key);
    }
    private void advanceClaim(CommandRow claim) {
        var key=claim.key();var input=json.decode(claim.requestBytes(),Input.class);
        Progress p=claim.frozenBytes()==null?null:json.decode(claim.frozenBytes(),Progress.class);
        switch(claim.phase()) {
            case "PREPARE" -> next(claim,"QUIESCE",new Progress(CatalogKafkaPublisher.identity(bootstrap),0,0,null,null));
            case "QUIESCE" -> {
                tx.on(p.shard(),()->{
                    var c=local.binding(p.shard());
                    if("LEGACY".equals(c.mode()))affected(init.quiesce(p.shard(),key,p.identity().clusterId(),p.identity().topicId(),input.topic(),json.text(input.writerManifest())));
                    else requireBound(c,key,p.identity(),input.writerManifest());
                    if(!init.layout(p.shard()))throw failure(Failure.INCONSISTENT);
                    return null;
                });
                next(claim,p.shard()==0?"QUIESCE":"ROOTS",new Progress(p.identity(),p.shard()==0?1:0,0,null,null));
            }
            case "ROOTS", "ADS", "CHILDREN" -> page(claim,p);
            case "BASE_NEXT" -> {
                var target=init.next(p.shard(),key.command());
                if(target==null)next(claim,p.shard()==0?"BASE_NEXT":"VERIFY",new Progress(p.identity(),p.shard()==0?1:0,0,null,null));
                else {
                    var owners=business.ownersOfAd(target.targetId());
                    if(owners.size()!=1 || owners.getFirst()!=target.targetOwnerId())throw failure(Failure.INCONSISTENT);
                    next(claim,"BASE_OWNER",new Progress(p.identity(),p.shard(),0,target,null));
                }
            }
            case "BASE_OWNER" -> {
                var t=p.target();var request=step(key,"BASE_OWNER_",p,claim.requestBytes());
                var owner=steps.execute(request,Owner.class,()->{
                    var root=business.root(t.targetOwnerId());var ad=business.ad(t.targetOwnerId(),t.targetId());
                    if(root==null || ad==null || business.lockAggregate(p.shard(),t.targetId())!=null)throw failure(Failure.INCONSISTENT);
                    var guard=local.lockGuard(p.shard(),t.targetOwnerId());
                    if(guard==null || guard.activeCommandId()!=null)throw failure(Failure.CONFLICT);
                    affected(business.createAggregate(p.shard(),t.targetId(),t.targetOwnerId()));
                    return new Owner(root,ad,ad,guard.advertiserVersion(),1,business.now(p.shard()),new ArrayList<>());
                },Owner::effects);
                next(claim,"BASE_CHILD",new Progress(p.identity(),p.shard(),0,t,new Frozen(owner,null,null,null)));
            }
            case "BASE_CHILD" -> {
                var t=p.target();var childProgress=new Progress(p.identity(),(int)(t.targetId()%2),0,t,p.snapshot());
                var children=steps.execute(step(key,"BASE_CHILD_",childProgress,json.encode(p.snapshot().owner())),Children.class,
                        ()->new Children(steps.words(t.targetId()),steps.creatives(t.targetId()),null,new ArrayList<>()),Children::effects);
                next(claim,"BASE_ENRICH",new Progress(p.identity(),p.shard(),0,t,new Frozen(p.snapshot().owner(),children,null,null)));
            }
            case "BASE_ENRICH" -> {
                var frozen=p.snapshot();var embedding=enrichment.read(frozen.owner().desired().itemId());
                next(claim,"BASE_FINAL",new Progress(p.identity(),p.shard(),0,p.target(),new Frozen(frozen.owner(),frozen.children(),embedding,null)));
            }
            case "BASE_FINAL" -> {
                var frozen=p.snapshot();var step=step(key,"BASE_FINAL_",p,json.encode(frozen));
                steps.execute(step,Final.class,()->{
                    var event=new CatalogSnapshotAssembler().event(key,frozen.owner(),frozen.children(),frozen.embedding(),false);
                    byte[] bytes=codec.encode(event);outbox.append(key,step.stepId(),event,bytes);
                    affected(business.materialize(p.shard(),event.adId(),1,event.eventId(),false));
                    affected(init.completeTarget(p.shard(),key.command(),event.adId(),bytes));
                    return new Final(json.tree(Map.of("adId",event.adId(),"eventId",event.eventId())),new ArrayList<>());
                },Final::effects);
                next(claim,"BASE_SEND",p);
            }
            case "BASE_SEND" -> {
                String eventId=com.recsys.common.ad.CatalogEventCodec.eventId(source,p.target().targetId(),1);
                var row=outbox.claimBaseline(key,p.shard(),eventId);
                if(row==null)throw failure(Failure.RESULT_UNKNOWN);
                if(!OUTBOX_PUBLISHED.equals(row.state())) {
                    try(var publisher=new CatalogKafkaPublisher(bootstrap,p.identity().clusterId(),p.identity().topicId())) {
                        var ack=publisher.send(row);
                        if(!outbox.published(row,ack.partition(),ack.offset()))throw failure(Failure.RESULT_UNKNOWN);
                    } catch(Exception error) {
                        if(error instanceof InterruptedException)Thread.currentThread().interrupt();
                        outbox.failed(row,Failure.RESULT_UNKNOWN);
                        throw error instanceof CatalogException c?c:failure(Failure.RESULT_UNKNOWN);
                    }
                }
                next(claim,"BASE_NEXT",new Progress(p.identity(),p.shard(),0,null,null));
            }
            case "VERIFY" -> {
                var now=CatalogKafkaPublisher.identity(bootstrap);if(!now.equals(p.identity()))throw failure(Failure.INCONSISTENT);
                for(int shard=0;shard<2;shard++) {
                    requireBound(local.binding(shard),key,p.identity(),input.writerManifest());
                    if(init.unfinished(shard,key.command())!=0 || !init.layout(shard))throw failure(Failure.INCONSISTENT);
                }
                next(claim,"ACTIVATE",new Progress(p.identity(),0,0,null,null));
            }
            case "ACTIVATE" -> {
                tx.on(p.shard(),()->{
                    var c=local.binding(p.shard());requireBound(c,key,p.identity(),input.writerManifest());
                    if(!c.initializationReady())affected(init.activate(p.shard(),key));return null;
                });
                next(claim,p.shard()==0?"ACTIVATE":"COMPLETE",new Progress(p.identity(),1,0,null,null));
            }
            case "COMPLETE" -> {
                binding.verify(true);
                tx.on(0,()->{requireOwner(key);affected(local.finishCommand(key,claim.fence(),json.encode(Map.of("initialized",true,"sourceId",source.toString()))));return null;});
            }
            default -> throw failure(Failure.INCONSISTENT);
        }
    }
    private void page(CommandRow claim,Progress p) {
        InventoryKind kind=switch(claim.phase()){case "ROOTS"->InventoryKind.ROOT;case "ADS"->InventoryKind.AD;default->InventoryKind.CHILD;};
        var page=json.decode(utf8(init.page(p.shard(),kind.name(),p.after())),SourceTarget[].class);
        String digest=sha256(json.encode(page));
        if(kind==InventoryKind.CHILD) {
            for(var target:page)if(business.ownersOfAd(target.id()).size()!=1)throw failure(Failure.INCONSISTENT);
        } else tx.on(p.shard(),()->{
            for(var target:page) {
                if(kind==InventoryKind.ROOT) {
                    local.insertGuard(p.shard(),target.id(),null,null,null);
                    var guard=local.lockGuard(p.shard(),target.id());if(guard==null || guard.activeCommandId()!=null)throw failure(Failure.CONFLICT);
                } else affected(init.freezeTarget(p.shard(),claim.commandId(),target.id(),target.owner(),digest));
            }
            return null;
        });
        if(page.length==100)next(claim,claim.phase(),new Progress(p.identity(),p.shard(),page[99].id(),null,null));
        else if(p.shard()==0)next(claim,claim.phase(),new Progress(p.identity(),1,0,null,null));
        else next(claim,switch(kind){case ROOT->"ADS";case AD->"CHILDREN";default->"BASE_NEXT";},new Progress(p.identity(),0,0,null,null));
    }
    private StepRequest step(CommandKey key,String name,Progress p,byte[] request) {
        return new StepRequest(p.shard(),key,name+p.target().targetId(),p.target().targetOwnerId(),p.target().targetId(),1L,request,"[]");
    }
    private void next(CommandRow claim,String phase,Progress progress) {
        byte[] bytes=json.encode(progress);requireBytes(bytes);
        tx.on(0,()->{requireOwner(claim.key());affected(local.advanceCommand(claim.key(),claim.fence(),phase,phase,bytes));return null;});
    }
    private void requireOwner(CommandKey key) {
        var c=local.binding(0);if(c==null || !key.command().equals(c.initializingCommandId()) || c.stopReason()!=null)throw failure(Failure.CONFLICT);
    }
    private void requireBound(CatalogLocalMapper.Binding c,CommandKey key,CatalogKafkaPublisher.Identity identity,Map<String,Integer> manifest) {
        if(c==null || !key.command().equals(c.initializingCommandId()) || !source.equals(c.sourceId()) || !TOPOLOGY.equals(c.topologyVersion())
                || !identity.clusterId().equals(c.clusterId()) || !identity.topicId().equals(c.topicId()) || !VersionedAdCatalogEvent.TOPIC.equals(c.topicName())
                || c.stopReason()!=null || !json.tree(manifest).equals(json.tree(json.decode(utf8(c.writerManifest()),Map.class))))throw failure(Failure.INCONSISTENT);
    }
    private CommandKey key(UUID command){return new CommandKey(0,source,ScopeKind.INITIALIZE,"INITIALIZE",command);}
}
