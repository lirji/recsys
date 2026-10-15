# SQL / JDBC 初筛清单

基线：`e7838776a0b09904a0d4702589639534901d27ee`；2026-10-15。状态：按下方文件级核销清单持续更新。

范围：`rg --files` 可见的生产 Java 和独立 SQL 资源，排除 target/node_modules。匹配 JDBC 使用或 SQL 关键字。注释、嵌套 SELECT、重复字符串也计数；这不是独立 SQL 条数或完成率。逐条语义映射将在相应工作包实施前补充。

`SQL 模式命中=0` 的文件通常为数据源装配或调用公共查询工具，仍必须检查。首次命中行用于定位，并不表示整份文件已逐行审完。测试源码不在下表，但每批必须检索构造器/方法引用并调整其装配。

## 模块汇总

| 模块 | Java 文件 | SQL 模式命中 | 工作包 |
|---|---:|---:|---|
| recsys-ad-serving | 2 | 15 | MP04c |
| recsys-ad | 41 | 206 | MP04 / MP05 / MP08～MP12 |
| recsys-advertiser | 3 | 36 | MP04b |
| recsys-behavior | 3 | 16 | MP06a/b |
| recsys-console | 2 | 9 | MP06c |
| recsys-content | 5 | 21 | MP02 |
| recsys-offline | 44 | 161 | MP13 |
| recsys-query | 1 | 2 | MP03a |
| recsys-rank | 2 | 2 | MP03d |
| recsys-rec-engine | 14 | 33 | MP07 / MP12 |
| recsys-recall | 12 | 22 | MP03b/c |
| recsys-streaming | 1 | 1 | MP14 |
| recsys-user | 1 | 2 | MP01b |

合计：13 个模块、131 个 Java 文件、526 处模式命中。

## 文件级核销清单

迁移时把 `TODO` 替换为实际 Mapper/XML 路径、验证证据和提交；保留的非业务 SQL 必须填明确原因，不能简单删行。

### recsys-ad-serving

| 来源文件 | 首次命中行 | SQL 模式命中 | 核销状态 |
|---|---:|---:|---|
| [com/recsys/adserving/catalog/AdServableRepository.java](../../../recsys-ad-serving/src/main/java/com/recsys/adserving/catalog/AdServableRepository.java) | 12 | 14 | DONE MP04c |
| [com/recsys/adserving/report/AdReportStatsRepository.java](../../../recsys-ad-serving/src/main/java/com/recsys/adserving/report/AdReportStatsRepository.java) | 4 | 1 | DONE MP04c |

### recsys-ad

