package com.recsys.ad.billing;

import com.recsys.common.ad.billing.BillingReconciliation.Scope;
import com.recsys.common.ad.billing.BillingRecovery.*;
import com.recsys.common.ad.billing.ReconciliationTask;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.util.UUID;

/** 每命令一个显式目标，同事务保存作用和结果；没有后台扫描或自动改账。 */
public class BillingRecoveryService {
    private final BillingRecoveryRepository repository;
    private final ReservationService impressions;
    private final ClickSettlementService clicks;
    private final ConversionSettlementService conversions;
    private final TransactionTemplate transactions;

    /** 不允许恢复命令与资金效果落在不同连接池或嵌套独立事务。 */
    public BillingRecoveryService(BillingRecoveryRepository repository, ReservationService impressions,
            ClickSettlementService clicks, ConversionSettlementService conversions, TransactionTemplate transactions) {
        if (!(transactions.getTransactionManager() instanceof DataSourceTransactionManager manager)
                || manager.getDataSource() != repository.dataSource() || impressions.dataSource() != repository.dataSource()
                || clicks.dataSource() != repository.dataSource() || conversions.dataSource() != repository.dataSource()
                || transactions.isReadOnly() || transactions.getPropagationBehavior() != TransactionDefinition.PROPAGATION_REQUIRES_NEW
                || transactions.getIsolationLevel() != TransactionDefinition.ISOLATION_READ_COMMITTED
                || transactions.getTimeout() != ReconciliationTask.TRANSACTION_SECONDS) {
            throw new IllegalArgumentException("恢复命令及资金效果必须同adDb五秒事务");
        }
        this.repository = repository; this.impressions = impressions; this.clicks = clicks;
        this.conversions = conversions; this.transactions = transactions;
    }

    /** 重复命令查原结果；提交未知由上层原ID查询，不自行换键或重试。 */
    public Result recover(Command command) {
        noOuterTransaction();
        return transactions.execute(tx -> {
            if (!repository.insert(command)) {
                var previous = repository.find(command.advertiserId(), command.commandId()).orElseThrow(ReconciliationTask.Conflict::new);
                if (!command.sameIntent(previous.command())) throw new ReconciliationTask.Conflict();
                return previous;
            }
            Status status;
            if (command.action() == Action.REPUBLISH) status = repository.republish(command);
            else {
                var scope = new Scope(command.advertiserId(), command.budgetDate());
                status = switch (command.family()) {
                    case IMPRESSION -> impressions.expireForRecovery(scope, command.targetId());
                    case CLICK -> clicks.expireForRecovery(scope, command.targetId());
                    case CONVERSION -> conversions.expireForRecovery(scope, command.targetId());
                };
            }
            repository.complete(command, status);
            return repository.find(command.advertiserId(), command.commandId()).orElseThrow();
        });
    }

    /** 只查询原结果，查询不到不能证明在途事务从未执行。 */
    public Result find(long advertiserId, UUID commandId) {
        noOuterTransaction(); ReconciliationTask.identity(advertiserId, commandId);
        return repository.find(advertiserId, commandId).orElseThrow(ReconciliationTask.NotFound::new);
    }

    /** 包内到期入口必须已经加入相同资源的真实读写事务。 */
    static void requireTransaction(DataSource dataSource) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                || !TransactionSynchronizationManager.hasResource(dataSource)) {
            throw new IllegalStateException("到期恢复必须处于同owner读写事务");
        }
    }

    private static void noOuterTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("恢复不能加入外层事务");
    }
}
