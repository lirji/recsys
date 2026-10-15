# Codex Progress

## 任务目标

连续实施“曝光可信、钱算对、事件不漏、故障可恢复、效果可验证”计划，按切片验证并正常 Git 交付。用户已明确“开始实施吧 / go on”；首个闭环仅使用 sandbox，不切换真实账户、不部署生产。

## 已完成

- 首版政策已确认：sandbox；CNY 六位小数、每次收费 HALF_UP；Asia/Shanghai 预算日，跨日义务归预占日，调低预算不得低于消费加预占；图文 50% 连续可见 1 秒，hidden 重置，资格/预占 5 分钟且服务端到期前接受回执。
- 评估与计划、五类前端只读角色分析、独立草案审查已落盘；文档提交 `9bcb18e5`、`10ecfb01`、`eca38650` 已在 main，无需重做总体规划。
- ER-S01 DONE，提交 `9cad79fc` 已合并推送 main：修正旧预算分/元，未知不伪零，报价按自身单位展示。21 项后端、9 项前端、2 项浏览器测试通过，截图已查看；见 ER-S01_RESULT。
- ER-S02 DONE，提交 `f05eb60b` 已合并推送 main：账户/幂等命令、NUMERIC 约束、明确 adDb 事务、版本迁移与 SHA 校验、billing.v1 RPC、ADMIN HTTP API 已完成。最终 43 项钱链路测试通过（新增 29，真实 PG 11），旧权限/预算展示 21 项额外回归通过；见 ER-S02_RESULT。默认关闭，不修改旧收费链路。
- ER-S02A DONE，提交 `56259727` 已合并推送 main：BiddingService 新 CPM/GSP 精确边界、V002 报价持久化、proto 快照完成；57 项钱链路通过（新增 14，真实 PG 5），旧竞价额外 17 项回归通过，见 ER-S02A_RESULT。
- ER-S03 DONE，提交 `7b6636df` 已合并推送 main：预占/查询/释放、固定原预算日、有界到期 worker、V003 和完整恢复说明。74 项钱链路通过（本片真实 PG 12 + worker 5），最终窄回归 17 项通过；见 ER-S03_RESULT。
- ER-S04 DONE / PASS，提交 `9c95f2dc` 已合并推送 main：纯候选、签名准备、engine 整次持久化恢复、双模式 RPC/ADMIN API、ad V004 + engine V001。103 项钱链路与 25 项审查后窄回归通过（本片新增 29，PG 14）；见 ER-S04_RESULT。
- ER-S05 DONE / PASS：签名可见回执、原子捕获/账本/Outbox、RPC/HTTP 与 gateway 权限路由完成；126 项钱链路通过（新增 23，真实 PG 13），见 ER-S05_RESULT。仅持久化事件，S06 继续可靠投递。
- 已核验 S04 CI run `38662648548` 成功；S03 CI run `38660364391` 成功；S01/S02/S02A 的 GitHub Actions 也均成功；S02A run `38659158459`、S02 run `38658267901`、S01 run `38655809696`。
- 迁移说明、配置、查询未知结果与回退方式写入 SANDBOX_BUDGET_RUNBOOK；只操作过临时 Testcontainers PG，没有迁移共享数据库。

## 已修改文件

ER-S05 本片：

- ad-common ImpressionReceipt 与 CAPTURED 状态；ad ImpressionReceiptService/Repository/Configuration、ReservationRepository 捕获；不可变 V005。
- impression.proto / Mapper、ad-serving RPC；engine 两种 gateway 与 ADMIN 回执 Controller；gateway 的 sandbox 路由及 ADMIN 权限。
- 七个新测试类（23 项），C05、TECH_SELECTION、IMPLEMENTATION_SLICES、runbook、ER-S05_RESULT 与本进度。
- V001～V004 未修改。用户已有六文件与 console/test-results 原样保留。

## 未完成

