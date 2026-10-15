# Codex Progress

## 任务目标

连续实施“曝光可信、钱算对、事件不漏、故障可恢复、效果可验证”计划，按切片验证并正常 Git 交付。用户已明确“开始实施吧 / go on”；首个闭环仅使用 sandbox，不切换真实账户、不部署生产。

## 已完成

- 首版政策已确认：sandbox；CNY 六位小数、每次收费 HALF_UP；Asia/Shanghai 预算日，跨日义务归预占日，调低预算不得低于消费加预占；图文 50% 连续可见 1 秒，hidden 重置，资格/预占 5 分钟且服务端到期前接受回执。
- 评估与计划、五类前端只读角色分析、独立草案审查已落盘；文档提交 `9bcb18e5`、`10ecfb01`、`eca38650` 已在 main，无需重做总体规划。
- ER-S01 DONE，提交 `9cad79fc` 已合并推送 main：修正旧预算分/元，未知不伪零，报价按自身单位展示。21 项后端、9 项前端、2 项浏览器测试通过，截图已查看；见 ER-S01_RESULT。
- ER-S02 DONE，提交 `f05eb60b` 已合并推送 main：账户/幂等命令、NUMERIC 约束、明确 adDb 事务、版本迁移与 SHA 校验、billing.v1 RPC、ADMIN HTTP API 已完成。最终 43 项钱链路测试通过（新增 29，真实 PG 11），旧权限/预算展示 21 项额外回归通过；见 ER-S02_RESULT。默认关闭，不修改旧收费链路。
- ER-S02A DONE，分支 `feat/exact-billing-quote` 正在 Git 交付：BiddingService 新 CPM/GSP 精确边界、V002 报价持久化、proto 快照完成；57 项钱链路通过（新增 14，真实 PG 5），旧竞价回归通过，见 ER-S02A_RESULT。
- 迁移说明、配置、查询未知结果与回退方式写入 SANDBOX_BUDGET_RUNBOOK；只操作过临时 Testcontainers PG，没有迁移共享数据库。

## 已修改文件

ER-S02A 当前范围：BiddingService 新入口；ad-common CpmPrice/FrozenCpmQuote；ad 的 CpmQuoteRepository/Configuration、V002 及测试；BudgetRepository 复用迁移摘要函数；billing.proto 与 CpmQuoteProtoMapper；C02A、状态、runbook、ER-S02A_RESULT 和本进度。V001 未改写。此前 ER-S02 范围已交付：

- `recsys-ad-common/src/{main,test}/java/com/recsys/common/ad/billing/`：金额/账户/命令/结果契约与校验。
- `recsys-ad/src/{main,test}/java/com/recsys/ad/billing/`、`recsys-ad/pom.xml`：Repository、用例、专属事务装配及真实 PG 测试。
- `recsys-ad/src/main/resources/db/ad-billing/V001__budget_account.sql`：三个带注释的版本/账户/命令表。
- `recsys-proto/src/main/proto/billing.proto`、BudgetProtoMapper 及测试。
- `recsys-ad-serving/.../grpc/SandboxBudgetGrpcService.java` 及测试。
- `recsys-advertiser/.../billing/`：RPC adapter、HTTP 控制器及测试。
- `docs/design/enterprise-reliability/CONTRACTS.md`、TECH_SELECTION、IMPLEMENTATION_SLICES、ER-S02_RESULT、SANDBOX_BUDGET_RUNBOOK、本进度。

## 未完成

- ER-S02A 正常提交/合并/推送后记录实际 SHA，继续 ER-S03 预占；当前产品代码已通过验收，不重复实施 S01/S02/S02A。
- ER-D0/CPM 的预占、交付、资格、回执和事件契约逐片冻结；ER-S03～ER-S25 尚未完成。依赖及验收以 IMPLEMENTATION_SLICES / PLAN 为准。
- 优先形成 ER-S02～ER-S07 的最小 sandbox 曝光资金闭环，再推进自然行为、CPC/CPA、可靠事件、恢复和效果门禁。
- CPA 归因/迟到窗口、正式来源与期初账务、容量和恢复目标仍待确定；不阻碍无关切片，不重复询问已确定 CPM 政策。

## 当前问题

- 历史响应即曝光/扣费、Redis 预算、尽力发送等风险仍在旧链路；新预算入口默认关闭，未声称整体整改完成。
- 根 Maven 未配置 formatter/static-analysis；Code Hygiene 为 IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS（无 formatter），保留现有风格，不新建工具链。
- 新预算入口需要显式可信 advertiser 服务身份，旧通用身份 `recsys` 无权调用；详细配置见 runbook。共享签名方案未升级成独立服务密钥。
- 六个用户既有文件须原样保留、不纳入提交：`docker/docker-compose.yml`、`docs/08-本地运行.md`、`docs/09-权限接入auth-platform.md`、`docs/skills/19-工程化-容器编排与可观测.md`、`scripts/dev-local.sh`、未跟踪 `PROGRESS.md`。已有哈希保护通过。
- `console/test-results/` 为 S01 可重建的未跟踪截图/测试产物，不纳入源码提交，暂保留作为本地观察证据。忽略的本地配置、数据、模型和构建目录不清理。只有原项目工作目录，无新增 worktree。

## 下一步建议

1. 核实本片 Git 交付实际状态，完成必要的正常提交/合并/推送；只显式暂存本片路径，不夹带用户文件或截图。
2. 冻结 C03：只引用服务端 quoteId 的预占/查询/释放/过期回收；固定报价与预算日、账户行锁、合法状态转换和并发对账测试。
3. 依次推进预占、整个交付操作的持久化恢复、回执、账本/Outbox 与前端验收；逐片验证和记录，不要求用户再输入继续。

## 恢复 Prompt

请读取 `CODEX_PROGRESS.md`、`docs/design/enterprise-reliability/IMPLEMENTATION_SLICES.md` 与 `CONTRACTS.md`，从未完成片继续。实施已获授权，sandbox、金额/时区、硬预算和图文曝光政策已确认，不重复询问。保留既有用户改动，逐片验证、更新进度并正常 Git 交付；不因完成一个切片就停下，不将整体计划宣称完成，不执行真实收费切换或生产部署。