| 来源文件 | 首次命中行 | SQL 模式命中 | 核销状态 |
|---|---:|---:|---|
| [com/recsys/ad/AdEmbeddingSimilarity.java](../../../recsys-ad/src/main/java/com/recsys/ad/AdEmbeddingSimilarity.java) | 5 | 1 | DONE MP05b |
| [com/recsys/ad/AdEventLogger.java](../../../recsys-ad/src/main/java/com/recsys/ad/AdEventLogger.java) | 11 | 4 | DONE MP05a |
| [com/recsys/ad/AdOutcomeService.java](../../../recsys-ad/src/main/java/com/recsys/ad/AdOutcomeService.java) | 4 | 1 | DONE MP05a |
| [com/recsys/ad/AdRepository.java](../../../recsys-ad/src/main/java/com/recsys/ad/AdRepository.java) | 10 | 3 | DONE MP04a |
| [com/recsys/ad/AdShardingConfig.java](../../../recsys-ad/src/main/java/com/recsys/ad/AdShardingConfig.java) | 10 | 0 | MP04a复核：连接/会话初始化SQL保留（无业务SQL） |
| [com/recsys/ad/CreativeSelector.java](../../../recsys-ad/src/main/java/com/recsys/ad/CreativeSelector.java) | 8 | 2 | DONE MP05b |
| [com/recsys/ad/GuaranteedDeliveryService.java](../../../recsys-ad/src/main/java/com/recsys/ad/GuaranteedDeliveryService.java) | 10 | 1 | DONE MP05b |
| [com/recsys/ad/PacingService.java](../../../recsys-ad/src/main/java/com/recsys/ad/PacingService.java) | 9 | 1 | DONE MP05b |
| [com/recsys/ad/ShardedAdCatalogReader.java](../../../recsys-ad/src/main/java/com/recsys/ad/ShardedAdCatalogReader.java) | 7 | 6 | DONE MP04a |
| [com/recsys/ad/UpliftAssignmentService.java](../../../recsys-ad/src/main/java/com/recsys/ad/UpliftAssignmentService.java) | 9 | 5 | DONE MP05a |
| [com/recsys/ad/billing/BillingEventConfiguration.java](../../../recsys-ad/src/main/java/com/recsys/ad/billing/BillingEventConfiguration.java) | 15 | 0 | DONE 装配已迁移：见MP11A_RESULT/MP11B_RESULT |
| [com/recsys/ad/billing/BillingOutboxRepository.java](../../../recsys-ad/src/main/java/com/recsys/ad/billing/BillingOutboxRepository.java) | 3 | 12 | DONE MP11a：见MP11A_RESULT |
| [com/recsys/ad/billing/BillingProjectionRepository.java](../../../recsys-ad/src/main/java/com/recsys/ad/billing/BillingProjectionRepository.java) | 3 | 3 | DONE MP11b：见MP11B_RESULT |
| [com/recsys/ad/billing/BudgetConfiguration.java](../../../recsys-ad/src/main/java/com/recsys/ad/billing/BudgetConfiguration.java) | 8 | 0 | DONE MP08a |
| [com/recsys/ad/billing/BudgetRepository.java](../../../recsys-ad/src/main/java/com/recsys/ad/billing/BudgetRepository.java) | 7 | 11 | DONE MP08a |
| [com/recsys/ad/billing/ClickEventConfiguration.java](../../../recsys-ad/src/main/java/com/recsys/ad/billing/ClickEventConfiguration.java) | 13 | 0 | DONE 装配已迁移：见MP11A_RESULT/MP11B_RESULT |
| [com/recsys/ad/billing/ClickPreparationConfiguration.java](../../../recsys-ad/src/main/java/com/recsys/ad/billing/ClickPreparationConfiguration.java) | 10 | 0 | DONE 装配已迁移：见MP08C_RESULT |
| [com/recsys/ad/billing/ClickProjectionRepository.java](../../../recsys-ad/src/main/java/com/recsys/ad/billing/ClickProjectionRepository.java) | 3 | 5 | DONE MP11b：见MP11B_RESULT |
| [com/recsys/ad/billing/ClickQuoteConfiguration.java](../../../recsys-ad/src/main/java/com/recsys/ad/billing/ClickQuoteConfiguration.java) | 6 | 0 | DONE 装配已迁移：见MP09A_RESULT |
| [com/recsys/ad/billing/ClickQuoteRepository.java](../../../recsys-ad/src/main/java/com/recsys/ad/billing/ClickQuoteRepository.java) | 5 | 9 | DONE MP09a：见MP09A_RESULT |
| [com/recsys/ad/billing/ClickSettlementConfiguration.java](../../../recsys-ad/src/main/java/com/recsys/ad/billing/ClickSettlementConfiguration.java) | 11 | 0 | DONE 装配已迁移：见MP09B_RESULT |
| [com/recsys/ad/billing/ClickSettlementRepository.java](../../../recsys-ad/src/main/java/com/recsys/ad/billing/ClickSettlementRepository.java) | 6 | 22 | DONE MP09b：见MP09B_RESULT |
| [com/recsys/ad/billing/ConversionEventConfiguration.java](../../../recsys-ad/src/main/java/com/recsys/ad/billing/ConversionEventConfiguration.java) | 13 | 0 | DONE 装配已迁移：见MP11A_RESULT/MP11B_RESULT |
| [com/recsys/ad/billing/ConversionPreparationConfiguration.java](../../../recsys-ad/src/main/java/com/recsys/ad/billing/ConversionPreparationConfiguration.java) | 10 | 0 | DONE 装配已迁移：见MP08C_RESULT |
| [com/recsys/ad/billing/ConversionProjectionRepository.java](../../../recsys-ad/src/main/java/com/recsys/ad/billing/ConversionProjectionRepository.java) | 3 | 3 | DONE MP11b：见MP11B_RESULT |
| [com/recsys/ad/billing/ConversionQuoteConfiguration.java](../../../recsys-ad/src/main/java/com/recsys/ad/billing/ConversionQuoteConfiguration.java) | 6 | 0 | DONE 装配已迁移：见MP10A_RESULT |
| [com/recsys/ad/billing/ConversionQuoteRepository.java](../../../recsys-ad/src/main/java/com/recsys/ad/billing/ConversionQuoteRepository.java) | 5 | 9 | DONE MP10a：见MP10A_RESULT |
| [com/recsys/ad/billing/ConversionSettlementConfiguration.java](../../../recsys-ad/src/main/java/com/recsys/ad/billing/ConversionSettlementConfiguration.java) | 10 | 0 | DONE 装配已迁移：见MP10B_RESULT |
| [com/recsys/ad/billing/ConversionSettlementRepository.java](../../../recsys-ad/src/main/java/com/recsys/ad/billing/ConversionSettlementRepository.java) | 6 | 24 | DONE MP10b：见MP10B_RESULT |
| [com/recsys/ad/billing/CpmQuoteConfiguration.java](../../../recsys-ad/src/main/java/com/recsys/ad/billing/CpmQuoteConfiguration.java) | 7 | 0 | DONE 装配已迁移：见MP08B_RESULT |
| [com/recsys/ad/billing/CpmQuoteRepository.java](../../../recsys-ad/src/main/java/com/recsys/ad/billing/CpmQuoteRepository.java) | 5 | 8 | DONE MP08b：见MP08B_RESULT |
| [com/recsys/ad/billing/FeedCandidateConfiguration.java](../../../recsys-ad/src/main/java/com/recsys/ad/billing/FeedCandidateConfiguration.java) | 7 | 0 | DONE 装配已迁移：见MP12A_RESULT |
| [com/recsys/ad/billing/FeedCandidateRepository.java](../../../recsys-ad/src/main/java/com/recsys/ad/billing/FeedCandidateRepository.java) | 5 | 8 | DONE MP12a：见MP12A_RESULT |
| [com/recsys/ad/billing/ImpressionReceiptConfiguration.java](../../../recsys-ad/src/main/java/com/recsys/ad/billing/ImpressionReceiptConfiguration.java) | 9 | 0 | DONE 装配已迁移：见MP08C_RESULT |
| [com/recsys/ad/billing/ImpressionReceiptRepository.java](../../../recsys-ad/src/main/java/com/recsys/ad/billing/ImpressionReceiptRepository.java) | 7 | 10 | DONE MP08c：见MP08C_RESULT |
| [com/recsys/ad/billing/PreparationConfiguration.java](../../../recsys-ad/src/main/java/com/recsys/ad/billing/PreparationConfiguration.java) | 14 | 0 | DONE 装配已迁移：见MP08C_RESULT |
| [com/recsys/ad/billing/PreparationRepository.java](../../../recsys-ad/src/main/java/com/recsys/ad/billing/PreparationRepository.java) | 6 | 8 | DONE MP08c：见MP08C_RESULT |
| [com/recsys/ad/billing/ReconciliationConfiguration.java](../../../recsys-ad/src/main/java/com/recsys/ad/billing/ReconciliationConfiguration.java) | 6 | 0 | DONE 装配已迁移：见MP11C_RESULT |
| [com/recsys/ad/billing/ReconciliationRepository.java](../../../recsys-ad/src/main/java/com/recsys/ad/billing/ReconciliationRepository.java) | 6 | 36 | DONE MP11c：见MP11C_RESULT |
| [com/recsys/ad/billing/ReservationConfiguration.java](../../../recsys-ad/src/main/java/com/recsys/ad/billing/ReservationConfiguration.java) | 9 | 0 | DONE 装配已迁移：见MP08B_RESULT |
| [com/recsys/ad/billing/ReservationRepository.java](../../../recsys-ad/src/main/java/com/recsys/ad/billing/ReservationRepository.java) | 6 | 14 | DONE MP08b：见MP08B_RESULT |

### recsys-advertiser

