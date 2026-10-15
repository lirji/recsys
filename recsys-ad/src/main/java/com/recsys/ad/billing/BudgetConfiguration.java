package com.recsys.ad.billing;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;

/** 默认关闭的新账务装配；不注册全局事务管理器，避免让原主库自动配置退避。 */
@org.springframework.context.annotation.Conditional(SandboxOwnerCondition.class)
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "recsys.ad.billing.sandbox-enabled", havingValue = "true")
public class BudgetConfiguration {
    /** 开启前强制校验 owner 迁移，缺表或历史漂移直接启动失败。 */
    @Bean
    public BudgetRepository budgetRepository(@Qualifier("adDbSqlSessionTemplate") SqlSessionTemplate sessions) {
        BudgetRepository repository = new BudgetRepository(sessions);
        repository.verifySchema();
        return repository;
    }

    /** 独立短事务避免加入其他数据库的外层事务，超时受配置上限约束。 */
    @Bean
    public BudgetAccountService budgetAccountService(BudgetRepository repository,
            @Qualifier("adDbDataSource") DataSource dataSource,
            @Value("${recsys.ad.billing.transaction-timeout-seconds:5}") int timeoutSeconds) {
        return new BudgetAccountService(repository, transactions(dataSource, timeoutSeconds));
    }

    /** 账务用例统一复用同源短事务设置，不把事务管理器注册成全局默认值。 */
    static TransactionTemplate transactions(DataSource dataSource, int timeoutSeconds) {
        if (timeoutSeconds < 1 || timeoutSeconds > 30) {
            throw new IllegalArgumentException("预算事务超时须在 1～30 秒之间");
        }
        TransactionTemplate transactions = new TransactionTemplate(new JdbcTransactionManager(dataSource));
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transactions.setTimeout(timeoutSeconds);
        return transactions;
    }
}
