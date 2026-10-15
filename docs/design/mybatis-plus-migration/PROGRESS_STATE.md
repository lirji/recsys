# MyBatis-Plus 迁移进度

## 任务目标

整个项目引入 MyBatis-Plus，迁移全部生产业务 SQL，并完成必要的持久化结构调整和最终评审。SQL 全部完成后统一错误码、错误提示展示并减少相关魔法值（用户追加授权）。

## 当前状态

`IN_PROGRESS`。整体计划已批准；MP00～MP07及MP08a已交付，MP08a=b7853b47。MP08b已交付ddcd60ba；MP08c已交付14679624，MP08整体完成。MP09a已交付394fb6a2；MP09b/c已交付345dcb23，MP09整体完成。MP10a转化报价DONE/PASS，待Git交付后继续MP10b结算。

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
- MP00～MP09已完成；MP10～MP15及追加统一错误码目标未完成。
- 当前验证见 MP01_RESULT；全仓 clean verify 和显式浏览器验收留待 MP15，未执行项不记 PASS。
- MP01已提交/ff合并/push main：e8462f5dbdf7c9d10b95be1ba765f04c39d54473；CI 38711518885被后续CI并发策略取消；MP02a CI 38712301983全绿；MP02b CI 38713385372被后续并发策略取消；最新CI运行/排队，需最终核验。

## 已修改文件

MP01～MP03已交付。当前MP10a：转化报价Mapper/XML、Repository/配置及构造引用测试，本目录证据。根恢复文档只追加入口，旧交接保留。

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

1. MP10a已验证通过，Git交付后继续MP10b。
2. 继续MP10～MP15，全部SQL完成后处理统一错误码。
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
