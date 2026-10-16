package com.recsys.recengine.filter.seen;

import com.recsys.recengine.persistence.mapper.primary.SeenRecoveryMapper.Command;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.*;
import static com.recsys.recengine.filter.seen.SeenRecord.Code.*;

/** 仅engine内部受控入口：无HTTP、无调度；caller提供可信actor/目标授权。 */
public final class SeenRecoveryService {
    public enum Mode { RETRY, RECHECK, RESTORE }
    public record CommandReport(UUID commandId,String kind,String status,String result,int attempts,long fence,
                                OffsetDateTime leaseUntil,OffsetDateTime completedAt) { }
    public record RecordReport(UUID recordId,String status,String firstClassification,String error,int attempts,long epoch,
                               long fence,boolean payloadComplete,String digest,int partition,long offset,
                               UUID activeCommandId,OffsetDateTime createdAt) { }
    private final SeenRecoveryRepository repository;
    private final SeenOffsetInitializer offsets;
    private final SeenProjectionService projection;
    private final SeenMessageDecoder decoder;
    private final boolean enabled;
    private final SeenStream stream;
    /** 受控能力不自动开启；外部适配仍须提供可信主体。 */
    public SeenRecoveryService(SeenRecoveryRepository repository,SeenOffsetInitializer offsets,SeenProjectionService projection,SeenMessageDecoder decoder,boolean enabled) {
        this(repository,offsets,projection,decoder,enabled,SeenStream.LEGACY);
    }
    /** 每个内部用例绑定一条流，不能借另一流的组/管理权限恢复任意记录。 */
    public SeenRecoveryService(SeenRecoveryRepository repository,SeenOffsetInitializer offsets,SeenProjectionService projection,SeenMessageDecoder decoder,boolean enabled,SeenStream stream) {
        this.repository=repository;this.offsets=offsets;this.projection=projection;this.decoder=decoder;this.enabled=enabled;this.stream=stream;
    }
    /** 先持久冻结请求，不在登记操作内调用alter。 */
    public CommandReport initialize(UUID id,SeenOffsetInitializer.Mode mode,String actor,String reason) {
        requireEnabled();if(mode==null)throw new SeenRecord.Failure(INVALID_COMMAND);
        return report(offsets.initialize(id,mode,actor,reason));
    }
    /** 单记录原字节恢复；同ID改任何请求内容冲突，不接受新payload。 */
    public CommandReport requestRecovery(UUID id,UUID recordId,Mode mode,String expectedDigest,String actor,String reason) {
        requireEnabled();audit(id,actor,reason);
        if(recordId==null || mode==null || expectedDigest==null || !expectedDigest.matches("[0-9a-f]{64}"))throw new SeenRecord.Failure(INVALID_COMMAND);
        var record=repository.rawRecord(recordId);
        requireStream(record.stream());
        if(!record.digest().equals(expectedDigest))throw new SeenRecord.Failure(COMMAND_CONFLICT);
        String request=recordId+":"+mode+":"+expectedDigest;
        return report(repository.request(command(id,mode.name(),record.stream(),recordId,request,actor,reason)));
    }
    /** 单次最多一次外部尝试；结果未知保留同ID和预算，下次先查持久现状。 */
    public CommandReport advance(UUID id) {
        requireEnabled();if(id==null)throw new SeenRecord.Failure(INVALID_COMMAND);
        report(id); // 先核对绑定流，跨流调用不得消耗目标命令预算。
        var claim=repository.claimCommand(id);var command=claim.command();
        if(!claim.acquired())return report(command);
        io.micrometer.core.instrument.Metrics.counter("recsys.seen.attempts","stream",command.stream(),"path","recovery").increment();
        try {
            if(command.kind().equals(SeenRecord.CommandKind.INITIALIZE.name())) {offsets.advance(command);return report(id);}
            var record=claim.record();
            byte[][] bytes=record.payloadComplete()?new byte[][]{record.keyBytes(),record.valueBytes()}:offsets.fetch(record);
            var decoded=decoder.decode(command.stream(),bytes[0],bytes[1]);
            if(decoded.classification().equals(SeenRecord.Code.INVALID_DATA.name()))repository.recovered(claim,SeenRecord.State.QUARANTINED.name(),"STILL_INVALID",false);
            else if(decoded.classification().equals(SeenRecord.State.NO_EFFECT.name()))repository.recovered(claim,SeenRecord.State.NO_EFFECT.name(),SeenRecord.State.NO_EFFECT.name(),true);
            else {
                projection.applyRedis(decoded); // RESTORE必须真实再次SADD，不能被原APPLIED短路。
                repository.recovered(claim,SeenRecord.State.APPLIED.name(),SeenRecord.State.APPLIED.name(),true);
            }
        } catch(SeenRecord.Failure failure) {
            // PG结果提交未知不能假定失败并覆盖；先读命令终态再决定是否记可重试依赖失败。
            var observed=repository.rawCommand(id);
            if(observed!=null && observed.terminal())return report(observed);
            if(failure.code()==SOURCE_UNAVAILABLE || failure.code()==IDENTITY_CHANGED || failure.code()==COORDINATE_CONFLICT) {
                if(claim.record()!=null)repository.recovered(claim,SeenRecord.State.QUARANTINED.name(),failure.code().name(),false);
                else repository.commandFailed(claim,failure.code(),SeenProjectionService.delay(command.attempts()));
            } else if(failure.code()==DEPENDENCY_UNAVAILABLE || failure.code()==ACTIVE_LEASE || failure.code()==OFFSET_GAP || failure.code()==COMMAND_CONFLICT) {
                repository.commandFailed(claim,failure.code(),SeenProjectionService.delay(command.attempts()));
            } else throw failure;
        }
        var result=report(id);
        io.micrometer.core.instrument.Metrics.counter("recsys.seen.recovery","stream",command.stream(),"kind",command.kind(),"status",result.status()).increment();
        return result;
    }
    /** 查结果不续办、不重置预算。 */
    public CommandReport report(UUID id) { var command=repository.rawCommand(id);if(command==null)throw new SeenRecord.Failure(NOT_FOUND);requireStream(command.stream());return report(command); }
    /** 查询仅返回脱敏技术状态，不输出key/value或业务ID。 */
    public RecordReport record(UUID id) {var value=repository.rawRecord(id);requireStream(value.stream());return record(value);}
    /** 隔离查询稳定游标且不返回原消息。 */
    public List<RecordReport> quarantine(OffsetDateTime at,UUID id,int limit) {
        if(limit<1 || limit>100 || (at==null)!=(id==null))throw new SeenRecord.Failure(INVALID_COMMAND);
        return repository.quarantine(stream.name(),at,id,limit).stream().map(this::record).toList();
    }
    /** 只读核查成功且整流无屏障后解除持久停止；容器仍由显式调用方启动/恢复。 */
    public void authorizeResume() {requireEnabled();offsets.validate();repository.clearStop(stream.name());}
    private RecordReport record(SeenRecord r) {return new RecordReport(r.recordId(),r.status(),r.firstClassification(),r.lastError(),r.attempts(),r.epoch(),r.fence(),r.payloadComplete(),r.digest(),r.partitionId(),r.recordOffset(),r.activeCommandId(),r.createdAt());}
    private CommandReport report(Command c) {return new CommandReport(c.commandId(),c.kind(),c.status(),c.result(),c.attempts(),c.fence(),c.leaseUntil(),c.completedAt());}
    private void requireStream(String value) {if(!stream.name().equals(value))throw new SeenRecord.Failure(NOT_ELIGIBLE);}
    private void requireEnabled() {if(!enabled)throw new SeenRecord.Failure(NOT_ELIGIBLE);}
    static void audit(UUID id,String actor,String reason) {
        if(id==null || actor==null || actor.isBlank() || actor.length()>200 || reason==null || reason.isBlank() || reason.length()>1000)throw new SeenRecord.Failure(INVALID_COMMAND);
    }
    static Command command(UUID id,String kind,String stream,UUID record,String request,String actor,String reason) {
        return new Command(id,kind,stream,record,request,SeenMessageDecoder.digest(null,request.getBytes(StandardCharsets.UTF_8)),actor,reason,
                SeenRecord.CommandState.READY.name(),null,0,0,null,null,null,null,null,null,false,true);
    }
}
