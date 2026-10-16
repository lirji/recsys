package com.recsys.recengine.filter.seen;

import com.recsys.recengine.persistence.mapper.primary.SeenRecoveryMapper;
import com.recsys.recengine.persistence.mapper.primary.SeenRecoveryMapper.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.function.Supplier;
import static com.recsys.recengine.filter.seen.SeenRecord.Code.*;

/** 主库技术事实边界；control→command→record锁序，不跨Redis/Admin持锁。 */
public final class SeenRecoveryRepository {
    public static final String MIGRATION = "db/engine-seen/V001__seen_recovery.sql";
    private final SeenRecoveryMapper mapper;
    private final TransactionTemplate tx;
    public record Claim(Command command, SeenRecord record, boolean acquired) { }

    /** 显式绑定primary事务；校验调用方不会把mapper与事务送入两个库。 */
    public SeenRecoveryRepository(SqlSessionTemplate sessions, DataSourceTransactionManager manager) {
        if (sessions.getSqlSessionFactory().getConfiguration().getEnvironment().getDataSource() != manager.getDataSource())
            throw new SeenRecord.Failure(INVALID_COMMAND);
        mapper = sessions.getMapper(SeenRecoveryMapper.class);
        tx = new TransactionTemplate(manager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        tx.setTimeout(5);
    }
    /** 启动仅校验；迁移由独立受控执行者显式执行。 */
    public void verifySchema() {
        transaction(() -> {
            if (!List.of(migrationSha256()).equals(mapper.migrationHashes())) throw new SeenRecord.Failure(MIGRATION_INVALID);
            mapper.control("LEGACY"); mapper.partitions("LEGACY"); mapper.record(new UUID(0,0)); mapper.command(new UUID(0,0));
            return null;
        });
    }
    /** 迁移工具、测试和启动共享同一脚本摘要。 */
    public static String migrationSha256() {
        try (var in = new ClassPathResource(MIGRATION).getInputStream()) {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(in.readAllBytes()));
        } catch (Exception failure) { throw new SeenRecord.Failure(MIGRATION_INVALID); }
    }
    private <T> T transaction(Supplier<T> work) {
        try { return tx.execute(status -> { mapper.tune(); return work.get(); }); }
        catch (SeenRecord.Failure known) { throw known; }
        catch (org.springframework.dao.DataAccessException | org.springframework.transaction.TransactionException unavailable) {
            Throwable root=unavailable;while(root.getCause()!=null)root=root.getCause();
            org.slf4j.LoggerFactory.getLogger(SeenRecoveryRepository.class).warn("Seen持久化失败 type={} sqlState={}",root.getClass().getSimpleName(),root instanceof java.sql.SQLException sql?sql.getSQLState():"NONE");
            throw new SeenRecord.Failure(DEPENDENCY_UNAVAILABLE);
        }
        catch (RuntimeException internal) { throw new SeenRecord.Failure(INTERNAL_ERROR); }
    }
    /** 只读恢复现场，不暴露字节；原文仅供包内受控重放使用。 */
    SeenRecord rawRecord(UUID id) { return transaction(() -> require(mapper.record(id))); }
    Command rawCommand(UUID id) { return transaction(() -> mapper.command(id)); }
    /** 返回持久控制状态供启动检查，不修改Kafka。 */
    public Control control(String stream) { return transaction(() -> mapper.control(stream)); }
    /** 起点清单最多32项，恢复时只读取已完成登记。 */
    public List<Partition> partitions(String stream) { return transaction(() -> List.copyOf(mapper.partitions(stream))); }
    /** 清算耗尽的过期领取并检查全流屏障，防止重启清零。 */
    public boolean blocked(String stream) {
        return transaction(() -> { var c = require(mapper.lockControl(stream)); mapper.settleExpired(stream); return c.initializingCommandId()!=null || c.stopReason()!=null || mapper.barriers(stream)>0; });
    }
    /** 位点不能越过持久未完成义务；broker仍是提交位点权威。 */
    public boolean hasGap(String stream, int partition, long committed) { return transaction(() -> mapper.gaps(stream,partition,committed)>0); }
    /** PG失败时此写也可能失败，调用者必须仍停止容器并保留未知状态。 */
    public void stop(String stream, SeenRecord.Code reason) { transaction(() -> { one(mapper.stop(stream,reason.name())); return null; }); }
    /** 仅供已完成外部身份/offset核查的显式恢复入口调用，不由定时器自动清除。 */
    void clearStop(String stream) {
        transaction(() -> { var c=require(mapper.lockControl(stream));
            if(c.initializingCommandId()!=null || mapper.barriers(stream)>0) throw new SeenRecord.Failure(STREAM_PAUSED);
            one(mapper.stop(stream,null)); return null;
        });
    }
    /** 副作用前在同一短事务持久占有整个流；不同命令不能先写Kafka后争唯一键。 */
    Command initialize(Control binding, Command proposed, List<Integer> existingPartitions) {
        return transaction(() -> {
            mapper.insertControl(binding); var c=require(mapper.lockControl(binding.stream())); sameBinding(c,binding);
            var old=mapper.lockCommand(proposed.commandId());
            if(old!=null) { sameInitialization(old,proposed); return old; }
            if(c.initializingCommandId()!=null || !mapper.partitions(binding.stream()).stream().map(Partition::partitionId).toList().equals(existingPartitions)) throw new SeenRecord.Failure(COMMAND_CONFLICT);
            one(mapper.insertCommand(proposed)); one(mapper.ownInitialization(c.stream(),proposed.commandId()));
            return require(mapper.command(proposed.commandId()));
        });
    }
    /** 每次外部offset副作用前重查当前持久所有权和有效领取，过期执行器不能发起新调用。 */
    void initializationActive(Command claim) {
        transaction(()->{var c=require(mapper.lockControl(claim.stream()));var current=require(mapper.lockCommand(claim.commandId()));
            if(!claim.commandId().equals(c.initializingCommandId()) || !valid(current,claim))throw new SeenRecord.Failure(ACTIVE_LEASE);return null;});
    }
    /** 成功的冻结分区绑定与命令结果原子提交；异常保留初始化所有权。 */
    void initialized(Command claim, List<Partition> partitions) {
        transaction(() -> {
            var c=require(mapper.lockControl(claim.stream())); var current=require(mapper.lockCommand(claim.commandId()));
            if(!claim.commandId().equals(c.initializingCommandId()) || !valid(current,claim)) throw new SeenRecord.Failure(ACTIVE_LEASE);
            for(var p:partitions) one(mapper.insertPartition(p));
            one(mapper.finishCommand(claim.commandId(),claim.fence(),SeenRecord.CommandState.SUCCEEDED.name(),"INITIALIZED"));
            one(mapper.releaseInitialization(claim.stream(),claim.commandId())); return null;
        });
    }
    /** 注册原字节、分类并领预算；隔离提交失败时调用方不会获得可确认结果。 */
    SeenRecord register(String stream, int partition, long offset, byte[] key, byte[] value, SeenMessageDecoder.Decoded decoded) {
        return transaction(() -> {
            var c=require(mapper.lockControl(stream));
            if(c.initializingCommandId()!=null || c.stopReason()!=null) throw new SeenRecord.Failure(STREAM_STOPPED);
            if(mapper.partitions(stream).stream().noneMatch(p->p.partitionId()==partition)) throw new SeenRecord.Failure(OFFSET_GAP);
            boolean complete=(key==null || key.length<=SeenMessageDecoder.MAX_KEY) && (value==null || value.length<=SeenMessageDecoder.MAX_VALUE);
            String state=switch(SeenRecord.Classification.valueOf(decoded.classification())) {case POSITIVE->SeenRecord.State.READY.name();case NO_EFFECT->SeenRecord.State.NO_EFFECT.name();default->SeenRecord.State.QUARANTINED.name();};
            var proposed=new SeenRecord(UUID.randomUUID(),stream,c.clusterId(),c.topicId(),c.topic(),c.groupId(),partition,offset,
                    complete?key:null,complete?value:null,key==null?-1:key.length,value==null?-1:value.length,
                    SeenMessageDecoder.digest(key,value),complete,decoded.classification(),decoded.error(),state,0,0,0,null,null,decoded.error(),null,null,false,true);
            mapper.insertRecord(proposed); var stored=require(mapper.coordinate(stream,partition,offset));
            if(!stored.digest().equals(proposed.digest())) throw new SeenRecord.Failure(COORDINATE_CONFLICT);
            mapper.expireRecord(stored.recordId()); return require(mapper.record(stored.recordId()));
        });
    }
    SeenRecord claim(SeenRecord record) {
        return transaction(() -> {
            var c=require(mapper.lockControl(record.stream()));
            if(c.initializingCommandId()!=null || c.stopReason()!=null || mapper.barriers(record.stream())>0) return null;
            mapper.lockRecord(record.recordId());
            if(mapper.claimRecord(record.recordId())!=1) return null;
            return require(mapper.record(record.recordId()));
        });
    }
    /** 有效租约/fence才可记成功，stale执行器即使SADD成功也无权ACK。 */
    boolean applied(SeenRecord claim) { return transaction(() -> mapper.finishRecord(claim.recordId(),claim.fence(),SeenRecord.State.APPLIED.name(),null)==1); }
    boolean failed(SeenRecord claim, long delay) { return transaction(() -> mapper.failRecord(claim.recordId(),claim.fence(),DEPENDENCY_UNAVAILABLE.name(),delay)==1); }

