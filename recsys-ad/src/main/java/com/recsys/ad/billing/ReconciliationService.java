package com.recsys.ad.billing;

import com.recsys.common.ad.billing.BillingReconciliation.*;
import com.recsys.common.ad.delivery.SandboxDelivery.ChargeMode;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.ArrayList;

/** 一次快照仅在同adDb的只读一致视图中核查，绝不顺手释放或重发。 */
public class ReconciliationService {
    private final ReconciliationRepository repository;
    private final TransactionTemplate transactions;
    public ReconciliationService(ReconciliationRepository repository,TransactionTemplate transactions) {
        if(!(transactions.getTransactionManager() instanceof DataSourceTransactionManager manager)
                || manager.getDataSource()!=repository.dataSource() || !manager.isEnforceReadOnly()
                || !transactions.isReadOnly() || transactions.getIsolationLevel()!=TransactionDefinition.ISOLATION_REPEATABLE_READ
                || transactions.getPropagationBehavior()!=TransactionDefinition.PROPAGATION_REQUIRES_NEW || transactions.getTimeout()!=5) {
            throw new IllegalArgumentException("对账必须使用同adDb的强制只读REPEATABLE_READ五秒独立事务");
        }
        this.repository=repository;this.transactions=transactions;
    }
    /** 依赖异常直接向调用方传播未知，不能返回空报告或以0掩盖不可核查。 */
    public Snapshot inspect(Scope scope) {
        java.util.Objects.requireNonNull(scope);
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("对账不能加入外层事务");
        return transactions.execute(tx->{
            var observedAt=repository.observedAt();var account=repository.account(scope).orElse(null);
            var families=new ArrayList<FamilySummary>();
            for(var family:ChargeMode.values())families.add(repository.family(scope,family,observedAt));
            return new Snapshot(scope,observedAt,account,families);
        });
    }
}
