package com.recsys.advertiser.catalog;

import com.recsys.advertiser.persistence.mapper.primary.CatalogLocalMapper;
import com.recsys.advertiser.persistence.model.GeneratedId;
import org.springframework.dao.DuplicateKeyException;

import java.util.Arrays;
import java.util.Objects;

import static com.recsys.advertiser.catalog.CatalogProtocol.*;

/** 原命令与广告主持久占有；只负责本库短事务，不在此处发Kafka或访问另一分片。 */
public final class CatalogCommandRepository {
    private final CatalogLocalMapper mapper;
    private final CatalogLocalTransactions transactions;

    public CatalogCommandRepository(CatalogLocalMapper mapper,CatalogLocalTransactions transactions) {
        this.mapper=mapper; this.transactions=transactions;
    }

    /** 已删除资源仍可先查原命令；调用方须在调用前完成当前权限校验。 */
    public CommandRow find(CommandKey key) { return mapper.command(key); }

    /** 开户已在固定home冻结身份，才可在实际owner库提交该身份的持久占有。 */
    public void acquireRootOwner(CommandRow root) {
        if(!Kind.ROOT_CREATE.name().equals(root.kind()) || root.advertiserId()==null)throw failure(Failure.INCONSISTENT);
        int shard=(int)(root.advertiserId()%2);
        transactions.on(shard,()->{
            requireActive(mapper.lockControl(shard),root.key());
            mapper.insertGuard(shard,root.advertiserId(),root.shardId(),root.key().scope(),root.commandId());
            var guard=mapper.lockGuard(shard,root.advertiserId());
            if(guard==null || !root.commandId().equals(guard.activeCommandId()) || !root.key().scope().equals(guard.activeCommandScope())
                    || !Integer.valueOf(root.shardId()).equals(guard.activeCommandHome()))throw failure(Failure.CONFLICT);
            return null;
        });
    }

