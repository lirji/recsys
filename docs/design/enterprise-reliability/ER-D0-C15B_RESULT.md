# ER-D0/C15B：旧 BehaviorService 可靠事件契约门禁

## 状态与交付范围

回合 `30bd2b82-dda3-46b7-ae2f-63271c05cc0e`，**设计门禁 DONE / PASS**。正式契约已写入 [C15_DESIGN 的 C15B 节](C15_DESIGN.md) 与 [CONTRACTS](CONTRACTS.md)；状态以 [IMPLEMENTATION_SLICES](IMPLEMENTATION_SLICES.md) 为准。**本轮只完成设计，没有实现 S15B，没有执行 B01～B12 产品验收。** S15B1=READY，S15B2/S15C=TODO；父 ER-S15 仍 IN_PROGRESS，S15～S21 总目标未完成。

基线/main/origin-main起点均为 `7fccd6fe0e4c8752b087f435066c3120ef81e0da`；原目录任务分支 `docs/er-s15b-behavior-event-contract`，没有额外 worktree。S22～S25暂停；无共享迁移、开关启用、应用部署或基础设施重启。用户原有11个保护文件、S15A源码及历史交接/部署入口保持原字节。

## 前置事实与远程 CI

已逐项核对 S15A 验证时18个源码/测试/workflow摘要与当前文件及7fccd6fe提交一致；前置 CPA 时间测试修正为 `c032ea12cf596b9e58466eec73de7e1cd2c46b0c`，未重复修改。上轮本地自然108项、行为16项、资金462项不作为远程CI替代。

