package com.recsys.advertiser.catalog;

import com.recsys.advertiser.catalog.CatalogFragments.Request;
import com.recsys.advertiser.dto.AdvertiserUpsert;
import com.recsys.advertiser.persistence.mapper.primary.*;
import java.util.*;
import static com.recsys.advertiser.catalog.CatalogProtocol.*;
import static com.recsys.advertiser.catalog.CatalogCommandRepository.affected;

/** 仅内部受控的显式跨owner状态批次；home只登记逐目标事实，不建立跨库事务或共同快照。 */
public final class CatalogStatusBatchService {
    private static final String TARGET="STATUS_OWNER";
    private final CatalogLocalMapper local;
    private final CatalogTaskMapper tasks;
    private final CatalogLocalTransactions tx;
    private final CatalogCommandRepository commands;
    private final CatalogJson json;
    private final UUID source;
    private final boolean admission,recovery;
    /** 开关由实际运行装配传入；停新准入/审计不丢失原批次结果。 */
    public CatalogStatusBatchService(CatalogLocalMapper local,CatalogTaskMapper tasks,CatalogLocalTransactions tx,
            CatalogCommandRepository commands,CatalogJson json,UUID source,boolean admission,boolean recovery) {
        this.local=local;this.tasks=tasks;this.tx=tx;this.commands=commands;this.json=json;this.source=source;this.admission=admission;this.recovery=recovery;
    }
    public record StatusTarget(long advertiserId,long expectedAdvertiserVersion,String targetStatus) { }
    public record Batch(List<StatusTarget> targets) { }
    public record Progress(Long target) { }
    public record Summary(UUID batchId,String state,CatalogTaskMapper.Counts counts) { }
    public record Audit(Kind kind,UUID batchId,Long target,Integer expectedRound) { }

    /** 可信actor来自内部调用上下文；没有新增公网批次入口。≤100目标规范排序并与子命令身份同库冻结。 */
    public CommandRow submitStatusBatch(UUID batchId,List<StatusTarget> targets,String actor,String reason) {
        if(targets==null || targets.isEmpty() || targets.size()>100 || targets.stream().anyMatch(Objects::isNull))throw failure(Failure.INVALID_INPUT);
        var ordered=targets.stream().sorted(Comparator.comparingLong(StatusTarget::advertiserId)).toList();
        long previous=0;
        for(var target:ordered) {
            if(target.advertiserId()<=previous || target.expectedAdvertiserVersion()<=0
                    || target.targetStatus()==null || !Set.of("active","paused").contains(target.targetStatus()))throw failure(Failure.INVALID_INPUT);
            previous=target.advertiserId();
        }
        var submission=new Submission(key(batchId),Kind.STATUS_BATCH,json.encode(new Batch(ordered)),actor,reason);
        try{return tx.on(submission.key().shard(),()->{
            CatalogCommandRepository.requireActive(local.lockControl(submission.key().shard()),submission.key());
            var original=local.command(submission.key());if(original!=null)return CatalogCommandRepository.same(submission,original);
            if(!admission)throw failure(Failure.NOT_READY);
            // 本库control共享锁不串行不同batch；同ID竞争由唯一键使整个清单随命令一起回滚。
            affected(local.insertCommand(submission,null));
            long ordinal=0;
            for(var target:ordered)affected(tasks.insert(submission.key(),new CatalogTaskMapper.Target(ordinal++,TARGET,target.advertiserId(),target.advertiserId(),
                    target.expectedAdvertiserVersion(),childId(submission.key(),target.advertiserId()),TaskState.READY.name(),null,null,submission.digest(),TaskPhase.ADMIT.name(),1,0,null)));
            return local.command(submission.key());
        });}catch(org.springframework.dao.DuplicateKeyException raced) {
            // 已知唯一键竞争已回滚；提交未知由调用者查原ID，不能在这里另派一份清单。
            var original=commands.find(submission.key());if(original==null)throw failure(Failure.INCONSISTENT);
            return CatalogCommandRepository.same(submission,original);
        }
    }
    /** 单次只推进一个目标；新目标先DISPATCHED持久化，随后才允许另一物理库出现副作用。 */
    public CommandRow advance(CommandKey key) {
        requireKey(key);settleExpiredObservation(key);var current=required(key);
        if(current.completed() || State.PAUSED.name().equals(current.state()))return current;
        var claim=commands.claim(key);if(claim==null)return required(key);
        try {
            switch(claim.phase()) {
                case "PREPARE","BATCH_WORK" -> select(claim);
                case "BATCH_OBSERVE" -> observe(claim);
                default -> throw failure(Failure.INCONSISTENT);
            }
        } catch(RuntimeException error) {
            var saved=required(key);if(saved.completed())return saved;
            commands.failed(claim,error instanceof CatalogException c?c.failure():Failure.RESULT_UNKNOWN);throw error;
        }
        return required(key);
    }
    /** 原home的准确计数，COMPLETED仅表示全部目标业务事实完成，不表示Kafka或S17已经完成。 */
    public Summary query(UUID batchId) {
        var row=required(key(batchId));var counts=tasks.counts(row.key());
        String state=row.completed()?TaskState.COMPLETED.name():counts.completed()+counts.conflicts()+counts.cancelled()+counts.paused()>0?"PARTIAL":row.state();
        return new Summary(batchId,state,counts);
    }
    /** 查询冻结目标分页，不读取另一owner当前业务去替换原输入。 */
    public List<CatalogTaskMapper.Target> targets(UUID batchId,long afterOrdinal){required(key(batchId));return tasks.page(key(batchId),afterOrdinal);}

