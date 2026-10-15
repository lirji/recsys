# Codex Progress

## 任务目标

连续完成docs/design/enterprise-reliability/PLAN.md原企业可靠性DAG S01～S25，逐片实现、验证并按用户持续授权正常提交/合并/push origin/main。原总体计划与五类前端规划已批准，不重开全部规划、不完成一片就停；仅sandbox，不启用生产收费或生产部署。

## 已完成

- S01～S10已交付，细节见各ER-Sxx_RESULT；S10=b0b19432。
- S11完整CPC闭环：A365595e2、Bb2c2911a、C1e599b3e5、C2f914f571；旧浏览器fixture名称修复6904889c，继承CI38699749896全部SUCCESS，原失败已关闭。
- S12完整CPA闭环已DONE/PASS：A2f5c3dab、B2d107e28、C1f23ca416；三批CI分别38699749896、38701260200、38702085570全部五门禁SUCCESS。
- S12C2已交付b8cf737b，CI38705469195五门禁全部SUCCESS：严格广告主edit/fullyConsistent、独立engine/advertiser caller、稳定双模式交付、转化回调与原结果查询，原业务事件自身费用不混同资格费用。详见ER-S12C2_RESULT/ER-S12_RESULT。
- C2最终验证：355钱链路/67类0失败/错误/跳过；全仓其余328项/81类0失败/错误、4个既有按需fixture跳过；最终CPA专项45项0失败/错误/跳过；显式广告4+自然5+混排5真实浏览器全部PASS。最后三套脚本实际执行了默认跳过的三个浏览器宿主。

## 已修改文件

- 当前原目录唯一worktree，分支feat/sandbox-billing-reconciliation，S13A基线b8cf737b。
- S13A共6源文件，清单`/tmp/recsys-s13a-source-paths.json`：BillingReconciliation、ReconciliationRepository/Service/Configuration、V016、ReconciliationPostgresTest。
- 最终源指纹`9f3d8e1753aa89dd580a5860c416432e14c7c5a38d1251c40dea40321059a2bb`，验证后未改源码。
- 同步C13_DESIGN、CONTRACTS、BACKEND_ARCHITECTURE、TECH_SELECTION、IMPLEMENTATION_SLICES、SANDBOX_BUDGET_RUNBOOK、ER-S13A_RESULT、S12C2实际交付证据及本文件。

## 未完成

- S13A DONE/PASS，待当前Git交付与远程CI核查；父S13仍IN_PROGRESS。B持久化任务、C受控续办/RPC/offline均须完成。
- 原S14～S25继续；S15必须同时包含自然Outbox与旧BehaviorService可靠事件，未跟踪C15A草案不替代原范围。

## 当前问题

- 无当前实现阻塞。S13A真实PG 11项和完整资金链路366项/68类全部0失败/错误/跳过；日志`/tmp/recsys-s13a-postgres-final.log`、`/tmp/recsys-s13a-money.log`。
- Hygiene COMPLETE_WITH_LIMITATIONS，无阻塞；统一formatter及独立Java静态分析未配置，审查细节见ER-S13A_RESULT。
- 7项用户文件指纹`/tmp/recsys-s11b-protected.json`全部不变。保留docker/docker-compose.yml、docs/08-本地运行.md、docs/09-权限接入auth-platform.md、docs/skills/19-工程化-容器编排与可观测.md、scripts/dev-local.sh、PROGRESS.md、C15A_DESIGN.md；不stage。
- console/test-results和忽略env/数据/模型/venv/构建产物保留，无清理授权，无额外worktree。

## 下一步建议

1. 核对Git事实，完成S13A显式路径提交、ff合并、push main与CI记录；不得重复提交已交付内容。
2. 细化并冻结C13B持久化只读任务、检查点/fence、取消/重试/审计，随后实现；继续C13C受控续办与offline。临时笔记`/tmp/recsys-c13b-notes.md`不是正式契约。
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

## 验证日志

`/tmp/recsys-s12c2-{money,nonmoney,targeted,browser-ad,browser-natural,browser-feed}.log`、`/tmp/recsys-er-s12c2-hygiene.json`。首次窄测试还有persistence/online-final/authz日志。最终专项在hygiene修正后执行，最终源指纹见上。

## 恢复 Prompt

读取CODEX_PROGRESS.md、IMPLEMENTATION_SLICES与原PLAN，从真实Git状态继续。S12已全部交付且CI38705469195全绿；S13A本地验证已PASS，先核对其Git交付，继而完成B/C与S14～S25。CPA五分钟/一次收费/先转化等待/迟到非计费已批准，不再询问。保护七项用户文件和本地数据，不重开全部规划，不等待重复继续，不启用真实收费或生产部署。
