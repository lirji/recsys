# MyBatis-Plus 迁移进度

## 任务目标

整个项目引入 MyBatis-Plus，迁移全部生产业务 SQL，并完成必要的持久化结构调整和最终评审。SQL 全部完成后统一错误码、错误提示展示并减少相关魔法值（用户追加授权）。

## 当前状态

`IN_PROGRESS`。整体计划已批准；MP00～MP07及MP08a已交付，MP08a=b7853b47。MP08b已交付ddcd60ba；MP08c已交付14679624，MP08整体完成。MP09a已交付394fb6a2；MP09b/c已交付345dcb23，MP09整体完成。MP10a已交付30eb28be；MP10b/c已交付e0949431，MP10整体完成；MP11a已交付b8e01cc2，原生页签修复34a2a7a7一起推main；MP11b已交付50b677ae；MP11c已交付b596eeb2，MP11整体完成；MP12a已交付5bc7cf2e；MP12b已交付2a02851a；MP12c已交付3108245f；MP12d已交付541651d7，原生窗口修复85804013；MP12整体完成。MP13a1=6e307304、MP13a2=ae9ec1c4均已正常ff/push main；MP13b1=6b96d0d3、b2=dcac4a8d、b3=37c51498均已ff/push main；MP13b4=13f6a9ef、b5=abe65238均已ff/push main；MP13b6=19592ca6已ff/push main；正反馈可空ID修复52f9fba8及连接探针773cf8c3已ff/push main；MP13b7=ac21c920已ff/push main；MP13b8=42b2de17已ff/push main；MP13c1=cd144c02已ff/push main；MP13c2=38e404f1已ff/push main；MP13c3=9d7897b5已ff/push main；MP13c4本地DONE/PASS。MP12d远程CI38741661134五门禁及console-ci38741660789全部SUCCESS；MP13其余业务迁移继续。

- 当前分支：`refactor/mybatis-plus-migration`，基线 `e7838776`。
- 原企业可靠性 S13B～S25 继续暂停，根 CODEX_PROGRESS.md 原文保持。
- 新任务恢复入口：[PLAN.md](PLAN.md)。

## 已完成

- MP08预算、CPM报价/预占、准备/回执全部完成，见MP08A_RESULT、MP08B_RESULT、MP08C_RESULT。

- MP07b MMR/DPP向量查询完成，MP07整体DONE，见MP07B_RESULT。

- MP07a推荐输入/曝光日志完成，见MP07A_RESULT。

- MP06c控制台查询完成，MP06整体DONE，见MP06C_RESULT。

- MP06b自然回执事务迁移完成，见MP06B_RESULT。

- MP06a行为事实/归因迁移完成，见MP06A_RESULT。

- MP05b辅助读取完成，MP05整体DONE，见MP05B_RESULT。

- MP05a广告事件/归因/uplift完成，见MP05A_RESULT。

- MP04c目录副本/报表/DDL资源提取完成，MP04整体DONE，见MP04C_RESULT。

- MP04b广告主写库/报表完成，见MP04B_RESULT。

- MP04a广告目录/召回三源Mapper完成，见MP04A_RESULT。

- MP03d评分序列完成，MP03整体DONE，见MP03D_RESULT。
- MP03c3语义ID/TIGER完成，召回生产Java SQL全部核销，见MP03C3_RESULT。
- MP03c2图/多兴趣/双塔完成，见MP03C2_RESULT。
- MP03c1普通向量/语义ANN与派生源隔离完成，见MP03C1_RESULT。
- MP03b召回输入完成，见MP03B_RESULT。
- MP03a词形查询完成，见MP03A_RESULT。
- MP02b目录/FTS迁移及双来源回归完成，见MP02B_RESULT。
- MP02a内容CRUD与共享源clean宿主19项全通过，见MP02A_RESULT。
- MP00 全仓694测试，0失败/错误；MP01 用户8项+推荐3项真实集成全部通过。见 [MP01_RESULT](MP01_RESULT.md)。

- 盘点 13 模块/131 个生产 Java 候选文件、45 个 SQL 资源。
- 形成 PLAN、BACKEND_ARCHITECTURE、TECH_SELECTION、SQL_INVENTORY、PROJECT_REFACTORING_REPORT。
- 重构前增量编译通过：`mvn -o -B -DskipTests compile`；23 reactor 项目 SUCCESS。
- 创建原目录任务分支；没有额外 worktree。

## 未完成

- 计划已批准；无需重复确认。
- MP00～MP12已完成；MP13剩余离线作业、MP14、MP15及追加统一错误码目标未完成。
- 当前验证见 MP01_RESULT；全仓 clean verify 和显式浏览器验收留待 MP15，未执行项不记 PASS。
- MP01已提交/ff合并/push main：e8462f5dbdf7c9d10b95be1ba765f04c39d54473；CI 38711518885被后续CI并发策略取消；MP02a CI 38712301983全绿；MP02b CI 38713385372被后续并发策略取消；最新CI运行/排队，需最终核验。

## 已修改文件

MP00～MP12及MP13a1/a2、b1～b6已交付。当前修复共用正反馈Mapper对历史可空ID的兼容，之后继续b7推荐模拟；具体见OFFLINE_NULL_ID_RESULT。根恢复文档只更新入口，旧暂停交接字节保留。

## 保护内容

以下为本任务开始时的已有未提交文件，必须保持归属，不全量 stage；不从指纹强制恢复文件。另保留 console/test-results 和忽略文件。

| 路径 | 起始 SHA-256 |
|---|---|
| `CODEX_PROGRESS.md` | `09ca9b776a47ea4c4ae8dee26595390a4ec791ba1c757b6e2bd65378a7fe6862` |
| `docker/docker-compose.yml` | `800063e913c5fe67adf6c9c24b909e96752d9274918da437abed6175d818fae1` |
| `docs/08-本地运行.md` | `0f2f36eb5bdb3899cd612b269da5e616f254d3ed72ddfda8da91e5a5b996a997` |
| `docs/09-权限接入auth-platform.md` | `25453e7383c2b16daabd4332eaf6bf1c7502e5058242525b18d0c06a78b89f3b` |
| `docs/skills/19-工程化-容器编排与可观测.md` | `ccce0eced1452a255e078d89e0c65fa18214e750b5c07cd8e2ee65cabb25d43f` |
| `scripts/dev-local.sh` | `353ce585f75e44e49b18b52dcde4cf616b0fa06d1b102b76a73e71060d9d7133` |
| `PROGRESS.md` | `c773919843507060f06e27dd5e27ae18415b45f4f1c1d36f9e32a33a197a3ee7` |
| `docs/design/enterprise-reliability/C15A_DESIGN.md` | `9a09d948ae60bd1f7bd2178bccb5fdcef4eb4338c51135f42d7a303a97ca98c1` |

## 下一步

1. MP13c4本地完成并保存提交；继续MP13c5 ImportSemanticIdJob旧PG保护，再正常ff/push已验证提交。
2. 继续MP13～MP15，全部SQL完成后处理统一错误码。
3. 依次推进全部工作包，每批更新本文件和 SQL 核销证据，不等待重复“继续”。

## 恢复 Prompt

请读取 docs/design/mybatis-plus-migration/PLAN.md 和 PROGRESS_STATE.md，继续全项目 MyBatis-Plus 迁移。本计划已经由用户明确批准，按 MP00～MP15 连续实施、真实验证并按既有 Git 授权交付。原企业可靠性 S13B～S25 仍暂停，不能误恢复该任务；保护原用户文件，不改历史迁移摘要，不使用万能 SQL Mapper 代替实际迁移。