实际查询 [CI 38923751782](https://github.com/lirji/recsys/actions/runs/38923751782)：headSha精确为7fccd6fe，**completed/success，7个job全部success**，含build、money-chain、natural-exposure、security及3个浏览器job。本轮先只读分析/设计，等待该源码CI终态保存后才允许新main推送；未被concurrency取消。证据 `.dev/codex-flow-reliability/round-30bd2b82/s15a-ci.json`。本设计新提交的CI单独查询，不沿用该源码成功为新提交结论。

## 正式冻结的决定

1. behavior源行+本库Outbox同事务，持久sourceId与源行ID组成稳定eventId；原CLICK唯一约束、归因/时间、无键POST与batch逐条语义不变，不发明请求内容hash/业务去重窗口。
2. 重复CLICK不再仅凭insert=0提前结束可靠路径：按原owner/user/item读原行，已有事件原样保留；缺事件时以原行建立历史待发义务，不用新请求的ts/bucket创建新在线事实。并发冲突后用下一条READ_COMMITTED语句读赢家。
3. 新版本live/history topic隔离，旧topic只保留旧格式。历史混有engine/offline/旧POST，来源与原发送状态不可知；显式LEGACY_ROW/UNKNOWN，不冒充可信自然曝光、trafficClass或实时Flink样本；无时区历史时间原样保留。
4. 历史恢复使用最多100个明确ID的持久命令/逐项结果，每次最多10项短事务，原任务可续办和审计取消。低ID晚提交保留WAITING_SOURCE，后续可恢复；无MAX(id)提交水位、无未经批准全表重发。原快照存在时即使源行已删仍可查；无快照的已删事实不能虚构。
5. capture/live-relay/history-relay均默认false；开启任一新功能且旧发布开关仍true时拒绝启动，避免双发。旧格式消费者不支持新envelope，B2用topic guard明确防误配；实际新版Seen可靠消费仍属C15C，Flink checkpoint/窗口去重属S19。
6. S15B1先交付原子接受、CLICK修复与可靠发送；S15B2交付显式历史清单和消费兼容。父B要求两片全部验收，不能用在线Outbox完成替代历史恢复。

技术取舍DB15-01～05、源码事实FB01～07、wire字段、表/状态/锁序、资源预算、错误/回退及B01～B12未来测试都在正式设计。未引入新组件/依赖/业务政策；实际环境清单与切换是后续运行前提，不阻塞隔离实现、不在本轮执行。

## 独立设计验证

实现前的独立只读复核pass由同一Agent执行，未声称第二评审者或子Agent。以原PLAN F14/ER-S15、真实writer/consumer源码和迁移为依据反向检查正式契约；机器脚本只验证源摘要、覆盖/状态/链接/路径，不冒充SQL、Kafka或产品故障验证。

| 检查 | 核验依据与结果 | 结论 |
|---|---|---|
| D-B01 基线与前置 | Git精确SHA、S15A18项源码指纹、源CI7个job终态成功；旧时间修正不重复 | PASS |
| D-B02 业务身份/CLICK | BehaviorService/Mapper partial unique/现有并发测试；原行冲突、重复缺事件修复、无键POST及batch边界完整 | PASS |
| D-B03 原子性与持久身份 | primary配置、Mapper/XML、独立迁移/namespace；冲突分语句读、同库写/回滚、无网络持锁、原payload/fence/重放 | PASS |
| D-B04 历史来源与覆盖 | Engine/Import/Simulation写表、TIMESTAMP/可空列；独立UNKNOWN历史流、低ID等待、漂移/删除/取消边界，没有全表追平冒称 | PASS |
| D-B05 旧消费兼容 | Seen独立ObjectMapper、Flink value-only/根字段/处理时间；保留旧格式、阻止新topic误配，C15C/S19验收不转移 | PASS |
| D-B06 默认开关/回退 | 旧00/01/10/11与新capture/双relay组合表；拒绝双发、默认关闭、排空与新写保证分开、真实切换不执行 | PASS |
| D-B07 实施与验收覆盖 | B1/B2职责/文件/串行依赖和B01～B12映射；父S15范围、S22～S25暂停不变；无新公共错误码/技术栈 | PASS |
| D-B08 保护与文档 | 23个直接证据源文件未变、11保护SHA一致、原进度历史与部署入口逐字保留；相对链接与diff检查 | PASS |
| D-B09 真实完成/交付边界 | 只交付10个设计/状态文档；产品/测试/SQL/workflow无修改；设计PASS与未来产品UNVERIFIED分开，源CI已结束才推送 | PASS |

复核中已明确修正：历史无时区时间不能强转当前UTC；旧表其他writer禁止被trigger全量捕获为在线POST；取消与advance统一command→item→source→Outbox锁序；原行已删但已有事件应返回原快照；保留源快照独立于wire，避免历史/在线时间表示影响漂移比较；无业务键请求不能因投递去重而合并。以上是正式设计决定，不是已运行产品结果。

首轮机器核验因Git默认中文路径转义导致允许清单比较失败，改用NUL分隔的原路径读取后复验；未修改文件允许范围或保护断言。

机器核验：`.dev/codex-flow-reliability/round-30bd2b82/validate-design.py`、`design-validation.json`、`source-evidence.json`、`start-verification.json`。本轮不启动Maven/PG/Kafka/Redis/Flink/浏览器，无产品变更故Code Hygiene Gate/编译/产品测试为N/A；不把以前测试数量记成本门禁执行数量。

## Git、运行记录与下一操作

交付允许清单10个文档：C15_DESIGN、CONTRACTS、BACKEND_ARCHITECTURE、TECH_SELECTION、VALIDATION_PLAN、IMPLEMENTATION_SLICES、ER-S15_RESULT、ER-S15A_RESULT、本文件、CODEX_PROGRESS。只显式stage这些路径，所有.dev、原C15A草案、配置/运行说明、PROGRESS与console/test-results保持未暂存；未stash/clean/reset或改写历史。

实际提交SHA/ff合并/push与新CI状态记录在本地同目录 `delivery.json` / `ci-snapshot.json`，不提交自引用SHA；最终答复同步给出。无本轮测试/应用/部署后台进程，无新codex-flow实例；外层协调者保持原控制。本轮独立任务分支保留，无需清理worktree或原用户/忽略文件。

无需要新业务决策的设计阻塞。本回合到此交接；下一回合先核对本门禁Git/CI与保护指纹，再执行 **ER-S15B1**，从behavior-events/V001与同库Mapper事务开始，并以真实隔离PG/Kafka完成B1验收。不得提前把S15B、S15或S15～S21记DONE。
