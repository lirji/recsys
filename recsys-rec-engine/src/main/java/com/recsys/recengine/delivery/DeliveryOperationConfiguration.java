package com.recsys.recengine.delivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recsys.ad.AdProperties;
import com.recsys.common.query.QueryUnderstandingService;
import com.recsys.recengine.ad.AdServingGateway;
import com.recsys.recengine.experiment.ExperimentService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** engine owner 只引用 primary jdbcTemplate；与 adDb 预占无共享事务。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "recsys.ad.billing.sandbox-enabled", havingValue = "true")
public class DeliveryOperationConfiguration {
    /** 显式绑定 primary，启动只做 schema 校验。 */
    @Bean
    public DeliveryOperationRepository deliveryOperationRepository(@Qualifier("jdbcTemplate") JdbcTemplate jdbc, ObjectMapper json) {
        var repository = new DeliveryOperationRepository(jdbc, json);
        repository.verifySchema();
        return repository;
    }
    /** 同进程与 grpc 只替换 gateway，恢复逻辑保持同一实现。 */
    @Bean
    public DeliveryOperationService deliveryOperationService(DeliveryOperationRepository repository, QueryUnderstandingService queries,
            ExperimentService experiments, AdProperties properties, AdServingGateway gateway) {
        return new DeliveryOperationService(repository, queries, experiments, properties, gateway);
    }
}