| 来源文件 | 首次命中行 | SQL 模式命中 | 核销状态 |
|---|---:|---:|---|
| [com/recsys/advertiser/AdvertiserDataSourceConfig.java](../../../recsys-advertiser/src/main/java/com/recsys/advertiser/AdvertiserDataSourceConfig.java) | 6 | 0 | MP04b复核：原连接装配保留，无业务SQL |
| [com/recsys/advertiser/AdvertiserRepository.java](../../../recsys-advertiser/src/main/java/com/recsys/advertiser/AdvertiserRepository.java) | 8 | 35 | DONE MP04b |
| [com/recsys/advertiser/DbAdReportReader.java](../../../recsys-advertiser/src/main/java/com/recsys/advertiser/DbAdReportReader.java) | 4 | 1 | DONE MP04b |

### recsys-behavior

| 来源文件 | 首次命中行 | SQL 模式命中 | 核销状态 |
|---|---:|---:|---|
| [com/recsys/behavior/BehaviorService.java](../../../recsys-behavior/src/main/java/com/recsys/behavior/BehaviorService.java) | 15 | 2 | DONE MP06a |
| [com/recsys/behavior/exposure/NaturalReceiptConfiguration.java](../../../recsys-behavior/src/main/java/com/recsys/behavior/exposure/NaturalReceiptConfiguration.java) | 11 | 0 | DONE MP06b |
| [com/recsys/behavior/exposure/NaturalReceiptRepository.java](../../../recsys-behavior/src/main/java/com/recsys/behavior/exposure/NaturalReceiptRepository.java) | 9 | 14 | DONE MP06b |

### recsys-console

| 来源文件 | 首次命中行 | SQL 模式命中 | 核销状态 |
|---|---:|---:|---|
| [com/recsys/console/report/ReportService.java](../../../recsys-console/src/main/java/com/recsys/console/report/ReportService.java) | 12 | 2 | DONE MP06c |
| [com/recsys/console/user/UserProfileService.java](../../../recsys-console/src/main/java/com/recsys/console/user/UserProfileService.java) | 7 | 7 | DONE MP06c |

### recsys-content

| 来源文件 | 首次命中行 | SQL 模式命中 | 核销状态 |
|---|---:|---:|---|
| [com/recsys/content/AbstractItemCatalogReader.java](../../../recsys-content/src/main/java/com/recsys/content/AbstractItemCatalogReader.java) | 5 | 16 | MP02b DONE：ItemCatalogMapper.xml；见MP02B_RESULT |
| [com/recsys/content/DbItemCatalogReader.java](../../../recsys-content/src/main/java/com/recsys/content/DbItemCatalogReader.java) | 4 | 0 | MP02b DONE：ItemCatalogMapper.xml；见MP02B_RESULT |
| [com/recsys/content/JdbcContentService.java](../../../recsys-content/src/main/java/com/recsys/content/MybatisContentService.java) | 5 | 5 | MP02a DONE：MybatisContentService / ItemMapper.xml；见MP02A_RESULT |
| [com/recsys/content/ReplicaItemCatalogReader.java](../../../recsys-content/src/main/java/com/recsys/content/ReplicaItemCatalogReader.java) | 4 | 0 | MP02b DONE：ItemCatalogMapper.xml；见MP02B_RESULT |
| [com/recsys/content/ReplicaJdbcContentService.java](../../../recsys-content/src/main/java/com/recsys/content/ReplicaMybatisContentService.java) | 4 | 0 | MP02a DONE：MybatisContentService / ItemMapper.xml；见MP02A_RESULT |

### recsys-offline