## 追加目标

SQL全量完成后统一错误码及错误提示展示，并减少相关魔法值，详见PLAN第10节；仍为当前持续目标，不能在SQL完成时提前标记整个任务完成。

## 根恢复入口说明

为长任务恢复，按用户AGENTS要求在CODEX_PROGRESS.md头部追加当前活跃任务入口，原暂停交接字节完整保留在后方（原文SHA仍为保护表中的09ca9b…）。该文件包含原任务未提交内容，仍不stage；其他保护文件未改。运行时保护指纹中仅根文件更新为追加后的指纹，不能用旧摘要强制覆盖它。

## MP04a当前检查点

DONE/PASS，详见MP04A_RESULT。最终18项宿主通过，全仓编译通过，源指纹已冻结。继续MP04b广告主；广告主主源仍由Boot自动配置，派生源原来藏在derivedJdbc内，不能随意新增DataSource bean导致Boot主源退避。优先让派生工厂复用derivedJdbc.getDataSource()，精确绑定XML；测试需验证同一实例和主源仍创建。

最近已观察CI：MP03d 38716894284 SUCCESS；MP03c3/c2被后续并发策略取消。

## MP04b检查点

DONE/PASS，见MP04B_RESULT；AdvertiserDataSourceConfig连接装配原样保留，工厂仅借用JdbcTemplate.getDataSource()，Java业务SQL已全部核销。继续MP04c，需要提取AdServableRepository运行时DDL到新资源并补全表/列注释，保留降级、JSON、upsert/delete和统计口径；不能修改历史08_ad_servable.sql等资源。

## MP04c检查点

DONE/PASS，见MP04C_RESULT；29项联合验证全通过。下一片MP05a：AdEventLogger、AdOutcomeService、UpliftAssignmentService；原Jdbc batch曝光写入需保留参数/创意NULL、异步及异常降级，新增真实PG验证批量失败语义；uplift advisory lock+条件插入、转换事实ON CONFLICT幂等、开关关闭不建表及运行时DDL资源提取。已有两个纯Mock测试需随构造器迁移并以真实PG补证。

## MP05a检查点

DONE/PASS，见MP05A_RESULT。当前页曝光多VALUES写入的整批失败语义已由新旧真实PG验证；原异步生命周期保留。继续MP05b：分片预算/两次创意查询、主源GD合约、派生源候选向量。可扩展已有真实双分片/向量测试以复用隔离容器，先运行旧实现保护，再迁移；GD需覆盖PG bigint舍入及缺失/NULL数值。

## MP05b检查点

DONE/PASS，见MP05B_RESULT；最终44项通过。下一片MP06a BehaviorService：原两个SQL（行为插入与曝光bucket权威回查），已有BehaviorServicePostgresTest并发CLICK及LIKE差异保护、4项mock归因测试。迁移前补真实NULL bucket/归属不匹配/非点击时间语义；Mapper需要区分“存在但bucket=NULL”和“不存在”，不能单String/null吞掉曝光ID验证。行为和自然回执共享原primary DataSource，后者尚待MP06b迁移，不改变事务边界。

## MP06a检查点

DONE/PASS，见MP06A_RESULT。继续MP06b NaturalReceiptRepository/Configuration：15项真实PG测试刚通过旧实现，可作为基线（MP06a-after.log）；schema验证SELECT也进入Mapper，SET LOCAL与clock_timestamp要保持同一事务连接、锁后新时间。UUID需要明确TypeHandler；原资源V001摘要不可变。更新NaturalReceiptPostgresTest的匿名故障Repository及NaturalExposureBrowserAcceptanceTest构造入口，后者还被FeedExposureBrowserAcceptanceTest复用；最终需显式跑自然/混排浏览器验收。

## MP06b检查点

DONE/PASS，见MP06B_RESULT；30项后端与自然/混排共10项真实浏览器验收通过。MP06c控制台UserProfileService有6组查询及逐项失败降级，ReportService有2组查询及JSON/CSV回退；应替换为可选具名Mapper，保留无数据库时空画像/CSV和NULL字段、毫秒时间语义。content已传递引入MP与共享主源配置，console需显式声明直接依赖及精确扫描；新增真实PG测试先保护旧实现。

## MP06c检查点

DONE/PASS，见MP06C_RESULT；旧8、新22测试及24 reactor编译通过。MP07a涉及DbSeenItemsSource、ColdStartDetector、PersonalizationScorer、ExposureLogger：主源行为，派生源用户/物品向量；现有ExposureLoggerTest只校验同步Redis归因，需真实PG保护批次约束失败整批不落、value/position一基与异步排空。与MP05a同类批处理迁移可用foreach多VALUES，但必须先验证旧行为。复用现有主/派生工厂，精确引擎Mapper扫描；RecommendIntegrationTest提供真实pgvector+Redis宿主验证。

## MP07a检查点

DONE/PASS，见MP07A_RESULT；旧8、新12及24 reactor编译通过。MP07b MmrReranker/DppReranker现有相同item_embedding::text查询，去重候选后逐行解析，失败时保留已解析向量并按类目降级；可共用EngineEmbeddingMapper新增具名ResultHandler方法，保留各算法原解析/计算。DppRerankerTest现有2个Mock测试需改Mapper；补真实pgvector对照，确保有向量/NULL/缺行/缺表/候选不足/池裁剪的排序与元数据不变。

## MP07b检查点

DONE/PASS，见MP07B_RESULT。MP08a BudgetRepository/Configuration有schema校验、命令唯一键/摘要、账户插入锁定、UPDATE RETURNING、完成条件更新和原快照读取。多配置（Click/ConversionQuote及三族Event）临时new BudgetRepository做迁移校验；多模块测试/浏览器fixture也手工构造，须完整更新。建议Repository从明确的adDbSqlSessionTemplate获取Mapper及实际DataSource，避免声明DataSource与Mapper真实连接源不一致；不新建池/不改默认关闭条件。真实BudgetAccountPostgresTest已有11项（含并发16、约束失败回滚、迁移漂移、错误池），BudgetConfigurationTest2项；先跑旧实现，新增RETURNING事务/无缓存检查。官方MyBatis XML文档已核验affectData支持自3.5.12，本项目3.5.19满足。

## MP08a检查点

DONE/PASS，见MP08A_RESULT；27源文件，最终指纹cb8cf4cb62e62a84158e5da2ae4a3058956bc581ea1ae962c4916d8c203b7e3f。旧13、新预算PG13+配置2与完整资金链路368/68类均通过，sandbox4/feed5浏览器及宿主3通过，24 reactor编译/hygiene通过。当前预算Repository从实际SqlSessionTemplate取得Mapper/DataSource，相关配置只变V001探针的装配；其他Repository尚未迁移。MP08b继续CpmQuoteRepository/Configuration、ReservationRepository/Configuration，真实money-chain刚通过，可复用其中CPM5、预占12/回收5作为旧实现基线。保持报价clock_timestamp CTE/五分钟期限/threshold与bill_factor文本精度、冻结摘要与冲突恢复；预占统一锁序、SKIP LOCKED、有界到期扫描、held条件更新/捕获/释放与原预算日不变。需同步全部new构造引用及浏览器fixture（同MP08a）；不改历史V002/V003。最新已观察MP07b CI38725073212全部SUCCESS。首次Hygiene沿用PENDING字面量未过，已改具名私有常量，最终15项预算测试、24 reactor编译及Hygiene均复验通过。

