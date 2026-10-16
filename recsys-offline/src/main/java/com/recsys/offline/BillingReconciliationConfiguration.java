package com.recsys.offline;

import com.recsys.platform.security.AuthProperties;
import com.recsys.proto.security.InternalAuthGrpcClientInterceptor;
import net.devh.boot.grpc.client.interceptor.GlobalClientInterceptorConfigurer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 仅启用对账CLI时安装可信出站身份，不改变其他离线任务的准入。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AuthProperties.class)
@ConditionalOnProperty(name = "recsys.offline.reconciliation.enabled", havingValue = "true")
public class BillingReconciliationConfiguration {
    /** 独立offline身份必须与owner允许列表一致，nosec不能悄悄以匿名身份调用资金管理。 */
    @Bean public GlobalClientInterceptorConfigurer reconciliationIdentity(AuthProperties auth,
            @Value("${recsys.ad.billing.offline-caller:recsys-offline}") String expectedCaller,
            @Value("${recsys.ad.billing.advertiser-caller:recsys-advertiser}") String advertiserCaller) {
        if (!auth.isEnabled() || expectedCaller == null || !expectedCaller.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
                || "recsys".equals(expectedCaller) || expectedCaller.equals(advertiserCaller) || !expectedCaller.equals(auth.getServiceName())
                || auth.getInternalSecret() == null || auth.getInternalSecret().isBlank()
                || auth.getInternalHeader() == null || auth.getInternalHeader().isBlank() || auth.getTokenTtlSeconds() <= 0) {
            throw new IllegalArgumentException("对账CLI必须配置可信独立offline身份");
        }
        return interceptors -> interceptors.add(new InternalAuthGrpcClientInterceptor(auth.getInternalHeader(), auth.getServiceName(),
                "SERVICE", auth.getTokenTtlSeconds(), auth.getInternalSecret()));
    }
}
