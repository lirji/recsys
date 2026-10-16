# 全项目 MyBatis-Plus 迁移计划

状态：MP00～MP15已实施、Git交付且CI五门通过；追加错误码EC01～EC08实现与本地验收完成，最终Git/CI状态见根交接，汇总见../error-code-governance/EC08_RESULT.md。用户于 2026-10-15 明确回复“确认执行”，并确认 Mapper 接口 + XML 形式。

用户目标：整个项目引入 MyBatis-Plus，修改所有 SQL，并评估、调整必要的代码结构。
本计划整体范围、A01 SQL 分类边界和新增轻量持久化模块均已获确认；连续执行 MP00～MP15，不重复请求计划授权。

## 1. 目标与验收范围

1. 全部 Java 生产业务查询、写入从 JdbcTemplate / 原生 JDBC / Java SQL 拼接迁移到 MyBatis-Plus 管理的 Mapper；普通实体 CRUD 可用 BaseMapper，复杂 SQL 使用 XML。
2. 覆盖在线服务、推荐库、离线作业和非 Spring Boot 的 Flink 作业，不只迁移广告模块或普通 CRUD。
3. 已有 Repository 保持业务入口；Service、算法类和 Job 中的数据库访问下沉到本模块持久层。业务模块、数据 owner、部署单元和对外契约保持。
4. 基于真实 PostgreSQL 验证金额、幂等、锁序、回滚、快照、特殊类型和 SQL 结果；迁移完成后不得仅凭能编译宣称兼容。

**已确认的范围解释 A01：**“所有 SQL”指全量生产业务 SQL 迁移。已有 45 个独立 `.sql` 资源纳入盘点，但不改写已执行迁移；测试建数/断言 SQL、独立初始化脚本、连接初始化 SQL 按职责保留。Java 中的运行时 DDL 提取为版本管理的 SQL 资源，保留原触发条件和完整注释。不得使用万能 `execute(sql)` Mapper 包装原 JDBC 来声称迁移完成。

本任务不恢复已暂停的企业可靠性 S13B～S25；不增加业务功能，不升级整个 Spring/Flink/ShardingSphere 栈，不变更公共 API、事件、数据库结构和数据归属，不执行生产部署或共享库的数据清空/重灌。

## 2. 现状证据

基线 commit：`e7838776a0b09904a0d4702589639534901d27ee`。
任务分支：`refactor/mybatis-plus-migration`；使用原项目唯一 worktree。

- 根 POM：Java 21、Spring Boot 3.2.5，22 个子模块；未引入 MyBatis/MyBatis-Plus。
- 自动初筛命中 13 个模块的 131 个生产 Java 文件，526 处 SELECT/INSERT/UPDATE/DELETE/TRUNCATE/CREATE TABLE 模式命中；包括配置、注释及嵌套语句，**不是独立 SQL 条数**。详见 [SQL_INVENTORY](SQL_INVENTORY.md)，实施时逐方法核销，不能把关键词数字作为迁移完成率。
- 45 个 `.sql` 文件：offline 23、ad 16、engine 5、behavior 1。
- `AdShardingConfig`/`OfflineAdShardingConfig` 同时有默认、广告分片、adDb、derived 数据源；advertiser 的 derived 数据源目前藏在 JdbcTemplate Bean 内。
- `BudgetRepository` 等有手写 SQL、受影响行数判断和迁移摘要校验；`ReconciliationConfiguration` 强制新建只读 REPEATABLE_READ 事务与 5 秒截止。
- `AdvertiserRepository` 的广告 ID 由 ShardingSphere 生成；不能改为 MP 默认的 ASSIGN_ID。
- `AbstractItemCatalogReader` 使用 PG 全文检索、数组和 item/item_local 两种来源；offline 还包含动态来源、as-of 时间边界及批量处理。
- `RealtimeFeatureJob.CategoryEnricher.open` 使用原生 JDBC；该模块是 Flink shade jar，不是 Boot 服务。

本次实跑：`mvn -o -B -DskipTests compile` 成功，23 个 reactor 项目 SUCCESS，9.567 秒。属于**增量编译**，不是 clean build，也没有运行测试；日志 `/tmp/recsys-mybatis-baseline-compile.log`。历史资金验证证据沿用原 ER-S13A_RESULT，但实施前仍需实跑当前测试基线。

## 3. 方案决策

