package com.recsys.ad.billing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recsys.ad.delivery.DeliveryTokenCodec;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;

/** 捕获默认关闭；先扩展迁移和所有 owner 版本升级，再显式开启回执接受。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "recsys.ad.billing", name = {"sandbox-enabled", "receipts-enabled"}, havingValue = "true")
@Conditional(SandboxOwnerCondition.class)
public class ImpressionReceiptConfiguration {
    /** 只校验 adDb V005，不自动迁移共享库。 */
    @Bean
    public ImpressionReceiptRepository impressionReceiptRepository(@Qualifier("adDbJdbc") JdbcTemplate jdbc, ObjectMapper json) {
        var repository = new ImpressionReceiptRepository(jdbc, json); repository.verifySchema(); return repository;
    }
    /** 明确绑定同一 adDb 事务，不加入 engine 或其他库的事务。 */
    @Bean
    public ImpressionReceiptService impressionReceiptService(ImpressionReceiptRepository receipts, PreparationRepository preparations,
            ReservationRepository reservations, CpmQuoteRepository quotes, DeliveryTokenCodec tokens,
            @Qualifier("adDbDataSource") DataSource dataSource,
            @Value("${recsys.ad.billing.transaction-timeout-seconds:5}") int timeoutSeconds,
            @Value("${recsys.ad.billing.receipt-admission-enabled:true}") boolean admissionEnabled) {
        return new ImpressionReceiptService(receipts, preparations, reservations, quotes, tokens, BudgetConfiguration.transactions(dataSource, timeoutSeconds), admissionEnabled);
    }
}
