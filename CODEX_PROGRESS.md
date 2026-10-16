# 当前任务：ER-D0/C16 广告目录物理分片可靠事件设计

唯一实施状态：`docs/design/enterprise-reliability/IMPLEMENTATION_SLICES.md`；正式契约/结果/运行边界：同目录`C16_DESIGN.md`、`ER-D0-C16_RESULT.md`、`CATALOG_EVENTS_RUNBOOK.md`。恢复证据：`.dev/codex-flow-reliability/round-301afed4/{handoff,delivery,independent-design-review,design-file-checks,preceding-ci-before-commit}.json`；当前请求301afed4-8c0b-43c7-98b4-9c686ff63bbf。

C16设计门禁D16-01～09同Agent分离只读PASS，ER-D0/C16 DONE，ER-S16A READY、B TODO、父S16 IN_PROGRESS。正式冻结实际物理分片本库Outbox、持久排他命令/本地fence、完整版本事件、暂停清单与多主批次、默认profile兼容、初始化/恢复及S17必要边界；全部C16-01～20产品验收尚未运行，不宣称实现或上线。

原目录分支`feat/er-s16-catalog-outbox`，基线/main/origin-main=686454dc132da7831dfdc197cb6fdfa9e6348db0；唯一worktree及原用户改动保留。本轮仅9个设计/进度文档的独立本地设计提交，实际SHA见delivery.json；不merge/push纯设计。下一回合复用此分支实施S16A，保留设计与实现独立逻辑提交，必要验证通过后正常ff/push。S16原依赖ER-S06保持，之后S17～21，S22～25暂停。

S15A/B/C必要本地验收及正常Git交付核对完成；83项/14类零失败错误跳过、22源码指纹和原交付blob一致，复用不重跑。11保护、37旧迁移/fixture、1373产品/测试/构建/脚本文件、59份旧SQL和下方历史后缀核对保持。当前S15 CI38964531069精确686454dc已7门禁成功，build仍运行；本地设计获准继续，下一main推送前须核对其终态，真实失败先处理，不取消有效证据。前置C1精确d05d23c0的CI38958960102已8/8成功。

无本地运行中产品/测试/Git操作；提交/文件检查结果以本轮delivery/handoff为准。采用backend-architecture-design、implementation-slicing、project-documentation、update-progress-docs及task-git-delivery；architecture-standards适用章节已核对。保留11保护、已发布迁移/fixture、.dev/target和全部历史正文；不部署、不访问共享业务数据、不启用共享开关、不重启共享容器、不stash/reset/clean/强推、不递归codex-flow。

---

# 已完成独立任务：2026-10-16 本机 Docker 最新代码部署（DONE / PASS）

权威状态与恢复入口：`.dev/deployments/latest-1f77eb4d0378/PROGRESS.md`，结果见同目录`deployment-result.json`。已部署`1f77eb4d037844199c922cd2bf92ea230fc0a56f`到本机recsys全部9应用；8服务健康、21项smoke、实际JAR摘要及浏览器验证通过。CI最终全绿；原数据、功能开关和用户改动保留。回退镜像清单同目录，无本任务运行进程。本任务不续做下方企业可靠性开发，原任务交接保持。

---

# Codex Progress

当前任务：2026-10-16 用户明确恢复企业可靠性 S13B/C、S14～S21，持续实现、验证和 Git 交付。权威状态：`docs/design/enterprise-reliability/IMPLEMENTATION_SLICES.md`；设计与恢复入口：同目录 `C13_DESIGN.md`、`PLAN.md`。S22～S25 不在本轮范围；仅 Sandbox，不部署生产、不启用共享 CPC/CPA。

原目录`/Users/liruijun/personal/LLM/recsys`，当前分支`feat/sandbox-reconciliation-report`，基线/main/origin-main=a3bfb3bb。S13B=600989e7、C1=1482947c、C2=a3bfb3bb均正常提交/ff合并/push；A/B/C本地完整PASS，见ER-S13_RESULT和各分片验收。C2协议/真实TCP/PG/CLI16项、相关回归32项、恢复专项8项、TS类型/格式通过，hygiene有限制无阻断。S13B CI38851321776部分成功后被新提交取消，不是整轮全绿；最新a3bfb3bb的console-ci 38853983179和CI 38853983215均已completed/success，后者7门禁全部成功。S14运行任务见下。