## MP08b迁移前基线

`/tmp/recsys-mp08b-before.log`：23项/3类全部通过。CPM新增首次签发期限已过不插入、已有同一报价期限已过仍恢复原结果、改负载仍冲突、五分钟TTL保持；预占12/到期调度5原测试通过。此时两个Repository仍为原JDBC，准备整体迁移Mapper/XML并同步其构造引用。

## MP08b最终验证

DONE/PASS，见MP08B_RESULT。25源文件指纹f63df6b52a2bda1dc9f20efaf0e25f3ba8c412a4be76088aa705addbe4893853；旧/新专项23项、完整资金链369项/68类、sandbox浏览器4项、混排5项、推荐宿主3项全部通过，全仓24 reactor编译与hygiene通过。45历史SQL/8保护文件不变。MP08a远程CI38726514355全绿。本批Git后继续MP08c准备/回执：本次money-chain可作为迁移前基线；Preparation的三种固定表、独立提交/5秒查询超时、原ID恢复和首次结果不覆盖必须保持；Receipt的四份事实同事务、可空时间/ID与半成品拒绝需保护。

## MP08c迁移前保护

复用MP08b完整资金链369项基线，另新增未完成准备的主体/请求校验、PENDING回执在空字段映射前拒绝。四套准备/回执PG专项旧实现通过，日志/tmp/recsys-mp08c-before.log。迁移全部固定模式准备SQL和CPM回执/账本/Outbox写入，保留Prepared JSON文本、首次结果、查询超时5秒及同库回滚。

## MP08c最终验证

DONE/PASS，MP08整体完成。23源文件指纹8b86c433e636bb540a70472e6c2c2a1b3a179787aef4a033c2a682e292480d21；旧/新专项38项，新增锁超时兼容测试后准备9项通过，最终全仓24 reactor资金链372项/68类全通过；sandbox4/feed5浏览器、推荐宿主3项通过，hygiene无阻塞。最初超时异常预期错误，现直接对照旧JdbcTemplate确认相同异常类别和SQLSTATE57014，产品逻辑未改。45历史SQL/8保护文件不变。MP08b CI38728391213已SUCCESS。Git后继续MP09a ClickQuoteRepository/Configuration，装配ClickEventConfiguration及全部构造调用；保持CPC/OCPC价格/摘要/五分钟期限、位置冲突优先于过期、主体先校验与5秒超时。当前完整资金链可作为旧实现基线。

## MP09a迁移基线

MP08c最终资金链372项作为旧路径基线，包含ClickQuotePostgresTest6项、点击准备/结算、三模式兼容与在线宿主。迁移ClickQuoteRepository六条SQL，保留原hash编码、CPC/OCPC价格构造、期限/冲突优先级/鉴权和5秒超时。构造装配和相关测试同步使用adDbSqlSessionTemplate，点击结算SQL仍待MP09b。

## MP09a最终验证

DONE/PASS，见MP09A_RESULT。12源文件指纹1bb4d22620fd45bad3db3158826fa3c7c4c3311b054c568da82b1359cb58d307；迁移后点击/转化兼容及在线宿主85项/8类通过，推荐宿主3项、全仓24 reactor编译和hygiene通过。45历史SQL/8保护文件不变。本片不重复未改变路径的CPM/混排浏览器，MP08c刚验证且MP15仍要求最终全量门禁。Git后MP09b：ClickSettlementRepository现181行，查询回执原决定与当前结算状态须保留同一JOIN快照，quote→rate→account锁序、SKIP LOCKED、条件计数/版本/held更新、账本/Outbox同事务、5秒超时及原结果构造顺序不变。旧基线MP08c372资金链和本片ClickSettlement22/ClickOnline8等已通过。

## MP09b实施检查点

迁移前新增unfinishedReceiptCannotBecomeCanonicalOrJoinedResult：数据库拒绝PENDING独立提交；同源事务内claim后canonical/JOIN读取均先拒绝半成品，rollback后无回执且账户/账本不变。最初测试直接提交被既有延迟约束正确拒绝，已改为断言该约束并在事务内检查；旧路径23项全部通过，/tmp/recsys-mp09b-before-final.log。随后已新增ClickSettlementMapper/XML并迁移Repository/配置/引用测试，正在运行/tmp/recsys-mp09b-after.log；待实测确认嵌套record映射。

已读ClickSettlementRepository全部181行。实施时将SNAPSHOT_COLUMNS/FROM移入XML，保持回执决定与当前Snapshot同一JOIN；可用明确的SnapshotRow、ReceiptRow及嵌套构造StoredRow，以constructor arg resultMap复用同一结果集映射，禁止nested select拆成多次查询。MyBatis官方Mapper XML文档支持constructor/resultMap，已核对本地3.5.19 JAR的DTD（arg/idArg均支持resultMap/name/columnPrefix）：https://mybatis.org/mybatis-3/sqlmap-xml.html。最终仍需真实PG验证嵌套record构造、可空字段和列别名。保留stored中receipt先于snapshot校验的既有顺序；PENDING用私有常量，不扩展公开ReceiptStatus。

装配引用：ClickSettlementConfiguration、ClickEventConfiguration；测试ClickSettlementPostgresTest（建议新增static sessions供故障子类复用）、ClickPreparationPostgresTest（已有sessions）、ClickEventsFixture、ConversionSettlementPostgresTest、ClickOnlineFixture（已有sessions）、ClickOnlinePostgresTest/ConversionOnlinePostgresTest（f.sessions）。需要同步错误DataSource断言中的new JdbcTemplate(other)构造。所有语句仍5秒超时；quote→rate→account锁序、唯一键等待、LIMIT1 SKIP LOCKED、计数分钟边界、条件版本/held检查、账本与Outbox同事务及JSON契约不变。

MP09a远程CI38730596602运行中；MP08c CI38730135316因随后提交被取消，不能标全绿。最近完整成功CI为MP08b38728391213。本地MP08c完整372和MP09a85专项/宿主3均已通过。

## MP09b/c最终验证

DONE/PASS，MP09整体完成，见MP09B_RESULT。12源文件指纹ab3a4accb08bc102054796cc22c98f1eefc9a334c4c2696b614bdcabce24512b；迁移前23项、迁移后点击准备/结算/在线与转化在线49项通过；最终全仓24 reactor完整资金链373项/68类、推荐宿主3项及hygiene通过。嵌套constructor resultMap真实PG验证成功，22条SQL、5秒超时、同一JOIN快照、全部资金/计数/版本/回滚语义保持。45历史SQL/8保护文件不变。MP09a CI38730596602全部SUCCESS。MP09c无新增SQL，其准备已MP08c迁移，原装配/消费者由专项覆盖；Outbox/投影仍MP11，engine交付仍MP12。下一步MP10a ConversionQuoteRepository/Configuration，现代码与点击报价相似但必须保留CPA bid_type强校验，不能机械照抄ClickPrice构造；V012和原摘要/五分钟期限保持，MP09b资金链作为旧路径基线。

## MP10a实施检查点

复用MP09b完整资金链373项作为旧路径基线。ConversionQuoteRepository六条SQL已移入独立ConversionQuoteMapper/XML，V012、CPA bid_type强校验、原hash函数字节、金额/文本精度、位置冲突先于过期、五分钟TTL及每句5秒超时保持。配置/ConversionEventConfiguration与全部构造调用同步，迁移后专项进行中：/tmp/recsys-mp10a-after.log。

## MP10a最终验证与下一片

