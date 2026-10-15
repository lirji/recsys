package com.recsys.ad.billing;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** 报价沿用同一 sandbox 开关和 adDb owner；缺 V002 时不能启用新账务版本。 */
@org.springframework.context.annotation.Conditional(SandboxOwnerCondition.class)
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "recsys.ad.billing.sandbox-enabled", havingValue = "true")
public class CpmQuoteConfiguration {
    /** 启动只校验，真实环境迁移仍由受控操作执行。 */
    @Bean
    public CpmQuoteRepository cpmQuoteRepository(@Qualifier("adDbJdbc") JdbcTemplate jdbc) {
        var repository = new CpmQuoteRepository(jdbc);
        repository.verifySchema();
        return repository;
    }
}
