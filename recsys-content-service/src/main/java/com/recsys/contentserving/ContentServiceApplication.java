package com.recsys.contentserving;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 内容/物料在线服务入口(P2)。扫描本模块 + {@code com.recsys.content}(装配 JdbcContentService)+
 * {@code com.recsys.platform}(启用平台内部令牌安全链 InternalSecurityConfig + 统一异常处理;否则退回 Spring 默认
 * 安全,recsys.security.permit-paths 失效,/actuator/prometheus 被 401 抓不到指标)。item 表走普通 Postgres 主数据源(非分片)。
 */
@SpringBootApplication(scanBasePackages = {"com.recsys.contentserving", "com.recsys.content", "com.recsys.platform"})
public class ContentServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ContentServiceApplication.class, args);
    }
}