当前S14五维规划及独立复审PASS，实现/测试尚未Git交付。真实HTTP+TCP+PG原11项PASS，新增默认false/坏响应2项待跑；前端类型检查及8项API/组件测试PASS，首轮刷新按钮可访问名受装饰icon影响已修复。grpc环境浏览器enabled/default-off两项PASS，真实三族账户消费0.600006、资金与投影状态分开、查询/刷新/权限拒绝恢复/390宽无溢出、所有资金表逐行不变。截图已实际查看；补充横滚提示后需用最终版再拍。两种engine环境均使用advertiser→owner TCP只读路径，不能冒充不同engine调用链测试。真实SpiceDB验收运行session62975，日志/tmp/recsys-s14-real-authz.log；脚本使用既有隔离763ca23e jar，不改外部仓库。首次宿主表名错写qualification已改为实际settlement；前端lint的故意精度丢失字面量已改为Number(原string)，待复验。下步真实授权结论→in-process环境浏览器→HTTP13项/前端全回归/构建/hygiene→S14提交合并push→S15～21。保护指纹/tmp/recsys-er-resume-protected.json；七项用户文件及console/test-results不stage不清理。采用frontend-implementation、runtime-and-deploy及相关视觉/状态规范，原AntD主题；本轮不部署不启用共享CPC/CPA。

上一任务 ADV-UI-02 DONE/PASS，完整记录 `docs/design/sandbox-click-conversion-ui/RESULT.md`；仅 console 发布源码 `19f58e8c`，两项新开关 false。发布证据与回退文件 `.dev/deployments/adv-ui-02-19f58e8cb133/` 保留。

已采用 `~/.codex/architecture-standards.md` 的业务建模、配置治理、持久化事务、分布式契约、并发安全验证、发布治理章节。后续按涉及范围补读。以下另一项任务的暂停交接原文保持。

# 以下为 2026-10-15 的历史暂停交接（当前授权与状态以上方及实施清单为准）

# Codex Progress

> 当前状态：用户主动暂停，先处理其他事项。交接更新于 2026-10-15T16:49:03+08:00。收到明确恢复指令前，不继续S13B或后续开发。

## 任务目标

连续完成docs/design/enterprise-reliability/PLAN.md原企业可靠性DAG S01～S25，逐片实现、验证并按用户持续授权正常提交/合并/push origin/main。原总体计划与五类前端规划已批准，不重开全部规划、不完成一片就停；仅sandbox，不启用生产收费或生产部署。

## 已完成

- S13A已完成并正常提交、ff合并、push到origin/main：`e7838776a0b09904a0d4702589639534901d27ee`（feat(ad): 增加三类资金的一致账户日对账快照）。11项真实PG专项、完整资金链路366项/68类均0失败/错误/跳过。详见`docs/design/enterprise-reliability/ER-S13A_RESULT.md`。

- S01～S10已交付，细节见各ER-Sxx_RESULT；S10=b0b19432。
- S11完整CPC闭环：A365595e2、Bb2c2911a、C1e599b3e5、C2f914f571；旧浏览器fixture名称修复6904889c，继承CI38699749896全部SUCCESS，原失败已关闭。
- S12完整CPA闭环已DONE/PASS：A2f5c3dab、B2d107e28、C1f23ca416；三批CI分别38699749896、38701260200、38702085570全部五门禁SUCCESS。
- S12C2已交付b8cf737b，CI38705469195五门禁全部SUCCESS：严格广告主edit/fullyConsistent、独立engine/advertiser caller、稳定双模式交付、转化回调与原结果查询，原业务事件自身费用不混同资格费用。详见ER-S12C2_RESULT/ER-S12_RESULT。
- C2最终验证：355钱链路/67类0失败/错误/跳过；全仓其余328项/81类0失败/错误、4个既有按需fixture跳过；最终CPA专项45项0失败/错误/跳过；显式广告4+自然5+混排5真实浏览器全部PASS。最后三套脚本实际执行了默认跳过的三个浏览器宿主。