    /** owner最终步骤在原事务调用，completeStep若失败也会回滚完成与释放。 */
    public void finishLocal(CommandRow claim,byte[] result) {
        if(!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("完成命令需要本库事务");
        affected(mapper.finishCommand(claim.key(),claim.fence(),result));
        affected(mapper.releaseGuard(claim.shardId(),claim.advertiserId(),claim.key()));
    }

    /** 开户home可能与实际owner不同，先验证本地最终receipt，随后仅完成home记录。 */
    public void finishRoot(CommandRow claim,byte[] result) {
        transactions.on(claim.shardId(),()->{affected(mapper.finishCommand(claim.key(),claim.fence(),result));return null;});
    }

    /** 首步尚无任何已提交结果时可拒绝；调用者必须先查本地receipt以排除未知提交。 */
    public void rejectUnstarted(CommandRow claim,Failure error) {
        transactions.on(claim.shardId(),()->{
            lockOwner(claim.key());affected(mapper.rejectUnstarted(claim.key(),claim.fence(),error.name()));
            if(claim.key().scopeKind()==ScopeKind.ADVERTISER)affected(mapper.releaseGuard(claim.shardId(),claim.advertiserId(),claim.key()));
            return null;
        });
    }

    /** 跨库副作用前提交广告主占有，相同请求复用原结果，不受当前业务行是否已删除影响。 */
    public CommandRow admit(Submission submission) {
        return admitExpected(submission,null);
    }

    /** 批次预期版本在同一guard锁下核验；占有冲突可续办，版本冲突不能被迟到批次覆盖。 */
    public CommandRow admitExpected(Submission submission,Long expectedVersion) {
        CommandKey key=submission.key();
        if(key.scopeKind()!=ScopeKind.ADVERTISER)throw new IllegalArgumentException("普通命令需要广告主作用域");
        long advertiser=Long.parseLong(key.scopeId());
        if(advertiser<=0 || advertiser%2!=key.shard())throw failure(Failure.CONFLICT);
        return transactions.on(key.shard(),()->{
            requireActive(mapper.lockControl(key.shard()),key);
            CommandRow original=mapper.command(key);
            if(original!=null)return same(submission,original);
            var guard=mapper.lockGuard(key.shard(),advertiser);
            if(guard==null)throw failure(Failure.NOT_READY);
            // 锁等待后再次读同一命令：第一个请求可能已经提交占有。
            original=mapper.command(key);
            if(original!=null)return same(submission,original);
            if(expectedVersion!=null && guard.advertiserVersion()!=expectedVersion)throw failure(Failure.CONFLICT);
            if(guard.activeCommandId()!=null)throw failure(expectedVersion==null?Failure.CONFLICT:Failure.OWNER_BUSY);
            affected(mapper.insertCommand(submission,advertiser));
            affected(mapper.acquireGuard(key,advertiser));
            return mapper.command(key);
        });
    }

    /** 开户先写固定home命令，再在同事务保存原Snowflake身份；提交未知绝不重新换home或换号。 */
    public CommandRow admitRoot(Submission submission) {
        CommandKey key=submission.key();
        if(submission.kind()!=Kind.ROOT_CREATE || !key.equals(CommandKey.root(key.source(),key.command())))
            throw new IllegalArgumentException("开户必须使用固定ROOT_CREATE身份");
        try {
            return transactions.on(key.shard(),()->{
                requireActive(mapper.lockControl(key.shard()),key);
                CommandRow original=mapper.command(key);
                if(original!=null)return same(submission,original);
                affected(mapper.insertCommand(submission,null));
                var generated=new GeneratedId();
                affected(mapper.reserveIdentity(key.shard(),key.source(),key.command(),generated));
                if(generated.valueOrZero()<=0)throw failure(Failure.INCONSISTENT);
                affected(mapper.bindIdentity(key,generated.valueOrZero()));
                return mapper.command(key);
            });
        } catch(DuplicateKeyException raced) {
            // 只对已知唯一键竞争在回滚后读取赢家；连接/提交未知不在这里当成失败重做。
            CommandRow original=mapper.command(key);
            if(original==null)throw failure(Failure.INCONSISTENT);
            return same(submission,original);
        }
    }

    /** 领取最多一步；租约/预算在数据库持久化，返回null表示此刻不能领取。 */
    public CommandRow claim(CommandKey key) {
        return transactions.on(key.shard(),()->{
            requireActive(mapper.lockControl(key.shard()),key);
            lockOwner(key);
            if(mapper.lockCommand(key)==null)throw failure(Failure.NOT_FOUND);
            mapper.pauseSpentCommand(key);
            if(mapper.claimCommand(key)==0)return null;
            return mapper.command(key);
        });
    }

    /** 已核验本地APPLIED结果后才能调用；旧协调者不能覆盖新检查点。 */
    public void advance(CommandRow claimed,String nextPhase,String nextStep,byte[] frozen) {
        requireBytes(frozen);
        transactions.on(claimed.shardId(),()->{
            requireActive(mapper.lockControl(claimed.shardId()),claimed.key());lockOwner(claimed.key());
            affected(mapper.advanceCommand(claimed.key(),claimed.fence(),nextPhase,nextStep,frozen));return null;
        });
    }

    /** 外部失败只更新本命令；即使预算耗尽也不释放广告主占有。 */
    public boolean failed(CommandRow claimed,Failure error) {
        return transactions.on(claimed.shardId(),()->{
            requireActive(mapper.lockControl(claimed.shardId()),claimed.key());lockOwner(claimed.key());
            return mapper.failCommand(claimed.key(),claimed.fence(),error.name())==1;
        });
    }

    private void lockOwner(CommandKey key) {
        if(key.scopeKind()!=ScopeKind.ADVERTISER)return;
        var guard=mapper.lockGuard(key.shard(),Long.parseLong(key.scopeId()));
        if(guard==null || !Objects.equals(guard.activeCommandId(),key.command())
                || !Objects.equals(guard.activeCommandHome(),key.shard()) || !Objects.equals(guard.activeCommandScope(),key.scope()))
            throw failure(Failure.CONFLICT);
    }

    static CommandRow same(Submission request,CommandRow row) {
        if(!row.kind().equals(request.kind().name()) || request.kind()==Kind.ROOT_CREATE && !row.actor().equals(request.actor()) || !row.requestSha256().equals(request.digest())
                || !Arrays.equals(row.requestBytes(),request.request()))throw failure(Failure.CONFLICT);
        return row;
    }
    static void requireActive(CatalogLocalMapper.Control control,CommandKey key) {
        if(control==null || !"ACTIVE".equals(control.mode()) || !control.initializationReady() || control.stopReason()!=null
                || !key.source().toString().equals(control.sourceId()) || !TOPOLOGY.equals(control.topologyVersion()))
            throw failure(Failure.NOT_READY);
    }
    static void affected(int count){if(count!=1)throw failure(Failure.FENCED);}
}
