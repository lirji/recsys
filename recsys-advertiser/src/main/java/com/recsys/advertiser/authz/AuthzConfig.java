package com.recsys.advertiser.authz;

import com.lrj.authz.protocol.AuthzEngine;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 细粒度归属判权装配。守卫 bean 恒注册(mode=disabled 时全部 no-op,零行为变化);
 * {@link AuthzEngine}(SDK 的 RemoteAuthzEngine→auth-platform-server)由 SDK 自动配置提供,
 * 地址/凭证走 {@code authz.client.*}。
 */
@Configuration
@EnableConfigurationProperties(AuthzProperties.class)
public class AuthzConfig {

    @Bean
    public AdvertiserAuthz advertiserAuthz(AuthzProperties props, ObjectProvider<AuthzEngine> engines) {
        return new AdvertiserAuthz(props.getMode(), engines);
    }
}
