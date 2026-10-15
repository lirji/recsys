# ProjectRefactoringReport：全项目 MyBatis-Plus 迁移

## projectSummary

阶段：计划已批准，MP00/MP01完成；处于增量重构阶段。全项目迁移、最终验收和结构终审未完成。
目标与完整执行顺序见 [PLAN](PLAN.md)。

## baseline

基线 `e7838776`；源码及 POM 无先存未提交修改。已有 8 个用户/暂停交接文件保持。
当前结构与清单见 [BACKEND_ARCHITECTURE](BACKEND_ARCHITECTURE.md)、[SQL_INVENTORY](SQL_INVENTORY.md)。
重构前增量编译及全仓测试成功：694测试，0失败/错误，4个既有跳过。具体范围见 MP01_RESULT。

## problems

| ID | 优先级 | 问题及证据 | 影响与本次处理 |
|---|---|---|---|
| P01 | P2 | `recsys-user/UserProfileService`、`query/IdfWeighter`、`offline/IdfJob` 等直接执行 SQL | 业务与数据访问耦合；迁移到专用 Mapper/XML |
| P02 | P1 | ad/offline 多 DataSource、advertiser 内嵌 derived 源，资金 Repository 检查 DataSource 身份 | MP 自动单源扫描可能串库；必须显式绑定并验证事务。此项是迁移风险，不表示现有串库事故 |
| P03 | P1 | BillingOutboxRepository 使用 UPDATE RETURNING/租约；ReconciliationRepository/Configuration 使用 DB 时间与 RR 快照 | 框架缓存、提交、超时语义可能改变资金结果；精确配置并真实并发验证 |
| P04 | P2 | AbstractItemCatalogReader/BehaviorQuery/ItemQuery 使用动态来源与 SQL 片段 | 保留封闭来源选择，移为 Mapper 参数/固定 XML 分支，避免万能 SQL 执行器 |
| P05 | P2 | RealtimeFeatureJob 原生 JDBC，offline 运行时 DDL/批量导入 | 易在“全项目”迁移时遗漏；独立工作包、打包/游标/批次测试 |
| P06 | P2 | 现有部分测试 Mock JdbcTemplate，另一些 PG 测试直接 new Repository | 只调整 Mock 不能证明 Mapper；先补特征测试和 session 装配，保留独立 JDBC 断言 |

## targetArchitecture / refactoringPlan

目标、过渡与决策见 BACKEND_ARCHITECTURE；MP00～MP15 及各有界子项见 PLAN。
无新业务域、共享写模型或部署单元；新增持久化技术模块与各模块 Mapper/XML。

## testProtection / verification

- 构建基线：增量 COMPILE=PASS。
- 测试基线：本轮全仓694测试；偏好6项旧JDBC特征测试与迁移后8项/宿主3项真实集成通过。
- MyBatis-Plus 接入/许可证/已知漏洞查询：MP01完成，见MP01_RESULT；Flink/真实分片待对应批次。
- 最终真实 PG/Kafka/分片/浏览器全量验收：待后续实施和MP15，不把MP01通过视为全项目通过。
- 计划覆盖检查：包含 13 个模块与独立 DDL/测试/Flink 边界；未将只读扫描当成逐 SQL 完整语义审查。
- Code Hygiene：MP01无阻断发现；缺少统一formatter与独立Java静态分析的限制见MP01_RESULT。

## changeSets / implementation

生产 changeSets：MP01基础接入及用户画像迁移，详见 MP01_RESULT。后续 changeSets 仍待实施；历史迁移未修改。

## behaviorCompatibility

| 边界 | 当前事实 | 目标 |
|---|---|---|
| API | UNCHANGED（MP01真实集成验证） | UNCHANGED；其余路径完成后测试确认 |
| DB schema/data | UNCHANGED（仅独占测试库建数/断言） | UNCHANGED；DDL资源提取不改历史脚本 |
| MQ | UNCHANGED | UNCHANGED |
| External / Redis | UNCHANGED | UNCHANGED |
| Business | UNCHANGED | UNCHANGED，金额/资格/锁序/幂等必须实测 |