## 已修改文件

- 当前原目录唯一worktree：`/Users/liruijun/personal/LLM/recsys`；当前分支`feat/sandbox-billing-reconciliation`。HEAD、main、origin/main均为`e7838776a0b09904a0d4702589639534901d27ee`。S13A实现基线b8cf737b；不要重复提交或重复合并S13A。
- S13A共6源文件，清单`/tmp/recsys-s13a-source-paths.json`：BillingReconciliation、ReconciliationRepository/Service/Configuration、V016、ReconciliationPostgresTest。
- 最终源指纹`9f3d8e1753aa89dd580a5860c416432e14c7c5a38d1251c40dea40321059a2bb`，验证后未改源码。
- 同步C13_DESIGN、CONTRACTS、BACKEND_ARCHITECTURE、TECH_SELECTION、IMPLEMENTATION_SLICES、SANDBOX_BUDGET_RUNBOOK、ER-S13A_RESULT、S12C2实际交付证据及本文件。

## 未完成

- S13A本地DONE/PASS及Git交付已完成；远程CI尚未结束。Run `38708139517`，对应源码e7838776；交接时security-scan=SUCCESS，build、money-chain-gate、natural-exposure-gate、sandbox-exposure-browser-gate均IN_PROGRESS，不能记为CI全绿。链接：https://github.com/lirji/recsys/actions/runs/38708139517
- 父S13仍IN_PROGRESS；S13B/S13C均TODO。暂停前仅探索B方案，未修改B代码、未正式冻结C13B/C13C契约；B持久化任务、C受控续办/RPC/offline均须完成。
- 原S14～S25继续；S15必须同时包含自然Outbox与旧BehaviorService可靠事件，未跟踪C15A草案不替代原范围。

## 当前问题

- 无当前实现阻塞。S13A真实PG 11项和完整资金链路366项/68类全部0失败/错误/跳过；日志`/tmp/recsys-s13a-postgres-final.log`、`/tmp/recsys-s13a-money.log`。
- Hygiene COMPLETE_WITH_LIMITATIONS，无阻塞；统一formatter及独立Java静态分析未配置，审查细节见ER-S13A_RESULT。
- 7项用户文件指纹`/tmp/recsys-s11b-protected.json`全部不变。保留docker/docker-compose.yml、docs/08-本地运行.md、docs/09-权限接入auth-platform.md、docs/skills/19-工程化-容器编排与可观测.md、scripts/dev-local.sh、PROGRESS.md、C15A_DESIGN.md；不stage。
- console/test-results和忽略env/数据/模型/venv/构建产物保留，无清理授权，无额外worktree。

## 下一步建议

1. 用户明确恢复后，先读本文件、IMPLEMENTATION_SLICES、C13_DESIGN和原PLAN，核对当前分支/工作树。执行`gh run view 38708139517 --json headSha,status,conclusion,jobs,url`，记录S13A实际CI结论；有失败先看日志处理，不重做已交付S13A。
2. 原目录复用任务分支；先将下方B/C探索方案转为正式C13_DESIGN、CONTRACTS、TECH_SELECTION、IMPLEMENTATION_SLICES中的单片范围，再按backend-implementation实施S13B；独立验证、进度、Git交付之后继续C13C。临时笔记不是正式契约，关键内容已保存在本交接中，不依赖/tmp留存。
3. 继续原S14～S25，不因尚未定的生产参数停止独立sandbox工作。

## 重要上下文

