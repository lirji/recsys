package com.recsys.contentserving;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 内容/物料在线服务入口(P2)。扫描本模块 + {@code com.recsys.content}(装配 JdbcContentService)。
 * 类路径闭包仅 recsys-content(+common+proto),故无需扫更广;item 表走普通 Postgres 主数据源(非分片)。
 */
@SpringBootApplication(scanBasePackages = {"com.recsys.contentserving", "com.recsys.content"})
public class ContentServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ContentServiceApplication.class, args);
    }
}
