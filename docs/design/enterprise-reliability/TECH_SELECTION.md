# 技术与方案取舍记录

状态：实施已获授权，具体切片决定以下方补充及正式 CONTRACTS 为准。沿用项目现有 PostgreSQL、Redis、Kafka、Java/Spring、React/Ant Design/TanStack Query/Axios、Vitest/Playwright。公共组件优先复用 dev_infra 的隔离资源，不修改共享实例数据或顺手升级依赖。

| 决策 | 备选 | 草案选择与原因 | 成本/边界 |
|---|---|---|---|
| 曝光口径 | 服务端响应即曝光；客户端可见回执；第三方验证 | **用户已选可见回执 + 服务端资格/签名/幂等** | 终端测量可被伪造；第三方反作弊、终端可信度仍是独立建设项 |
| 预算约束 | Redis 节奏/允许超投；持久化硬预占 | **用户已选硬上限预占**；建议 PG 条件更新/行锁 + 业务唯一约束 | 较高争用和未决预算占用；CPA 必须评估长窗口可用额度 |
| 金额表达 | double；整分；精确小数/更小定点 | 推荐 BigDecimal + PG NUMERIC + JSON 字符串，精度待冻结 | proto 可新增字符串/结构化 Money，不能静默改变旧 double 单位 |
| 可靠事件 | 同步双写；应用 Outbox；CDC | 推荐同库事务 Outbox，复用 Kafka；现状与业务键最易直接关联 | Relay/清理/重放需运维；分片 Outbox 不能写在另一个公共库。CDC 作为吞吐实测后的替代，不同时新增 |
| 消费效果 | 仅靠 offset；Inbox + 版本化投影 | 推荐 Inbox/业务唯一键 + 聚合版本 + 删除墓碑 | 至少一次传输，外部副作用需自己的幂等；Redis 另走可靠派生刷新 |
| 服务边界 | 新建账务微服务；既有 ad owner 内模块 | 推荐现有 `recsys-ad` 增加账务用例，由同一 ad owner 执行 | 先修逻辑和事务边界；以后是否拆服务由容量与团队 ownership 决定 |
| 前端采集 | ItemCard 内自动上报；页面显式 scope + hook/队列；新 SDK | 推荐页面显式采集，组件保持展示职责 | 四页逐步接入；避免沙盘、历史和对比产生收费 |
| 回执暂存 | 仅内存；有界会话存储；IndexedDB 离线平台 | 推荐内存 + 有界 session 恢复作为首版候选 | 不保证浏览器清存储/关闭后的补发；不存 Bearer；若业务要求重启后恢复再选 IndexedDB |
| schema 演进 | 修改初始化 SQL；版本化迁移工具/脚本 | 必须新增版本化迁移；具体工具在 D0 核对现有依赖和发布方式后冻结 | 不修改已执行迁移历史；表与每个字段有中文注释；先扩展再切换 |
| 搜索技术 | 立即引入 ES；先补 query 相关性评测 | 本轮不新增 ES | 以中文/长尾检索实测决定，不让搜索改造分散资金与事件闭环 |

