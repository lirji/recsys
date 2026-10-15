package com.recsys.ad.billing;

import com.recsys.common.ad.billing.BudgetAccount;
import com.recsys.common.ad.billing.BudgetCommand;
import com.recsys.common.ad.billing.BudgetCommandResult;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/** 日预算用例边界：幂等命令、账户修改和首次结果必须在同一个 adDb 事务中提交。 */
public class BudgetAccountService {
    private final BudgetRepository repository;
    private final TransactionTemplate transactions;

    public BudgetAccountService(BudgetRepository repository, TransactionTemplate transactions) {
        if (!(transactions.getTransactionManager() instanceof DataSourceTransactionManager manager)
                || manager.getDataSource() != repository.dataSource()) {
            throw new IllegalArgumentException("预算事务与 Repository 必须绑定同一个 adDb DataSource");
        }
        this.repository = repository;
        this.transactions = transactions;
    }

    /** 重试直接返回原结果；只有 execute 完成提交后才向协议层返回。 */
    public BudgetCommandResult setLimit(BudgetCommand command) {
        return transactions.execute(tx -> {
            var previous = repository.claim(command);
            if (previous.isPresent()) return previous.get();
            BudgetAccount account = repository.lockAccount(command.advertiserId(), command.budgetDate());
            BudgetCommandResult.Status status;
            if (account.version() != command.expectedVersion()) {
                status = BudgetCommandResult.Status.VERSION_CONFLICT;
            } else if (command.limitAmount().compareTo(account.heldAmount().add(account.chargedAmount())) < 0) {
                status = BudgetCommandResult.Status.BELOW_COMMITTED;
            } else {
                status = BudgetCommandResult.Status.APPLIED;
                account = repository.apply(command);
            }
            var result = new BudgetCommandResult(command.commandId(), status, account);
            repository.complete(result);
            return result;
        });
    }

    /** 读取当前账户；不以不存在推断在途命令未执行。 */
    public Optional<BudgetAccount> findAccount(long advertiserId, LocalDate date) {
        return repository.findAccount(advertiserId, date);
    }

    /** 提交结果未知时，调用方通过原命令标识恢复观察。 */
    public Optional<BudgetCommandResult> findCommand(long advertiserId, UUID commandId) {
        return repository.findCommand(advertiserId, commandId);
    }
}
