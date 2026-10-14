# Codex Progress

## 任务目标

实施已记录的“曝光可信、钱算对、事件不漏、故障可恢复、效果可验证”计划。用户已明确“开始实施吧”，按切片连续推进、验证并交付；首个闭环仅使用 sandbox，不切换真实账户、不部署生产。

## 已完成

- 实施授权已生效；首版政策确认：sandbox；CNY 六位小数按次 HALF_UP；Asia/Shanghai 预算日，义务归预占日，预算不得调低到占用以下；图文 50% 连续可见 1 秒，hidden 重置，资格/预占 5 分钟。
- 已建立 CONTRACTS C01 与 IMPLEMENTATION_SLICES；ER-S01 修复预算分/元和报价展示已完成，工作分支 `fix/enterprise-budget-display`。21 项后端、9 项前端、2 项浏览器测试通过；typecheck/build/lint（0 error）通过，截图已查看，见 ER-S01_RESULT。正在正常 Git 交付后推进 ER-S02。

- 已基于真实源码记录能力与 ER-R01～ER-R15 风险，保留搜索相关性、运营域与模型平台等后续建设项。
- 已记录用户确定决策：客户端可见曝光回执 + 服务端资格/签名/幂等；硬预算上限 + 预占后计费。
- 已按 AGENTS 完成五类只读角色分析；形成后端架构、契约提案、技术取舍、分阶段候选计划与验证矩阵。
- 已创建任务分支 `feat/enterprise-reliability-plan`，原项目目录作业，无新增 worktree。
- ER-DOC-01/02：DONE（文档范围）；独立审查 3 个 high、2 个 medium 均修订闭合，DRAFT_REVIEW_PASS；本地链接、依赖 DAG、围栏、差异及用户既有文件哈希检查通过，见 [REVIEW](docs/design/enterprise-reliability/REVIEW.md)。
- 规划阶段仅交付文档；本轮实施验证以各 ER-Sxx_RESULT 为准，不将规划检查替代业务验收。
- 文档提交 `9bcb18e5`（评估）及 `10ecfb01`（计划）已由任务分支正常 fast-forward 合并并推送 `origin/main`；远程 `main` 已核验为 `10ecfb0147cbd92648b887c1a993ba739cadbdb0`。本记录后续仅同步交付事实，最终记录提交以 Git 历史为准。
- 六个既有用户文件哈希保持一致，仅本轮十个 Markdown 文件进入交付。无新增 worktree；既有忽略的配置、模型、数据、构建/缓存目录均保留，未清理。

## 已修改文件

当前实施变更：advertiser 的 Service/Repository/AdvertiserView 与预算回归测试；console 预算列表/详情、报价表/图/漏斗/重放、API 类型、金额辅助函数与单测/E2E；本目录 CONTRACTS/IMPLEMENTATION_SLICES/ER-S01_RESULT、PLAN 与本进度。此前规划交付文件如下（已在 main）：

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

- ER-S01 正常 Git 交付；随后继续 ER-S02 的预算命令、持久化和真实 PG 并发验证。
- ER-D0/CPM 的具体预算/定价/预占/回执 API 与迁移契约逐片冻结。26 个实施项未整体完成，进度以 IMPLEMENTATION_SLICES 为准。
- CPA 归因/迟到窗口、真实来源/期初账务、容量及恢复目标仍待确定；已确认的 CPM 政策不重复询问。

## 当前问题

- 用户已批准实施计划；新的高风险业务参数按正式契约冻结，例行实施/验证/Git 交付无需再次请求授权。
- 历史代码存在响应即曝光/扣费、Redis 预算、RPC 写入重试与未知结果吞错、多数据源事务、尽力消息发送等风险；本轮仅记录，没有修复。
- 既有用户改动须原样保留：`docker/docker-compose.yml`、`docs/08-本地运行.md`、`docs/09-权限接入auth-platform.md`、`docs/skills/19-工程化-容器编排与可观测.md`、`scripts/dev-local.sh`、未跟踪 `PROGRESS.md`。该 PROGRESS 属于此前本地启动任务，不与本文件合并、不纳入本次提交。

## 下一步建议

1. 从 [实施状态](docs/design/enterprise-reliability/IMPLEMENTATION_SLICES.md) 恢复当前片，不重做计划。
2. 完成 ER-S01 验证与交付；继续 ER-S02/ER-S02A，依赖满足后依次推进预占、交付、回执与 Outbox。
3. 优先完成 ER-S02～ER-S07 的 sandbox 预算/曝光最小闭环，逐片实现、真实组件验证与记录。
4. 继续 CPC/CPA、可靠事件、恢复及效果门禁；生产切换和部署需独立范围/环境授权。

## 恢复 Prompt

请读取 `CODEX_PROGRESS.md`、`docs/design/enterprise-reliability/IMPLEMENTATION_SLICES.md` 与 `CONTRACTS.md`，继续当前未完成切片。实施已获授权，首版 sandbox、金额/时区和图文曝光政策已确认，不重复询问。保留既有用户改动，逐片验证、更新进度和正常 Git 交付，不因完成一个切片就停下；不将尚未完成的整体计划宣称完成，不执行生产切换或部署。
