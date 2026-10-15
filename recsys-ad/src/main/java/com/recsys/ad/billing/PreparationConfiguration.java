package com.recsys.ad.billing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recsys.ad.AdPipeline;
import com.recsys.ad.delivery.DeliveryTokenCodec;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.mybatis.spring.SqlSessionTemplate;
import java.util.Map;

/** 新交付 owner 使用专用密钥环；开关关闭不要求密钥或迁移。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "recsys.ad.billing.sandbox-enabled", havingValue = "true")
@Conditional(SandboxOwnerCondition.class)
public class PreparationConfiguration {
    /** 不提供默认秘密；配置缺失直接拒绝启用。 */
    @Bean
    public DeliveryTokenCodec deliveryTokenCodec(ObjectMapper json, Environment env) {
        Map<String, String> keys = Binder.get(env).bind("recsys.ad.delivery.signing.keys", Bindable.mapOf(String.class, String.class)).orElse(Map.of());
        return new DeliveryTokenCodec(json, env.getRequiredProperty("recsys.ad.delivery.signing.current-key-id"), keys);
    }
    /** V004 校验失败不能以半套能力接单。 */
    @Bean
    public PreparationRepository preparationRepository(@Qualifier("adDbSqlSessionTemplate") SqlSessionTemplate sessions, ObjectMapper json) {
        var repository = new PreparationRepository(sessions, json);
        repository.verifySchema();
        return repository;
    }
    /** 与已有报价和预占 owner 复用同一 adDb。 */
    @Bean
    public SandboxPreparationService sandboxPreparationService(AdPipeline pipeline, @Qualifier("preparationRepository") PreparationRepository repository,
            CpmQuoteRepository quotes, ReservationService reservations, DeliveryTokenCodec tokens) {
        return new SandboxPreparationService(pipeline, repository, quotes, reservations, tokens);
    }
}
