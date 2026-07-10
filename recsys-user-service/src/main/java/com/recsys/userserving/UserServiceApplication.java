package com.recsys.userserving;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 用户画像在线服务入口(P3)。扫描本模块 + {@code com.recsys.user}(装配 UserProfileService)+
 * {@code com.recsys.platform}(启用平台内部令牌安全链 InternalSecurityConfig + 统一异常处理;否则退回 Spring 默认
 * 安全,recsys.security.permit-paths 失效,/actuator/prometheus 被 401 抓不到指标)。app_user 走普通 Postgres 主数据源(非分片)。
 */
@SpringBootApplication(scanBasePackages = {"com.recsys.userserving", "com.recsys.user", "com.recsys.platform"})
public class UserServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(UserServiceApplication.class, args);
    }
}
