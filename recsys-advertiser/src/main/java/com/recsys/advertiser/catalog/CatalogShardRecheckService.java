package com.recsys.advertiser.catalog;

import com.recsys.advertiser.persistence.mapper.primary.*;
import java.util.*;
import static com.recsys.advertiser.catalog.CatalogProtocol.*;
import static com.recsys.advertiser.catalog.CatalogCommandRepository.affected;

/** 显式全分片RECHECK；全部原事件/receipt/schema/拓扑一致才恢复该分片，其他PAUSED占有保持。 */
public final class CatalogShardRecheckService {
    private final CatalogLocalMapper local;private final CatalogRecheckMapper checks;
    private final CatalogLocalTransactions tx;private final CatalogRuntimeBinding binding;
    private final CatalogJson json;private final CatalogSnapshotCodec codec;private final UUID source;private final String bootstrap;
    public CatalogShardRecheckService(CatalogLocalMapper local,CatalogRecheckMapper checks,CatalogLocalTransactions tx,
            CatalogRuntimeBinding binding,CatalogJson json,CatalogSnapshotCodec codec,UUID source,String bootstrap) {
        this.local=local;this.checks=checks;this.tx=tx;this.binding=binding;this.json=json;this.codec=codec;this.source=source;this.bootstrap=bootstrap;
    }
    public record Input(int shard,long revision,String stopReason,String schemaDigest) { }
    public record Cursor(String after,long examined) { }
    /** 同ID原输入在当前状态读取之前返回；另一停止修订须另一个审计ID。 */
    public CommandRow submit(int shard,UUID id,String actor,String reason) {
        var key=key(shard,id);binding.verify(false);
        return tx.on(shard,()->{
            var prior=local.command(key);if(prior!=null)return prior;
            var stop=checks.stop(shard);var control=local.binding(shard);
            if(stop.stopReason()==null || !"ACTIVE".equals(control.mode()) || !source.equals(control.sourceId()) || !control.initializationReady())throw failure(Failure.NOT_READY);
            var input=new Input(shard,stop.revision(),stop.stopReason(),local.schemaChecksum(shard));
            affected(local.insertCommand(new Submission(key,Kind.RECHECK,json.encode(input),actor,reason),null));return local.command(key);
        });
    }
    /** 每轮最多十条512KiB原事实，一步最多一次Kafka身份查询，不无限扫描或自动循环start。 */
    public CommandRow advance(int shard,UUID id) {
        var key=key(shard,id);binding.verify(false);
        var claim=tx.on(shard,()->{var row=local.lockCommand(key);if(row==null)throw failure(Failure.NOT_FOUND);if(row.completed())return null;
            local.pauseSpentCommand(key);return local.claimCommand(key)==1?local.command(key):null;});
        if(claim==null)return local.command(key);
        try {
            var input=json.decode(claim.requestBytes(),Input.class);requireSame(input);
            var cursor=claim.frozenBytes()==null?new Cursor("",0):json.decode(claim.frozenBytes(),Cursor.class);
            switch(claim.phase()) {
                case "PREPARE" -> {
                    var identity=CatalogKafkaPublisher.identity(bootstrap);var control=local.binding(shard);
                    if(!identity.clusterId().equals(control.clusterId()) || !identity.topicId().equals(control.topicId()))throw failure(Failure.INCONSISTENT);
                    next(claim,"EVENTS",cursor);
                }
                case "EVENTS" -> {
                    var rows=checks.events(shard,cursor.after());
                    for(var row:rows) {
                        var event=codec.decode(row.keyBytes(),row.payloadBytes());
                        if(!source.equals(event.sourceId()) || event.ownerShard()!=shard || !event.eventId().equals(row.eventId())
                                || event.adId()!=row.adId() || event.aggregateVersion()!=row.aggregateVersion() || !sha256(row.payloadBytes()).equals(row.payloadSha256()))throw failure(Failure.INCONSISTENT);
                    }
                    next(claim,rows.size()==10?"EVENTS":"STEPS",new Cursor(rows.size()==10?rows.getLast().outboxId():"",cursor.examined()+rows.size()));
                }
                case "STEPS" -> {
                    var rows=checks.steps(shard,cursor.after());
                    for(var row:rows) {
                        if(!source.equals(row.sourceId()) || !sha256(row.requestBytes()).equals(row.requestSha256()))throw failure(Failure.INCONSISTENT);
                        if(row.applied() && (!sha256(row.resultBytes()).equals(row.resultSha256()) || !checks.receiptValid(shard,row.stepKey())))throw failure(Failure.INCONSISTENT);
                    }
                    next(claim,rows.size()==10?"STEPS":"RESUME",new Cursor(rows.size()==10?rows.getLast().stepKey():"",cursor.examined()+rows.size()));
                }
                case "RESUME" -> tx.on(shard,()->{
                    requireSame(input);affected(checks.resume(shard,input.revision(),input.stopReason()));
                    affected(local.finishCommand(key,claim.fence(),json.encode(Map.of("shard",shard,"examined",cursor.examined(),"resumed",true))));return null;
                });
                default -> throw failure(Failure.INCONSISTENT);
            }
        } catch(RuntimeException error) {
            var current=local.command(key);if(current.completed())return current;
            tx.on(shard,()->local.failCommand(key,claim.fence(),error instanceof CatalogException c?c.failure().name():Failure.RESULT_UNKNOWN.name()));throw error;
        }
        return local.command(key);
    }
    private void requireSame(Input input) {
        var stop=checks.stop(input.shard());
        if(stop.revision()!=input.revision() || !Objects.equals(stop.stopReason(),input.stopReason())
                || !CatalogSchemaInstaller.checksum().equals(input.schemaDigest()) || !local.compatibleSchema(input.shard()))throw failure(Failure.INCONSISTENT);
    }
    private void next(CommandRow claim,String phase,Cursor cursor){tx.on(claim.shardId(),()->{affected(local.advanceCommand(claim.key(),claim.fence(),phase,phase,json.encode(cursor)));return null;});}
    private CommandKey key(int shard,UUID id){return new CommandKey(shard,source,ScopeKind.RECOVERY,"SHARD_RECHECK",id);}
}
