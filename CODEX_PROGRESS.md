# Codex Progress

## 任务目标

记录当前项目面向企业级搜推广的优势、不足和改进讨论，形成“曝光可信、钱算对、事件不漏、故障可恢复、效果可验证”的下一阶段计划。本轮只交付文档，不实施业务功能或部署。

## 已完成

- 已基于真实源码记录能力与 ER-R01～ER-R15 风险，保留搜索相关性、运营域与模型平台等后续建设项。
- 已记录用户确定决策：客户端可见曝光回执 + 服务端资格/签名/幂等；硬预算上限 + 预占后计费。
- 已按 AGENTS 完成五类只读角色分析；形成后端架构、契约提案、技术取舍、分阶段候选计划与验证矩阵。
- 已创建任务分支 `feat/enterprise-reliability-plan`，原项目目录作业，无新增 worktree。
- ER-DOC-01/02：DONE（文档范围）；独立审查 3 个 high、2 个 medium 均修订闭合，DRAFT_REVIEW_PASS；本地链接、依赖 DAG、围栏、差异及用户既有文件哈希检查通过，见 [REVIEW](docs/design/enterprise-reliability/REVIEW.md)。
- 本轮未执行 Maven/前端构建/真实组件测试，没有业务实现、运行验收或部署完成声明。
- 文档提交 `9bcb18e5`（评估）及 `10ecfb01`（计划）已由任务分支正常 fast-forward 合并并推送 `origin/main`；远程 `main` 已核验为 `10ecfb0147cbd92648b887c1a993ba739cadbdb0`。本记录后续仅同步交付事实，最终记录提交以 Git 历史为准。
- 六个既有用户文件哈希保持一致，仅本轮十个 Markdown 文件进入交付。无新增 worktree；既有忽略的配置、模型、数据、构建/缓存目录均保留，未清理。

## 已修改文件

- `docs/12-企业级搜推广评估.md`
- `docs/design/enterprise-reliability/PLAN.md`
- `docs/design/enterprise-reliability/BACKEND_ARCHITECTURE.md`
- `docs/design/enterprise-reliability/CONTRACTS_DRAFT.md`
- `docs/design/enterprise-reliability/TECH_SELECTION.md`
- `docs/design/enterprise-reliability/VALIDATION_PLAN.md`
- `docs/design/enterprise-reliability/REVIEW.md`
- `README.md`、`docs/07-后续路线执行清单.md`（入口链接）
- `CODEX_PROGRESS.md`

## 未完成

- 本轮评估与计划文档交付已完成；后续业务实现仍待批准，远程 CI 结果未作为本轮业务验收声明。
- ER-D0 与 26 个实施候选（ER-S01～ER-S25，另有 ER-S02A）均未实施，不能标 READY/DONE；尚无已批准正式 CONTRACTS/IMPLEMENTATION_SLICES。
- 可见阈值、来源准入、预占/归因窗口、CPA 迟到、时区/跨日、币种精度、恢复/容量目标与期初账务仍按 PLAN Q-01～Q-09 待冻结。

## 当前问题

- 当前请求范围是“记录讨论并做 plan”，不是批准全部业务实现。先完成计划交付，后续按批准范围进入 ER-D0；不能把两项原则确认当作所有参数已定。
- 历史代码存在响应即曝光/扣费、Redis 预算、RPC 写入重试与未知结果吞错、多数据源事务、尽力消息发送等风险；本轮仅记录，没有修复。
- 既有用户改动须原样保留：`docker/docker-compose.yml`、`docs/08-本地运行.md`、`docs/09-权限接入auth-platform.md`、`docs/skills/19-工程化-容器编排与可观测.md`、`scripts/dev-local.sh`、未跟踪 `PROGRESS.md`。该 PROGRESS 属于此前本地启动任务，不与本文件合并、不纳入本次提交。

## 下一步建议

1. 本轮文档交付已完成，无须重新扫描或重新编写计划；先读取 [PLAN](docs/design/enterprise-reliability/PLAN.md) 和 REVIEW。
2. 后续确认首个 CPM 闭环参数并完成 ER-D0，形成正式契约及实施切片，保留候选 ID。
3. 获准实施后从 ER-S01 开始，优先推进 ER-S02～ER-S07 的预算/曝光最小闭环，逐片实现、真实组件验证与记录。
4. 继续 CPC/CPA、可靠事件、恢复及效果门禁；生产切换和部署需独立范围/环境授权。

## 恢复 Prompt

请读取 `CODEX_PROGRESS.md` 和 `docs/design/enterprise-reliability/PLAN.md`，基于其中的“未完成”和“下一步建议”继续。保留已确认的可见曝光回执、硬预算预占决定和既有用户改动。若本轮文档交付尚未完成，先完成审查、校验及正常 Git 交付；若文档已交付且我已批准实施，从 ER-D0 的待定参数与正式契约冻结开始，不重新规划全部任务，不等待我反复输入“继续”。本轮计划草案不代表业务实现已批准。