DONE/PASS，见MP10A_RESULT。9源文件指纹0615b6d01b1118d7c1cb21c9b196bf04550a4914f90418bbc51d0672cf7236ae；迁移后49项/5类通过，推荐宿主3项、全仓24 reactor编译与hygiene通过，45历史SQL/8保护文件不变。下一片MP10b ConversionSettlementRepository已读全201行：可见回执和业务Outcome独立原决定，状态/结果仍同一JOIN快照；必须区分settled_outcome_id与event_outcome_id，不能丢advertiser过滤/映射。claimOutcome的ON CONFLICT(advertiser_id,event_id)不能改为吞掉所有冲突；原actor仅审计，当前授权actor可恢复同业务内容。保留CPA双条件、原预算日、held/charged条件更新、Outbox及5秒超时。可复用MP09b nested constructor resultMap模式但不能带入CPC rate逻辑。旧基线MP09b373资金链和本片CPA结算23/准备8/在线10已通过；先补充可见与Outcome的事务内PENDING/可空字段保护，再迁移。

## MP10b迁移前检查点

迁移前ConversionSettlementPostgresTest新增unfinishedVisibleAndOutcomeCannotBecomeCanonicalOrJoinedResults：两类事实独立提交PENDING均被延迟约束拒绝；持有quote锁的同源事务中claim后，canonical/同JOIN读取都先拒绝半成品，rollback后无记录且账户/账本不变。旧路径24项通过，/tmp/recsys-mp10b-before.log。

计划使用独立ConversionSettlementMapper/XML，SnapshotRow17字段（outcomeId映射settled_outcome_id）、VisibleRow6字段、OutcomeRow9字段（outcomeId映射event_outcome_id）、StoredVisibleRow/StoredOutcomeRow嵌套constructor resultMap保留单次JOIN。公共snapshotColumns必须保留s.advertiser_id供OutcomeRow读取（原s.*中存在）；canonical outcome读取自身advertiser_id；两个outcome别名不能混同。共24条语句预计，必须逐方法核对。构造引用：ConversionSettlementConfiguration、ConversionEventConfiguration；ConversionSettlementPostgresTest可新增static sessions供故障spy复用；ConversionPreparationPostgresTest已有sessions；ConversionOnlineFixture/ConversionOnlinePostgresTest已有sessions/f.sessions；ConversionEventsFixture构造已throws Exception可创建模板。无CPC rate路径，不拷入计数逻辑。

最新Git：MP10a=30eb28be86f408503936c1cf0beef3f1eee251df已正常推main；CI38732423173 pending。MP09b=345dcb2390fe2fe11a518ff2b618e96b4d6e0b9d，CI38732026360运行中；MP09a CI38730596602已SUCCESS。随后已完成ConversionSettlementMapper/XML、Repository/配置和构造引用适配，运行/tmp/recsys-mp10b-after.log。

MP10b当前10源文件清单/tmp/recsys-mp10b-source-paths.json，指纹3f34f9b7aff5a75e278ae4a05457852990fba911062c5bd9d4680968ba68ed1b；24条Mapper/XML方法静态对应，真实PG验证中。

## MP10b/c最终验证

DONE/PASS，MP10整体完成，见MP10B_RESULT。10源文件指纹3f34f9b7aff5a75e278ae4a05457852990fba911062c5bd9d4680968ba68ed1b；旧24项、迁移后42项、全仓24 reactor完整资金链374项/68类、推荐宿主3项均通过；hygiene无阻塞，45历史SQL/8保护文件不变。MP10c准备与消费者无额外SQL，Outbox/投影仍属于MP11，engine交付仍MP12。MP10a CI38732423173五门禁全部SUCCESS；MP09b CI38732026360被后续提交取消。

下一片MP11a BillingOutboxRepository：10条独立原子语句（三族schema探针、exhaust/claim/published/failed/replay/backlog）；固定表族移到XML choose，不再Java replace SQL，不使用用户表名。claim为UPDATE RETURNING，必须affectData=true/flushCache=true/useCache=false；保留30秒租约、20次尝试、exhaust批次100、clock_timestamp、fence+未过期条件和重放审计CTE同语句。每条5秒超时，调用方不能持数据库锁等待Kafka。构造引用为三EventConfiguration、三EventsFixture、三EventsKafkaTest故障子类及SandboxExposureBrowserFixtureTest；fixture可复用cpm.sessions避免临时checked exception。当前完整资金链374项作为旧基线；新增真实claim事务回滚、SKIP LOCKED与重放审计冲突回滚保护后再迁移。

## MP11a迁移前保护

BillingEventsPostgresTest新增RETURNING领取自动提交/事务回滚、锁住最早待办后跳过并领取其他记录、重放审计键冲突时CTE中的状态/尝试次数/fence全部回滚。旧实现运行中/tmp/recsys-mp11a-before.log；未改Outbox产品代码。MP10b/c已正常ff合并并推main=e0949431，完整374项资金链可作为其余三族事件路径旧基线。

MP11a旧实现12项PG通过；已完成10条Mapper/XML及三族装配、fixture和Kafka故障子类适配，迁移后验证/tmp/recsys-mp11a-after.log运行中。14源文件清单/tmp/recsys-mp11a-source-paths.json，指纹9c9b21d4ceb18b35575f59f64afaea2c394082073d5d61ca9165157f65095726。MP10b CI38734120451运行中。

MP11a迁移后60项/10类全部通过，hygiene无阻塞；sandbox真实浏览器正在运行/tmp/recsys-mp11a-sandbox-browser.log。后续默认关闭推荐宿主与全仓编译，全部完成后才提交。

## MP11a最终验证及CI故障

MP11a本地DONE/PASS，见MP11A_RESULT：旧12项PG、新60项三族PG/Kafka/配置/worker、推荐宿主3项、sandbox浏览器4项及24 reactor编译全部通过，hygiene无阻塞；45历史SQL/8保护文件不变。14源文件指纹9c9b21d4ceb18b35575f59f64afaea2c394082073d5d61ca9165157f65095726。

MP10b CI38734120451资金链/自然/security成功，但混排原生hidden场景在创建空白页签后仍visible而失败；sandbox4/自然5均成功，混排4成功/1失败。三套同类测试已显式激活新target，保留原生hidden断言及事实检查；单独修复提交，证据NATIVE_TAB_CI_RESULT。当前混排运行/tmp/recsys-native-tab-feed.log，之后依次自然/sandbox；Maven仍串行。

## MP11b实施准备

下一片三族ProjectionRepository已读全：Billing 3条SQL、Click 4条、Conversion 3条。保留各自Mapper/XML，不用可空的统一万能Fact：CPM单receipt、CPC双receipt与跨kind共同唯一索引、CPA独立visible/outcome/businessEvent身份约束不同。CPC两receipt按UUID排序领取，两条必须全部插入，失败回滚Inbox；三族同ID同hash重复false，冲突不同ID/资格/内容仍失败。累计金额不按账户版本水位丢弃独立事实，SQL ON CONFLICT全部约束及原精度保持。

三Repository改用SqlSessionTemplate，从实际factory取DataSource供Service同事务身份校验；Billing/Click原来没有单句超时，保持XML不另加默认；Conversion原单句5秒要保留。三个EventConfiguration的Projection装配改sessions后可移除JdbcTemplate引用。引用：三EventsFixture（共享cpm.sessions已MP11a引入）、三EventsPostgresTest故障子类/restart、SandboxExposureBrowserFixtureTest；原PG测试已覆盖Inbox/汇总前后失败回滚、并发重复、反序版本和身份冲突，三族Kafka测试涵盖ACK/位点窗口和重启。新增同URL不同DataSource拒绝保护应在旧实现先跑，可放ConversionEventsPostgresTest一次覆盖三族；构造测试fixture仍用实际同一DataSource。之后MP11c ReconciliationRepository 只读RR与实际资源/超时独立验证，不更改V016。

