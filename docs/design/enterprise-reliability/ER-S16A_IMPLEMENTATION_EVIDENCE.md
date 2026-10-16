# ER-S16A 实现证据

当前S16A必要本地实现/验收与复审已DONE/PASS；最终TEST_RESULT见[ER-S16A_RESULT](ER-S16A_RESULT.md)。唯一状态见 [IMPLEMENTATION_SLICES](IMPLEMENTATION_SLICES.md)，恢复先读 `.dev/codex-flow-reliability/round-43880a89/checkpoint.json`。原目录、分支 `feat/er-s16-catalog-outbox`、独立设计提交 `5c0f29012dbfa250aaa4f6614571b05b1c59910b` 保留。实现提交、正常ff/push和新CI事实见本轮`delivery.json`，不由本地结果推断。

## 产品结果与范围

- 四个实际Controller通过可选 `CatalogWriteRouter` 接通原广告属性、初始词、关键词/创意CRUD、审核/送审、单广告status、删除及开户/name/budget。原DTO/校验、method role、edit/manage/platform review/administrate政策保留。广告主status变化明确CATALOG_FANOUT_NOT_AVAILABLE，等待S16B；不落回旧reindex。
- 先持久占有原advertiser，再依次推进PREPARE/CHILDREN/ENRICH/FINAL；开户固定home同事务冻结ROOT_CREATE及Snowflake身份，再走原actor的AUTHZ TOUCH。完整owner/child片段、版本、响应及事件按原请求冻结。删除先在owner同事务提交高版本墓碑，再按原命令清理另一物理库children；失败保留cleanup与占有。
- 每个本地步骤明确物理shard，业务、APPLIED receipt、不可变本库Outbox同实际完整PG txid，guard/deferred约束拒绝错库、旧事实、缺事实、错误fence及旧writer。Mapper/XML采用原生UUID/JSONB类型处理器；本片未发布V001中的路由native函数用于实际ShardingSphere5.5.0不支持的SQL，不改原分片规则或旧迁移。
- `CatalogInitializationService`固定home排他、两库QUIESCED、100行分页冻结存量、逐ad完整v1基线及BASE_SEND真实Kafka确认，全部读回后READY。非法/超限数据阻断，不截断/空快照/伪删除。`CatalogSchemaInstaller`仅显式物理安装，不是启动迁移。
- 新版seed在首次任何adDb/目录/Redis效果前，保持两真实物理只读会话完成探测：9张治理表明确全无或完整合法未登记LEGACY可通过；部分安装、control缺失/多行/身份错配、半登记、非LEGACY、权限/连接未知及同库地址别名拒绝。父23:07UTC确认原“最新用户覆盖例外”判断无依据，现已撤回并恢复正式C16边界。18项真实seed测试通过，原清理/向量/Redis断言保留。
- 原事件字节通过 `CatalogKafkaPublisher`/relay发往独立ad-catalog-v1。原集群/topicId每次外发前有界核验；错配持久STOPPED。ACK后标记前退出允许原字节重复；最后一次领取退出仍耗预算。实际发送每轮5次、命令/步骤每轮3次、30秒lease、本地短事务5秒/锁4秒，关闭不清计次。
- 内部 `CatalogRecoveryService`/`CatalogShardRecheckService`有稳定审计ID/目标/摘要/轮次/fence。原REPLAY_ACK先于再次发送；已在事件库提交的成功可完成未知home，即使broker此刻不可达。PAUSED事件审计确认同库保存ACK和PUBLISHED，不清历史预算；已PUBLISHED原坐标不被重放覆盖。全shard逐页重验只清原revision的STOPPED，不解其他PAUSED占有。
- 五个开关默认false；显式可靠profile保留旧物理拓扑/连接模板，仅新增治理路由与writer-id并关闭SQL参数日志。恢复专用装配允许未登记源启动内部初始化。worker两个串行槽、每轮10候选；派生读取单槽零排队1秒，UNKNOWN不冒充删除。查询按当前权限返回有界安全摘要，指标仅有限shard/kind/state标签。

产品入口/配置/处置限制见 [CATALOG_EVENTS_RUNBOOK](CATALOG_EVENTS_RUNBOOK.md)。S17版本化Inbox/高水位/墓碑/Redis刷新未实施；Kafka成功不等于线上索引已可靠更新。

## 验证版本与失败留存

前轮基础版本为 `round-25e0dd1e/catalog-budget-retry-3.log` 的27项PASS（物理26+profile1），另33项源码未变证据曾复用；更早57项完整运行另有原XML。不是一次60项运行。当前Controller/编排等源码已变，受影响项必须使用本轮最终回归，不能继承“全产品不变”的旧表述。

本轮各次独立运行、失败根因及精确运行状态保存在 `round-43880a89/checkpoint.json`；原日志和XML全部保留。主要已观察的中间结果：