| 来源文件 | 首次命中行 | SQL 模式命中 | 核销状态 |
|---|---:|---:|---|
| [com/recsys/offline/AbReportJob.java](../../../recsys-offline/src/main/java/com/recsys/offline/AbReportJob.java) | 6 | 3 | TODO |
| [com/recsys/offline/AdCalibrateJob.java](../../../recsys-offline/src/main/java/com/recsys/offline/AdCalibrateJob.java) | 8 | 2 | TODO |
| [com/recsys/offline/AdExploreStatsJob.java](../../../recsys-offline/src/main/java/com/recsys/offline/AdExploreStatsJob.java) | 8 | 3 | TODO |
| [com/recsys/offline/AdReportJob.java](../../../recsys-offline/src/main/java/com/recsys/offline/AdReportJob.java) | 6 | 7 | TODO |
| [com/recsys/offline/AttributionJob.java](../../../recsys-offline/src/main/java/com/recsys/offline/AttributionJob.java) | 6 | 1 | TODO |
| [com/recsys/offline/BackfillEmbeddingJob.java](../../../recsys-offline/src/main/java/com/recsys/offline/BackfillEmbeddingJob.java) | 11 | 3 | DONE MP13c1：derived向量写入；见MP13C1_RESULT |
| [com/recsys/offline/BanditStatsJob.java](../../../recsys-offline/src/main/java/com/recsys/offline/BanditStatsJob.java) | 15 | 1 | PARTIAL MP13a2：正反馈完成，其余SQL待迁移；见MP13A2_RESULT |
| [com/recsys/offline/BehaviorQuery.java](../../../recsys-offline/src/main/java/com/recsys/offline/BehaviorQuery.java) | 51 | 1 | DONE MP13a2：共用正反馈Mapper/XML；见MP13A2_RESULT |
| [com/recsys/offline/BuildFeaturesJob.java](../../../recsys-offline/src/main/java/com/recsys/offline/BuildFeaturesJob.java) | 7 | 7 | TODO |
| [com/recsys/offline/ColdBanditStatsJob.java](../../../recsys-offline/src/main/java/com/recsys/offline/ColdBanditStatsJob.java) | 8 | 1 | TODO |
| [com/recsys/offline/DataQualityJob.java](../../../recsys-offline/src/main/java/com/recsys/offline/DataQualityJob.java) | 6 | 9 | TODO |
| [com/recsys/offline/DelayModelJob.java](../../../recsys-offline/src/main/java/com/recsys/offline/DelayModelJob.java) | 8 | 1 | TODO |
| [com/recsys/offline/EvalJob.java](../../../recsys-offline/src/main/java/com/recsys/offline/EvalJob.java) | 14 | 2 | TODO |
| [com/recsys/offline/FtrlTrainJob.java](../../../recsys-offline/src/main/java/com/recsys/offline/FtrlTrainJob.java) | 13 | 1 | PARTIAL MP13a2：正反馈完成，其余SQL待迁移；见MP13A2_RESULT |
| [com/recsys/offline/GenAdCvrSamplesJob.java](../../../recsys-offline/src/main/java/com/recsys/offline/GenAdCvrSamplesJob.java) | 11 | 3 | TODO |
| [com/recsys/offline/GenAdUpliftSamplesJob.java](../../../recsys-offline/src/main/java/com/recsys/offline/GenAdUpliftSamplesJob.java) | 7 | 2 | TODO |
| [com/recsys/offline/GenSamplesImprJob.java](../../../recsys-offline/src/main/java/com/recsys/offline/GenSamplesImprJob.java) | 7 | 4 | TODO |
| [com/recsys/offline/GenSamplesJob.java](../../../recsys-offline/src/main/java/com/recsys/offline/GenSamplesJob.java) | 8 | 3 | TODO |
| [com/recsys/offline/GenSamplesMtJob.java](../../../recsys-offline/src/main/java/com/recsys/offline/GenSamplesMtJob.java) | 7 | 3 | TODO |
| [com/recsys/offline/HotJob.java](../../../recsys-offline/src/main/java/com/recsys/offline/HotJob.java) | 9 | 1 | TODO |
| [com/recsys/offline/IdfJob.java](../../../recsys-offline/src/main/java/com/recsys/offline/IdfJob.java) | 9 | 6 | TODO |
| [com/recsys/offline/ImportBehaviorJob.java](../../../recsys-offline/src/main/java/com/recsys/offline/ImportBehaviorJob.java) | 7 | 3 | DONE MP13b1；见MP13B1_RESULT |
| [com/recsys/offline/ImportR9EmbeddingsJob.java](../../../recsys-offline/src/main/java/com/recsys/offline/ImportR9EmbeddingsJob.java) | 7 | 10 | TODO |
| [com/recsys/offline/ImportSemanticIdJob.java](../../../recsys-offline/src/main/java/com/recsys/offline/ImportSemanticIdJob.java) | 6 | 6 | DONE MP13c5：500行BATCH/独立bootstrap；见MP13C5_RESULT |
| [com/recsys/offline/ImportTowerJob.java](../../../recsys-offline/src/main/java/com/recsys/offline/ImportTowerJob.java) | 7 | 6 | DONE MP13c4：500行BATCH/独立bootstrap；见MP13C4_RESULT |
| [com/recsys/offline/ItemCfJob.java](../../../recsys-offline/src/main/java/com/recsys/offline/ItemCfJob.java) | 9 | 0 | DONE MP13a2：共用正反馈Mapper/XML；见MP13A2_RESULT |
| [com/recsys/offline/LookalikeJob.java](../../../recsys-offline/src/main/java/com/recsys/offline/LookalikeJob.java) | 9 | 4 | TODO |
| [com/recsys/offline/MultimodalBackfillJob.java](../../../recsys-offline/src/main/java/com/recsys/offline/MultimodalBackfillJob.java) | 8 | 3 | DONE MP13c2：derived仅UPDATE融合；见MP13C2_RESULT |
| [com/recsys/offline/OcpcCalibrateJob.java](../../../recsys-offline/src/main/java/com/recsys/offline/OcpcCalibrateJob.java) | 8 | 4 | TODO |
| [com/recsys/offline/OfflineAdShardingConfig.java](../../../recsys-offline/src/main/java/com/recsys/offline/OfflineAdShardingConfig.java) | 10 | 0 | PARTIAL MP13a1/b5：四源工厂/Mapper扫描及真实种子路由完成，旧JdbcTemplate待其余作业退出 |
| [com/recsys/offline/PublishReportsJob.java](../../../recsys-offline/src/main/java/com/recsys/offline/PublishReportsJob.java) | 10 | 2 | TODO |
| [com/recsys/offline/QualityScoreJob.java](../../../recsys-offline/src/main/java/com/recsys/offline/QualityScoreJob.java) | 8 | 3 | TODO |
| [com/recsys/offline/RecCalibrateJob.java](../../../recsys-offline/src/main/java/com/recsys/offline/RecCalibrateJob.java) | 13 | 1 | TODO |
| [com/recsys/offline/SeedAdsJob.java](../../../recsys-offline/src/main/java/com/recsys/offline/SeedAdsJob.java) | 10 | 11 | DONE MP13b5：真实四源/双分片；见MP13B5_RESULT |
| [com/recsys/offline/SimAdEventsJob.java](../../../recsys-offline/src/main/java/com/recsys/offline/SimAdEventsJob.java) | 7 | 5 | DONE MP13b6：保留真实大批次进度；见MP13B6_RESULT |
| [com/recsys/offline/SimAdUpliftDataJob.java](../../../recsys-offline/src/main/java/com/recsys/offline/SimAdUpliftDataJob.java) | 7 | 2 | DONE MP13b8：adDb双批次；见MP13B8_RESULT |
| [com/recsys/offline/SimRecEventsJob.java](../../../recsys-offline/src/main/java/com/recsys/offline/SimRecEventsJob.java) | 7 | 4 | DONE MP13b7：主源大批次/热度NULL兼容；见MP13B7_RESULT |
| [com/recsys/offline/SwingJob.java](../../../recsys-offline/src/main/java/com/recsys/offline/SwingJob.java) | 9 | 0 | DONE MP13a2：共用正反馈Mapper/XML；见MP13A2_RESULT |
| [com/recsys/offline/SyncAdEventLogJob.java](../../../recsys-offline/src/main/java/com/recsys/offline/SyncAdEventLogJob.java) | 6 | 9 | DONE MP13b4；见MP13B4_RESULT |
| [com/recsys/offline/SyncBehaviorLogJob.java](../../../recsys-offline/src/main/java/com/recsys/offline/SyncBehaviorLogJob.java) | 6 | 10 | DONE MP13b2；见MP13B2_RESULT |
| [com/recsys/offline/SyncItemCatalogJob.java](../../../recsys-offline/src/main/java/com/recsys/offline/SyncItemCatalogJob.java) | 6 | 9 | DONE MP13b3；见MP13B3_RESULT |
| [com/recsys/offline/TuneFusionJob.java](../../../recsys-offline/src/main/java/com/recsys/offline/TuneFusionJob.java) | 11 | 1 | TODO |
| [com/recsys/offline/UserCfJob.java](../../../recsys-offline/src/main/java/com/recsys/offline/UserCfJob.java) | 9 | 0 | DONE MP13a2：共用正反馈Mapper/XML；见MP13A2_RESULT |
| [com/recsys/offline/UserEmbeddingJob.java](../../../recsys-offline/src/main/java/com/recsys/offline/UserEmbeddingJob.java) | 7 | 4 | DONE MP13a2/c3：主源正反馈与派生向量；见MP13C3_RESULT |