原生页签修复本地DONE/PASS，独立提交34a2a7a7，三套真实浏览器混排5/自然5/sandbox4均通过；类型/Prettier/ESLint及联合hygiene通过。MP11a产品迁移全部验证完成，随后独立提交并与修复一起ff推main，继续MP11b。

## MP11b迁移前保护

MP11a=b8e01cc218f450842b6328cf9f415fed7aa37239和原生页签修复34a2a7a7已正常合并/push main。ConversionEventsPostgresTest新增三族投影实际DataSource身份检查：同URL不同实例的事务管理器都拒绝；旧路径运行/tmp/recsys-mp11b-before.log。尚未改投影产品代码。

MP11b旧路径14项PG保护通过；三族共10条SQL已迁移独立Mapper/XML，所有构造调用已适配；当前专项（含对账）/tmp/recsys-mp11b-after.log运行中。19源文件指纹fbc94a42c07d74a00da3d4adca642b715f25b79754839ccbf2c7bd849a56a2ac，清单/tmp/recsys-mp11b-source-paths.json。Billing/Click无单句timeout、Conversion 5秒差异已静态核对；真实事务身份以实际工厂DataSource为准。

## MP11b最终验证

DONE/PASS，见MP11B_RESULT；19源文件指纹fbc94a42c07d74a00da3d4adca642b715f25b79754839ccbf2c7bd849a56a2ac。旧14项、新三族事件与对账72项/11类、推荐宿主3项和全仓24 reactor编译全部通过；hygiene无阻塞，45历史SQL/8保护文件不变。MP11a CI38735452724浏览器/自然/security均SUCCESS，build/money仍在运行；console-ci38735452734全部SUCCESS，原生页签修复已通过远程浏览器。

下一片MP11c：ReconciliationRepository/Configuration及ReconciliationPostgresTest所有构造入口改实际sessions；现有11项真实PG已在本片72项中通过，可作为旧基线。FamilySummary的20个原始字段使用显式record/resultMap，BigDecimal再由原decimal函数转文本，两个可空时间保留。固定三族表与obligations选择进入XML，CPM无ledgerId核验、CPC按quote.advertiser过滤、CPA按settlement.advertiser过滤差异保留；scope绑定、全量身份/零金额笔数及缺行检查不变。observedAt保持transaction_timestamp，所有查询5秒、STATEMENT缓存，Service强制同源/只读/RR/REQUIRES_NEW/5秒配置不改；绝不恢复暂停的S13B任务或重写V016。全部SQL迁移后跑本片PG与完整资金链（预期378项），宿主/compile/hygiene按实际结果记录。

## MP11c实施检查点

复用MP11b 72项中的11项真实PG对账旧基线；9条SQL已迁入ReconciliationMapper/XML，20字段原始FamilyRow和5字段AccountRow显式构造，原金额文本转换/两个可空时间/原预算日映射保持。三族固定表和obligations选择仅XML，scope绑定及ledgerIdentityRequired服务端布尔值替代原formatted拼接；Service只读/RR/REQUIRES_NEW/5秒不改。配置及所有构造调用使用实际sessions。迁移后11项通过/tmp/recsys-mp11c-after.log，完整资金链/tmp/recsys-mp11c-money.log运行中，hygiene无阻塞。5源文件指纹5865eefbd1e7944dc9276d024869d1ff0efb4f0baa4ffb79efbd58675e9f73cf，清单/tmp/recsys-mp11c-source-paths.json。MP11a CI38735452724最终cancelled（已观察浏览器成功），MP11b CI38736158900运行中。

MP11c完整资金链378项/68类、全仓24 reactor测试编译通过（/tmp/recsys-mp11c-money.log）；默认关闭推荐宿主运行中。三族聚合XML展开后与原SQL归一化一致，保护8/历史45/源5摘要未变。MP11b CI38736158900钱链/自然/browser/security均SUCCESS，仅build最后观察运行中。

## MP11c最终验证与MP12a入口

DONE/PASS，MP11整体完成，见MP11C_RESULT。5源文件指纹5865eefbd1e7944dc9276d024869d1ff0efb4f0baa4ffb79efbd58675e9f73cf；新旧对账PG各11项、完整资金链378项/68类、全仓24 reactor测试编译及推荐宿主3项全部通过；hygiene无阻塞、8保护文件/45历史SQL不变。下一片MP12a仅FeedCandidateRepository/Configuration及两构造fixture：FeedDeliveryPostgresTest、FeedExposureBrowserAcceptanceTest。三条SQL（摘要、to_regclass索引检查、有界候选频控）进入addb Mapper/XML，候选只传adId/advertiserId目标记录，用foreach绑定VALUES；空列表仍直接返回，非法参数校验顺序保持。两个索引名称为服务端固定值，to_regclass NULL必须继续触发原缺索引错误。5秒超时、statement_timestamp上海接受日与原预算日分离、两级计数LIMIT阈值保持。现有FeedDeliveryPostgresTest11项已在378项钱链通过，其中跨用户/广告主/仅接受账本和日边界已保护；可新增缺索引NULL映射、空候选不查询账本保护后跑旧路径，再迁移。其FeedDeliveryRepository留到MP12d，不借此扩大改动。

## MP12a迁移前保护

MP11c=b596eeb2已正常提交/ff合并/push main。FeedDeliveryPostgresTest新增缺索引to_regclass NULL报错、空候选不查询账本两项真实PG保护；仅临时重命名独占测试schema中的索引/表并finally恢复。旧实现运行/tmp/recsys-mp12a-before.log，尚未改FeedCandidate产品代码。

MP12a旧路径15项（PG13+配置2）通过/tmp/recsys-mp12a-before.log；3条SQL已进入FeedCandidateMapper/XML，具名Target列表foreach绑定，空列表与参数校验顺序保持，两个fixture已适配。迁移后含推荐宿主测试运行中/tmp/recsys-mp12a-after.log。6源文件指纹0d7a12e5e5e4b2175ae069df1356f1c917cd214662cb1d26c82881de2fe27458，清单/tmp/recsys-mp12a-source-paths.json。

## MP12a最终验证与MP12b入口

DONE/PASS，见MP12A_RESULT。6源文件指纹0d7a12e5e5e4b2175ae069df1356f1c917cd214662cb1d26c82881de2fe27458；旧15、新18项（包含宿主3）、混排真实浏览器5项、全仓24 reactor编译全部通过，hygiene无阻塞；45历史SQL/8保护文件不变。MP11c CI38737280136运行中，security/自然成功，其他待核验。

下一片MP12b DeliveryOperationRepository（三模式）共8条SQL，固定表选择移入XML；owner主源为sqlSessionTemplate，XML应放mapper/recengine/primary，factory测试pattern=classpath*:mapper/recengine/primary/**/*.xml。原create的INSERT和后续find保持独立提交；acquire UPDATE RETURNING必须affectData=true/flushCache=true/useCache=false，全部5秒超时。原JSON编解码/异常、subject先于JSON校验、bucket/reserve冻结、lease30秒、fence/旧plan比较及可空plan保持。progress状态可用已有State枚举name，保持持久化值不变。

