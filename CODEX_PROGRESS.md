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
- ER-S05 DONE / PASS，提交 `7834ad3b` 已合并推送 main：签名可见回执、原子捕获/账本/Outbox、RPC/HTTP 与 gateway 权限路由完成；126 项钱链路通过（新增 23，真实 PG 13），见 ER-S05_RESULT。仅持久化事件，S06 继续可靠投递。
- ER-S06 DONE / PASS：持久化租约/fence/重试/审计重放、broker 确认发送、逐账本 Inbox/投影与手动位点完成；144 项钱链路通过（新增 18，PG 9、Kafka+PG 4），见 ER-S06_RESULT。
- 已核验 S05 CI run `38667200021` 成功；S04 CI run `38662648548` 成功；S03 CI run `38660364391` 成功；S01/S02/S02A 的 GitHub Actions 也均成功；S02A run `38659158459`、S02 run `38658267901`、S01 run `38655809696`。
- 迁移说明、配置、查询未知结果与回退方式写入 SANDBOX_BUDGET_RUNBOOK；只操作过临时 Testcontainers PG，没有迁移共享数据库。

## 已修改文件

ER-S06 本片：

- ad 的 BillingEventCodec/Configuration、OutboxRepository/Relay/Job、KafkaPublisher、ProjectionRepository/Service/Consumer；V006。
- ad POM 显式复用宿主 spring-kafka/BOM；CI 钱链路覆盖 engine/serving/advertiser/gateway。
- 四类新测试及隔离 fixture（18 项执行），C06、TECH_SELECTION、IMPLEMENTATION_SLICES、runbook、ER-S06_RESULT 与本进度。
- V001～V005 未修改。用户已有六文件与 console/test-results 原样保留。

## 未完成

- 当前分支 `feat/sandbox-billing-events`，基线 `7834ad3b`；S05 已交付，S06 已验证并完成文档，正在 Git 交付。不要重复实施 S01～S06。
- ER-D0/CPM 的交付、资格、回执和事件契约逐片冻结；ER-S07～ER-S25 尚未完成。依赖及验收以 IMPLEMENTATION_SLICES / PLAN 为准。
- 优先形成 ER-S02～ER-S07 的最小 sandbox 曝光资金闭环，再推进自然行为、CPC/CPA、可靠事件、恢复和效果门禁。
- CPA 归因/迟到窗口、正式来源与期初账务、容量和恢复目标仍待确定；不阻碍无关切片，不重复询问已确定 CPM 政策。

## 当前问题

- 历史响应即曝光/扣费、Redis 预算、尽力发送等风险仍在旧链路；新预算入口默认关闭，未声称整体整改完成。
- 根 Maven 未配置 formatter/static-analysis；Code Hygiene 为 IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS（无 formatter），保留现有风格，不新建工具链。
- 新预算入口需要显式可信 advertiser 服务身份，旧通用身份 `recsys` 无权调用；详细配置见 runbook。共享签名方案未升级成独立服务密钥。
- 六个用户既有文件须原样保留、不纳入提交：`docker/docker-compose.yml`、`docs/08-本地运行.md`、`docs/09-权限接入auth-platform.md`、`docs/skills/19-工程化-容器编排与可观测.md`、`scripts/dev-local.sh`、未跟踪 `PROGRESS.md`。已有哈希保护通过。
- `console/test-results/` 为 S01 可重建的未跟踪截图/测试产物，不纳入源码提交，暂保留作为本地观察证据。忽略的本地配置、数据、模型和构建目录不清理。只有原项目工作目录，无新增 worktree。

## 下一步建议

1. 核对 S06 Git 交付与远程 CI，按授权提交/正常合并推送；不因子片完成等待用户。
2. 推进 S07：复用已评审的前端 PLAN（五类只读角色和独立审查已完成），冻结 C07 的页面/队列细节，接入真实 C04/C05 API。
3. 完成浏览器阈值、hidden、重复/未知恢复、主体隔离、历史脱敏与真实 PG/Kafka 后端联调；然后继续其余切片。真实收费和生产部署保持关闭。

S06 恢复要点：

- V006 不改 V001～V005；Outbox READY/SENDING/PUBLISHED/DEAD，单行领取提交后再发 Kafka，ACK 后 fence/有效租约标记。每笔最多 20 次，租约 30 秒，退避最多 60 秒；DEAD 原事件审计重放。
- Inbox/事实和日汇总同库提交后提交单条位点。按 eventId 且已知字段摘要去重，不用账户最大版本。并发多唯一键冲突必须 ON CONFLICT DO NOTHING 后再验证原 eventId/负载，不能只以 event_id 为冲突目标。
- consumer 普通失败最多三次，错 schema 立即停止并保留位点；修复后重启。低基数指标与 topic/partition/offset 日志可排查；没有静默 skip。
- events.relay-enabled/projection-enabled 默认 false，需 sandbox/owner；不依赖 receipts-enabled，停新回执仍可续发。客户端沿用宿主 Spring Kafka 3.1.4 / Kafka 3.6.2 BOM，强制可靠性参数；隔离测试 broker 与 dev_infra recsys 一致为 Apache 3.7.1。
- 日志 `/tmp/recsys-er-s06-final.log`（144）与 `/tmp/recsys-er-s06-concurrency-fix.log`（9）。三个提交窗口、暂停测试 broker、消费者重建均有真实 PG/Kafka 证据；未做整个 JVM kill 或生产副本容灾。
- Code Hygiene 复用依赖须传 --approved-dependency spring-kafka；无 formatter 限制保留。S04/S05 身份/签名/资金协议见对应 RESULT 与 CONTRACTS。

## 恢复 Prompt

请读取 `CODEX_PROGRESS.md`、`docs/design/enterprise-reliability/IMPLEMENTATION_SLICES.md` 与 `CONTRACTS.md`，从未完成片继续。实施已获授权，sandbox、金额/时区、硬预算和图文曝光政策已确认，不重复询问。保留既有用户改动，逐片验证、更新进度并正常 Git 交付；不因完成一个切片就停下，不将整体计划宣称完成，不执行真实收费切换或生产部署。