- 已批准：SANDBOX、共享硬预算、CNY六位HALF_UP、Asia/Shanghai原预占预算日；跨日不改日；预算不得低于held+charged。图文50%连续1秒、hidden重置；自然natural-text-v1=5000/1000/300000。
- Q-03 CPC和Q-04 CPA已批准统一签发起5分钟、资格最多一次收费、先点击/转化持久化等可见、窗口外/释放后非计费。Q-04本轮用户回答“按照你的建议来”，已落PLAN/CONTRACTS，不再询问。
- CPA资格purpose=conversion-qualification，quoteId=qualificationId；业务键(SANDBOX,advertiserId,eventId UUID)，occurredAt UTC至微秒。actor来自当前授权，原actor只审计不参与内容幂等摘要。
- ad资金事务保持quote→命令唯一键→原预算日账户，持齐锁后读DB时间，双条件与ledger/Outbox/结果一起提交。额外事件DUPLICATE_QUALIFICATION自身false/null；原WAITING可随资格捕获显示自身实收但原status不变。
- 新能力默认关闭；停新增资格用conversion-preparation-admission-enabled，停新条件用conversion-receipt-admission-enabled，保留owner查询/到期与事件排空。三族relay/projection独立开关。
- CPA RPC展示组只接受独立engine，业务组只接受独立advertiser；HTTP每次强制edit/fullyConsistent，disabled/shadow/ADMIN不能绕过，依赖失败503、deny403、匿名401。内部caller沿现有受控凭据，不宣称共享凭据泄露后的强隔离。
- C2真实HTTP采用MockMvc+真实Controller/Authz/Client，TCP与PG真实；权限SDK/候选/实验替身不证明真实SpiceDB或JWT签发。测试只能使用独占PG/Kafka，不改共享dev_infra。
- S13拟沿ad owner聚合三类账户日权威/投影；offline通过契约驱动。快照必须一致、零元仍对账笔数、缺依赖不转0；任务检查点/fence/重放审计，不能改账本或跨服务写库。细节先正式冻结，不直接把临时笔记当代码规格。

## S13B/C恢复笔记（探索方案，尚未正式冻结）

- S13B建议：单广告主、显式起止原预算日，最多31天作为技术批次界限，非业务保留期；每advance只处理一天，复用A的只读RR快照。每日报告按(taskId,budgetDate)保存且不可变；各日observedAt不同，不宣称整个任务是同一快照。冻结日期范围，不能按MAX(UUID/自增ID)假设提交顺序。
- 考虑追加V017任务与day_result表：READY/RUNNING/COMPLETED/FAILED/CANCELLED；30秒租约、fence/version、nextDate/completedDays、attempts/availableAt/errorType。每广告主最多一个active任务，其他广告主可独立前进。每日期最多20次尝试，崩溃也计次、重启不清零；成功后推进下一日并归零，失败有上限退避和jitter，耗尽FAILED保留部分结果。COMPLETED仅表示核查完成，不表示账平。
- 提交冻结taskId/范围/原因，actor来自可信边界并保留原审计，不进入内容幂等；跨广告主拒绝。claim短事务→A独立只读快照→有效lease/fence条件下结果与检查点原子提交。测试提交前回滚、提交后丢响应恢复、过期旧执行器不能回写。取消不删已保存结果，需先明确取消命令幂等、原因审计及终态不可取消规则。B只内部创建/查询/推进/取消，不先加自动全账户调度器。
- S13C仍是原F12必需：受控原事件续发与指定账户日到期义务续办、可信RPC、`recsys-offline/.../BillingReconciliationJob.java`，真实PG/Kafka恢复后重新对账追平。
- Outbox既有replay仅DEAD。PUBLISHED但缺fact的续发必须保持原eventId/payload/ledger，冻结并审计稳定commandId/actor/reason；READY/SENDING不能抢活跃租约，坏fact/资金差额只标需人工，不能改删历史或重新收费。需正式决定最小等待窗口以免正常消费延迟造成重发风暴，重复命令返回原结果。批次不超过100，禁止自动无界修复。
- 到期held恢复必须限定广告主/原预算日；现有全局expireOne不能直接用作单账户恢复。沿quote→账户锁序、DB时间，不能释放未到期或已捕获。跨步骤失败恢复须查询真实状态，不虚报“本命令改变了N笔”的因果；稳定目标+检查点避免重复或遗漏。
- offline只通过owner契约，不直接写adDb。若新增proto/grpc依赖边，先TECH_SELECTION登记并复用项目现有BOM/版本，hygiene显式批准；独立可信offline caller。S14再接运营API/UI。新表注释完整、SQL集中仓储、默认关闭、保留期未知则不自动删记录。
- 现有可复用：ReconciliationService/Repository/Configuration、BillingOutboxRepository三族固定枚举、ConversionEventsFixture及ReconciliationPostgresTest。V016已交付，不修改旧迁移；现有表事实不可改删。内部BudgetAccount为精确BigDecimal，后续RPC必须金额字符串，不能直接序列化内部领域对象。