| 方案 | 优点 | 成本/问题 | 决策 |
|---|---|---|---|
| 只加 Starter，保留 JDBC | 修改小 | 不满足全量 SQL 迁移 | 不选 |
| 把全部 SQL 改成 Wrapper / ServiceImpl | 普通 CRUD 代码短 | 难以保真表达 CTE、RETURNING、行锁、全文和向量操作，复合业务键也不适配单 ID CRUD | 不选 |
| MP + 类型明确的 Mapper/XML，渐进替换 JDBC | 可保持复杂 PG 语义，统一持久层职责 | 需要逐语句映射、数据源装配及真实数据库回归 | 选择 |

详细结构与数据源设计见 [BACKEND_ARCHITECTURE](BACKEND_ARCHITECTURE.md)；新增依赖与兼容验证见 [TECH_SELECTION](TECH_SELECTION.md)。

## 4. 执行顺序与文件级工作包

下表是全范围有序工作包；每包下的字母子项为独立实施/验证/提交单元，不一次改完整个工作包再测试。共用配置按依赖顺序串行更新。每次只迁移一条业务路径及其调用方/fixture；保持中间状态可编译、可验证。

| 包 | 子项与具体路径（Java 路径均在本模块 src/main/java/com/recsys 下） | 依赖 | 验收 |
|---|---|---|---|
| MP00 | 保存清单/资源摘要；运行现有全仓及资金、自然链路基线；逐包补缺失的真实 PG 特征测试 | 计划批准 | 区分历史失败和新失败；核心业务修改前对应保护测试实际通过 |
| MP01 | a：根 pom、`recsys-persistence/pom.xml`、共享 PG TypeHandler；b：`recsys-user/.../UserProfileService`、本模块 persistence Mapper/XML/装配及真实 PG 测试 | MP00 | 首条偏好读写路径通过；JSONB 只合并 categories，保留其他键；空值/重复更新一致 |
| MP02 | a：`recsys-content/.../{JdbcContentService,ReplicaJdbcContentService}`；b：`{AbstractItemCatalogReader,DbItemCatalogReader,ReplicaItemCatalogReader}`；相关 host 装配 | MP01 | item/item_local 两模式、全文排名、标签数组、空集合、读取缺失值与旧实现一致 |
| MP03 | a：`recsys-query/.../IdfWeighter`；b：`recsys-recall/.../{BehaviorSequenceSource,channel/RecentPositiveItemsSource,channel/DbUserProfileReader,channel/I2iRecaller,channel/SwingRecaller}`；c：其余向量/图/语义/多兴趣/Tiger 召回；d：`recsys-rank/.../{DinRankService,DienRankService}` | MP02 | as-of/顺序/limit/向量距离/缺失降级不变；SQL 调用次数不增加，在线离线编码契约通过 |
| MP04 | a：`recsys-ad/.../{AdShardingConfig,AdRepository,ShardedAdCatalogReader}`；b：`recsys-advertiser/.../{AdvertiserDataSourceConfig,AdvertiserRepository,DbAdReportReader}`；c：`recsys-ad-serving/.../{catalog/AdServableRepository,report/AdReportStatsRepository}` | MP01 | 多库使用不同数据标记核验路由；分片主键回填、部分更新、目录版本及删除语义保持 |
| MP05 | a：ad 的 `AdEventLogger/AdOutcomeService/UpliftAssignmentService`；b：`AdEmbeddingSimilarity/CreativeSelector/GuaranteedDeliveryService/PacingService` | MP04 | 事件幂等、uplift 分配、创意/预算读取及向量评分保持；运行时 DDL 资源提取验收 |
| MP06 | a：`recsys-behavior/.../BehaviorService`；b：`exposure/{NaturalReceiptRepository,NaturalReceiptConfiguration}`；c：`recsys-console/.../{user/UserProfileService,report/ReportService}` | MP01 | 真实 PG 回执/事实原子性、重复事件、查询过滤；console 缺少可选数据库时原有响应保持 |
| MP07 | a：`recsys-rec-engine/.../{filter/DbSeenItemsSource,coldstart/ColdStartDetector,experiment/ExposureLogger,PersonalizationScorer}`；b：`rerank/{MmrReranker,DppReranker}` | MP02、MP03 | 去重、冷启动、曝光幂等和重排结果保持；算法层无执行 SQL |
| MP08 | a：ad billing 的 `BudgetRepository/BudgetConfiguration`；b：`CpmQuoteRepository/CpmQuoteConfiguration/ReservationRepository/ReservationConfiguration`；c：`PreparationRepository/PreparationConfiguration/ImpressionReceiptRepository/ImpressionReceiptConfiguration` | MP04、各子项资金保护测试 | 行锁/唯一键/金额/版本/影响行数/数据库时间一致；预占-捕获-释放及全事务回滚通过 |
| MP09 | a：`ClickQuoteRepository/ClickQuoteConfiguration`；b：`ClickSettlementRepository/ClickSettlementConfiguration`；c：点击准备配置及消费者 | MP08 | 五分钟期限、先点击后曝光、重复资格、迟到非计费与原有金额完全一致 |
| MP10 | a：`ConversionQuoteRepository/ConversionQuoteConfiguration`；b：`ConversionSettlementRepository/ConversionSettlementConfiguration`；c：转化准备配置及消费者 | MP09 | CPA 事件自身费用、幂等负载、资格一次收费及跨日语义保持 |
| MP11 | a：`BillingOutboxRepository` 与 CPM/Click/Conversion EventConfiguration；b：三族 ProjectionRepository 及装配；c：`ReconciliationRepository/ReconciliationConfiguration` | MP08～MP10 | RETURNING 的写入正确提交；租约/fence/重放；PG+Kafka 故障恢复；对账只读/RR/超时/资源身份验证 |
| MP12 | a：ad billing `FeedCandidateRepository/FeedCandidateConfiguration`；b：engine `delivery/{DeliveryOperationRepository,*Configuration}`；c：engine `exposure/{NaturalDeliveryRepository,NaturalDeliveryConfiguration}`；d：`feed/{FeedDeliveryRepository,FeedDeliveryConfiguration}` | MP06、MP08～MP11 | 原交付 ID 恢复、频控、最终位置/资格冻结、三种收费及自然/混排链路与 HTTP/gRPC 行为保持 |
| MP13 | a：`recsys-offline/.../OfflineAdShardingConfig` 与 `ItemQuery/BehaviorQuery`（改为明确参数，不输出 SQL）；b：导入/种子/目录和行为同步；c：embedding/semantic/R9/tower 导入回填；d：CF/Swing/Hot/IDF/特征；e：样本/训练/评估/校准；f：报表/归因/uplift/数据质量。每子项继续按单作业或共用查询路径实施 | MP02～MP05 | 全部 44 个初筛文件及 ItemQuery 被核销；真实隔离 PG 上验证批次/冲突/游标/时间边界/幂等，禁止共享库重灌 |
| MP14 | `recsys-streaming/.../RealtimeFeatureJob`、新增 `persistence/ItemCategoryMapper`、XML、非 Boot SqlSessionFactory 生命周期及测试 | MP01、MP02 | 类目富化一致，session/connection 正确关闭，Flink shade 包包含且能加载 XML，不引入 Boot 启动上下文 |
| MP15 | 残留扫描、全仓 clean verify、原专项门禁/浏览器验收、结构终审、文档同步及 Git/CI 交付 | 全部 | 满足第 7 节；未验证项不得标 PASS |

