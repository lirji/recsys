package com.recsys.recengine.exposure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recsys.common.exposure.*;
import com.recsys.recengine.RecommendOrchestrator;
import com.recsys.recengine.content.ContentGateway;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.Map;
import java.util.Objects;

/** 自然签发独立于广告开关；默认不装配，不引入 ad owner 依赖。 */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="recsys.natural.delivery-enabled",havingValue="true")
@EnableConfigurationProperties(NaturalDeliveryConfiguration.Properties.class)
public class NaturalDeliveryConfiguration {
    /** 政策和 key 必须显式配置，无隐式自然曝光默认值；仅启动快照。 */
    @ConfigurationProperties(prefix="recsys.natural")
    public record Properties(NaturalQualification.Policy policy,String currentKeyId,Map<String,String> keys,Boolean admissionEnabled) {
        public Properties { keys = keys == null ? null : Map.copyOf(keys); }
        /** 配置对象也必须脱敏，避免日志/调试输出当前签发秘密。 */
        @Override public String toString() { return "NaturalDeliveryProperties[policyConfigured=" + (policy != null) + "]"; }
    }
    /** 明确 primary 数据库，避免多数据源应用中误把交付表写进 adDb。 */
    @Bean public NaturalDeliveryRepository naturalDeliveryRepository(@Qualifier("jdbcTemplate") JdbcTemplate jdbc,ObjectMapper json) {
        var repository=new NaturalDeliveryRepository(jdbc,json); repository.verifySchema(); return repository;
    }
    /** 当前签发 key 必填，验证端单独使用 verifier-only 的装配。 */
    @Bean public NaturalDeliveryService naturalDeliveryService(NaturalDeliveryRepository repository,RecommendOrchestrator recommender,ContentGateway content,Properties props) {
        var tokens=new NaturalQualificationToken(Objects.requireNonNull(props.currentKeyId(),"必须配置自然签发 keyId"),props.keys());
        return new NaturalDeliveryService(repository,recommender,content,tokens,NaturalQualification.approvedPolicy(props.policy()),
                !Boolean.FALSE.equals(props.admissionEnabled()));
    }
}
