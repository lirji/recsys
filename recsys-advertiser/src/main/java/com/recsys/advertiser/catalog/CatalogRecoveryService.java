package com.recsys.advertiser.catalog;

import com.recsys.advertiser.persistence.mapper.primary.*;
import java.util.*;
import static com.recsys.advertiser.catalog.CatalogProtocol.*;
import static com.recsys.advertiser.catalog.CatalogCommandRepository.affected;

/** 仅内部受控恢复入口；新审计ID恢复原输入，未知先查本命令，不释放任何广告主占有。 */
public final class CatalogRecoveryService {
    private final CatalogLocalMapper local;
    private final CatalogRecoveryMapper recovery;
    private final CatalogLocalTransactions tx;
    private final CatalogRuntimeBinding binding;
    private final CatalogJson json;
    private final CatalogSnapshotCodec codec;
    private final UUID source;
    private final String bootstrap;
    public CatalogRecoveryService(CatalogLocalMapper local,CatalogRecoveryMapper recovery,CatalogLocalTransactions tx,
            CatalogRuntimeBinding binding,CatalogJson json,CatalogSnapshotCodec codec,UUID source,String bootstrap) {
        this.local=local;this.recovery=recovery;this.tx=tx;this.binding=binding;this.json=json;this.codec=codec;this.source=source;this.bootstrap=bootstrap;
    }
    /** 命令恢复带原完整scope及摘要；事件恢复只定位不可变原事件，绝不接受替换payload。 */
    public record Request(Kind kind,CommandKey targetCommand,Integer stepShard,String stepKey,int expectedRound,int expectedStepRound,
                          Integer eventShard,String eventId,String originalDigest) { }
    /** 审计原预算/fence/错误，不复制可能512KiB的原业务payload造成双倍内存。 */
    public record Plan(int commandRound,long commandFence,int commandAttempts,String commandError,String phase,
                       Integer stepRound,Long stepFence,Integer stepAttempts,String stepError,String stepDigest,String eventDigest) { }

