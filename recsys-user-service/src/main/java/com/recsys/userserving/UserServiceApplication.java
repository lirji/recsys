package com.recsys.userserving;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 用户画像在线服务入口(P3)。扫描本模块 + {@code com.recsys.user}(装配 UserProfileService)。
 * app_user 走普通 Postgres 主数据源(非分片)。
 */
@SpringBootApplication(scanBasePackages = {"com.recsys.userserving", "com.recsys.user"})
public class UserServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(UserServiceApplication.class, args);
    }
}
