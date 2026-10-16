package com.recsys.ad.billing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recsys.common.ad.billing.ReconciliationTask;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import javax.sql.DataSource;

/** 恢复默认关闭；安装三族owner后独立于新收费准入，不启动自动修复线程。 */
@Configuration(proxyBeanMethods = false)
@Conditional(SandboxOwnerCondition.class)
@ConditionalOnProperty(prefix = "recsys.ad.billing", name = {"sandbox-enabled", "reconciliation-enabled", "reconciliation-recovery-enabled"}, havingValue = "true")
public class BillingRecoveryConfiguration {
    /** 迁移由运维显式执行，运行时拒绝历史漂移。 */
    @Bean public BillingRecoveryRepository billingRecoveryRepository(@Qualifier("adDbSqlSessionTemplate") SqlSessionTemplate sessions, ObjectMapper json) {
        var repository = new BillingRecoveryRepository(sessions, json); repository.verifySchema(); return repository;
    }

    /** 复用原到期规则及其物理数据源，禁止独立重新实现收费/释放算式。 */
    @Bean public BillingRecoveryService billingRecoveryService(BillingRecoveryRepository repository, ReservationService impressions,
            ClickSettlementService clicks, ConversionSettlementService conversions, @Qualifier("adDbDataSource") DataSource dataSource) {
        return new BillingRecoveryService(repository, impressions, clicks, conversions,
                BudgetConfiguration.transactions(dataSource, ReconciliationTask.TRANSACTION_SECONDS));
    }
}