### recsys-query

| 来源文件 | 首次命中行 | SQL 模式命中 | 核销状态 |
|---|---:|---:|---|
| [com/recsys/query/IdfWeighter.java](../../../recsys-query/src/main/java/com/recsys/query/IdfWeighter.java) | 8 | 2 | MP03a DONE：LexemeMapper.xml；见MP03A_RESULT |

### recsys-rank

| 来源文件 | 首次命中行 | SQL 模式命中 | 核销状态 |
|---|---:|---:|---|
| [com/recsys/rank/DienRankService.java](../../../recsys-rank/src/main/java/com/recsys/rank/DienRankService.java) | 22 | 1 | MP03d DONE：RatingSequenceMapper.xml；见MP03D_RESULT |
| [com/recsys/rank/DinRankService.java](../../../recsys-rank/src/main/java/com/recsys/rank/DinRankService.java) | 22 | 1 | MP03d DONE：RatingSequenceMapper.xml；见MP03D_RESULT |

### recsys-rec-engine

| 来源文件 | 首次命中行 | SQL 模式命中 | 核销状态 |
|---|---:|---:|---|
| [com/recsys/recengine/PersonalizationScorer.java](../../../recsys-rec-engine/src/main/java/com/recsys/recengine/PersonalizationScorer.java) | 5 | 2 | DONE MP07a |
| [com/recsys/recengine/coldstart/ColdStartDetector.java](../../../recsys-rec-engine/src/main/java/com/recsys/recengine/coldstart/ColdStartDetector.java) | 7 | 2 | DONE MP07a |
| [com/recsys/recengine/delivery/ClickDeliveryConfiguration.java](../../../recsys-rec-engine/src/main/java/com/recsys/recengine/delivery/ClickDeliveryConfiguration.java) | 12 | 0 | DONE MP12b：见MP12B_RESULT |
| [com/recsys/recengine/delivery/ConversionDeliveryConfiguration.java](../../../recsys-rec-engine/src/main/java/com/recsys/recengine/delivery/ConversionDeliveryConfiguration.java) | 12 | 0 | DONE MP12b：见MP12B_RESULT |
| [com/recsys/recengine/delivery/DeliveryOperationConfiguration.java](../../../recsys-rec-engine/src/main/java/com/recsys/recengine/delivery/DeliveryOperationConfiguration.java) | 12 | 0 | DONE MP12b：见MP12B_RESULT |
| [com/recsys/recengine/delivery/DeliveryOperationRepository.java](../../../recsys-rec-engine/src/main/java/com/recsys/recengine/delivery/DeliveryOperationRepository.java) | 11 | 8 | DONE MP12b：见MP12B_RESULT |
| [com/recsys/recengine/experiment/ExposureLogger.java](../../../recsys-rec-engine/src/main/java/com/recsys/recengine/experiment/ExposureLogger.java) | 13 | 1 | DONE MP07a |
| [com/recsys/recengine/exposure/NaturalDeliveryConfiguration.java](../../../recsys-rec-engine/src/main/java/com/recsys/recengine/exposure/NaturalDeliveryConfiguration.java) | 13 | 0 | DONE MP12c：见MP12C_RESULT |
| [com/recsys/recengine/exposure/NaturalDeliveryRepository.java](../../../recsys-rec-engine/src/main/java/com/recsys/recengine/exposure/NaturalDeliveryRepository.java) | 8 | 8 | DONE MP12c：见MP12C_RESULT |
| [com/recsys/recengine/feed/FeedDeliveryConfiguration.java](../../../recsys-rec-engine/src/main/java/com/recsys/recengine/feed/FeedDeliveryConfiguration.java) | 16 | 0 | DONE MP12d：见MP12D_RESULT |
| [com/recsys/recengine/feed/FeedDeliveryRepository.java](../../../recsys-rec-engine/src/main/java/com/recsys/recengine/feed/FeedDeliveryRepository.java) | 7 | 9 | DONE MP12d：见MP12D_RESULT |
| [com/recsys/recengine/filter/DbSeenItemsSource.java](../../../recsys-rec-engine/src/main/java/com/recsys/recengine/filter/DbSeenItemsSource.java) | 6 | 1 | DONE MP07a |
| [com/recsys/recengine/rerank/DppReranker.java](../../../recsys-rec-engine/src/main/java/com/recsys/recengine/rerank/DppReranker.java) | 7 | 1 | DONE MP07b |
| [com/recsys/recengine/rerank/MmrReranker.java](../../../recsys-rec-engine/src/main/java/com/recsys/recengine/rerank/MmrReranker.java) | 7 | 1 | DONE MP07b |

### recsys-recall