配置引用DeliveryOperationConfiguration/ClickDeliveryConfiguration/ConversionDeliveryConfiguration；测试三DeliveryOperationPostgresTest（可新增static sessions复用故障子类）、ClickOnlineFixture/ConversionOnlineFixture（engine源独立sessions不能误用已有adDb sessions）、SandboxExposureBrowserFixtureTest（engineDs）。当前完整资金链378已涵盖旧三模式/在线；可在CPM旧测试增加acquire显式事务回滚不消耗fence/lease保护，再跑三模式/在线与宿主。自然与混排Repository留MP12c/d。

## MP12b迁移前保护

MP12a=5bc7cf2e已正常提交/ff合并/push main。DeliveryOperationPostgresTest新增acquire显式事务回滚不消耗fence/lease；同一事务第二次领取为空，回滚后fence回0，重新自动提交领取仍为1。旧三模式PG运行/tmp/recsys-mp12b-before.log，尚未改交付产品代码。

MP12b旧三模式PG24项通过。8条SQL已进入engine primary DeliveryOperationMapper/XML；三Configuration及全部fixture使用实际会话工厂，在线fixture单独绑定engineDs。owner校验先于JSON解码、5秒SQL超时、原create独立提交、30秒租约、fence/旧plan比较保持；acquire已显式affectData。迁移后专项/tmp/recsys-mp12b-after.log运行中；12源指纹ba62bc814df1d5f3928469c55c1941992af10af376c962b27cc441b288477cff，清单/tmp/recsys-mp12b-source-paths.json。MP12a CI38737890572运行中，MP11c最终cancelled。

MP12b首次迁移后45项中2项在线Spring装配失败，原因是测试上下文仅注册旧jdbcTemplate；已补齐独立engineSessions及adDb会话，多源绑定继续实测。修复后45项/6类全部通过（PG三模式24、在线CPC8/CPA10、宿主3），/tmp/recsys-mp12b-after-final.log。最终14源指纹dd9a818a8f68a834f644b1a52a87da3bd77c9aa1a6dd57f489e383f634fed08c；旧12源指纹已被fixture修正替代。sandbox浏览器/tmp/recsys-mp12b-sandbox-browser.log运行中，之后全仓24 reactor编译。

## MP12b最终验证

DONE/PASS，见MP12B_RESULT；14源指纹dd9a818a8f68a834f644b1a52a87da3bd77c9aa1a6dd57f489e383f634fed08c。旧24、新45项/6类、sandbox浏览器4项、全仓24 reactor编译全部通过；hygiene无阻塞，45历史SQL/8保护文件不变。下一片MP12c自然Repository的8条SQL及实际DataSource的REQUIRES_NEW/READ_COMMITTED/5秒事务保持；新增外层回滚仍保留独立领取/冻结保护后先跑旧路径，再迁移。

## MP12c迁移前保护

MP12b已正常提交/ff合并/push main=2a02851a。NaturalDeliveryPostgresTest新增外层回滚后独立create/acquire/freeze仍保存完整结果，验证REQUIRES_NEW及真实factory的DataSource绑定；旧实现PG/配置测试/tmp/recsys-mp12c-before.log运行中。MP12a CI38737890572钱链/自然/浏览器/security成功，build仍运行。

MP12c旧12项（真实PG10+配置2）通过；8条SQL迁入NaturalDeliveryMapper/XML，JSON/毫秒截断/4MiB限制保持，事务资源取实际SqlSessionFactory，REQUIRES_NEW/READ_COMMITTED/5秒不变。6源指纹ba1837cab3d2ae55770a2351f4a49d3788003844b2ce18a1f2fac95d3aea577a，清单/tmp/recsys-mp12c-source-paths.json；迁移后专项/tmp/recsys-mp12c-after.log运行中。

MP12c迁移后15项全部通过（PG10+配置2+宿主3），hygiene无阻塞；事务REVIEW_REQUIRED已通过实际factory同源、REQUIRES_NEW/RC/timeout源码审查及PG外层回滚保护确认。自然浏览器/tmp/recsys-mp12c-natural-browser.log运行中，随后全仓编译。

## MP12c最终验证

DONE/PASS，见MP12C_RESULT；6源指纹ba1837cab3d2ae55770a2351f4a49d3788003844b2ce18a1f2fac95d3aea577a。旧12、新15、自然浏览器5项及全仓24 reactor编译全部通过；hygiene无阻塞，事务人工审查完成，45历史SQL/8保护文件不变。下一片MP12d混排9条SQL：实际engine primary会话与同源REQUIRES_NEW/RC/5秒、JSON及MAX_SNAPSHOT_BYTES保持；测试FeedDeliveryPostgresTest和FeedExposureBrowserAcceptanceTest需独立engine sessions，不能误用addb。新增独立检查点不随外层回滚保护后跑旧路径，再迁移并跑完整钱链/自然门禁和混排浏览器完成MP12闭环。

## MP12d迁移前保护

MP12c已正常提交/ff合并/push main=3108245f。FeedDeliveryPostgresTest新增外层回滚不能撤销独立领取/冻结计划保护；旧PG/配置测试/tmp/recsys-mp12d-before.log运行中，尚未改混排产品代码。

MP12d旧16项（真实PG14+配置2）通过；9条SQL已进入FeedDeliveryMapper/XML，Repository和两个fixture使用实际engine主源会话，原REQUIRES_NEW/RC/5秒、subject、fence/旧plan比较与完整快照约束保持。6源指纹ac46ec24001ca0bfcdba2cf6bd664e7fae5b1edcb8531c218d83e0ab10622af7，清单/tmp/recsys-mp12d-source-paths.json；专项/tmp/recsys-mp12d-after.log运行中。初扫engine/ad/behavior/advertiser生产Java只剩SQL关键字注释，无业务SQL；最终全仓核销仍留MP15。

MP12d迁移后19项（PG14/配置2/宿主3）通过，hygiene无阻塞；事务人工审查同源/REQUIRES_NEW/RC/5秒与SQL条件完成。完整资金链/tmp/recsys-mp12d-money.log运行中，之后自然门禁和混排浏览器；所有Maven/浏览器串行。

## MP13入口只读发现（尚未实施）

OfflineAdShardingConfig已有四个DataSource及四个JdbcTemplate；Boot大包扫描已通过content/recall间接加载primary/derived持久配置。可先补offline明确依赖与四源工厂复用，不新建连接池，不改ShardingSphere 5.5.0/环境路由；实际分片验证沿作业路径进行。

BehaviorQuery.positiveFeedbackSql/params共用于ItemCf、Swing、UserCf、BanditStats、FtrlTrain、UserEmbedding，后者另有max(epoch)与带action/value/ts_epoch的流式读取；原Java SQL工具必须在这些调用入口一起迁移后删除，不能只改名字或保留execute(sql)。ItemQuery/BehaviorQuery/AdEventQuery的表选择最终用封闭参数+XML固定choose；未迁移调用方可短暂保持原白名单入口，需在最终核销全部消除拼SQL。不能把RowCallbackHandler扫描改为全量List；Mapper ResultHandler或会话内消费Cursor要结合原事务/抓取行为验证。IdfJob已有真实PG stemming测试可后续复用；其ts_stat内嵌查询也只能固定表分支，raw-token映射每批2000需保持。此节为源码发现，不替代MP13各单路径旧保护与验收，也不提前标记任何离线文件DONE。

