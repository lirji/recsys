# MyBatis-Plus 迁移进度

## 任务目标

整个项目引入 MyBatis-Plus，迁移全部生产业务 SQL，并完成必要的持久化结构调整和最终评审。SQL 全部完成后统一错误码、错误提示展示并减少相关魔法值（用户追加授权）。

## 当前状态

`IN_PROGRESS`。整体计划已批准；MP00～MP05已交付，MP06a/b已交付，MP06c DONE/PASS（22项测试）。MP06整体完成，准备Git交付后继续MP07a。

- 当前分支：`refactor/mybatis-plus-migration`，基线 `e7838776`。
- 原企业可靠性 S13B～S25 继续暂停，根 CODEX_PROGRESS.md 原文保持。
- 新任务恢复入口：[PLAN.md](PLAN.md)。

## 已完成

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
- MP00～MP06已完成；MP07～MP15及追加统一错误码目标未完成。
- 当前验证见 MP01_RESULT；全仓 clean verify 和显式浏览器验收留待 MP15，未执行项不记 PASS。
- MP01已提交/ff合并/push main：e8462f5dbdf7c9d10b95be1ba765f04c39d54473；CI 38711518885被后续CI并发策略取消；MP02a CI 38712301983全绿；MP02b CI 38713385372被后续并发策略取消；最新CI运行/排队，需最终核验。

## 已修改文件

MP01～MP03已交付。当前MP06c：console依赖、画像/报表服务、Mapper/XML/配置及真实PG测试、本目录证据。根恢复文档只追加入口，旧交接保留。

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

1. MP06b已交付0249ae19，MP06c已完成，准备Git交付。
2. 继续MP07～MP15，全部SQL完成后处理统一错误码。
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