| 来源文件 | 首次命中行 | SQL 模式命中 | 核销状态 |
|---|---:|---:|---|
| [com/recsys/recall/BehaviorSequenceSource.java](../../../recsys-recall/src/main/java/com/recsys/recall/BehaviorSequenceSource.java) | 8 | 2 | MP03b DONE：RecallInputMapper.xml；见MP03B_RESULT |
| [com/recsys/recall/channel/DbUserProfileReader.java](../../../recsys-recall/src/main/java/com/recsys/recall/channel/DbUserProfileReader.java) | 7 | 1 | MP03b DONE：RecallInputMapper.xml；见MP03B_RESULT |
| [com/recsys/recall/channel/GraphRecaller.java](../../../recsys-recall/src/main/java/com/recsys/recall/channel/GraphRecaller.java) | 13 | 2 | MP03c2 DONE：EmbeddingMapper.xml；见MP03C2_RESULT |
| [com/recsys/recall/channel/I2iRecaller.java](../../../recsys-recall/src/main/java/com/recsys/recall/channel/I2iRecaller.java) | 12 | 1 | MP03b DONE：RecallInputMapper.xml；见MP03B_RESULT |
| [com/recsys/recall/channel/MultiInterestRecaller.java](../../../recsys-recall/src/main/java/com/recsys/recall/channel/MultiInterestRecaller.java) | 18 | 1 | MP03c2 DONE：EmbeddingMapper.xml；见MP03C2_RESULT |
| [com/recsys/recall/channel/RecentPositiveItemsSource.java](../../../recsys-recall/src/main/java/com/recsys/recall/channel/RecentPositiveItemsSource.java) | 3 | 1 | MP03b DONE：RecallInputMapper.xml；见MP03B_RESULT |
| [com/recsys/recall/channel/SemanticIdRecaller.java](../../../recsys-recall/src/main/java/com/recsys/recall/channel/SemanticIdRecaller.java) | 9 | 3 | MP03c3 DONE：SemanticCodeMapper/RecallInputMapper XML；见MP03C3_RESULT |
| [com/recsys/recall/channel/SemanticRecaller.java](../../../recsys-recall/src/main/java/com/recsys/recall/channel/SemanticRecaller.java) | 13 | 1 | MP03c1 DONE：EmbeddingMapper.xml；见MP03C1_RESULT |
| [com/recsys/recall/channel/SwingRecaller.java](../../../recsys-recall/src/main/java/com/recsys/recall/channel/SwingRecaller.java) | 12 | 1 | MP03b DONE：RecallInputMapper.xml；见MP03B_RESULT |
| [com/recsys/recall/channel/TigerRecaller.java](../../../recsys-recall/src/main/java/com/recsys/recall/channel/TigerRecaller.java) | 17 | 5 | MP03c3 DONE：SemanticCodeMapper/RecallInputMapper XML；见MP03C3_RESULT |
| [com/recsys/recall/channel/TwoTowerRecaller.java](../../../recsys-recall/src/main/java/com/recsys/recall/channel/TwoTowerRecaller.java) | 19 | 2 | MP03c2 DONE：EmbeddingMapper.xml；见MP03C2_RESULT |
| [com/recsys/recall/channel/VectorRecaller.java](../../../recsys-recall/src/main/java/com/recsys/recall/channel/VectorRecaller.java) | 11 | 2 | MP03c1 DONE：EmbeddingMapper.xml；见MP03C1_RESULT |

### recsys-streaming

| 来源文件 | 首次命中行 | SQL 模式命中 | 核销状态 |
|---|---:|---:|---|
| [com/recsys/streaming/RealtimeFeatureJob.java](../../../recsys-streaming/src/main/java/com/recsys/streaming/RealtimeFeatureJob.java) | 26 | 1 | TODO |

### recsys-user

| 来源文件 | 首次命中行 | SQL 模式命中 | 核销状态 |
|---|---:|---:|---|
| [com/recsys/user/UserProfileService.java](../../../recsys-user/src/main/java/com/recsys/user/UserProfileService.java) | 6 | 2 | MP01 DONE：UserProfileMapper / mapper/user/primary/UserProfileMapper.xml；见 MP01_RESULT |

## 追加关联路径

- `recsys-offline/.../ItemQuery.java`：MP13b5已复用CatalogTable封闭枚举，SeedAds走source；其余旧作业table白名单入口待后续退出，最终核销所有调用方。
- 各 `src/test`、`src/main/resources/application*.yml`、sharding 配置、应用启动类、pom、Testcontainers fixtures 与浏览器宿主：按 Mapper 注册/构造参数变化定位，不能只机械改上表。
- schema/摘要校验 SELECT 属于生产查询，也迁移到明确 Mapper；连接初始化语句和 DDL 脚本 runner 属于有说明的例外。

## 独立 SQL 资源基线

以下内容保留，不修改已执行迁移。新追加或从 Java 提取的 DDL 使用新资源路径，不覆盖历史文件。SHA-256 用于最终核验未漂移。