MP12d完整资金链382项/68类和全仓24 reactor测试编译全部通过（/tmp/recsys-mp12d-money.log）。自然门禁/tmp/recsys-mp12d-natural.log运行中，之后混排真实浏览器；不再重复全仓compile。

MP12d完整自然门禁75项/13类全部通过（/tmp/recsys-mp12d-natural.log），混排浏览器原夹具5项通过。MP12c CI38739717158却再次混排原生hidden失败（sandbox4/自然5/混排4通过），原因证据及修复见NATIVE_WINDOW_CI_RESULT；不能宣称远程全绿。独立修复nativeBrowser唯一窗口、同窗target校验，12次探针及typecheck/Prettier/ESLint通过，现串行重跑三套浏览器；产品6源指纹不变。

## MP12d最终验证

本地DONE/PASS，MP12本地闭环，见MP12D_RESULT。旧16、新19项，完整钱链382项/68类、自然75项/13类、全仓24 reactor测试编译、混排浏览器5项全部通过；6源指纹ac46ec24001ca0bfcdba2cf6bd664e7fae5b1edcb8531c218d83e0ab10622af7。修复夹具后混排5/自然5也通过，sandbox运行中/tmp/recsys-native-window-sandbox.log；统一hygiene无阻塞，Java工具限制及事务人工审查已记录。不能将MP12c远程失败记为成功，待新提交CI核验。

原生窗口修复三套真实浏览器混排5/自然5/sandbox4全部通过，见NATIVE_WINDOW_CI_RESULT，4源指纹cb0abddb6eb468b7b58c69056cfc8e7ef87578ea1b072c232f05d32d4e69c2f1。分别提交夹具修复和MP12d，随后一起ff/push main。SQL_INVENTORY补齐15个已迁移ad配置的旧TODO：当前源码均显式adDbSqlSessionTemplate且无JdbcTemplate，引用原MP08～MP12证据；未改这些产品文件。

## MP13a1实施入口

MP12d=541651d7及原生窗口修复85804013已正常提交/ff合并/push main。先完成MP13a的有界基础批：offline POM明确声明已冻结的recsys-persistence与MP boot3 starter；现有OfflineAdShardingConfig导入四个共享会话工厂，复用原DataSource/环境/分片规则，不改连接池，也不提前删除仍被作业使用的JdbcTemplate。旧13类离线测试/tmp/recsys-mp13a1-before.log运行中。新增隔离PG四schema会话绑定验证，确认每个factory实际使用原DataSource且只加载其XML目录；真实ShardingSphere路由仍随后续分片作业验证，不用普通PG探针代替路由证明。

下一小片再处理BehaviorQuery生成SQL的共用路径与封闭表来源参数；本基础批不将44个离线业务文件或整个MP13a标为DONE。保持SQL迁移与错误码阶段顺序，当前原生窗口修复须等新远程CI实际通过后才关闭远程失败记录，避免再次提前推送取消其验证。

MP13a1旧64项/13类全部通过；offline已明确声明原冻结依赖并在原数据源配置导入四源工厂。新增真实PG四schema绑定测试通过，测试只加载实际Import清单而以独占源替换环境连接，不访问共享配置；同时核验原DS对象/各XML目录/STATEMENT及禁二级缓存。7源指纹8353c708fe8ed5b8cf3b671fb62afdf1b59aca343e7f355f1638ddb91d74da11，清单/tmp/recsys-mp13a1-source-paths.json；全离线回归/tmp/recsys-mp13a1-after.log运行中。CI38741661134（541651d7）仍运行，console-ci38741660789全部SUCCESS。

## MP13a1最终验证

本地DONE/PASS，见MP13A1_RESULT。旧64项/13类、新65项/14类及全仓24 reactor编译通过；依赖树保持MP3.5.17/MyBatis3.5.19/mybatis-spring3.0.5，hygiene按已批准artifact名称重跑无阻塞。7源指纹8353c708fe8ed5b8cf3b671fb62afdf1b59aca343e7f355f1638ddb91d74da11。当前541651d7 CI38741661134仍运行，暂不推送下一批以免取消原生窗口修复验证；没有新增技术取舍或待用户确认项。

## MP13a2最终验证

DONE/PASS，见MP13A2_RESULT。旧PG3项、新专项4项、全部离线68项/15类及全仓24 reactor测试编译通过；14源指纹958df7bfff535d2dcae07f4b9710a78b725d740a1011979661c6342cc2a01087。第一次after异常包装预期失败已按真实框架语义精确断言同一个根因，资源关闭/后续读取检查保留。ItemCf/Swing/UserCf及BehaviorQuery业务SQL已核销，其他三作业仅PARTIAL。MP13a1本地提交6e307304，待与本片一并ff/push。MP12d远程五门禁CI38741661134全部SUCCESS，窗口夹具问题闭环，历史失败不改记成功。

## MP13b1最终验证

DONE/PASS，见MP13B1_RESULT。旧PG4项、新全离线72项/16类、全仓24 reactor编译、hygiene通过；4源指纹78c4ed9aec461c6f854e70febe6b288ad0c200184c33ed23232d8f99ca73bb14。1000条批次/失败批无部分行/前批已提交/外层事务整体回滚/来源隔离保持；没有历史SQL变更。ae9ec1c4远程CI38744006253当前运行中，不能记全绿。下一片SyncBehaviorLogJob保持原同库快路径和跨库1000批次、增量水位与full语义，DDL迁到独立bootstrap资源并补字段注释。

## MP13b2最终验证

DONE/PASS，见MP13B2_RESULT。旧PG5项、新离线77项/17类、24 reactor编译、hygiene通过；9源指纹39784ef7f2698bd34de679e57dbc36bf66813b3c8eff8ea5c4f7253429e377f4。原MAX水位/1000批次/full/跨源NULL微秒/失败连接关闭/外层回滚/旧列升级保持，新增bootstrap列注释齐全。继续SyncItemCatalogJob；原历史SQL与用户文件保护不变。

## MP13b3最终验证

DONE/PASS，见MP13B3_RESULT。旧PG4项、新离线81项/18类、24 reactor编译通过；8源指纹9315f90a39fe9d502886074b7b261c3569341544623eb91017b8dfc46a6787ae。原NULL标签跨库/同库差异、元素顺序/NULL/重复、全列覆盖、生成列/GIN、1000批失败及外层回滚保持。ae9ec1c4的CI38744006253五门禁全部SUCCESS，现将MP13b1/b2与本片正常ff/push；之后SyncAdEventLogJob。

## MP13b4最终验证

DONE/PASS，见MP13B4_RESULT。旧PG4项、新离线85项/19类、全仓24 reactor编译、hygiene通过；8源指纹d124be7baec0a5351c77d18d60d29fbf84b147f8ce549b96c978a0a1311ab261。16列/NULL/Long大值/微秒/绑定文本、源连接关闭、1000批失败、外层full回滚和三索引保持，bootstrap16列注释齐全。MP13b1～b3已ff/push main=37c51498；下一片SeedAdsJob真实四源/分片/向量复制。

## MP13b5最终验证

DONE/PASS，见MP13B5_RESULT。旧真实四源/双分片/Redis4项、新初次专项6项、最终全离线91项/20类、24 reactor编译和hygiene通过；14源指纹bf4d50f931376cdc6f7d46ceab1cb9aea1b3ef1a652443e64403fe58bb692f41。额外发现全NULL record投影返回null行，真实PG复现后以原跳过语义修复，不能把所有后续可空投影默认为非null对象。两次种子重建各23条分片INSERT参数/路由与旧日志一致（只排除创意生成ID），原随机算法未改。37c51498的CI38745667586五门禁全部SUCCESS，b4=13f6a9ef随本片交付；之后SimAdEventsJob等离线写入。

