package com.recsys.behavior;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 行为采集服务启动类。POST /api/behavior → Kafka/库。
 *
 * <p>{@code scanBasePackages} 纳入 {@code com.recsys.platform},装配技术平台的安全链/统一异常处理(P0)。
 */
@SpringBootApplication(scanBasePackages = {"com.recsys.behavior", "com.recsys.platform"})
public class BehaviorApplication {
    public static void main(String[] args) {
        SpringApplication.run(BehaviorApplication.class, args);
    }
}