## 验证日志

- S13A：`/tmp/recsys-s13a-postgres-final.log`（11通过）、`/tmp/recsys-s13a-money.log`（366通过）、`/tmp/recsys-er-s13a-hygiene.json`。首轮专项仅预期Spring异常子类与当前PG驱动实际映射不符，已改为精确DataAccessResourceFailureException且额外断言PSQLException SQLSTATE=57014，最终两次验证均通过，未放宽断言。
- S13A源清单`/tmp/recsys-s13a-source-paths.json`、交付清单`/tmp/recsys-s13a-delivery-paths.json`；持久证据以已提交ER-S13A_RESULT及Git diff为准，临时日志丢失不等于可伪造测试结果。

`/tmp/recsys-s12c2-{money,nonmoney,targeted,browser-ad,browser-natural,browser-feed}.log`、`/tmp/recsys-er-s12c2-hygiene.json`。首次窄测试还有persistence/online-final/authz日志。最终专项在hygiene修正后执行，最终源指纹见上。

## 本次暂停的工作树说明

- 用户要求先写交接，本轮只更新本文件，不继续实现、测试、提交或触发新CI；本文件作为未提交交接保留，下次交付可纳入显式路径。本次暂停不是因缺业务授权或失败门禁。
- S13A所有实现及验收文件已在e7838776交付；没有未提交的S13B源码。ER-S13A_RESULT中的交付前措辞尚未追加实际SHA，恢复后补同步即可，以本文件和Git事实为准。
- 暂停前的本地资金回归已经结束且BUILD SUCCESS；没有本轮启动的本地构建待等待。远程CI仍按GitHub Actions自行运行，未取消，也不在暂停期间持续监控。
- 保留五项已有tracked修改、两项用户未跟踪文档以及console/test-results；不stash、不clean、不强制覆盖。其他事项若改动仓库，恢复时重新区分归属，不把其他任务改动带入S13。

## 恢复 Prompt

请读取`CODEX_PROGRESS.md`，恢复企业可靠性原计划。先核对e7838776的CI Run 38708139517与当前Git事实，S13A已正常提交/合并/push，不要重复交付；从尚未冻结契约的S13B继续，完成B/C后推进S14～S25。CPA五分钟/每资格一次收费/先转化等待/迟到非计费已批准，不再询问。保留用户文件及其他任务新增改动，复用合适的既有任务分支，不重开全部规划，不要求反复继续，不启用真实收费或生产部署。

## 用户文件保护指纹（SHA-256）

以下为暂停时确认未变的原用户文件；若其他任务随后有意修改，以新的实际归属为准，不能用旧指纹强制还原。

- `docker/docker-compose.yml`：`800063e913c5fe67adf6c9c24b909e96752d9274918da437abed6175d818fae1`
- `docs/08-本地运行.md`：`0f2f36eb5bdb3899cd612b269da5e616f254d3ed72ddfda8da91e5a5b996a997`
- `docs/09-权限接入auth-platform.md`：`25453e7383c2b16daabd4332eaf6bf1c7502e5058242525b18d0c06a78b89f3b`
- `docs/skills/19-工程化-容器编排与可观测.md`：`ccce0eced1452a255e078d89e0c65fa18214e750b5c07cd8e2ee65cabb25d43f`
- `scripts/dev-local.sh`：`353ce585f75e44e49b18b52dcde4cf616b0fa06d1b102b76a73e71060d9d7133`
- `PROGRESS.md`：`c773919843507060f06e27dd5e27ae18415b45f4f1c1d36f9e32a33a197a3ee7`
- `docs/design/enterprise-reliability/C15A_DESIGN.md`：`9a09d948ae60bd1f7bd2178bccb5fdcef4eb4338c51135f42d7a303a97ca98c1`
