package com.recsys.ad.billing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recsys.common.ad.billing.ReconciliationTask;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import javax.sql.DataSource;

/** 内部持久任务默认关闭，停新收费后仍可核查已存在义务，不自动扫描全账户。 */
@Configuration(proxyBeanMethods = false)
@Conditional(SandboxOwnerCondition.class)
@ConditionalOnProperty(prefix = "recsys.ad.billing", name = {"sandbox-enabled", "reconciliation-enabled", "reconciliation-tasks-enabled"}, havingValue = "true")
public class ReconciliationTaskConfiguration {
    /** 启用时必须先显式执行V017，启动不擅自迁移共享数据库。 */
    @Bean
    public ReconciliationTaskRepository reconciliationTaskRepository(@Qualifier("adDbSqlSessionTemplate") SqlSessionTemplate sessions, ObjectMapper json) {
        var repository = new ReconciliationTaskRepository(sessions, json);
        repository.verifySchema();
        return repository;
    }

    /** 任务短事务与C13A快照复用同一资金owner连接池。 */
    @Bean
    public ReconciliationTaskService reconciliationTaskService(ReconciliationTaskRepository repository,
            ReconciliationService snapshots, @Qualifier("adDbDataSource") DataSource dataSource) {
        return new ReconciliationTaskService(repository, snapshots, BudgetConfiguration.transactions(dataSource, ReconciliationTask.TRANSACTION_SECONDS));
    }
}