全量源文件清单以 [SQL_INVENTORY](SQL_INVENTORY.md) 为准；配置文件、受构造器变化影响的测试和调用方通过引用检索补齐。每个来源文件对应本业务的 `persistence/mapper/*Mapper.java` 和 `src/main/resources/mapper/<模块>/<数据源>/*Mapper.xml`；类型映射按实际需要增加 `persistence/model/*Row.java`。无逻辑的新增 Repository 包装层不强制创建。

## 5. 各批固定执行流程

1. 重读该包源文件/正式契约，列出每个 SQL 方法、参数、结果、数据源、事务、失败行为与可执行测试。
2. 先在旧实现上跑保护测试；补充缺口，尤其真实 PG 而非只 Mock JdbcTemplate。
3. 迁移 Mapper/XML 和最小结构，更新调用方及装配；DDL 只提取资源，历史迁移字节不变。
4. 编译、单元测试、该路径 PG/必要 Kafka/分片集成、装配测试与差异审查。失败先修当前批，不能继续扩散。
5. 更新本目录 PROGRESS_STATE、SQL 核销表和验证证据，按完整逻辑单元提交；正常合并/push main 依照用户既有持续授权和 task-git-delivery 执行，遵守分支保护。目标分支新增提交时重核依赖与测试，不强推。

## 6. 测试策略与关键风险

