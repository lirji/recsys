# MP13b2：行为读仓同步

DONE/PASS。9源文件指纹`39784ef7f2698bd34de679e57dbc36bf66813b3c8eff8ea5c4f7253429e377f4`，清单`/tmp/recsys-mp13b2-source-paths.json`。

主源BehaviorLogMapper负责原同库INSERT SELECT、MAX(id)水位、full清空、冲突忽略和计数；源库BehaviorCopySourceMapper只在按需源会话加载，明确10列映射。BehaviorLogRow保留所有可空字段和Timestamp微秒值。原1000条批界保持，读取仍沿用原List物化；不借框架迁移改变既有MAX(id)对晚提交小ID的处理，也不宣称脚手架具有CDC完整性。

OfflineSourceSessions集中原PG环境参数和无池DriverManagerDataSource的会话装配；不改变来源库参数、不新增连接池，固定XML路径由作业提供，非任意SQL执行接口。bootstrap资源保留原建表/表注释/旧exposure_id列升级/索引顺序，补齐10列中文注释；不修改已执行的09_behavior_log.sql历史。DDL与Mapper使用实际SqlSessionFactory同一个原主DataSource，ResourceDatabasePopulator参与原外层事务。

## 验证

旧实现先只开放包内源工厂替换点（业务逻辑不变），用独占PG两个schema、两个独立DataSource保护同库/跨库路径；5项旧PG全部通过（`/tmp/recsys-mp13b2-before-final.log`）。覆盖幂等/MAX水位/源更新和迟到小ID不自动复制/full重灌、1002行跨库NULL/微秒、源连接关闭、第二批约束失败仅首1000行持久化、full外层事务回滚，以及旧exposure_id缺列升级和两个索引。

迁移后同样5项及全部离线77项/17类通过，0失败/错误/跳过（`/tmp/recsys-mp13b2-after.log`），额外确认源Mapper未被主源加载、10列注释齐全。全仓24 reactor测试编译通过（`/tmp/recsys-mp13b2-compile.log`）；hygiene无阻塞，统一Java formatter/独立静态分析未配置的既有限制保持（`/tmp/recsys-mp13b2-hygiene.json`）。普通独占PG双schema验证数据源隔离，不冒充跨集群故障或真实ShardingSphere验收。

45历史SQL/8保护文件摘要不变。MP13b1=6b96d0d3本地已提交；当前ae9ec1c4的CI38744006253仍需后续核验。下一片SyncItemCatalogJob保留原全量upsert/NULL标签差异/生成列及数组映射。