    /** 可信actor/reason由内部调用者提供；本服务没有公网Controller。 */
    public CommandRow submit(UUID id,Request request,String actor,String reason) {
        if(!Set.of(Kind.RETRY_STEP,Kind.REPLAY_EVENT,Kind.RECHECK).contains(request.kind()) || request.originalDigest()==null
                || !request.originalDigest().matches("[0-9a-f]{64}"))throw failure(Failure.CONFLICT);
        if(request.kind()==Kind.RETRY_STEP && (request.targetCommand()==null || !source.equals(request.targetCommand().source())
                || request.targetCommand().scopeKind()==ScopeKind.RECOVERY || (request.stepShard()==null)!=(request.stepKey()==null)))throw failure(Failure.CONFLICT);
        if(request.kind()==Kind.REPLAY_EVENT && (request.eventShard()==null || request.eventId()==null))throw failure(Failure.CONFLICT);
        if(request.eventShard()!=null)requireShard(request.eventShard());if(request.stepShard()!=null)requireShard(request.stepShard());
        binding.verify(false);var submission=new Submission(key(id),request.kind(),json.encode(request),actor,reason);
        return tx.on(submission.key().shard(),()->{
            var original=local.command(submission.key());if(original!=null)return CatalogCommandRepository.same(submission,original);
            affected(local.insertCommand(submission,null));return local.command(submission.key());
        });
    }
    /** 查询自身原结果，不触发重放、解锁或重置预算。 */
    public CommandRow query(UUID id){var row=local.command(key(id));if(row==null)throw failure(Failure.NOT_FOUND);return row;}
    /** 一次只领取本审计命令的一步，最多三次；本轮结束不会清空任何目标历史。 */
    public CommandRow advance(UUID id) {
        binding.verify(false);var key=key(id);
        var known=finishKnownReplay(id);if(known!=null)return known;
        var claim=tx.on(key.shard(),()->{
            var row=local.lockCommand(key);if(row==null)throw failure(Failure.NOT_FOUND);if(row.completed())return null;
            local.pauseSpentCommand(key);return local.claimCommand(key)==1?local.command(key):null;
        });
        if(claim==null)return query(id);
        try {
            var request=json.decode(claim.requestBytes(),Request.class);
            switch(claim.phase()) {
                case "PREPARE" -> next(claim,request.kind()==Kind.RETRY_STEP?"LOCAL":request.kind()==Kind.REPLAY_EVENT?"SEND":"CHECK",inspect(request));
                case "LOCAL" -> {
                    var plan=json.decode(claim.frozenBytes(),Plan.class);
                    if(request.stepKey()!=null && plan.stepRound()!=null)applyRetryStep(claim,request,plan);
                    next(claim,"COMMAND",plan);
                }
                case "COMMAND" -> {
                    var plan=json.decode(claim.frozenBytes(),Plan.class);applyRetryCommand(claim,request,plan);
                    finish(claim,Map.of("retriedOriginal",true,"round",plan.commandRound()+1));
                }
                case "SEND" -> {
                    var event=originalEvent(request);
                    // 原ACK已在事件库提交时，先复用它完成home；不可因broker现在不可达再次发送。
                    var saved=replayReceipt(claim,event);
                    if(saved==null) {
                        var bound=local.binding(request.eventShard());var original=codec.decode(event.keyBytes(),event.payloadBytes());
                        boolean baseline="QUIESCED".equals(bound.mode()) && original.aggregateVersion()==1
                                && original.commandId().equals(bound.initializingCommandId());
                        if(bound.stopReason()!=null || !(baseline || "ACTIVE".equals(bound.mode()) && bound.initializationReady()))throw failure(Failure.NOT_READY);
                        // 外部发送不在数据库事务内；未有持久ACK时才可在本审计ID预算内重复原字节。
                        try(var publisher=new CatalogKafkaPublisher(bootstrap,bound.clusterId(),bound.topicId())) {
                            var ack=publisher.send(event);
                            saved=tx.on(event.shardId(),()->{
                                var existing=replayReceipt(claim,event);if(existing!=null)return existing;
                                String fact=claim.key().stepKey("REPLAY_ACK");var observed=local.event(event.shardId(),event.eventId());
                                if(!OUTBOX_PUBLISHED.equals(observed.state()))affected(recovery.confirmReplay(event.shardId(),event.eventId(),event.fence(),ack.partition(),ack.offset()));
                                var result=new ReplayAck(event.eventId(),event.payloadSha256(),event.fence(),event.attempts(),ack.partition(),ack.offset());
                                byte[] receipt=json.encode(result);
                                affected(local.insertFact(event.shardId(),fact,source,claim.key().scope(),claim.commandId(),"REPLAY_ACK",receipt,sha256(receipt)));
                                return result;
                            });
                        } catch(InterruptedException e){Thread.currentThread().interrupt();throw failure(Failure.RESULT_UNKNOWN);}
                        catch(Exception e){throw e instanceof CatalogException c?c:failure(Failure.RESULT_UNKNOWN);}
                    }
                    finish(claim,Map.of("eventId",saved.eventId(),"partition",saved.partition(),"offset",saved.offset()));
                }
                case "CHECK" -> {
                    inspect(request);
                    // 只核验指定原文；不能由一条健康记录推断全shard无其他阻断并全局解锁。
                    finish(claim,Map.of("originalVerified",true,"workerAutomaticallyResumed",false));
                }
                default -> throw failure(Failure.INCONSISTENT);
            }
        } catch(RuntimeException error) {
            var observed=query(id);if(observed.completed())return observed;
            tx.on(key.shard(),()->local.failCommand(key,claim.fence(),error instanceof CatalogException c?c.failure().name():Failure.RESULT_UNKNOWN.name()));
            throw error;
        }
        return query(id);
    }
    /** 事件库已持久的首个broker确认坐标；后续恢复只能取原值。 */
    public record ReplayAck(String eventId,String digest,long fence,int attempts,int partition,long offset) { }
    private CommandRow finishKnownReplay(UUID id) {
        var row=query(id);if(row.completed())return row;
        if(!Kind.REPLAY_EVENT.name().equals(row.kind()) || !PHASE_SEND.equals(row.phase()))return null;
        var request=json.decode(row.requestBytes(),Request.class);var event=originalEvent(request);var ack=replayReceipt(row,event);
        if(ack==null)return null;
        return tx.on(row.shardId(),()->{
            var current=local.lockCommand(row.key());if(current.completed())return current;
            affected(recovery.finishKnownReplay(row.key(),current.fence(),json.encode(Map.of("eventId",ack.eventId(),"partition",ack.partition(),"offset",ack.offset()))));
            return local.command(row.key());
        });
    }
    private ReplayAck replayReceipt(CommandRow claim,CatalogOutboxRepository.EventRow event) {
        var receipt=recovery.receipt(event.shardId(),claim.key().stepKey("REPLAY_ACK"));if(receipt==null)return null;
        var ack=json.decode(receipt.payloadBytes(),ReplayAck.class);
        if(!event.eventId().equals(ack.eventId()) || !event.payloadSha256().equals(ack.digest()) || ack.partition()<0 || ack.offset()<0)
            throw failure(Failure.INCONSISTENT);
        return ack;
    }
    private Plan inspect(Request r) {
        if(r.targetCommand()==null) {
            var event=originalEvent(r);if(r.kind()==Kind.REPLAY_EVENT && !Set.of("PAUSED","PUBLISHED").contains(event.state()))throw failure(Failure.CONFLICT);
            return new Plan(0,0,0,null,null,null,null,null,null,null,event.payloadSha256());
        }
        var row=local.command(r.targetCommand());
        if(row==null)throw failure(Failure.NOT_FOUND);
        if(!row.sourceId().equals(source) || !sha256(row.requestBytes()).equals(row.requestSha256()) || !row.requestSha256().equals(r.originalDigest()))throw failure(Failure.INCONSISTENT);
        if(r.kind()==Kind.RETRY_STEP && row.round()==r.expectedRound()) {
            // QUIESCED初始化不能启动普通worker；审计目标先按原round/fence清算已到期末次，不解占有。
            var observed=row;
            tx.on(row.shardId(),()->recovery.pauseExpiredCommand(observed.key(),observed.round(),observed.fence()));
            row=local.command(r.targetCommand());
        }
        if(r.kind()==Kind.RETRY_STEP && (!State.PAUSED.name().equals(row.state()) || row.round()!=r.expectedRound()))throw failure(Failure.CONFLICT);
        StepRow step=null;
        if(r.stepKey()!=null) {
            step=local.step(r.stepShard(),r.stepKey());
            if(step==null)throw failure(Failure.NOT_FOUND);
            if(!step.sourceId().equals(source) || !step.requestSha256().equals(sha256(step.requestBytes())))throw failure(Failure.INCONSISTENT);
            if(!step.commandScope().equals(r.targetCommand().scope()) || !step.commandId().equals(r.targetCommand().command())
                    || step.round()!=r.expectedStepRound())throw failure(Failure.CONFLICT);
            if(r.kind()==Kind.RETRY_STEP) {
                var observed=step;
                tx.on(r.stepShard(),()->recovery.pauseExpiredStep(r.stepShard(),r.stepKey(),observed.round(),observed.fence()));
                step=local.step(r.stepShard(),r.stepKey());
            }
            if(step.round()!=r.expectedStepRound() || !Set.of("PAUSED","APPLIED").contains(step.state()))throw failure(Failure.CONFLICT);
            if(step.applied() && (!sha256(step.resultBytes()).equals(step.resultSha256()) || step.outboxId()==null))throw failure(Failure.INCONSISTENT);
        }
        return new Plan(row.round(),row.fence(),row.attempts(),row.errorType(),row.phase(),step==null || step.applied()?null:step.round(),
                step==null?null:step.fence(),step==null?null:step.attempts(),step==null?null:step.errorType(),step==null?null:step.requestSha256(),null);
    }
    private CatalogOutboxRepository.EventRow originalEvent(Request r) {
        if(r.eventShard()==null || r.eventId()==null)throw failure(Failure.CONFLICT);
        var row=local.event(r.eventShard(),r.eventId());
        if(row==null)throw failure(Failure.NOT_FOUND);
        if(!source.equals(row.sourceId()) || !r.originalDigest().equals(row.payloadSha256())
                || !sha256(row.payloadBytes()).equals(row.payloadSha256()))throw failure(Failure.INCONSISTENT);
        var event=codec.decode(row.keyBytes(),row.payloadBytes());
        if(!event.sourceId().equals(source) || event.ownerShard()!=r.eventShard() || !event.eventId().equals(row.eventId()))throw failure(Failure.INCONSISTENT);
        return row;
    }
    private void applyRetryStep(CommandRow claim,Request request,Plan plan) {
        tx.on(request.stepShard(),()->{
            String fact=claim.key().stepKey("RETRY_LOCAL");var original=recovery.receipt(request.stepShard(),fact);
            if(original!=null){if(!Arrays.equals(original.payloadBytes(),claim.frozenBytes()))throw failure(Failure.INCONSISTENT);return null;}
            affected(recovery.retryStep(request.stepShard(),request.stepKey(),plan.stepRound(),plan.stepFence()));
            fact(request.stepShard(),claim,"RETRY_LOCAL");return null;
        });
    }
    private void applyRetryCommand(CommandRow claim,Request request,Plan plan) {
        tx.on(request.targetCommand().shard(),()->{
            String fact=claim.key().stepKey("RETRY_COMMAND");var original=recovery.receipt(request.targetCommand().shard(),fact);
            if(original!=null){if(!Arrays.equals(original.payloadBytes(),claim.frozenBytes()))throw failure(Failure.INCONSISTENT);return null;}
            affected(recovery.retryCommand(request.targetCommand(),plan.commandRound(),plan.commandFence()));
            fact(request.targetCommand().shard(),claim,"RETRY_COMMAND");return null;
        });
    }
    private void fact(int shard,CommandRow claim,String step) {
        affected(local.insertFact(shard,claim.key().stepKey(step),source,claim.key().scope(),claim.commandId(),step,claim.frozenBytes(),sha256(claim.frozenBytes())));
    }
    private void next(CommandRow claim,String phase,Plan plan){tx.on(claim.shardId(),()->{affected(local.advanceCommand(claim.key(),claim.fence(),phase,phase,json.encode(plan)));return null;});}
    private void finish(CommandRow claim,Object result){tx.on(claim.shardId(),()->{affected(local.finishCommand(claim.key(),claim.fence(),json.encode(result)));return null;});}
    private CommandKey key(UUID id){return new CommandKey((int)(id.getLeastSignificantBits()&1),source,ScopeKind.RECOVERY,"RECOVERY",id);}
}
