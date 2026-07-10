package com.recsys.gateway;

import com.recsys.platform.security.AuthProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * API 网关启动类。路由规则见 application.yml。
 *
 * <p>{@code AuthProperties} 在此无条件注册,使 nosec(recsys.security.enabled=false)关闭安全链后
 * 登录端点 / 身份传播仍能拿到配置,不至于启动失败。
 */
@SpringBootApplication
@EnableConfigurationProperties(AuthProperties.class)
public class GatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
