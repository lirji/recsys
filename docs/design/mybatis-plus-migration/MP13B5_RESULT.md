# MP13b5：广告种子四源与真实分片

DONE/PASS。14源文件指纹`bf4d50f931376cdc6f7d46ceab1cb9aea1b3ef1a652443e64403fe58bb692f41`，清单`/tmp/recsys-mp13b5-source-paths.json`。

SeedAdsJob全部业务SQL迁入四源Mapper/XML：主库SeedItemMapper读取目录、derived SeedItemVectorMapper读取向量、addb SeedAdVectorMapper清理/写广告向量、sharded SeedAdMapper写广告主/广告/创意/竞价词及按固定表族清理。保留原查询次数、随机种子/稳定排序/分词/出价/Redis编排和逐条提交；不新加跨源事务。广告按advertiserId、创意与竞价词按adId路由，显式种子ID和创意Snowflake职责不变，未引入MP自动生成ID。

OfflinePersistenceConfiguration显式扫描四个数据源包，按需source包不参与扫描。宿主原四个DataSource工厂与环境配置不变；OfflineSessionBindingsPostgresTest直接使用实际Import/Mapper扫描配置，分别核验工厂DataSource身份、XML隔离及四类Mapper的实际SqlSessionTemplate。ItemQuery复用内容模块既有CatalogTable枚举，新路径用source；未迁移作业继续原白名单table入口，后续核销。ClearTable仅固定四表和原顺序，XML无原始表名插值。

## 验证

- 旧SeedAdsJob真实PG保护4项全部通过（`/tmp/recsys-mp13b5-before.log`）：两个独占PG实例加载原sharding.yaml规则，pgvector独立schema模拟主/广告/派生来源，独占Redis；校验物理广告主/广告路由、创意Snowflake及adId路由、向量/模型复制、Redis member/score、clear只清自己的数据、重复重建、replica来源、缺向量/空目录及不clear的重复键失败。
- 迁移后初次专项6项通过（种子5+四源装配1，`/tmp/recsys-mp13b5-after.log`）。随后审查发现全NULL向量投影：MyBatis返回null行对象，原JdbcTemplate返回两元素全NULL数组。新增真实PG用例实际复现NPE（`/tmp/recsys-mp13b5-null-before-fix.log`）；仅在该读取路径保留原跳过行为，不修改全局空行设置。最终全NULL与仅model存在两种缺向量都已保护。
- 最终全部离线91项/20类通过，0失败/错误/跳过（`/tmp/recsys-mp13b5-offline-final.log`）；全仓24 reactor测试编译通过（`/tmp/recsys-mp13b5-compile.log`）。
- 比较旧/新两次clear重建的真实ShardingSphere日志，每次23条INSERT的物理目标及参数完全一致；仅创意生成ID归一化排除，ID实际生成/路由由PG断言覆盖。初始单行日志解析未处理XML多行SQL，已按完整日志记录重解析，不涉及产品修复（`/tmp/recsys-mp13b5-routed-comparison.json`）。
- hygiene无阻塞、无发现（`/tmp/recsys-mp13b5-hygiene.json`）；Java统一formatter/独立静态分析未配置的既有限制保持。45历史SQL与8保护文件摘要保持。

## 后续

MP13b1～b3对应main 37c51498的CI38745667586五门禁全部SUCCESS。MP13b4已本地提交13f6a9ef，随本片正常ff/push。继续SimAdEvents等离线模拟/种子写入与其余作业。复用本片真实分片经验，但其他作业事务/游标/批次必须按实际代码单独验证，不能把本片结果泛化为全部MP13通过。
