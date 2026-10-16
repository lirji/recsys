package com.recsys.advertiser.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recsys.advertiser.persistence.mapper.primary.CatalogLocalMapper;
import org.springframework.dao.DuplicateKeyException;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.Supplier;

import static com.recsys.advertiser.catalog.CatalogProtocol.*;
import static com.recsys.advertiser.catalog.CatalogCommandRepository.affected;

/** 本地步骤独立领取/防迟到/原结果；协调者只有核验本记录后才可推进或释放owner。 */
public final class CatalogStepRepository {
    private final CatalogLocalMapper mapper;
    private final CatalogLocalTransactions transactions;
    private final ObjectMapper json;
    public CatalogStepRepository(CatalogLocalMapper mapper,CatalogLocalTransactions transactions,ObjectMapper json) {
        this.mapper=mapper;this.transactions=transactions;this.json=json;
    }

    /** 返回原结果，APPLIED后不得重读现库作为历史结果。 */
    public StepRow find(StepRequest step){return mapper.step(step.shard(),step.stepKey());}

    /** 仅接受协调者已持久派发的固定步骤；重复时逐字段核验原输入。 */
    public StepRow register(StepRequest request) {
        try {
            return transactions.on(request.shard(),()->{
                requireControl(request);
                StepRow original=find(request);
                if(original!=null)return same(request,original);
                lockLocalOwner(request);
                affected(mapper.insertStep(request));return find(request);
            });
        } catch(DuplicateKeyException raced) {
            StepRow original=find(request);
            if(original==null)throw failure(Failure.INCONSISTENT);
            return same(request,original);
        }
    }

    /** 本地领取必须先于业务事务；到期只接管原输入，第三次退出会清算为PAUSED。 */
    public StepRow claim(StepRequest request) {
        return transactions.on(request.shard(),()->{
            requireControl(request);
            StepRow original=find(request);
            if(original==null)throw failure(Failure.NOT_FOUND);
            same(request,original);
            if(original.applied())return original;
            lockLocalOwner(request);
            mapper.pauseSpentStep(request.shard(),request.stepKey());
            if(mapper.claimStep(request.shard(),request.stepKey())==0)return null;
            return find(request);
        });
    }

    /** callback只执行目标库SQL并返回冻结原文；调用处不能在callback内访问其他库或外部系统。 */
    public StepRow apply(StepRequest request,StepRow claim,Supplier<LocalResult> mutation) {
        return transactions.on(request.shard(),()->{
            requireControl(request);
            StepRow existing=find(request);
            same(request,existing);
            if(existing.applied())return existing;
            // PG函数持行锁并验证本地租约，不能只用owner的fence来约束另一数据库。
            mapper.enterStep(request.shard(),request.stepKey(),claim.fence());
            LocalResult result=mutation.get();requireBytes(result.bytes());
            validateEffects(result);
            String fact=request.stepKey();String digest=sha256(result.bytes());
            affected(mapper.insertFact(request.shard(),fact,request.commandKey().source(),request.commandKey().scope(),
                    request.commandKey().command(),request.stepId(),result.bytes(),digest));
            affected(mapper.completeStep(request.shard(),request.stepKey(),claim.fence(),result.bytes(),digest,result.effects(),fact));
            return find(request);
        });
    }

    /** 失败不覆盖原结果；过期执行器只能观察新状态。 */
    public boolean failed(StepRow claim,Failure error) {
        return transactions.on(claim.shardId(),()->mapper.failStep(claim.shardId(),claim.stepKey(),claim.fence(),error.name())==1);
    }

    /** 命令先变PAUSED后仍须清算本库末次退出的step；只记耗尽，不重试业务或释放guard。 */
    public int pauseExpired(int shard,java.util.UUID source) {
        return transactions.on(shard,()->{
            var control=mapper.lockControl(shard);
            if(control==null || !source.toString().equals(control.sourceId()))throw failure(Failure.NOT_READY);
            int count=0;
            for(String key:mapper.expiredSpentSteps(shard,source))count+=mapper.pauseSpentStep(shard,key);
            return count;
        });
    }

    private void requireControl(StepRequest request) {
        var control=mapper.lockControl(request.shard());
        if(control==null || control.stopReason()!=null || !request.commandKey().source().toString().equals(control.sourceId())
                || !TOPOLOGY.equals(control.topologyVersion()) || (!"ACTIVE".equals(control.mode()) &&
                !("QUIESCED".equals(control.mode()) && request.commandKey().scopeKind()==ScopeKind.INITIALIZE)))
            throw failure(Failure.NOT_READY);
    }

    private void lockLocalOwner(StepRequest request) {
        if(request.shard()==request.advertiserId()%2 && request.commandKey().scopeKind()!=ScopeKind.INITIALIZE) {
            // 同库孩子步骤也沿control→guard→step，避免与owner接管形成反向等待。
            var guard=mapper.lockGuard(request.shard(),request.advertiserId());
            if(guard==null || !Objects.equals(guard.activeCommandId(),request.commandKey().command())
                    || !Objects.equals(guard.activeCommandHome(),request.commandKey().shard())
                    || !Objects.equals(guard.activeCommandScope(),request.commandKey().scope()))throw failure(Failure.CONFLICT);
        }
    }

    private StepRow same(StepRequest request,StepRow row) {
        if(row==null || !Objects.equals(row.sourceId(),request.commandKey().source())
                || row.ownerShard()!=request.commandKey().shard() || !row.commandScope().equals(request.commandKey().scope())
                || !row.commandId().equals(request.commandKey().command()) || !row.stepId().equals(request.stepId())
                || row.advertiserId()!=request.advertiserId() || (request.adId()!=null && !Objects.equals(row.adId(),request.adId()))
                || !Objects.equals(row.aggregateVersion(),request.aggregateVersion()) || !row.requestSha256().equals(request.digest())
                || !Arrays.equals(row.requestBytes(),request.request()))throw failure(Failure.CONFLICT);
        try { if(!json.readTree(row.writePlan()).equals(json.readTree(request.writePlan())))throw failure(Failure.CONFLICT); }
        catch(java.io.IOException e){throw failure(Failure.INCONSISTENT);}
        return row;
    }

    private void validateEffects(LocalResult result) {
        try {
            var effects=json.readTree(result.effects());
            if(!effects.isArray() || !effects.equals(json.readTree(result.bytes()).get("effects")))throw failure(Failure.INCONSISTENT);
        } catch(java.io.IOException e){throw failure(Failure.INCONSISTENT);}
    }
    /** 原文与实际写入清单一起持久；表名和操作来自固定业务处理器，不能来自客户端任意SQL。 */
    public record LocalResult(byte[] bytes,String effects) { }
}
