# MyBatis-Plus 迁移进度

## 任务目标

整个项目引入 MyBatis-Plus，迁移全部生产业务 SQL，并完成必要的持久化结构调整和最终评审。SQL 全部完成后统一错误码、错误提示展示并减少相关魔法值（用户追加授权）。

## 当前状态

`IN_PROGRESS`。整体计划已批准；MP00～MP07及MP08a已交付，MP08a=b7853b47。MP08b已交付ddcd60ba；MP08c已交付14679624，MP08整体完成。MP09a已交付394fb6a2；MP09b/c已交付345dcb23，MP09整体完成。MP10a已交付30eb28be；MP10b/c已交付e0949431，MP10整体完成；MP11a已交付b8e01cc2，原生页签修复34a2a7a7一起推main；MP11b已交付50b677ae；MP11c已交付b596eeb2，MP11整体完成；MP12a DONE/PASS，准备Git后继续MP12b三模式交付操作。

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
- MP00～MP10已完成；MP11～MP15及追加统一错误码目标未完成。
- 当前验证见 MP01_RESULT；全仓 clean verify 和显式浏览器验收留待 MP15，未执行项不记 PASS。
- MP01已提交/ff合并/push main：e8462f5dbdf7c9d10b95be1ba765f04c39d54473；CI 38711518885被后续CI并发策略取消；MP02a CI 38712301983全绿；MP02b CI 38713385372被后续并发策略取消；最新CI运行/排队，需最终核验。

## 已修改文件

MP01～MP03已交付。当前MP10b：转化结算Mapper/XML、Repository/配置及构造引用测试，本目录证据。根恢复文档只追加入口，旧交接保留。

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

1. MP10b/c已交付e0949431，继续MP11a Outbox旧实现保护及迁移。
2. 继续MP11～MP15，全部SQL完成后处理统一错误码。
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
