package com.recsys.ad.billing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recsys.ad.AdPipeline;
import com.recsys.ad.delivery.DeliveryTokenCodec;
import com.recsys.common.ad.delivery.SandboxDelivery.ChargeMode;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** 在线CPA能力显式安装；停止新增用admission，保留查询/回执和后台恢复。 */
@Configuration(proxyBeanMethods=false)
@Conditional(SandboxOwnerCondition.class)
@ConditionalOnProperty(prefix="recsys.ad.billing",name={"sandbox-enabled","conversions-enabled","conversion-settlement-enabled","conversion-delivery-enabled"},havingValue="true")
public class ConversionPreparationConfiguration {
    /** 只校验adDb新迁移，不隐式迁移共享环境。 */
    @Bean public PreparationRepository conversionPreparationRepository(@Qualifier("adDbJdbc") JdbcTemplate jdbc,ObjectMapper json) {
        var repository=new PreparationRepository(jdbc,json,ChargeMode.CONVERSION);repository.verifySchema();return repository;
    }
    /** 使用同一预算owner，金额只来自签名报价。 */
    @Bean public ConversionPreparationService conversionPreparationService(AdPipeline pipeline,@Qualifier("conversionPreparationRepository") PreparationRepository repository,
            ConversionQuoteRepository quotes,ConversionSettlementService settlements,DeliveryTokenCodec tokens,
            @Value("${recsys.ad.billing.conversion-preparation-admission-enabled:true}") boolean admission) {
        return new ConversionPreparationService(pipeline,repository,quotes,settlements,tokens,admission);
    }
}
