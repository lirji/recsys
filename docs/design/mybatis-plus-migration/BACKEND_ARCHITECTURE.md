# 持久化迁移目标与过渡架构

状态：已批准。范围与验收见 [PLAN](PLAN.md)。

## 事实、假设与决策

- F01：现有 22 个业务/技术子模块，数据归属由企业可靠性架构及实际 DataSource/Repository 确定。
- F02：JdbcTemplate 分散于 Repository、Service、推荐算法和 Job，包含 PG 特殊 SQL；Flink 有原生 JDBC。
- F03：`recsys-platform/pom.xml` 明确定位 web 技术 starter，领域契约由 common/ad-common/proto 承载。
- A01：所有生产业务 SQL 迁移；已执行 DDL、测试断言和连接初始化不机械转换为 MP CRUD。已随整体计划获用户确认。
- D01：保留领域/服务/数据库 owner 和部署拓扑，调整本模块内持久化职责。
- D02：增加一个轻量 `recsys-persistence` 复用真正跨模块的 PostgreSQL 类型处理；不把数据库依赖放进 common/proto/platform，不建设通用业务仓储框架。
- D03：现有 Repository 保留非 SQL 的业务映射、结果校验和资源身份接口；简单 CRUD 可由应用服务使用专用 Mapper，不为分层添加空接口。
- D04：先试点偏好读写，再分路径覆盖所有模块；最后按证据决定是否还有必须改的结构问题。

## 当前、过渡与目标

当前：Service/算法/Job → JdbcTemplate/Connection → PG 或 ShardingSphere；部分已通过 Repository 隔离。

过渡：每次替换一个访问入口。未迁移路径仍使用原 JDBC，迁移路径使用明确绑定 DataSource 的 Mapper。新旧共享原事务资源，不引入第二写入路径。禁止保留长期双实现后声称完成。

目标：

```text
Controller / gRPC / Kafka / OfflineJob / Flink operator
                ↓
应用服务 / 算法 / 现有业务 Repository
                ↓
本模块 persistence.mapper（带明确参数/结果类型）
                ↓
MyBatis-Plus Mapper + 本模块 mapper XML
                ↓
明确的 SqlSessionFactory / 原 DataSource / 原事务边界
                ↓
PostgreSQL / ShardingSphere-JDBC
```

```text
recsys-persistence/
  src/main/java/com/recsys/persistence/typehandler/
  src/test/java/com/recsys/persistence/...
recsys-<业务>/
  src/main/java/com/recsys/<业务>/persistence/
    mapper/       # 按数据源子包或 marker 精确扫描
    model/        # 仅实际需要的持久化行/查询结果
    config/       # 必要的本模块注册；宿主拥有连接池
  src/main/resources/mapper/<模块>/<数据源>/*.xml
  src/main/resources/db/bootstrap/*.sql  # 只承接现有运行时 DDL
```

领域对象和 API DTO 不加 TableName 等持久层注解。现有有语义的 record 结果可按构造参数显式映射，避免为每条 SQL 机械新建 PO/DTO/VO 三层。名称中的 Jdbc 在实现真正替换后更新为 Mybatis/持久层语义名，同时调整实际引用；ProtoMapper 保持为协议映射类，不纳入数据库 Mapper 扫描。

## 数据源和事务矩阵

| 宿主/路径 | 现有资源 | 迁移约束 |
|---|---|---|
| engine / ad 的普通读取 | `dataSource` / `jdbcTemplate` | 默认源只注册其拥有的 Mapper；引入多个 SqlSessionFactory 后不能依赖 Boot 单源默认行为 |
| 广告目录分片 | `adShardingDataSource` / `adShardingJdbc`；advertiser 主源 | 独立 factory/template 与 Mapper 包；保留路由键、广播规则、ShardingSphere 生成 ID |
| 资金、广告事件、服务目录 | `adDbDataSource` / `adDbJdbc` | 同一个原 DataSource 与本地事务管理器；保留资源身份检查，不新建同 URL 的连接池替代 |
| 派生向量 | `derivedDbDataSource` / `derivedJdbc` | 保留 hnsw 会话初始化和原来源；advertiser 将原内嵌 DataSource 显式建模时同时保留主源创建语义 |
| offline | 默认、分片、adDb、derived 四路 | 作业参数选择只读来源不改变 owner；既有跨 owner 历史访问记录为遗留边界，本次不扩大 |
| behavior / content-service / user-service / console | 宿主现有普通或可选 DataSource | 不让跨模块 Mapper 扫描误强制开启数据库；可选 DB/能力关闭模式保留 |
| Flink | CategoryEnricher.open 中独立连接 | 非 Boot factory；在 operator open 创建、使用、关闭会话，不序列化运行时 session，不开启 Spring 容器 |