- 原始 SQL 结果对照：相同隔离数据分别验证旧/新路径的业务结果；浮点近似沿原测试精度，金额精确比较；不放宽原有断言。
- 多数据源：不同实例/schema 放入互斥标记；Mapper 与事务管理器必须指向**同一 DataSource 对象**，错误绑定应失败；每个宿主含开/关配置都测试。
- 资金：行锁竞争、双条件满足、同键不同负载、回滚注入、DB 时间、跨日、已捕获/到期、0 元、影响行数、错误 SQLSTATE 等保留。
- MyBatis 语义：关键 SELECT 每次到库（STATEMENT 级本地缓存，禁二级缓存）；记录到构造参数映射、SQL NULL、UUID、JSONB/text[]/float8[]/vector、时区/微秒精度、超时异常转换都需验证。
- PG DML RETURNING 使用正确事务/缓存声明；不能把 `UPDATE ... RETURNING` 当无副作用 SELECT。
- 分片：真实两分片隔离环境验证路由/广播/跨片限制和现有 Snowflake 主键回填；只直连单 PG 不算验证 ShardingSphere。
- 离线：批次有界、批处理 flush 与 rollback、游标在事务/会话关闭前消费；核验 as-of 时间边界，不能把流式扫描改为一次全量 List。
- 兼容：原 HTTP/gRPC 请求响应/错误码、Kafka payload/key、Redis key、默认关闭开关保持；原生 test SQL 用于独立核验，允许保留。

执行现有门禁：`mvn -B clean verify`；资金 tag `money-chain`；自然 tag `natural-exposure`；`scripts/verify_contracts.py`；三套 `scripts/test-{sandbox,natural,feed}-exposure.py`。脚本各自参数以当前仓库为准。新增检查只覆盖真实迁移回归：禁止业务层 JDBC/SQL 回流、扫描/路由绑定、迁移脚本摘要、XML 打包与可加载。

## 7. 完成标准

- 所有初筛文件和追加发现路径均有明确核销记录；所有业务 SQL 经 Mapper 执行，Java 业务层不再嵌 SQL，也无 `@Select/@Update` 大段 SQL 或万能动态 SQL 入口。
- 标准 CRUD 实际使用 BaseMapper 的适用能力；复合键、锁定和复杂 PG 操作使用明确 XML 方法，不强制套单 ID 模型。
- JDBC API 仅出现在明确允许的类型处理/连接事务/DDL 脚本执行和独立测试代码；每个例外有路径与原因。
- 全仓 clean verify、必须的真实 PG/Kafka/分片/浏览器检查通过；Docker 不可用造成 skip 必须记录，不能算通过。
- 历史 `.sql` 摘要未漂移，无 API/DB/MQ/业务契约变化，无新跨 owner 写库，无共享库破坏性测试。
- 项目结构终审有具体证据；文档同步、Code Hygiene 及 CI 实际状态记录完成；只有通过验证的本任务改动进入正常 Git 交付。

## 8. 回滚与保护

每个子项独立可审查提交；优先修复当前批，必要时正常 revert 本任务提交，不 reset/强推。保持数据库和协议兼容，旧二进制仍可读写；不靠回退代码撤销已经产生的业务数据。MP/JDBC 允许在过渡批次共存，但同一业务写入口只选择一种实现，不双写。

保留原 8 个已有文件的内容与归属（含 CODEX_PROGRESS、用户运行配置/文档和 C15A 草案），保留 console/test-results 与忽略文件，不 stash/clean。指纹见 [PROGRESS_STATE](PROGRESS_STATE.md)。本目录作为新任务恢复入口，原企业可靠性进度继续暂停。

## 9. 批准点

用户已确认本计划整体范围、A01 的 SQL 分类边界、新增轻量 `recsys-persistence` 模块及 Mapper + XML 形式。连续执行 MP00～MP15，不逐包询问“是否继续”。

该确认点来自用户提供的 AGENTS.md 执行方式第 3 条：“计划确认后，连续执行所有步骤”。不是额外增加的 Git/部署授权请求。依赖的具体补丁版本在 MP01 的兼容门禁通过后冻结；若唯一可用方案必须升级 Spring/Flink 或改变业务契约，单独说明证据和取舍。

## 10. SQL 完成后的追加目标（2026-10-15 用户授权）

MP15 全量 SQL 迁移及验收完成后，继续提取错误提示并形成统一错误码展示，减少魔法值。先盘点现有 HTTP/gRPC 错误契约和前端展示路径，再在保留兼容性的前提下集中定义稳定业务错误码、默认提示及必要语义常量；避免把所有数值机械常量化。此阶段不提前混入 SQL 等价迁移；错误详情不能暴露 SQL、内部堆栈或凭据。正式文件级计划在 MP15 后基于届时代码落盘并连续实施，最终验证错误映射和界面实际展示。
