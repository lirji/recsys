# 持久层与 Mapper 规范

项目使用 MyBatis-Plus 3.5.17 / MyBatis 3.5.19，保持 Java 21、Spring Boot 3.2.5、PostgreSQL 驱动 42.7.3 和 ShardingSphere 5.5.0。在线服务、离线作业及 Flink 的生产业务 SQL 均已迁入 Mapper；复杂查询和写入使用 XML。初始 45 个数据库脚本保持原字节，运行时建表 SQL 已离开 Java，集中于 `db/bootstrap` 或既有作业的具名 Mapper XML，无需把数据库迁移脚本改成 Mapper。

## 代码放在哪里

保持业务模块，不因更换持久框架拆服务或添加机械透传层。

```text
recsys-<业务>/src/main/java/com/recsys/<业务>/
  <既有服务、算法、Job或Repository>  # 编排、计算、缓存和既有事务边界
  persistence/
    mapper/<数据源>/*Mapper.java    # 有业务含义的查询/写入接口
    model/                         # 必需的持久化行、结果和来源枚举
    config/                        # 本模块扫描与宿主会话绑定
recsys-<业务>/src/main/resources/
  mapper/<模块>/<数据源>/*.xml      # SQL、列映射和绑定参数
  db/bootstrap/*.sql               # 原运行时DDL及表/字段注释
recsys-persistence/                # 共享PG类型处理、MyBatis设置和会话装配
```

简单 CRUD 可复用 `BaseMapper`；已有 `ItemMapper.selectById/selectCount` 实际用于权威 item 表。副本来源、复合键、行锁、PG upsert/RETURNING、向量和全文查询使用明确 XML 方法，不为套单 ID 模型修改表结构。API DTO 和领域对象不添加持久框架注解，实际需要时使用 `ItemRow` 等持久化类型。

## 数据源与事务

| 资源 | 会话 | 用途 |
|---|---|---|
| 主 `dataSource` | `sqlSessionFactory/sqlSessionTemplate` | 宿主自己的默认业务源 |
| `adShardingDataSource` | `shardedSqlSessionFactory/shardedSqlSessionTemplate` | ShardingSphere 逻辑广告分片目录 |
| `adDbDataSource` | `adDbSqlSessionFactory/adDbSqlSessionTemplate` | 广告自有数据、账务/事件路径 |
| `derivedDbDataSource` | `derivedSqlSessionFactory/derivedSqlSessionTemplate` | 派生向量等读模型 |

每个宿主只创建它需要的资源。advertiser 主源由 Boot 按 `spring.datasource` 创建；派生源在 `AdvertiserDataSourceConfig` 中保持非 Bean、无池，直接构建派生工厂，避免 Boot 主数据源自动配置退避。表来源由 `CatalogTable/BehaviorSource/AdEventSource` 等封闭枚举决定，XML 用固定分支选择。

MyBatis 工厂与原事务管理器复用**同一个 DataSource 对象**。继续使用已有用例事务、隔离级别、超时与锁顺序。关闭二级缓存，一级缓存为 STATEMENT，避免重复查询、数据库时间或资金锁定被缓存替代。返回行的写操作必须声明 `affectData="true" flushCache="true" useCache="false"`。

普通批次和大 JDBC 批次的部分提交语义不相同。现有 `SpringMapperBatches` 使用原连接资源的独立 BATCH 会话，不自行增加事务。报表发布的eval_report建表/注释/索引保留在ReportPublicationMapper XML的具名方法中，以保持原逐步初始化及失败边界（MP13F2_RESULT）；其他现有bootstrap为独立SQL资源。R9 导入保留其已实证的手动提交/回滚边界，`R9EmbeddingStore` 内部 JdbcTemplate 仅用于连接适配，无业务 SQL；不要将其替换为默认 Spring 事务后假定行为不变。

Flink 的 `ItemCategoryLoader` 在算子 `open()` 内构造无池、非 Spring 的 MP 工厂，自动提交会话用完即关；factory/session/连接不进入算子序列化。最终 shade JAR 的 XML、驱动 SPI 和实际 PG 查询由 Failsafe 独立 JVM 验证。

## 新增或修改查询

1. 先确定表的 owner、目标数据源及事务边界，再给 Mapper 增加具名方法。
2. 值使用 `#{}` 绑定；表、列和排序只能由服务端固定分支决定，不使用 `${}` 拼入原始输入。
3. 保留明确列映射、NULL 含义、时间精度、稳定排序和受影响行数判断。数组/UUID/JSONB 使用已存在的类型处理器。
4. 验证真实 PG 的锁、约束、RETURNING、批次失败和资源释放；分片路径必须经过真实逻辑分片源。纯 Mock 不能证明事务行为。
5. 运行 `python3 scripts/check_persistence_boundaries.py`，阻止 Java SQL/JDBC 回流、原文插值、RETURNING 声明丢失及初始迁移脚本漂移。该检查为词法约束，不替代语义审查和集成测试。

迁移决策与逐片验证见 [迁移计划](design/mybatis-plus-migration/PLAN.md)、[SQL 核销表](design/mybatis-plus-migration/SQL_INVENTORY.md)和[当前进度](design/mybatis-plus-migration/PROGRESS_STATE.md)。旧离线错误提示、降级状态和部分发布日志的问题已单独记录，SQL 迁移完成不表示这些后续治理项完成。
