# ER-S19 验收结果

2026-10-18：本地DONE / PASS。授权范围为原F18，详细协议见[C19_DESIGN](C19_DESIGN.md)，启停/恢复见[C19_RUNBOOK](C19_RUNBOOK.md)。源码与配置已实现，未启用共享namespace、未切换线上读取、未部署。

## 实际结果

| 验收 | 真实证据与结果 |
|---|---|
| C19-01 来源/配置/坏输入 | FeatureWireTest 3项：LIVE稳定身份、LEGACY坐标/长ID、错误key/source/version/未来时间、必填参数失败关闭 |
| C19-02～05 窗口/恢复 | FeatureRecoveryTest 4项：真实Flink+Kafka+Redis+PG，持久checkpoint关闭后新MiniCluster恢复、重复不加计数、类别冻结、Redis领先checkpoint不倒退、迟到排除、完整空窗清理 |
| C19-06 Redis故障 | 实际暂停Redis导致Flink RUNNING→RESTARTING→RUNNING，新的sink token从checkpoint恢复；输出键丢失后心跳重建，绝对过期不延长 |
| C19-07 容量/身份/保留 | max-events=1超限失败，恢复参数漂移拒绝；真实删除独占Kafka已保留区间后恢复明确OffsetOutOfRangeException，不能自动earliest跳洞 |
| C19-03 Redis完整快照 | FeatureRedisTest 6项：旧版拒绝/同版冲突、重复重建、删除消失成员/类目/用户、过期不复活、租约隔离、wrong-type无部分删除、真实暂停异常传播 |
| C19-08 兼容/读取/产物 | 旧解析2、PG类目6、最终shade子JVM真实PG/SPI1；热度/TAG命名空间2、用户画像Redis10、原召回输入PG5。合计39项，零失败/错误/跳过 |

主命令：`mvn -pl recsys-streaming -am verify -Dtest=FeatureWireTest,FeatureRedisTest,FeatureRecoveryTest,CategoryEnricherPostgresTest,LegacyBehaviorCompatibilityTest -Dsurefire.failIfNoSpecifiedTests=false`；读取端命令为 `mvn -pl recsys-recall,recsys-console -am test -Dtest=RealtimeProjectionRedisTest,UserRealtimeRedisTest,RecallInputsPostgresTest -Dsurefire.failIfNoSpecifiedTests=false`。删除未使用Kafka测试依赖后重做窄wire+最终artifact verify通过，测试组件仍用既有GenericContainer。

本机证据 `.dev/er-s19-s21/s19-final-gate.log`、`s19-artifact-final.log`、`s19-readers-final.log`、`S19_TEST_RESULT.json`及源码指纹清单。新增CI独立streaming-reliability-gate；本地成功不代表远程CI已全绿，Git/CI状态见该目录delivery/checkpoint。

## 分离只读复核与限制

复核 checkpoint与外部副作用窗口、源身份/重放范围、旧执行器、绝对过期、整窗删除、配置读取一致性和回退。修正了初始实现的Kafka自动reset风险、长停机后的逐tick补跑和遗漏的TAG读取选择；最终对应恢复/读取验证通过。没有修改资金、行为接受记录或已发布数据库迁移。

Hygiene：IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS，无阻断；仓库无统一Java formatter/static-analysis，沿周边格式、编译、diff检查和人工审查。FeatureWire边界catch保留为脱敏分类，不能携带原payload cause。仅新增已记录的platform codec依赖边（排除传递依赖），Surefire沿父版本只补JDK模块参数；无新增中间件。

单并行度/受限快照不声称生产容量；file checkpoint只证明同盘恢复。未验证跨主机丢盘、Redis Cluster、任意元数据灾难或生产RTO/RPO。旧演示模式仍无此恢复承诺。类别快照刷新、改变窗口口径应以新投影重建后切换，不修改旧checkpoint身份。