    /** 新命令明确开启新epoch；旧字节与首次分类不变，前轮预算保留于审计。 */
    Command request(Command proposed) {
        return transaction(() -> {
            require(mapper.lockControl(proposed.stream())); var old=mapper.lockCommand(proposed.commandId());
            if(old!=null) {sameCommand(old,proposed);return old;}
            var record=require(mapper.lockRecord(proposed.recordId())); mapper.expireRecord(record.recordId()); record=require(mapper.record(record.recordId()));
            if(record.activeCommandId()!=null || record.leaseActive()) throw new SeenRecord.Failure(ACTIVE_LEASE);
            String expected=switch(SeenRecord.CommandKind.valueOf(proposed.kind())) {case RETRY->SeenRecord.State.PAUSED.name();case RECHECK->SeenRecord.State.QUARANTINED.name();case RESTORE->SeenRecord.State.APPLIED.name();default->throw new SeenRecord.Failure(INVALID_COMMAND);};
            if(!expected.equals(record.status())) throw new SeenRecord.Failure(NOT_ELIGIBLE);
            var command=new Command(proposed.commandId(),proposed.kind(),proposed.stream(),record.recordId(),proposed.requestJson(),proposed.requestDigest(),
                    proposed.actor(),proposed.reason(),SeenRecord.CommandState.READY.name(),null,0,0,null,record.status(),record.epoch(),record.attempts(),null,null,false,true);
            one(mapper.insertCommand(command));one(mapper.attachCommand(record.recordId(),command.commandId()));return require(mapper.command(command.commandId()));
        });
    }
    /** 一次advance最多一次外部尝试；最后一次退出后仍会清算终态并释放record。 */
    Claim claimCommand(UUID id) {
        var hint=require(rawCommand(id));
        return transaction(() -> {
            require(mapper.lockControl(hint.stream()));var c=require(mapper.lockCommand(id));
            if(c.terminal()) return new Claim(c,null,false);
            if(mapper.expireCommand(id)==1) {
                if(c.recordId()!=null) one(mapper.releaseRecord(c.recordId(),id,SeenRecord.CommandKind.RECHECK.name().equals(c.kind())?SeenRecord.State.QUARANTINED.name():SeenRecord.State.PAUSED.name()));
                return new Claim(require(mapper.command(id)),null,false);
            }
            if(mapper.claimCommand(id)!=1) return new Claim(c,null,false);
            SeenRecord r=null;
            if(c.recordId()!=null) {
                r=require(mapper.lockRecord(c.recordId()));
                if(!id.equals(r.activeCommandId())) throw new SeenRecord.Failure(COMMAND_CONFLICT);
                one(mapper.claimRecoveryRecord(c.recordId())); r=require(mapper.record(c.recordId()));
            }
            return new Claim(require(mapper.command(id)),r,true);
        });
    }
    void recovered(Claim claim, String state, String result, boolean succeeded) {
        transaction(() -> {
            var c=claim.command(); require(mapper.lockControl(c.stream())); var current=require(mapper.lockCommand(c.commandId()));
            var r=require(mapper.lockRecord(claim.record().recordId()));
            if(!valid(current,c) || !c.commandId().equals(r.activeCommandId())) throw new SeenRecord.Failure(ACTIVE_LEASE);
            one(mapper.finishRecord(r.recordId(),claim.record().fence(),state,SeenRecord.State.QUARANTINED.name().equals(state)?SeenRecord.Code.INVALID_DATA.name():null));
            one(mapper.finishCommand(c.commandId(),c.fence(),succeeded?SeenRecord.CommandState.SUCCEEDED.name():SeenRecord.CommandState.FAILED.name(),result));
            one(mapper.releaseRecord(r.recordId(),c.commandId(),state)); return null;
        });
    }
    void commandFailed(Claim claim, SeenRecord.Code error, long delay) {
        transaction(() -> {
            var c=claim.command();require(mapper.lockControl(c.stream()));var current=require(mapper.lockCommand(c.commandId()));
            if(!valid(current,c)) throw new SeenRecord.Failure(ACTIVE_LEASE);
            one(mapper.failCommand(c.commandId(),c.fence(),error.name(),delay));
            if(claim.record()!=null) {
                one(mapper.failRecord(claim.record().recordId(),claim.record().fence(),error.name(),delay));
                if(c.attempts()==3) one(mapper.releaseRecord(claim.record().recordId(),c.commandId(),SeenRecord.CommandKind.RECHECK.name().equals(c.kind())?SeenRecord.State.QUARANTINED.name():SeenRecord.State.PAUSED.name()));
            }
            return null;
        });
    }
    /** PG是状态数量权威，失败由指标层显示NaN，不能包装为零积压。 */
    public long statusCount(String status) {return statusCount(SeenStream.LEGACY.name(),status);}
    /** 各流分别观测，禁止新版借用LEGACY计数。 */
    public long statusCount(String stream,String status) {
        SeenStream.valueOf(stream);
        if(!Set.of(SeenRecord.State.READY.name(),SeenRecord.State.APPLYING.name(),SeenRecord.State.APPLIED.name(),SeenRecord.State.NO_EFFECT.name(),SeenRecord.State.QUARANTINED.name(),SeenRecord.State.PAUSED.name()).contains(status))throw new SeenRecord.Failure(INVALID_COMMAND);
        return transaction(()->mapper.statusCount(stream,status));
    }
    /** 只在显式指标采集时读取，不新增扫描调度器或自动清理。 */
    public double oldestPendingSeconds() {return oldestPendingSeconds(SeenStream.LEGACY.name());}
    /** 查询指定流的积压年龄，异常继续由指标层报告未知。 */
    public double oldestPendingSeconds(String stream) {SeenStream.valueOf(stream);return transaction(()->mapper.oldestPendingSeconds(stream));}
    List<SeenRecord> quarantine(String stream,OffsetDateTime at,UUID id,int limit) { return transaction(()->mapper.quarantine(stream,at,id,limit)); }
    private boolean valid(Command current,Command claim) { return current.status().equals(SeenRecord.CommandState.RUNNING.name()) && current.fence()==claim.fence() && current.leaseActive(); }
    // 并发同ID的只读观测可能不同；请求语义相同就复用先提交的完整冻结清单。
    private static void sameInitialization(Command a, Command b) {
        if(!a.kind().equals(b.kind()) || !a.stream().equals(b.stream()) || !Objects.equals(a.recordId(),b.recordId())
                || !a.actor().equals(b.actor()) || !a.reason().equals(b.reason())) throw new SeenRecord.Failure(COMMAND_CONFLICT);
        try {
            var json=new com.fasterxml.jackson.databind.ObjectMapper();
            var first=json.readTree(a.requestJson());var next=json.readTree(b.requestJson());
            for(String key:List.of("mode","stream","clusterId","topicId","topic","groupId"))
                if(!first.path(key).equals(next.path(key)))throw new SeenRecord.Failure(COMMAND_CONFLICT);
        } catch(com.fasterxml.jackson.core.JsonProcessingException invalid) {throw new SeenRecord.Failure(COMMAND_CONFLICT);}
    }
    static void sameCommand(Command a,Command b) {
        if(!a.kind().equals(b.kind()) || !a.stream().equals(b.stream()) || !Objects.equals(a.recordId(),b.recordId())
                || !a.requestJson().equals(b.requestJson()) || !a.actor().equals(b.actor()) || !a.reason().equals(b.reason())) throw new SeenRecord.Failure(COMMAND_CONFLICT);
    }
    static void sameBinding(Control a,Control b) {
        if(!a.clusterId().equals(b.clusterId()) || !a.topicId().equals(b.topicId()) || !a.topic().equals(b.topic()) || !a.groupId().equals(b.groupId())) throw new SeenRecord.Failure(IDENTITY_CHANGED);
    }
    private static <T> T require(T v) { if(v==null)throw new SeenRecord.Failure(NOT_FOUND);return v; }
    private void one(int changed) { if(changed!=1)throw new SeenRecord.Failure(ACTIVE_LEASE); }
}