- 当前分支 `feat/sandbox-visible-receipt`，基线 `9c95f2dc`；S04 已交付，S05 验证与文档完成，正在执行 Git 交付。不要重复实施 S01～S05。
- ER-D0/CPM 的交付、资格、回执和事件契约逐片冻结；ER-S06～ER-S25 尚未完成。依赖及验收以 IMPLEMENTATION_SLICES / PLAN 为准。
- 优先形成 ER-S02～ER-S07 的最小 sandbox 曝光资金闭环，再推进自然行为、CPC/CPA、可靠事件、恢复和效果门禁。
- CPA 归因/迟到窗口、正式来源与期初账务、容量和恢复目标仍待确定；不阻碍无关切片，不重复询问已确定 CPM 政策。

## 当前问题

- 历史响应即曝光/扣费、Redis 预算、尽力发送等风险仍在旧链路；新预算入口默认关闭，未声称整体整改完成。
- 根 Maven 未配置 formatter/static-analysis；Code Hygiene 为 IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS（无 formatter），保留现有风格，不新建工具链。
- 新预算入口需要显式可信 advertiser 服务身份，旧通用身份 `recsys` 无权调用；详细配置见 runbook。共享签名方案未升级成独立服务密钥。
- 六个用户既有文件须原样保留、不纳入提交：`docker/docker-compose.yml`、`docs/08-本地运行.md`、`docs/09-权限接入auth-platform.md`、`docs/skills/19-工程化-容器编排与可观测.md`、`scripts/dev-local.sh`、未跟踪 `PROGRESS.md`。已有哈希保护通过。
- `console/test-results/` 为 S01 可重建的未跟踪截图/测试产物，不纳入源码提交，暂保留作为本地观察证据。忽略的本地配置、数据、模型和构建目录不清理。只有原项目工作目录，无新增 worktree。

## 下一步建议

1. 核对 S05 Git 交付与远程 CI，按授权提交/正常合并推送；不因子片完成等待用户。
2. 推进 S06：先核对当前 Kafka/消费投影，再冻结 C06，实施可重试 Outbox relay 与按每条账本事实幂等的投影。
3. 之后继续 S07 sandbox 搜索 UI 浏览器曝光回执；默认关闭真实收费与部署。

S05 恢复要点：

- 回执原结果、held→charged、reservation CAPTURED、不可变 ledger 和 Outbox 共用 adDb 单事务，无事务内 RPC/MQ。receiptId 全局幂等，qualification 唯一账本；不同 receiptId 重复曝光返回原 ledger。
- 保持 reservation→account 锁序。账户锁获得后重新读取数据库时间，必须早于原到期时刻；跨日归原预占日。
- 对比专用 HMAC 资格与持久化 preparation 原 token，再核对 quote/reservation；POLICY_NOT_MET 等拒绝也保存原结果。客户端声明不等同防作弊/行业认证。
- receipts-enabled 默认 false；临时停新增应使用 receipt-admission-enabled=false，保留已提交结果查询/重试及预占回收。V005 后不可直接回退到不识别 CAPTURED 的旧 owner。
- 仅 Outbox 落库，尚无 broker ACK/投影或浏览器验收。事件金额/业务 ID 为字符串，时间为 ISO，不携带 token/subject。
- S05 验证日志 `/tmp/recsys-er-s05-final.log`（126）；真实 PG 包含 10 并发、四处提交前故障回滚、释放竞争、锁等待过期、跨日、不可变账本与暂停恢复。RPC/HTTP 使用替身 owner，不冒充完整服务 E2E。
- hygiene 无阻塞（formatter 不可用）；事务提示已核对同源/锁序/无外部副作用并有 PG 证据。S04 原有 operation/qualification 恢复协议见 ER-S04_RESULT 和 CONTRACTS。

## 恢复 Prompt

请读取 `CODEX_PROGRESS.md`、`docs/design/enterprise-reliability/IMPLEMENTATION_SLICES.md` 与 `CONTRACTS.md`，从未完成片继续。实施已获授权，sandbox、金额/时区、硬预算和图文曝光政策已确认，不重复询问。保留既有用户改动，逐片验证、更新进度并正常 Git 交付；不因完成一个切片就停下，不将整体计划宣称完成，不执行真实收费切换或生产部署。
