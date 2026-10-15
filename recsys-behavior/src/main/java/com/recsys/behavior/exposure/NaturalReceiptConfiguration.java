package com.recsys.behavior.exposure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recsys.common.exposure.NaturalQualification;
import com.recsys.common.exposure.NaturalQualificationToken;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import java.util.Map;

/** 默认关闭；只有完整受控政策、验证密钥和迁移齐备才能接受自然回执。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "recsys.natural.receipts-enabled", havingValue = "true")
@EnableConfigurationProperties(NaturalReceiptConfiguration.Properties.class)
public class NaturalReceiptConfiguration {
    /** 不提供自然分母政策默认值，也不随配置中心热刷新在途含义。 */
    @ConfigurationProperties(prefix = "recsys.natural")
    public record Properties(NaturalQualification.Policy policy, Map<String, String> keys, Boolean admissionEnabled) {
        public Properties { keys = keys == null ? null : Map.copyOf(keys); }
        /** 防止诊断配置对象时输出验证密钥。 */
        @Override public String toString() { return "NaturalReceiptProperties[policyConfigured=" + (policy != null) + "]"; }
    }

    /** 复用 behavior 自有 primary 数据源，不读取广告 owner 的表。 */
    @Bean public NaturalReceiptRepository naturalReceiptRepository(JdbcTemplate jdbc, ObjectMapper json) {
        var repository = new NaturalReceiptRepository(jdbc, json); repository.verifySchema(); return repository;
    }
    /** 验证端不配置 currentKeyId，无法误用该 bean 签发新的资格。 */
    @Bean public NaturalReceiptService naturalReceiptService(NaturalReceiptRepository repository, Properties props) {
        return new NaturalReceiptService(repository, new NaturalQualificationToken(null, props.keys()),
                NaturalQualification.approvedPolicy(props.policy()),
                new DataSourceTransactionManager(repository.dataSource()), !Boolean.FALSE.equals(props.admissionEnabled()));
    }
}
