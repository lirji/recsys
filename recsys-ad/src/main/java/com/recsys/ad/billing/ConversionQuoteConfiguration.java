package com.recsys.ad.billing;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.mybatis.spring.SqlSessionTemplate;

/** CPA能力显式默认关闭；grpc engine不能意外装配第二个资金owner。 */
@Configuration(proxyBeanMethods=false)
@Conditional(SandboxOwnerCondition.class)
@ConditionalOnProperty(name={"recsys.ad.billing.sandbox-enabled","recsys.ad.billing.conversions-enabled"},havingValue="true")
public class ConversionQuoteConfiguration {
    /** 本片只有冻结转化报价，不因存在此bean就自动开始预占或CPA收费。 */
    @Bean public ConversionQuoteRepository conversionQuoteRepository(@Qualifier("adDbSqlSessionTemplate") SqlSessionTemplate sessions, @Qualifier("adDbJdbc") JdbcTemplate jdbc) {
        new BudgetRepository(sessions).verifySchema();
        var repository = new ConversionQuoteRepository(jdbc); repository.verifySchema(); return repository;
    }
}