    /** 新稳定审计命令仅取消READY；DISPATCHED即使提交未知也必须保留并继续收敛。 */
    public CommandRow cancelUndispatched(UUID auditId,UUID batchId,String actor,String reason) {
        return audit(auditId,new Audit(Kind.CANCEL_UNDISPATCHED,batchId,null,null),actor,reason);
    }
    /** 新审计轮只重开一个原目标；原child若PAUSED须先由现有RETRY_STEP恢复，绝不隐式释放其guard。 */
    public CommandRow retryTarget(UUID auditId,UUID batchId,long advertiserId,int expectedRound,String actor,String reason) {
        if(advertiserId<=0 || expectedRound<=0)throw failure(Failure.INVALID_INPUT);
        return audit(auditId,new Audit(Kind.RETRY_STEP,batchId,advertiserId,expectedRound),actor,reason);
    }
    private CommandRow audit(UUID id,Audit request,String actor,String reason) {
        var parent=key(request.batchId());var key=new CommandKey(parent.shard(),source,ScopeKind.RECOVERY,"STATUS_BATCH:"+request.batchId(),id);
        var submission=new Submission(key,request.kind(),json.encode(request),actor,reason);
        return tx.on(key.shard(),()->{
            CatalogCommandRepository.requireActive(local.lockControl(key.shard()),parent);
            var batch=local.lockCommand(parent);if(batch==null)throw failure(Failure.NOT_FOUND);
            var original=local.command(key);if(original!=null)return CatalogCommandRepository.same(submission,original);
            if(!recovery)throw failure(Failure.NOT_READY);
            affected(local.insertCommand(submission,null));affected(local.claimCommand(key));var claim=local.command(key);
            var before=tasks.counts(parent);Object result;
            if(request.kind()==Kind.CANCEL_UNDISPATCHED) {
                int cancelled=tasks.cancelUndispatched(parent,json.encode(Map.of("auditId",id,"cancelledBeforeDispatch",true)));
                result=Map.of("cancelled",cancelled,"before",before,"after",tasks.counts(parent));
            } else {
                var target=tasks.target(parent,TARGET,request.target());if(target==null)throw failure(Failure.NOT_FOUND);
                if(!TaskState.PAUSED.name().equals(target.state()) || target.round()!=request.expectedRound())throw failure(Failure.CONFLICT);
                affected(tasks.retryTarget(parent,request.target(),request.expectedRound()));
                if(State.PAUSED.name().equals(batch.state()))affected(tasks.retryParent(parent,batch.round(),batch.fence(),json.encode(new Progress(null))));
                result=Map.of("target",request.target(),"originalChild",target.childCommandId(),"previousRound",target.round(),
                        "previousAttempts",target.attempts(),"previousError",Objects.toString(target.errorType(),""),"round",target.round()+1,"before",before);
            }
            affected(local.finishCommand(key,claim.fence(),json.encode(result)));return local.command(key);
        });
    }
    private void select(CommandRow claim) {
        tx.on(claim.shardId(),()->{
            lockParent(claim);var target=tasks.nextBatch(claim.key());
            if(target==null) {
                var counts=tasks.counts(claim.key());byte[] result=json.encode(counts);
                if(counts.complete())affected(local.finishCommand(claim.key(),claim.fence(),result));
                else if(counts.pending()==0)affected(tasks.pauseParent(claim.key(),claim.fence(),result));
                else next(claim,"BATCH_WORK",new Progress(null));
            } else {
                affected(tasks.dispatchBatch(claim.key(),target.targetId()));
                next(claim,"BATCH_OBSERVE",new Progress(target.targetId()));
            }
            return null;
        });
    }
    private void observe(CommandRow claim) {
        var progress=json.decode(claim.frozenBytes(),Progress.class);
        if(progress.target()==null)throw failure(Failure.INCONSISTENT);
        var target=tx.on(claim.shardId(),()->{
            lockParent(claim);var saved=tasks.target(claim.key(),TARGET,progress.target());
            if(saved==null || !TaskState.DISPATCHED.name().equals(saved.state()))throw failure(Failure.INCONSISTENT);
            if(saved.attempts()==3) {
                affected(tasks.pauseExhausted(claim.key(),saved.targetId()));next(claim,"BATCH_WORK",new Progress(null));return null;
            }
            affected(tasks.beginObservation(claim.key(),saved.targetId()));return saved;
        });
        if(target==null)return;
        try {
            var batch=json.decode(claim.requestBytes(),Batch.class);var input=batch.targets().get(Math.toIntExact(target.ordinal()));
            if(input.advertiserId()!=target.targetId() || input.expectedAdvertiserVersion()!=target.expectedVersion()
                    || !target.childCommandId().equals(childId(claim.key(),input.advertiserId())))throw failure(Failure.INCONSISTENT);
            var key=CommandKey.advertiser(source,input.advertiserId(),target.childCommandId());
            var mutation=new CatalogMutation(Kind.ROOT_UPDATE,input.advertiserId(),null,null,null,new AdvertiserUpsert(null,null,input.targetStatus()),null,null,null);
            var request=new Request(mutation,null,input.expectedAdvertiserVersion());
            var submission=new Submission(key,Kind.ROOT_STATUS,json.encode(request),claim.actor(),"STATUS_BATCH:"+claim.commandId());
            var child=commands.find(key);
            if(child==null) {
                if(TaskPhase.WAIT.name().equals(target.phase()))throw failure(Failure.INCONSISTENT);
                child=commands.admitExpected(submission,input.expectedAdvertiserVersion());
            } else CatalogCommandRepository.same(submission,child);
            String state=child.completed()?TaskState.COMPLETED.name():State.PAUSED.name().equals(child.state())?TaskState.PAUSED.name():State.REJECTED.name().equals(child.state())?TaskState.CONFLICT.name():TaskState.DISPATCHED.name();
            record(claim,target.targetId(),state,TaskPhase.WAIT.name(),child.resultBytes(),child.errorType());
        } catch(RuntimeException error) {
            if(error instanceof CatalogException c && c.failure()==Failure.CONFLICT) {
                // admitExpected只在无原child且guard锁内判版本冲突；没有提交root效果，原目标不得换版本重试。
                record(claim,target.targetId(),TaskState.CONFLICT.name(),target.phase(),json.encode(Map.of("originalTargetConflict",true)),Failure.CONFLICT.name());
            } else tx.on(claim.shardId(),()->{
                lockParent(claim);affected(tasks.unknown(claim.key(),target.targetId(),error instanceof CatalogException c?c.failure().name():Failure.RESULT_UNKNOWN.name()));
                next(claim,"BATCH_WORK",new Progress(null));return null;
            });
        }
    }
    private void record(CommandRow claim,long id,String state,String phase,byte[] result,String error) {
        tx.on(claim.shardId(),()->{lockParent(claim);affected(tasks.observed(claim.key(),id,state,phase,result,error));next(claim,"BATCH_WORK",new Progress(null));return null;});
    }
    private void settleExpiredObservation(CommandKey key) {
        // 最后一次协调领取退出时，将这一目标确定记PAUSED并转下一个目标；没有重试失败目标或释放其owner。
        // 此处是原步骤耗尽的清算，原command/target预算写入不可变fact，只有新审计轮能再访问该目标。
        if(!tasks.exhaustedObservation(key))return;
        tx.on(key.shard(),()->{
            CatalogCommandRepository.requireActive(local.lockControl(key.shard()),key);var row=local.lockCommand(key);
            if(row==null || !tasks.exhaustedObservation(key))return null;
            var progress=json.decode(row.frozenBytes(),Progress.class);if(progress.target()==null)throw failure(Failure.INCONSISTENT);
            var target=tasks.target(key,TARGET,progress.target());if(target==null)throw failure(Failure.INCONSISTENT);
            affected(tasks.pauseObservationTarget(key,target.targetId()));
            byte[] receipt=json.encode(Map.of("target",target.targetId(),"childCommand",target.childCommandId(),"commandRound",row.round(),
                    "commandAttempts",row.attempts(),"commandFence",row.fence(),"targetRound",target.round(),"targetAttempts",target.attempts()));
            String step="EXHAUSTED_"+row.fence();
            affected(local.insertFact(key.shard(),key.stepKey(step),source,key.scope(),key.command(),step,receipt,sha256(receipt)));
            affected(tasks.settleObservation(key,row.fence(),json.encode(new Progress(null))));return null;
        });
    }
    private void next(CommandRow claim,String phase,Progress progress){affected(local.advanceCommand(claim.key(),claim.fence(),phase,phase,json.encode(progress)));}
    private void lockParent(CommandRow claim) {
        CatalogCommandRepository.requireActive(local.lockControl(claim.shardId()),claim.key());var current=local.lockCommand(claim.key());
        if(current==null || current.fence()!=claim.fence() || !State.RUNNING.name().equals(current.state()))throw failure(Failure.FENCED);
    }
    private CommandRow required(CommandKey key){var row=commands.find(key);if(row==null)throw failure(Failure.NOT_FOUND);return row;}
    private void requireKey(CommandKey key){if(!key.equals(key(key.command())))throw failure(Failure.CONFLICT);}
    /** 固定home只由原UUID决定，恢复和查询不得按当前owner重新定位。 */
    public CommandKey key(UUID id){return new CommandKey((int)(id.getLeastSignificantBits()&1),source,ScopeKind.STATUS_BATCH,"STATUS_BATCH",id);}
    private static UUID childId(CommandKey batch,long owner){return UUID.nameUUIDFromBytes(utf8(batch.stepKey("OWNER_"+owner)));}
}