| 资源 | SHA-256 | 处理 |
|---|---|---|
| [recsys-ad/src/main/resources/db/ad-billing/V001__budget_account.sql](../../../recsys-ad/src/main/resources/db/ad-billing/V001__budget_account.sql) | `3c0b345728ed04c990ba51c5da93eef556e6eef95bf87fa8ff2173624cdb6d3f` | 保留/核验 |
| [recsys-ad/src/main/resources/db/ad-billing/V002__cpm_quote.sql](../../../recsys-ad/src/main/resources/db/ad-billing/V002__cpm_quote.sql) | `3288d2666d3da60af2533afebc468dec3f38a2f553128e8fc4920fd465776262` | 保留/核验 |
| [recsys-ad/src/main/resources/db/ad-billing/V003__budget_reservation.sql](../../../recsys-ad/src/main/resources/db/ad-billing/V003__budget_reservation.sql) | `1accbc28fb909db983593de5286ace01f0daed8a3ac82a4f49f84b844d57f17f` | 保留/核验 |
| [recsys-ad/src/main/resources/db/ad-billing/V004__delivery_preparation.sql](../../../recsys-ad/src/main/resources/db/ad-billing/V004__delivery_preparation.sql) | `225cff7afdc43ffc295b5e4f9441a653b1349fbbfd3ced20e2cea813bb9c1bd9` | 保留/核验 |
| [recsys-ad/src/main/resources/db/ad-billing/V005__impression_capture.sql](../../../recsys-ad/src/main/resources/db/ad-billing/V005__impression_capture.sql) | `dc4203de1e4ba5a3e39148279cd27f1caa6c2925fb3e9cfe23b2581448a74f14` | 保留/核验 |
| [recsys-ad/src/main/resources/db/ad-billing/V006__billing_event_delivery.sql](../../../recsys-ad/src/main/resources/db/ad-billing/V006__billing_event_delivery.sql) | `ee9dbfa9bf14dfe20c6fb5e60cb082525648e243d021abc7302c684ec278702f` | 保留/核验 |
| [recsys-ad/src/main/resources/db/ad-billing/V007__feed_frequency.sql](../../../recsys-ad/src/main/resources/db/ad-billing/V007__feed_frequency.sql) | `fdc8de11fcfdfe67fd8bdee761ec07ecdf41c86ed663a94f5f1879fb87779412` | 保留/核验 |
| [recsys-ad/src/main/resources/db/ad-billing/V008__click_quote.sql](../../../recsys-ad/src/main/resources/db/ad-billing/V008__click_quote.sql) | `54c9ca8abebf7dbfb468e2134d0cd3b3942bdfec8c9534c388518455591ff214` | 保留/核验 |
| [recsys-ad/src/main/resources/db/ad-billing/V009__click_settlement.sql](../../../recsys-ad/src/main/resources/db/ad-billing/V009__click_settlement.sql) | `a3565fddeac60707b13df2752ef8f2c34d484ca2f74f4f64fa30f01a1a3a5302` | 保留/核验 |
| [recsys-ad/src/main/resources/db/ad-billing/V010__click_event_delivery.sql](../../../recsys-ad/src/main/resources/db/ad-billing/V010__click_event_delivery.sql) | `fc9d3b771a0517245e0fd7b4d2c1b3670dd5973002c061afad0a78d0d96d2b93` | 保留/核验 |
| [recsys-ad/src/main/resources/db/ad-billing/V011__click_preparation.sql](../../../recsys-ad/src/main/resources/db/ad-billing/V011__click_preparation.sql) | `09815445a1c2b0c062e411faeb13f6339dea1dd7fbc92940959f56ccf8781808` | 保留/核验 |
| [recsys-ad/src/main/resources/db/ad-billing/V012__conversion_quote.sql](../../../recsys-ad/src/main/resources/db/ad-billing/V012__conversion_quote.sql) | `6ff7b33e6caa156767bddbfc7259c7fe9f860dc24298ddd9bf9501941fc4f266` | 保留/核验 |
| [recsys-ad/src/main/resources/db/ad-billing/V013__conversion_settlement.sql](../../../recsys-ad/src/main/resources/db/ad-billing/V013__conversion_settlement.sql) | `f5f9dc1a1285926ad5608480d6d189987d5b2e30298abf5008bee5d7cf90bffe` | 保留/核验 |
| [recsys-ad/src/main/resources/db/ad-billing/V014__conversion_event_delivery.sql](../../../recsys-ad/src/main/resources/db/ad-billing/V014__conversion_event_delivery.sql) | `6168dbb3f9cd7d8e1d6be1380ac7b008e4827d92427634091d497f399195b2c7` | 保留/核验 |
| [recsys-ad/src/main/resources/db/ad-billing/V015__conversion_preparation.sql](../../../recsys-ad/src/main/resources/db/ad-billing/V015__conversion_preparation.sql) | `5a9cb8e31f55c2a2094e2f08dff5cc574d2b08e643f1e8240d76d6077276e512` | 保留/核验 |
| [recsys-ad/src/main/resources/db/ad-billing/V016__reconciliation_indexes.sql](../../../recsys-ad/src/main/resources/db/ad-billing/V016__reconciliation_indexes.sql) | `67aa36bbd73c1f37a6f8b4cdf023ec18dbfed1a781706321bbfe7a8d7461c715` | 保留/核验 |
| [recsys-behavior/src/main/resources/db/natural-exposure/V001__natural_receipt.sql](../../../recsys-behavior/src/main/resources/db/natural-exposure/V001__natural_receipt.sql) | `6f78424221c76819ec7fbff4fa308583c8afb017d69d4115e48e11b756e43e19` | 保留/核验 |
| [recsys-offline/sql/00_spicedb_bootstrap.sql](../../../recsys-offline/sql/00_spicedb_bootstrap.sql) | `a78e09435a3120460b8b2ab7c4006456a94f5b22b592758afbd9492f9a7b8004` | 保留/核验 |
| [recsys-offline/sql/01_schema.sql](../../../recsys-offline/sql/01_schema.sql) | `624b9852e856f35d2eb2bb388478a0e0351a8aec8a18d1d604f96d13304be703` | 保留/核验 |
| [recsys-offline/sql/02_ad_schema.sql](../../../recsys-offline/sql/02_ad_schema.sql) | `f58635ebbb7d88a1c3574c54f20f0dfb3c6424ed40ca96939cf60941e162692a` | 保留/核验 |
| [recsys-offline/sql/02_two_tower.sql](../../../recsys-offline/sql/02_two_tower.sql) | `abec73f11cb9c8c35783ad865253acbacda48b3584ec176cff2953c033b6bf1c` | 保留/核验 |
| [recsys-offline/sql/03_semantic_id.sql](../../../recsys-offline/sql/03_semantic_id.sql) | `0021d7778605c36f83aa5024e0029a42a8677655c9edf174aa8058b403702584` | 保留/核验 |
| [recsys-offline/sql/04_ad_sharding.sql](../../../recsys-offline/sql/04_ad_sharding.sql) | `0eb577fa5f8596967b104b3857a611cb3e90aa081d023bf158a91c6b74293889` | 保留/核验 |
| [recsys-offline/sql/04b_ds1_bootstrap.sql](../../../recsys-offline/sql/04b_ds1_bootstrap.sql) | `f1c5d41ed7990df2b42794192fe6cd2ca8cc4cba4ceb67d244657375fbc73d56` | 保留/核验 |
| [recsys-offline/sql/05_partitioning.sql](../../../recsys-offline/sql/05_partitioning.sql) | `caf1e4173f6aae6df0dc5484f4695b5ac4924fec732474000d2974b1d3653c62` | 保留/核验 |
| [recsys-offline/sql/06_eval_report.sql](../../../recsys-offline/sql/06_eval_report.sql) | `ff4731fded9e365021d7c147882270467601a6fe6895e1595601615e6f49f9bd` | 保留/核验 |
| [recsys-offline/sql/08_ad_servable.sql](../../../recsys-offline/sql/08_ad_servable.sql) | `40f1a6a7a2b24185eb6fbb41737a7b0825b3820ffed7c1f2b2ad6359d9be5228` | 保留/核验 |
| [recsys-offline/sql/09_behavior_log.sql](../../../recsys-offline/sql/09_behavior_log.sql) | `591858344ceae19bfa96eb26d1c6cbb46e5e4eb7dcc1d78e52869da40bf4ffa3` | 保留/核验 |
| [recsys-offline/sql/10_behavior_db.sql](../../../recsys-offline/sql/10_behavior_db.sql) | `7aca2677d9c9d241fd05b5f2efd88b53912ee4203c81075b097342eec0005480` | 保留/核验 |
| [recsys-offline/sql/11_item_local.sql](../../../recsys-offline/sql/11_item_local.sql) | `a6b3923abbb883294b924774ae06243e10bc59879c8c69849f29b180d5987ce6` | 保留/核验 |
| [recsys-offline/sql/12_content_db.sql](../../../recsys-offline/sql/12_content_db.sql) | `6e4d32b306a72735260db0158654d80467b662bdc346d0487e15a995007df4ea` | 保留/核验 |
| [recsys-offline/sql/13_ad_serving_db.sql](../../../recsys-offline/sql/13_ad_serving_db.sql) | `b3a740fff435c6079a62fb8b7e69f8f9dc9e89ed2c1f940d1091a2405b47e710` | 保留/核验 |
| [recsys-offline/sql/14_ad_event_log.sql](../../../recsys-offline/sql/14_ad_event_log.sql) | `9e88d034e3b03a899af52daa110b824f0079c116620889005b53057c261aeddf` | 保留/核验 |
| [recsys-offline/sql/15_derived_db.sql](../../../recsys-offline/sql/15_derived_db.sql) | `de1d8a3e017c69f640687dbf3034a734458fbfd0d83e034280d548b7f9bda7b6` | 保留/核验 |
| [recsys-offline/sql/16_exposure_click_idempotency.sql](../../../recsys-offline/sql/16_exposure_click_idempotency.sql) | `9256620bac271a0e1c6217b6844a4f8f0669e1f1224372085483c37a6cbc7a7d` | 保留/核验 |
| [recsys-offline/sql/16_item_indexes.sql](../../../recsys-offline/sql/16_item_indexes.sql) | `bc35ea294f4e1c981b239c1427d148447f5b8b14950e3df15e99033cb5df9d2a` | 保留/核验 |
| [recsys-offline/sql/17_table_comments.sql](../../../recsys-offline/sql/17_table_comments.sql) | `8a27c982eb4e1993dcced79c89db21a4ecb2b2714162f5ea617fad83c73ab7ab` | 保留/核验 |
| [recsys-offline/sql/18_advanced_recall.sql](../../../recsys-offline/sql/18_advanced_recall.sql) | `88b717ad057ae2ee5f1b74f05e9b0fb1166815d9b9db54b555557069229060e5` | 保留/核验 |
| [recsys-offline/sql/19_ad_uplift.sql](../../../recsys-offline/sql/19_ad_uplift.sql) | `1b754737f891c3e48085e26ca30dfa0e841ca43bf11e6abcadf86265131f9c2b` | 保留/核验 |
| [recsys-offline/sql/20_ds1_bootstrap.sql](../../../recsys-offline/sql/20_ds1_bootstrap.sql) | `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855` | 保留/核验 |
| [recsys-rec-engine/src/main/resources/db/engine-delivery/V001__delivery_operation.sql](../../../recsys-rec-engine/src/main/resources/db/engine-delivery/V001__delivery_operation.sql) | `47ec047ba74bd9fbe5ee21338ab6ce3c56396e54130d38f8425c854a7b85f357` | 保留/核验 |
| [recsys-rec-engine/src/main/resources/db/engine-delivery/V002__natural_delivery.sql](../../../recsys-rec-engine/src/main/resources/db/engine-delivery/V002__natural_delivery.sql) | `85fb2af9bfcab31907ccb0f2df8174e9fac6be993fb431700c9068bb667f1742` | 保留/核验 |
| [recsys-rec-engine/src/main/resources/db/engine-delivery/V003__feed_delivery.sql](../../../recsys-rec-engine/src/main/resources/db/engine-delivery/V003__feed_delivery.sql) | `41c0fc6fe92fa9c0e5d59f912791fcc73e0026a58f887f4edf382e2d4e61e530` | 保留/核验 |
| [recsys-rec-engine/src/main/resources/db/engine-delivery/V004__click_delivery.sql](../../../recsys-rec-engine/src/main/resources/db/engine-delivery/V004__click_delivery.sql) | `3d53a3e09bd9011501350bd510415da6b60e3f4d8f148c44d4416663f68f1cbc` | 保留/核验 |
| [recsys-rec-engine/src/main/resources/db/engine-delivery/V005__conversion_delivery.sql](../../../recsys-rec-engine/src/main/resources/db/engine-delivery/V005__conversion_delivery.sql) | `aef1ad0697d23a46bdab4d8c46d2a80b0e116da7221834222ce81e8a6cba1c9b` | 保留/核验 |