## MP13b6入口：大批次语义必须实测（尚未改产品代码）

b4=13f6a9ef、b5=abe65238均已正常ff/push main。下一片SimAdEventsJob的默认20000条曝光不能整批foreach多VALUES（9参数/行已超PG绑定上限），也不能随意拆成新事务边界。原三次JdbcTemplate.batchUpdate分别写曝光/点击/转化，外层事务可统一约束；无外层事务时pgJDBC可能在内部Sync时提交部分批次，不能推断整批天然原子。MP13b1～b4的1000行批次已有独立实证，不能泛化到此大批次。

依据冻结版本[pgJDBC 42.7.3执行器](https://raw.githubusercontent.com/pgjdbc/pgjdbc/REL42.7.3/pgjdbc/src/main/java/org/postgresql/core/v3/QueryExecutorImpl.java)、[MyBatis 3.5.19 BatchExecutor](https://raw.githubusercontent.com/mybatis/mybatis-3/mybatis-3.5.19/src/main/java/org/apache/ibatis/executor/BatchExecutor.java)和[MyBatis-Spring 3.0.5资源管理](https://raw.githubusercontent.com/mybatis/spring/mybatis-spring-3.0.5/src/main/java/org/mybatis/spring/transaction/SpringManagedTransaction.java)，优先评估实际factory.openSession(ExecutorType.BATCH)内Mapper逐条addBatch、每个原逻辑batch只flushStatements一次，try-with-resources关闭；保持原DataSource实际autoCommit/Spring绑定资源，不开启新事务、不调用主动commit。SpringManagedTransaction在autoCommit或Spring事务中不自行rollback/commit，close用DataSourceUtils释放。不能直接用BATCH SqlSessionTemplate逐行调用（无事务会每条flush），也不能在已有SIMPLE事务会话上强换executor。需要保留Spring数据异常分类，检查PersistenceException/MyBatisExceptionTranslator对BatchUpdateException的翻译。候选辅助工具只能接受Mapper回调，不能接受任意SQL；是否抽共享由SimRecEvents同类真实需求决定。

实施前先真实双PG分片旧测试：空MAX跨分片聚合、活动创意有序读取/缺表降级、默认20000规模成功、第二类CLICK失败保留此前曝光、曝光第15000行约束失败的实际已提交数量/连接关闭、外层事务回滚。记录实际结果后冻结批边界，不凭假设加入TransactionTemplate。测试ad_event独占schema用BIGSERIAL及CHECK(id<>15000)注入晚失败；每例TRUNCATE RESTART IDENTITY；模拟时间/UUID不变，断言事件关系/NULL列/未来转化及原序列。迁移后还应覆盖同一个外层事务中已有SIMPLE会话后执行独立batch并继续读取、提交/回滚。

## MP13b6最终验证

DONE/PASS，见MP13B6_RESULT。旧6项两次实跑、新专项7项、全离线98项/21类、24 reactor测试编译、hygiene通过；8源指纹64f742e08116c22b7f449431a698a83117abbfd556131633d3e540e38e8730b3。实际晚失败已提交14847条（旧/新一致），CLICK失败保留5000曝光；同源Spring事务提交/回滚、已有SIMPLE会话与独立BATCH共存、连接关闭通过。SpringMapperBatches不接受SQL、不主动commit、不新增事务，需继续保持这个边界。abe65238远程CI38747583977五门禁全部SUCCESS。下一片SimRecEventsJob原单大批次、主源item/item_local选择、RATING热度和sim-rec来源清理；同样先旧PG测试，再复用batch helper。

## 正反馈可空ID检查点

见OFFLINE_NULL_ID_RESULT。旧JDBC对照已通过，迁移路径先复现全NULL行NPE，再经两ID投影COALESCE修复；全离线99/21类、全仓24 reactor测试编译通过。前序CI38749329611四门禁成功但build连接退出瞬时断言失败，先独立核实该观察时序再继续b7。

## 广告模拟连接观察修正

前序CI38749329611唯一失败为关闭后瞬时pg_stat_activity检查。新增真实Connection.isClosed立即检查与服务端5秒有界观察，故意打开连接的反向测试证明不掩盖泄漏；专项8项通过，见SIM_AD_CLOSE_PROBE_RESULT。产品未改；新CI实际结果待提交后核验。

## MP13b7最终验证

见MP13B7_RESULT。旧6项两次通过，新专项7+装配1通过，全离线107/22类通过；默认50000曝光/11285点击/3342点赞、完整有序摘要与旧一致，晚失败14847实测边界和已有SIMPLE事务共存保持。5源指纹5c0d35786470ea840d84729d5dcad6c5df6f377e7d90f310f9c021078875349a。下一片SimAdUpliftDataJob两个adDb大批次。

## MP13b8最终验证

见MP13B8_RESULT。旧6项两次、新专项7+装配1、全离线114/23类、24 reactor编译、hygiene通过。5源指纹9d8cdd7cde9e2628da1ba874ce8545333d182f4d14c45336fdeb8b953f309053；默认5000/2539 treatment/602事实、完整摘要及第4001行失败已提交3839结果与旧一致。继续MP13c1向量回填，配额停止与逐行失败语义不得改变。CI38751096791已取消，38751843655尚未完成，不记全绿。

## MP13c1最终验证

见MP13C1_RESULT。旧4、新专项4+装配1、全离线118/24类、24 reactor编译/hygiene通过。4源指纹db2d4ecc748cee219916c019fc7828f9d0efa4c5d152ee48d7352a697d40e0b7。derived ItemEmbeddingMapper保留PGvector setObject、NULL存在跳过、逐条失败继续与quota停止。下一片MultimodalBackfillJob，不能将原UPDATE擅改为upsert或改变中途失败边界。

## MP13c2最终验证

见MP13C2_RESULT。旧6再补为7项全部通过，新多模态7+文本4+装配1共12项、全离线125/25类、全仓编译通过。4源指纹92f51aaf666049dcaddd886a6724389709b779d54128d20da29b74414d5e624f；原UPDATE-only、NULL/海报/limit/融合结果及部分失败边界保持。已记录历史unsupported错误提示可能误称未改数据，留给后续统一提示阶段修正。下一片UserEmbeddingJob。

## MP13c3最终验证

见MP13C3_RESULT。旧7、新关联19、全离线132/26类、24 reactor测试编译、hygiene通过。6源指纹ce7d862a633826dc32e1b49f24e5a7ad7242e1d953bdc61fea8118a2ca6fc77e。UserEmbedding已无业务SQL，主/派生源边界、权重/时间、无输入不清理及TRUNCATE部分失败/外层回滚保持。下一片ImportTowerJob，先保护重复ID后沿原500行BATCH迁移，不能用单个多VALUES改变冲突行为。

## MP13c4最终验证

见MP13C4_RESULT。旧6两次、新7+装配1、完整离线139/27类、24 reactor测试编译及hygiene通过。6源指纹8bdebbded8f54f5b84e60133656df7bec59c1a2cbb376b04ba43715444a2658b；默认64维、重复ID后写覆盖、500行失败边界与DDL/批次同源事务保持。继续MP13c5语义ID导入。9d7897b5的CI38754987441三门禁SUCCESS、两项进行中；本片先本地提交，后续正常ff/push，不记远程已交付。
