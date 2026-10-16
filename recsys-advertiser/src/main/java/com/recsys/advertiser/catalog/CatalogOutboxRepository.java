package com.recsys.advertiser.catalog;

import com.recsys.advertiser.persistence.mapper.primary.CatalogLocalMapper;
import com.recsys.common.ad.CatalogEventCodec;
import com.recsys.common.ad.VersionedAdCatalogEvent;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import static com.recsys.advertiser.catalog.CatalogProtocol.*;

/** 本库完整事件待办与发送租约；事务内只访问本库，Kafka发送由relay在返回后完成。 */
public final class CatalogOutboxRepository {
    private final CatalogLocalMapper mapper;
    private final CatalogLocalTransactions transactions;
    private final CatalogSnapshotCodec codec;
    public CatalogOutboxRepository(CatalogLocalMapper mapper,CatalogLocalTransactions transactions,CatalogSnapshotCodec codec) {
        this.mapper=mapper;this.transactions=transactions;this.codec=codec;
    }

    /** 必须在owner业务步骤同一事务追加，完整事件原字节只写一次。 */
    public void append(CommandKey command,String stepId,VersionedAdCatalogEvent event,byte[] original) {
        if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("目录事件需要本库业务事务");
        var decoded=codec.decode(CatalogEventCodec.key(event.adId()),original);
        if(!decoded.equals(event) || (event.ownerShard()!=command.shard() && command.scopeKind()!=ScopeKind.INITIALIZE) || !event.sourceId().equals(command.source())
                || !event.commandId().equals(command.command()))throw failure(Failure.INCONSISTENT);
        var insert=new PendingEvent(event.ownerShard(),event.eventId(),event.eventType().name(),event.sourceId(),event.adId(),
                event.aggregateVersion(),command.scope(),command.command(),stepId,event.eventId(),VersionedAdCatalogEvent.TOPIC,
                CatalogEventCodec.key(event.adId()),original,CatalogEventCodec.digest(original));
        CatalogCommandRepository.affected(mapper.insertKafkaEvent(insert));
    }

    /** 一次最多10个候选，仅领取一条以免串行等待耗尽后续租约，包含到期耗尽的清算；跳过别的实例已锁定行，不无限扫描。 */
    public List<EventRow> claim(int shard,UUID source) {
        return transactions.on(shard,()->{
            var control=mapper.lockControl(shard);
            if(control==null || !source.toString().equals(control.sourceId()) || !"ACTIVE".equals(control.mode())
                    || !control.initializationReady() || control.stopReason()!=null)throw failure(Failure.NOT_READY);
            for(String id:mapper.lockReadyEventIds(shard)) {
                EventRow row=mapper.event(shard,id);
                if(row.attempts()>=SEND_ATTEMPTS){mapper.pauseSpentEvent(shard,row.outboxId());continue;}
                try {
                    var event=codec.decode(row.keyBytes(),row.payloadBytes());
                    if(!event.sourceId().equals(source) || event.ownerShard()!=shard || !event.eventId().equals(row.eventId())
                            || event.adId()!=row.adId() || event.aggregateVersion()!=row.aggregateVersion()
                            || !CatalogEventCodec.digest(row.payloadBytes()).equals(row.payloadSha256())
                            || !VersionedAdCatalogEvent.TOPIC.equals(row.topic()))throw failure(Failure.INCONSISTENT);
                } catch(IllegalArgumentException | CatalogException bad) {
                    mapper.stopShard(shard,Failure.INCONSISTENT.name());
                    // 返回前提交STOPPED原因，不能抛异常回滚这个停止事实。
                    return List.of();
                }
                if(mapper.claimEvent(shard,row.outboxId())==1)return List.of(mapper.event(shard,row.outboxId()));
            }
            return List.of();
        });
    }

    /** 初始化仅可领取本初始化命令的基线；QUIESCED不放行普通relay或任何业务写。 */
    public EventRow claimBaseline(CommandKey initialization,int shard,String id) {
        if(initialization.scopeKind()!=ScopeKind.INITIALIZE)throw failure(Failure.CONFLICT);
        return transactions.on(shard,()->{
            var control=mapper.binding(shard);var row=mapper.event(shard,id);
            if(control==null || !"QUIESCED".equals(control.mode()) || control.stopReason()!=null
                    || !initialization.source().equals(control.sourceId()) || !initialization.command().equals(control.initializingCommandId())
                    || row==null)throw failure(Failure.NOT_READY);
            var event=codec.decode(row.keyBytes(),row.payloadBytes());
            if(!event.sourceId().equals(initialization.source()) || !event.commandId().equals(initialization.command()) || event.ownerShard()!=shard || !event.eventId().equals(id)
                    || !sha256(row.payloadBytes()).equals(row.payloadSha256()) || event.aggregateVersion()!=1)throw failure(Failure.INCONSISTENT);
            if(OUTBOX_PUBLISHED.equals(row.state()))return row;
            mapper.pauseSpentEvent(shard,id);
            return mapper.claimEvent(shard,id)==1?mapper.event(shard,id):null;
        });
    }

    /** 真实broker元数据只有在有效state/fence/lease下才可确认，迟到ACK不能覆盖接管者。 */
    public boolean published(EventRow claimed,int partition,long offset) {
        if(partition<0 || offset<0)throw new IllegalArgumentException("无效Kafka确认坐标");
        return transactions.on(claimed.shardId(),()->mapper.markEventPublished(claimed.shardId(),claimed.outboxId(),claimed.fence(),partition,offset)==1);
    }
    /** 未知ACK保留原文，发送预算有上限；不能把超时当broker一定没收到。 */
    public boolean failed(EventRow claimed,Failure error) {
        return transactions.on(claimed.shardId(),()->mapper.failEvent(claimed.shardId(),claimed.outboxId(),claimed.fence(),error.name())==1);
    }
    /** 绑定身份冲突持久停止本shard；修复必须经受控全shard核验，不能周期任务自启。 */
    public void stop(int shard,Failure reason){transactions.on(shard,()->mapper.stopShard(shard,reason.name()));}
    /** 查询不触发重新发送或重读源表。 */
    public EventRow find(int shard,String id){return mapper.event(shard,id);}

    /** 仅本库插入参数；scope来自原命令，不来自新的发送进程。 */
    public record PendingEvent(int shardId,String outboxId,String recordKind,UUID sourceId,long adId,long aggregateVersion,
                               String commandScope,UUID commandId,String stepId,String eventId,String topic,
                               byte[] keyBytes,byte[] payloadBytes,String payloadSha256) { }
    /** 原文和有限发送状态保持分离。 */
    public record EventRow(int shardId,String outboxId,UUID sourceId,long adId,long aggregateVersion,String eventId,String topic,
                           byte[] keyBytes,byte[] payloadBytes,String payloadSha256,String state,int round,int attempts,
                           long fence,Instant leaseUntil,Integer brokerPartition,Long brokerOffset,String writeTxid) { }
}