| 运行 | 实际证据和限制 |
|---|---|
| business-flow-5 / seed-admission-1 | 4项业务/profile、12项seed分别PASS；前者MockMvc不等于网络HTTP |
| application-2 | 3项真实Boot/HTTP/JVM强退与原30秒租约接管/响应丢失PASS |
| runtime-default-recheck-1 | 无迁移旧模式Boot、恢复专用装配、全shard重验与低基数指标3项PASS |
| jvm-matrix-1 | 源提交未发退出、真实broker ACK后PG标记前退出、双JVM竞争、真实Kafka停机/同端点恢复4项PASS |
| dependency-stop-2 | 独占PG真实stop/start、503后同ID原结果PASS；Kafka原文副本数量期望修正后由jvm-matrix闭合，未弱化字节断言 |
| real-authz-2 | 实际SDK/auth-platform/独占SpiceDB、原actor/ID、TOUCH提交响应丢失、权限与实际server停机PASS；原管理面一致性政策保留，不宣称即时撤权 |
| acceptance-gaps-1 | 初始化6/业务5/HTTP2通过；双JVM仅HTTP对象字段顺序比较失败，后改语义比较且严格保持原持久结果字节 |
| baseline-closure-1 | HTTP2/业务6通过；后续Kafka夹具tar配置文件未写完便被读取，导致0.0.0.0 listener启动失败 |
| review-fixes-1/2 | 分别为新测试Container导入歧义、夹具/tmp root文件改名权限失败；无对应产品运行PASS，均保留日志 |
| review-fixes-3 | 19项中18PASS；唯一错误为Topic重建测试Admin超时配置非法，未到产品断言，已由full-gate-3真实负对照闭合 |
| catalog-full-gate-1 / 2 | 分别98项96PASS2失败、98项97PASS1错误；断点/重连/独立日志文件定位修正，原日志/XML保留 |
| lease-closure-1 | 独立5项PASS；worker和QUIESCED恢复专用最后领取清算、真实JVM退出、原文保留边界 |
| catalog-full-gate-3 | 114项/14类PASS，0失败/错误/跳过，9:29；中断客户端后原进程继续，等待真实终态后保存指纹及XML |
| parent-corrections-1 | 原完整门禁结束后修改seed及缺记录分类；受影响26项（Recovery8/Seed18）PASS，其余证据按源码复用，不伪称单次140项 |
| root-authz-final | 原actor/准入/PREPARE顺序变更导致真实开户链需重验；显式root-recovery模式PASS，原full其他权限证据保留；新查询其他actor404隐藏存在性 |

每次仅复验受影响部分或最终必要相关集合，没有重跑S15、资金、午夜或自然验收。物理停机容器由测试独占，固定本轮选出的loopback HostPort；记录同端点恢复，不把端口重分配当自动恢复。断点/代理只证明各自窗口，不宣称网络丢包、Kafka多副本容灾、ACL故障或生产性能。

## 分离复审与窄范围CI

依据engineering-workflows的diff-review流程，3个只读子Agent分别检查业务/权限、持久化/并发、运行配置/故障。确认7项并修正，源码复核及对应真实反例全部通过：

1. ROOT_CREATE首次空查询竞争返回赢家后缺原actor校验：仓储same统一验证，真实双JVM/两个actor通过PG插入锁屏障覆盖竞争窗口。
2. 已知初始词超1024仍留下owner占有：admit前拒绝，后续正常写不被阻断。
3. 重放ACK已提交/home未知仍访问Kafka：优先原ACK回查和无新增尝试的终态清算；随后真实broker停机仍可完成。
4. 存活producer只在构造时验证topic身份：外发前有界重验，错配持久STOPPED；真实同名topic重建负对照。

5. 末次step退出后父命令PAUSED导致普通worker和QUIESCED恢复专用路径卡住：有界SKIP LOCKED清算与按原round/fence的审计清算，保留占有/预算。
6. seed把明确无治理表也一律拒绝，违反正式legacy例外：恢复明确ABSENT准入，完整异常/同库检查不放松。
7. 内部恢复显式缺command/step/event混为一致性/冲突：独立NOT_FOUND，已有字节摘要损坏仍INCONSISTENT。

另修正APPLIED PREPARE先复用原receipt，再决定是否需当前item校验。三名审查者均未修改或运行产品；详细结果 `separate-review.json`。

CI只追加 `catalog-reliability-gate`（20分钟），显式运行 `Catalog*Test,SeedAdsPostgresTest,AdvertiserRepositoryPostgresTest,CreativeReviewTest,AdvertiserAuthzTest`；原所有job/触发器/步骤逐字节不变。专用 `CatalogRealAuthzAcceptance` main不在该CI选择内，须单独实际执行。Ruby Psych YAML、既有持久化/错误门禁通过；未新增第三方依赖或静态豁免。

## 保留与交付门槛

11保护、37旧迁移fixture、59旧SQL、正式C16摘要和CODEX_PROGRESS历史后缀复核保持。前置CI38964531069重新只读查询仍为精确686454dc全部8门禁SUCCESS；证据 `preceding-ci-before-delivery.json`。用户既有Compose/运行文档/脚本/PROGRESS/C15A草案及console/test-results不暂存；所有.dev/target保留。

A仅在适用C16-01～12/16～20、完整相关回归、最新真实authz、复审与hygiene全部满足后才评估DONE并进行独立逻辑实现提交。保留5c0f2901设计提交，不改写；随后按持续授权正常ff/push并登记新CI。A已满足本地DONE；父S16仍未完成，S16B与S17～21随后由父协调继续，S22～25暂停。
