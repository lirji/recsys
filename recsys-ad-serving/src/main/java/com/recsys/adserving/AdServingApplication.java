package com.recsys.adserving;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 广告在线服务入口(微服务化第一刀)。
 *
 * <p>{@code scanBasePackages = "com.recsys"} 与 rec-engine 一致,但本服务的类路径闭包是 rec-engine 的严格子集
 * ——只有 {@code recsys-ad → recsys-rank → recsys-feature/recsys-content} 及契约库,不含 recall/embedding/query/user。
 * 因此扫描 com.recsys 恰好装配广告管线所需的全部 bean(AdPipeline + 各广告 bean + RankRouter + 排序特征/模型 +
 * FeatureService/ContentService),且不会误触未在类路径上的模块。
 *
 * <p>{@link com.recsys.ad.AdShardingConfig} 会被扫描到,装配主(普通 PG,承载 ad_event/pgvector ANN)+
 * 次(ShardingSphere,读分片广告目录)双数据源,与 rec-engine 读侧完全一致。
 */
@SpringBootApplication(scanBasePackages = "com.recsys")
public class AdServingApplication {

    public static void main(String[] args) {
        SpringApplication.run(AdServingApplication.class, args);
    }
}