## 单语句映射模板

每个 SQL 方法在迁移前记录：来源类/方法 → 新 Mapper statement ID → 数据源 → 参数/空值/主键策略 → 结果/排序/limit → 事务/锁/超时/缓存 → 错误/影响行数 → 对应真实数据库测试 → 验证 revision。

机械关键词扫描只能发现遗漏；最终还需调用链、Mapper 实际注册/资源打包和真实业务测试共同证明覆盖。

MP06b：NaturalReceiptRepository/Configuration → `behavior.persistence.mapper.primary.NaturalReceiptMapper` + `mapper/behavior/primary/NaturalReceiptMapper.xml`；共享UUID handler；证据见MP06B_RESULT。

MP06c：UserProfileService/ReportService → `console.persistence.mapper.primary.{UserProfileMapper,ReportMapper}` + `mapper/console/primary/*Mapper.xml`；console生产Java SQL已核销，证据见MP06C_RESULT。

MP07a：推荐输入与曝光 → `recengine.persistence.mapper.{primary.EngineBehaviorMapper,derived.EngineEmbeddingMapper}` + `mapper/recengine/{primary,derived}/*.xml`；证据见MP07A_RESULT。

MP07b：MmrReranker/DppReranker → 派生EngineEmbeddingMapper.itemVectors + XML；算法SQL已核销，证据见MP07B_RESULT。

MP08a：BudgetRepository/Configuration → addb BudgetMapper/XML；实际SqlSessionTemplate资源身份，UPDATE RETURNING写标记，证据见MP08A_RESULT。Click/ConversionQuote及三族Event仅同步预算schema验证构造，其他SQL仍属于后续工作包。

MP13a2补充：PositiveFeedbackMapper.scanPairs/scanWeighted对历史可空ID显式保留JDBC getLong(NULL)=0，见OFFLINE_NULL_ID_RESULT；来源/过滤/重复行和其他投影不变。