Spring 事务管理器与 MyBatis SqlSessionFactory 必须使用同一 DataSource；XML 方法不自行提交。现有由 TransactionTemplate 创建的 owner 事务继续有效，不能统一替换为指向默认库的 `@Transactional`。原对账 readOnly/REQUIRES_NEW/REPEATABLE_READ/5 秒截止保留。[MyBatis-Spring 事务说明](https://mybatis.org/spring/transactions.html)

## SQL、类型与失败语义

- Mapper 参数用命名 `#{}` 绑定；表/列只能从代码封闭枚举选择固定 XML 分支/片段，优先 `<choose>`，不接受任意 SQL 字符串或外部表名。
- item/item_local、user_behavior/behavior_log、CPM/Click/Conversion 表族继续显式建模；`BehaviorQuery`/`ItemQuery` 只解析可信参数，不输出 SQL。
- 禁止二级缓存；默认采用 STATEMENT 级本地缓存以接近 JDBC 每次执行语义，尤其 DB 时间、锁与重复读取；业务缓存仍按原实现。[配置说明](https://mybatis.org/mybatis-3/configuration.html)
- 用正确的 `affectData`/flushCache 配置处理返回行的写操作；查询超时逐语句保持，不能给所有查询无依据加统一截止。[Mapper XML 说明](https://mybatis.org/mybatis-3/sqlmap-xml.html)
- UUID、BigDecimal、UTC Instant/OffsetDateTime、JSONB、PG 数组及 pgvector 显式映射；保留 SQL NULL 与 0/空串的既有区别，不用默认自动映射猜测。
- 数据库生成主键、分片 Snowflake、应用生成 UUID 分别保留；复合键使用自定义方法，禁止添加虚构单列主键。
- 原先返回 null/Optional/空集合、抛 EmptyResult/冲突/超时的边界逐条记录。框架改变内部异常子类时保留对外分类，并基于真实数据库错误原因验证。
- 既有动态 DDL 提取后保留初始化条件、执行次序、重入与失败语义；表/列注释完整。既有资金迁移脚本不可改写。

## 数据/协议/安全边界

权威仍是各 owner 的 PostgreSQL 表。资金账本、资格、预占和 Outbox 仍同 adDb 原子提交；engine 交付状态与 behavior 事实仍各归原 owner。Mapper 不暴露给其他服务绕过 RPC/权限，也不迁移业务规则到 SQL 拦截器。

HTTP/gRPC、Kafka 消息、Redis 键、租户/广告主过滤、业务开关及审计保持现有契约。沿用 `docs/design/enterprise-reliability/CONTRACTS.md` 等正式契约，不创建第二份竞争契约。实现时将技术替换记录回原 TECH_SELECTION/BACKEND_ARCHITECTURE，但不改变原业务事实。

无新缓存/MQ/搜索/配置中心/调度器；无新部署单元。历史跨模块直接读库、启动 DDL 或 SQL 降级策略不自动借本任务全部重写；实证结构问题进入最终报告，影响行为的调整需明确取舍。

## 结构评估结论与终审

需要改：SQL 从 Service/Job/算法下沉；精确的数据源-Mapper 绑定；PG 类型处理复用；消除 SQL 拼接工具；现有 Jdbc 实现命名随迁移更新。

暂不需要改：22 个既有业务模块的领域边界、HTTP/gRPC 入口、前端、服务拆分、全部领域对象形态。终审检查依赖方向、事务资源身份、Mapper 泄漏、空透传层、重复查询、任意 SQL 入口及遗漏路径，依据源码与验证结果追加结论。

容量/SLA/RTO/RPO 沿用现有定义；本次未测性能，不承诺更快。选取现有关键路径比较数据库调用次数、分页/批大小、执行计划和查询结果，必要时再测延迟，避免凭框架切换声明性能收益。
