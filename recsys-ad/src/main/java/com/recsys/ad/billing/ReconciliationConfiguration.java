package com.recsys.ad.billing;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;

/** 对账默认关闭且只安装在资金owner；不依赖收费admission，停新签发后仍能核查。 */
@Configuration(proxyBeanMethods=false)
@Conditional(SandboxOwnerCondition.class)
@ConditionalOnProperty(prefix="recsys.ad.billing",name={"sandbox-enabled","reconciliation-enabled"},havingValue="true")
public class ReconciliationConfiguration {
    /** 显式迁移由运行手册负责，应用仅校验。 */
    @Bean public ReconciliationRepository reconciliationRepository(@Qualifier("adDbSqlSessionTemplate") SqlSessionTemplate sessions) {
        var repository=new ReconciliationRepository(sessions);repository.verifySchema();return repository;
    }
    /** 独立事务管理器不注册为全局Bean，避免接管宿主其他库。 */
    @Bean public ReconciliationService reconciliationService(ReconciliationRepository repository,@Qualifier("adDbDataSource") DataSource ds) {
        return new ReconciliationService(repository,transactions(ds));
    }
    /** PostgreSQL SET TRANSACTION READ ONLY实际阻止写入，不能只依赖应用层只读提示。 */
    static TransactionTemplate transactions(DataSource ds) {
        var manager=new JdbcTransactionManager(ds);manager.setEnforceReadOnly(true);
        var tx=new TransactionTemplate(manager);tx.setReadOnly(true);tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);tx.setTimeout(5);return tx;
    }
}
