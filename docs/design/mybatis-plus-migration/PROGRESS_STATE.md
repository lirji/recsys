# MyBatis-Plus 迁移进度

## 任务目标

整个项目引入 MyBatis-Plus，迁移全部生产业务 SQL，并完成必要的持久化结构调整和最终评审。SQL 全部完成后统一错误码、错误提示展示并减少相关魔法值（用户追加授权）。

## 当前状态

`IN_PROGRESS`。用户已批准整体计划；MP00～MP03已交付，MP04a=1f000266、MP04b=d8ad16eb已交付；MP04c DONE/PASS，联合29项真实/契约/宿主测试通过，MP04整体完成。继续MP05，不等待重复确认。

- 当前分支：`refactor/mybatis-plus-migration`，基线 `e7838776`。
- 原企业可靠性 S13B～S25 继续暂停，根 CODEX_PROGRESS.md 原文保持。
- 新任务恢复入口：[PLAN.md](PLAN.md)。

## 已完成

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
- MP00～MP04已完成；MP05～MP15及追加统一错误码目标未完成。
- 当前验证见 MP01_RESULT；全仓 clean verify 和显式浏览器验收留待 MP15，未执行项不记 PASS。
- MP01已提交/ff合并/push main：e8462f5dbdf7c9d10b95be1ba765f04c39d54473；CI 38711518885被后续CI并发策略取消；MP02a CI 38712301983全绿；MP02b CI 38713385372被后续并发策略取消；最新CI运行/排队，需最终核验。

## 已修改文件

MP01～MP03已交付。当前MP04c：ad-serving两个Repository、adDb Mapper/XML/配置、DDL资源、真实PG测试及依赖、本目录证据。根恢复文档只追加入口，旧交接保留。

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

1. MP04b已交付d8ad16eb，MP04c已完成，准备Git交付。
2. 继续MP05～MP15，全部SQL完成后处理统一错误码。
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