## remainingRisks / technicalDebt

首选依赖版本已通过MP01路径与当前Boot/Spring栈实测；其他模块尤其分片/Flink仍需验证，部分非资金路径需逐包补齐真实数据库测试。
历史跨 owner 读库和运行时 DDL 先保持并记录，不能利用持久框架替换静默改变架构/业务行为。
源码初筛计数不是 SQL AST 清单，实施前逐方法补参数、结果、事务、来源和测试映射。
未测性能，不承诺吞吐或延迟提升。

## rollbackPlan / nextMilestone / evidence

按 PLAN 第 8 节执行有界提交回退；不修改 Git 历史、共享数据或用户改动。
下一步：MP01 Git交付 → MP02a 内容CRUD → MP02b目录查询，按PLAN连续推进。
原企业可靠性任务继续暂停，不隐式继续 S13B。

证据入口：PLAN、SQL_INVENTORY、BACKEND_ARCHITECTURE、TECH_SELECTION、PROGRESS_STATE；本轮编译日志 `/tmp/recsys-mybatis-baseline-compile.log`。

## 当前增量状态（MP14）

前文MP01是初始基线。当前已完成MP00～MP12、MP13a1/a2、b1～b8、c1～c7、d1～d5、e1～e5及f1～f7，MP13整体完成；逐片证据以PROGRESS_STATE及对应RESULT为准，MP14已完成（非Boot旧5/新6/最终shade真实PG1），MP15正在执行。最新完整离线280/46类及全仓24 reactor测试编译/hygiene通过，不代表最终全仓验收。

新增已证实技术债：ImportR9EmbeddingsJob原手动事务会提交/回滚外层先行工作，迁移R9EmbeddingStore保持该边界并用真实PG保护；未来事务治理须独立决定传播策略。Lookalike/Hot沿用两条Redis命令删除后写入，窗口仍存在，不能宣称原子替换；查询失败发生在删除前时旧数据保留已实证，见MP13C7_RESULT/MP13D1_RESULT。

后续错误提示治理补充：Bandit增量成功日志lambda可与实际模型不一致，writeModel捕获写失败后仍可能记录已写；保持本次SQL兼容，见MP13D3_RESULT。

后续错误提示/参数治理补充：AdCalibrateJob的bins=0会先发布空曲线再越界报错，已用真实PG/Redis固定原行为，见MP13F1_RESULT；后续统一校验须在发布前拒绝。

后续类型与失败提示治理补充：Attribution原double[]路径会损失超过2^53的广告ID精度（9007199254740993→9007199254740992），本轮保留旧报表行为并用真实PG保护；应在明确输出变化的修复中改long具名路径。Attribution/Quality捕获CSV或部分Redis失败后仍记完成，后续统一提示需区分成功/部分失败。见MP13F3_RESULT。

AdReport原无效点击计数异常降级为0，不能区分“无无效点击”与“未知”。本次保留SQL兼容，后续错误提示治理应明确状态，见MP13F4_RESULT。

oCPC的days<=0实际仍排除epoch之前及NULL点击，旧说明“全部”不精确；无效lambda/旧k读取降级、Redis多广告主部分发布边界保持。后续提示治理应区分降级与正常输入，不宣称多键原子，见MP13F5_RESULT。

DataQuality检查全部因缺表跳过时仍记breaches=0和巡检通过，真实旧/新测试已固定该历史行为；后续统一提示需以已执行检查数和检查状态区分未检查与通过，见MP13F6_RESULT。

过渡装配清理已完成：原DataSource归属不变，advertiser派生工厂直接持有原非Bean无池源，保持Boot主源创建；三个表名字符串入口移除，固定XML来源枚举保留，见MP13F7_RESULT。
