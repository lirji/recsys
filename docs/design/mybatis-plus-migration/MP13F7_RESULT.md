# MP13f7：退出过渡JDBC装配与表名字符串入口

DONE/PASS。11源指纹 `f05087c5df796f648a169cc6c2f81242752ea29cbf3383053037d6ca44c76bf1`，清单 `/tmp/recsys-mp13f7-source-paths.json`。

AdShardingConfig/OfflineAdShardingConfig删除已无生产消费者的四路JdbcTemplate Bean，实际DataSource构造、连接配置及事务资源不变。advertiser删除主JdbcTemplate；派生SqlSessionFactory直接由原AdvertiserDataSourceConfig使用原环境配置创建，派生DriverManagerDataSource仍为非Bean、无池，避免Boot主数据源自动配置退避。删除BehaviorQuery/ItemQuery/AdEventQuery的过渡table字符串方法，调用方全部使用封闭来源枚举及XML固定分支。

- 旧24/5类、新24/5类全部通过：真实双PG分片目录、advertiser CRUD/Boot主源装配、在线推荐宿主、离线四源工厂绑定及来源校验。
- advertiser测试断言主DataSource Bean仍唯一，主工厂指向同一实例；派生工厂为不同的无池源，两个工厂共存，无derivedJdbc Bean。
- 完整离线280/46类，0失败/错误/跳过；全仓24 reactor测试编译通过。测试编译亦包含下一片新增的Flink旧行为保护测试，Flink生产此时未改；该测试不纳入本片路径或行为证据。
- Hygiene IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS，无阻塞；统一formatter和独立Java静态分析未配置。45历史SQL、8保护文件摘要保持。
- Boot仍可能自动提供主JdbcTemplate；R9EmbeddingStore保留无SQL连接适配以保持旧手动事务边界。不能将本次清理表述为运行时彻底禁用JdbcTemplate。

日志 `/tmp/recsys-mp13f7-{before,after,offline,compile}.log`、`/tmp/recsys-mp13f7-hygiene.json`。MP13整体完成，生产业务Java SQL只剩MP14 Flink类目读取。MP15完整验收及统一错误码/魔法值目标仍未完成。

前序f2e4ccff的CI38779450526五门禁全部SUCCESS；f6与本片按相邻逻辑提交正常ff/push，最终Git事实见下一片进度入口。
