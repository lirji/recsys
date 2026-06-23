package com.recsys.advertiser;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 广告主侧管理服务启动类(docs/05 §2 新增模块 recsys-advertiser)。
 *
 * <p>提供广告主/广告/创意/竞价词的增删改查与投放报表。与 {@code recsys-ad}(在线检索/竞价/计费)
 * 互补:本服务是写侧/管理面,改库的同时把在线召回所依赖的派生存储
 * (Redis 竞价词倒排 {@code bidword:inv:{keyword}} + {@code ad_embedding})保持一致,
 * 让新建/暂停/改价的广告对在线链路即时生效——契约与离线 {@code seed-ads} 一致。
 */
@SpringBootApplication
public class AdvertiserApplication {
    public static void main(String[] args) {
        SpringApplication.run(AdvertiserApplication.class, args);
    }
}