PG 行锁可以串行化相关账户/预占更新，但要求正确锁序和事务边界，不能代替业务约束（[PostgreSQL 16 Explicit Locking](https://www.postgresql.org/docs/16/explicit-locking.html)）。Kafka 的传输事务不自动覆盖外部数据库（[Kafka 3.7 design](https://kafka.apache.org/37/design/design/)）。浏览器 Intersection Observer 提供元素与视口交叉观测，不能证明注意力或真人身份（[W3C Intersection Observer](https://www.w3.org/TR/intersection-observer/)）。

没有新增版本选型或依赖安装；正式实施如需新增迁移/测试依赖，先核对当前官方兼容性、维护状态、许可证和已知漏洞，并复用仓库版本管理。当前 SLO、RTO/RPO、吞吐预算不在本文件编造数值。

ER-S02 冻结：复用现有 Spring JDBC 持久化风格（SQL 集中 Repository），专属 TransactionTemplate 绑定同一 adDb DataSource，不引入第二套 Mapper 框架。迁移采用独立 owner 的事务化版本 SQL 与 schema history，显式执行并记录 checksum，启动仅校验；避免新增 Flyway 后误触默认主数据源自动迁移。测试复用项目已选 Testcontainers PostgreSQL 依赖/BOM 和 `postgres:16-alpine`，新关键测试不允许无 Docker 静默跳过。

机制核对：PostgreSQL 唯一键与 ON CONFLICT 处理并发领取（[PG 16 INSERT](https://www.postgresql.org/docs/16/sql-insert.html)）；Spring JDBC 事务必须使用绑定的数据源连接（[Spring JDBC connections](https://docs.spring.io/spring-framework/reference/data-access/jdbc/connections.html)）。这些机制仍需本片真实数据库故障测试证明。

ER-S03：到期预占本身就是持久化待办，采用 Spring 单线程定时唤醒 + 每笔 PG `FOR UPDATE SKIP LOCKED` 短事务，不新增 MQ/分布式调度框架或独立租约。多实例靠同库行锁和状态条件竞争，一笔失败回滚后可续办。扫描轮次、超时、错误退避有界；隔离调度线程避免阻塞其他任务。新增预占 admission 开关可停止新占用，同时保留查询、释放和到期 worker；不能通过关掉整个 sandbox 丢弃在途回收职责。

时间边界核对：扫描使用 statement_timestamp 固定本次 SQL 的截止时间，获取账户锁后用 clock_timestamp 判断是否已到期，避免事务开始时间在锁等待后陈旧（[PG 16 当前日期/时间](https://www.postgresql.org/docs/16/functions-datetime.html#FUNCTIONS-DATETIME-CURRENT)）。应用服务器默认时区不参与预算日判定。

ER-S04：整次交付选用 engine primary PG 的不可变计划 + 有效租约/fence 条件更新，单条 SQL 原子保存检查点，不新增队列/执行器。操作开始先固定请求/分桶/底价，准备在事务外，拒绝在外层数据库事务中执行整个编排。ad owner 用独立表保存准备输入和原结果；报价、预占、资格为可恢复多步提交，不假装一个跨库事务。故障可产生暂时未使用的 held，由既有到期 worker 释放。

候选证明与资格采用现有 JDK HmacSHA256 + Jackson，无新增依赖。版本、用途和 keyId 一起签名，使用独立受控密钥环，防止把候选证明当回执资格。相较让 engine 传价格直接预占，这使价格依据只由 ad owner 生成；相较候选阶段创建数据库报价，则保留候选查询无持久化副作用。代价是 key 轮换须保留所有未完成操作需要的验证 key，候选有独立五分钟受理期限。资格最终期限由真实 PG 报价签发时间决定。

S04 搜索入口最多 10 位，最终去重/截断后持久化计划；feed 接入及频控仍属于 S10。已有 recall/rank/audience 的内部降级保留，不能因此宣称终端可信、反作弊或定向强一致已经完成。新代码不沿用旧响应即扣费/空响应 fallback。RPC 与同进程共用域 DTO/owner；grpc engine 不装配本地新账务和回收器，但历史 ad 模块已有连接池不在本片重构。

ER-S05：回执接受采用同 adDb `TransactionTemplate`，依次领取回执键、锁预占和原预算日账户；不持锁调用 RPC/MQ。捕获、唯一 ledger、Outbox 和首次回执结果一起提交，账本用唯一 qualification_id 与禁止改删触发器保护。事件使用原 ledgerId 去重和关联，不能用账户最大版本过滤金额事实。Outbox 本片只落持久化待发记录，S06 才实现 broker 确认、重试/隔离与投影。

回执能力和暂停新接受分开：`receipts-enabled` 缺省 false，部署扩展 schema/新 owner 后才能启用；`receipt-admission-enabled=false` 暂停新命令但保留查询及同 ID 原结果恢复。资格签发时间与服务端接受时间约束声明时长，不使用客户端 wall-clock 作为到期权威；可见观测仍不是真人或终端完整性证明。gateway 两种 route profile 增加 sandbox namespace 并限定 ADMIN，不变更基础设施和用户已有运行文件。

ER-S06：复用宿主已有 Spring Kafka 3.1.4 / Kafka client 3.6.2（Spring Boot 3.2.5 BOM）；ad 库显式声明 spring-kafka 以使用同一连接/安全配置绑定，未升级版本。发送/消费使用专用客户端，不改变旧日志或目录消费者的确认策略。继承受控 spring.kafka 连接/TLS/SASL 属性，覆盖 acks、幂等、重试/截止、手动位点和有界 poll 参数。消费与 PG 无 XA，采用“同库 Inbox+投影提交 → offset 提交”，不把 Kafka 幂等 producer 等同端到端 exactly-once。未知版本停止消费、保留位点，暂不引入第二个隔离队列。

官方机制核对：[Kafka 3.7 producer 配置](https://kafka.apache.org/37/configuration/producer-configs/) 要求幂等与 acks/retries/in-flight 相容；[consumer 配置](https://kafka.apache.org/37/configuration/consumer-configs/) 区分自动提交、poll 与组存活。版本共存实际由 Kafka 3.6.2 客户端连接 Apache 3.7.1 临时 broker 测试证明，不声称覆盖所有 broker 版本。Spring Kafka/Kafka 均沿用已在项目中的 Apache-2.0 依赖；不借本片升级全仓库，现有 CI 依赖扫描仍是报告模式，不声明消除了历史依赖漏洞。

公共组件事实：dev-infra/compose.yaml 的 recsys-kafka 使用 apache/kafka:3.7.1，宿主端口默认 59092；测试复用相同镜像版本，以已有 Testcontainers GenericContainer 启动隔离 KRaft broker，临时唯一 topic/group 和 PG schema，无新测试依赖或 Compose 服务。单节点仅证明本片协议恢复，不证明副本容灾/RPO。topic 先显式创建、不 compact；保留期限待业务决定，sandbox 暂不自动过期并监控空间。


ER-S11C2测试装配补充：为验证同一真实资金owner在in-process和TCP gRPC下的行为，ad-serving新增**仅test scope**的现有engine `test-support`普通类包依赖（版本`${project.version}`）、仓库已选Testcontainers `junit-jupiter`/`postgresql`（沿现有Boot BOM，不指定新版本）。这是C02已批准真实PostgreSQL验收技术的跨模块复用，不增加生产依赖边、第三方组件或版本，也不改变应用可执行包。engine测试支持包生成方式沿已交付S09修复；当前原依赖扫描仍为报告模式，不宣称清除历史漏洞。Code Hygiene ChangeSet `approvedDependencies=[recsys-rec-engine,junit-jupiter,postgresql]`只覆盖这三条测试边；不授予新增运行基础设施或格式化工具。


ER-S12C2测试装配：ad-serving增加现有advertiser的`test-support`普通类包（`${project.version}`，仅test scope）；沿engine既有process-classes打包到target/test-support的方案，验证真实SandboxConversionController、AdvertiserAuthz与GrpcSandboxConversionClient跨TCP调用资金owner。没有新增第三方组件/版本或生产依赖边；权限SDK仍为项目既有版本，调用其已有fullyConsistent能力，测试替身不证明真实权限关系数据。Code Hygiene本批`approvedDependencies=[recsys-advertiser,maven-jar-plugin]`仅覆盖该测试边与同项目既有打包插件的显式执行，原安全扫描报告模式的限制保持。


ER-S13A：沿ad owner的现有PostgreSQL/JDBC，对单账户原预算日用REQUIRES_NEW+REPEATABLE_READ+readOnly+5秒截止实现一致核查。逐条READ_COMMITTED可能跨捕获提交读取不一致；锁账户会增加资金写等待，均不选。新增V016账户日索引，不改历史迁移。没有新第三方组件/依赖/版本/调度器；offline可信调用的依赖仅在后续C契约批准后引入。数据源/只读/隔离语义通过真实PG并发验证，不编造容量或RTO/RPO数字。

ER-S13B：沿用adDb的MyBatis-Plus Mapper/XML与现有Jackson。V017追加任务、不可变日结果和审计；同广告主部分唯一索引代替全局分布式锁，领取/回写短事务与RR核查分离。30秒lease和20次有界尝试仅为sandbox技术运行边界；不引入调度框架或自动清理。

ER-S13C：恢复按单目标同步短事务和持久命令结果执行，复用原owner到期规则与Outbox relay，避免新增恢复调度器。V018只保存命令/审计；PUBLISHED最小等待60秒是sandbox防重发风暴的技术边界。C2在offline增加已有recsys-proto与项目已用gRPC client starter依赖边，版本由根BOM统一，复用内部身份/deadline；不新增技术栈或跨库写入。

C13C2复用现有net.devh GrpcClientAutoConfiguration做offline限定启动上下文，仅在唯一作业billing-reconciliation时由原main显式选择；无新服务/版本/基础设施。普通作业仍走原Spring Boot入口。新错误只编辑contracts/errors.json并生成Java/TS，金额和空值由reconciliation.v1 typed消息承载。

ER-S14：复用advertiser现有gRPC client、owner C13只读快照及严格view/fullyConsistent；显式字符串HTTP DTO避免Jackson时间/金额策略和JS精度影响。前端沿AntD/React Query/既有纯身份scope+epoch，追加响应后校验，不引入新事件队列/依赖。独立默认false构建flag通过现有Dockerfile ARG/ENV传递，不改受保护Compose、不部署。两种engine环境中只读链均为advertiser→owner TCP，浏览器测试不冒充engine写路径覆盖。
