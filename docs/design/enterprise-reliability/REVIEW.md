# 计划评审与文档验证

范围：ER-DOC-01 评估记录、ER-DOC-02 计划草案。业务实现/部署/故障演练不在本轮范围。基线 `2dca4b86b8f082d0b07d999943a5c05e7507ed86`，任务分支 `feat/enterprise-reliability-plan`。

## 只读分析输入

按 [AGENTS.md](../../../AGENTS.md) 完成五类角色分析，全部结果返回后综合；受并发槽位限制，部分角色复用已完成的 agent。未由子 agent 修改文件。

| 角色 | 分析者 | 纳入计划的关键结论 |
|---|---|---|
| requirements and user flows | exposure_requirements | 最终混排后签资格/预占；Console 与生产来源隔离；点击不能补造曝光 |
| UI/UX and interaction states | exposure_ux | 待确认/接受/拒绝/过期状态；报价和实扣分开；未知不显示零 |
| frontend architecture | exposure_front_arch | 页面 scope + hook/有界队列；纯 ItemCard 不自动打点；缓存与历史语义 |
| repository constraints and reusable components | exposure_front_arch（后续只读任务） | 复用 Axios/auth/Query/UI/测试；proto 双模式；DataSource 及 Outbox 所有权 |
| testing, risks, and edge cases | exposure_requirements（后续只读任务） | 真实组件故障矩阵；RPC 超时是未知；旧 fallback 测试需修正 |

## 独立审查

审查者：未编写主文档的 exposure_requirements。首轮无 blocker，发现 3 个 high、2 个 medium；修订后完成窄范围复审，五项均在设计层面闭合，无残留 blocker/high。

| ID / 等级 | 问题 | 已纳入的修订 |
|---|---|---|
| H1 / high | 只有单项资格幂等，整次响应重试可能重召回/重排 | engine 持久化 DeliveryOperation、条目/最终位置、子操作与响应；恢复原操作；V07 增加候选变化和部分预占成功 |
| H2 / high | 账务增量按最新版本覆盖会丢掉晚到的合法资金事实 | 区分 snapshot/delta/immutable-fact；账本按业务键逐条幂等；delta 缺口补齐；V12 增加先 v3 后 v2 |
| H3 / high | 仅改 NUMERIC 无法解决上游 double 结算价来源 | 新增 ER-S02A 与 BiddingService 定价规范化、精确输入/单位/转换/舍入版本；S03 显式依赖；V20 补定价边界 |
| M1 / medium | 错误 DataSource 测试可能暗示跨库本地事务 | V05 明确正确配置整体回滚，错误配置由门禁拒绝 |
| M2 / medium | 评估中推荐曝光方法名不准确 | ER-R01 改为实际 `exposureLogger.log(...)` |

复审结论：**DRAFT_REVIEW_PASS**，草案具备交付评审的条件。该结论不表示业务参数冻结、正式 CONTRACTS/IMPLEMENTATION_SLICES 已批准、业务实现完成或运行验收通过。

## 本轮验证状态

2026-10-15 在本任务工作树执行文档范围检查：

- 10 个任务 Markdown 文件的本地链接存在性、代码围栏闭合：PASS；首次检查覆盖 76 个本地链接。
- 候选 DAG：27 个节点（ER-D0 + 26 个实施候选），依赖存在、无环、稳定 ID 集合完整：PASS。
- 五目标、已确认决定/待决策项、前端路由/组件/状态/API/文件/依赖/验收/回滚要求：人工交叉检查及独立审查 PASS。
- `git diff --check`：PASS；提交前另核对显式暂存文件及文档新增内容。
- 六个既有修改/未跟踪文件的 SHA-256 与任务开始快照一致：PASS；未暂存、不覆盖、不清理用户内容。

没有执行 Maven、前端构建、真实组件测试或部署；本轮没有修改业务代码，这些验证属于后续实施。ER-DOC-01/02 的文档验证 PASS 不可转换为 ER-Sxx 的业务验收结果。

## 交付与工作区边界

使用原项目目录任务分支，无新增 worktree。按用户持续授权，仅对本轮文档显式 stage/commit，正常合并并推送 main，不强推、不部署。实际提交和远程结果在根目录 CODEX_PROGRESS 的交付记录及 Git 历史中核验。

已检查忽略文件：`.env`、本地覆盖配置、`.dev/`、模型/训练数据/评估产物均保留，不能混入提交或视为可丢弃垃圾；`target/`、`console/dist/`、缓存和依赖目录本轮未创建清理任务。无新增可清理 worktree；用户既有 `PROGRESS.md` 保留给此前任务。
