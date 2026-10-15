package com.recsys.recengine.delivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recsys.ad.AdProperties;
import com.recsys.common.ad.delivery.SandboxDelivery.ChargeMode;
import com.recsys.common.query.QueryUnderstandingService;
import com.recsys.recengine.ad.AdServingGateway;
import com.recsys.recengine.experiment.ExperimentService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** CPA整次交付只存engine primary，双模式共享同一恢复编排。 */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(prefix="recsys.ad.billing",name={"sandbox-enabled","conversions-enabled","conversion-settlement-enabled","conversion-delivery-enabled"},havingValue="true")
public class ConversionDeliveryConfiguration {
    /** 新迁移须预先显式执行，应用只校验。 */
    @Bean public DeliveryOperationRepository conversionDeliveryOperationRepository(@Qualifier("jdbcTemplate") JdbcTemplate jdbc,ObjectMapper json) {
        var repository=new DeliveryOperationRepository(jdbc,json,ChargeMode.CONVERSION);repository.verifySchema();return repository;
    }
    /** 固定CONVERSION仓储决定候选/准备调用，不以客户端模式动态选择表。 */
    @Bean public DeliveryOperationService conversionDeliveryOperationService(@Qualifier("conversionDeliveryOperationRepository") DeliveryOperationRepository repository,
            QueryUnderstandingService queries,ExperimentService experiments,AdProperties properties,AdServingGateway gateway) {
        return new DeliveryOperationService(repository,queries,experiments,properties,gateway);
    }
}
