# 持久层结构终审（MP15）

生产实现基于 `bc945903`；审查结论为保留现有业务模块，在模块内集中 Mapper/XML 和必要行类型。无需因接入 MyBatis-Plus 再拆服务、改变数据所有权或给每个查询增设 Repository。最终运行验收以 MP15_RESULT 为准，本结构审查不替代测试。

| 检查 | 证据与结论 |
|---|---|
| SQL 归属 | 初始13模块/131候选Java逐项核销；当前591个生产Java、90个Mapper XML检查无业务SQL字面量、SQL注解或直接JDBC查询，无XML `${}`。初始526是关键词命中数，不能当SQL条数。 |
| 业务模块边界 | Mapper和XML留在所属业务模块。`recsys-persistence`只复用类型映射、配置和会话装配，不拥有业务表、业务Mapper、连接参数或连接池。 |
| 简单 CRUD | `content/ItemMapper extends BaseMapper<ItemRow>`，权威目录实际调用selectById/selectCount；副本、复合键、PG特殊查询不强行套用BaseMapper单ID模型。 |
| 多数据源 | `SpringSqlSessions`接收实际DataSource；主、adDb、derived、sharded各自精确XML与Mapper扫描。真实互斥schema、双分片及事务测试在对应RESULT中证明绑定，没有用同URL新建池冒充同一资源。 |
| 宿主装配 | MP13f7移除旧JdbcTemplate Bean及table字符串入口；advertiser派生源保持非Bean，主源仍由Boot创建。未误扫gRPC协议Mapper为数据库Mapper。 |
| 事务与批次 | 原用例事务、资源身份、隔离、超时和行数条件保持。RETURNING写声明齐备。普通VALUES批次、大批次BATCH及R9手动事务分别按真实故障测试保持，不统一改变部分提交边界。 |
| Flink 生命周期 | 一次性ItemCategoryLoader拥有局部非Boot工厂/无池会话；算子不序列化连接。最终shade独立JVM真实PG验证XML、SPI及Spring缺席，见MP14_RESULT。 |
| 表结构和契约 | 初始45历史SQL字节不变。8个bootstrap资源承接原运行时DDL，11张表均有表/字段注释；eval_report的初始化留在ReportPublicationMapper XML，8字段注释齐备并保持分步调用/失败边界（MP13F2_RESULT）。不新增业务表、跨owner写入、MQ/HTTP/gRPC契约或连接参数。 |
| 自动约束 | `scripts/check_persistence_boundaries.py`接入现有CI，6个回归场景验证Java文本块、SQL注解/JDBC、注释排除、R9例外、XML插值与RETURNING片段。它是词法检查，不能代替SQL语义、数据源或事务验证。 |

## 有意保留的技术边界

- `R9EmbeddingStore`内部JdbcTemplate仅管理原Connection回调；Mapper负责SQL。原手动commit/rollback可能影响外层先行工作，已实证并保留，未来传播策略变更须作为单独行为改动验证。
- JDBC类型处理器中的PreparedStatement/ResultSet、事务管理器、驱动配置和bootstrap脚本执行是持久化基础设施；独立测试仍可直接JDBC核验数据库事实。
- `JdbcAdRecallService`原本就是通过AdRepository/Redis编排召回，本次没有SQL实现替换。保留已有类/Bean名避免无效改名和调用方扰动；不要仅凭名称将它判为SQL残留。
- `MybatisContentService`等实际替换JDBC实现的类已更新命名；领域对象、API DTO与持久化行按真实职责区分，没有机械增加三套对象或空接口。

## 后续目标与限制

统一错误码阶段必须处理已记录的无效参数晚失败、全部检查跳过仍记通过、发布部分失败仍记完成、计数失败降为0、内部异常详情透传及Attribution大ID精度问题。SQL等价迁移的保护测试记录的是历史行为，不能被当作这些问题已经解决的证据。

统一Java formatter和独立静态分析器未配置；现有hygiene检查、真实数据库回归和审查证据不等于“所有静态分析通过”。不借本次迁移引入新的质量平台或升级原技术栈。代码回滚兼容既有表结构与协议，但不撤销已经发生的业务写入。
