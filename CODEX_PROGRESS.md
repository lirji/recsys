# Codex Progress

## 任务目标

连续实施“曝光可信、钱算对、事件不漏、故障可恢复、效果可验证”计划，按切片验证并正常 Git 交付。用户已明确“开始实施吧 / go on”；首个闭环仅使用 sandbox，不切换真实账户、不部署生产。

## 已完成

- 首版政策已确认：sandbox；CNY 六位小数、每次收费 HALF_UP；Asia/Shanghai 预算日，跨日义务归预占日，调低预算不得低于消费加预占；广告 CPM 图文 50% 连续可见 1 秒，hidden 重置，资格/预占 5 分钟且服务端到期前接受回执。
- 评估与计划、五类前端只读角色分析、独立草案审查已落盘；文档提交 `9bcb18e5`、`10ecfb01`、`eca38650` 已在 main，无需重做总体规划。
- ER-S01 DONE，提交 `9cad79fc` 已合并推送 main：修正旧预算分/元，未知不伪零，报价按自身单位展示。21 项后端、9 项前端、2 项浏览器测试通过，截图已查看；见 ER-S01_RESULT。
- ER-S02 DONE，提交 `f05eb60b` 已合并推送 main：账户/幂等命令、NUMERIC 约束、明确 adDb 事务、版本迁移与 SHA 校验、billing.v1 RPC、ADMIN HTTP API 已完成。最终 43 项钱链路测试通过（新增 29，真实 PG 11），旧权限/预算展示 21 项额外回归通过；见 ER-S02_RESULT。默认关闭，不修改旧收费链路。
- ER-S02A DONE，提交 `56259727` 已合并推送 main：BiddingService 新 CPM/GSP 精确边界、V002 报价持久化、proto 快照完成；57 项钱链路通过（新增 14，真实 PG 5），旧竞价额外 17 项回归通过，见 ER-S02A_RESULT。
- ER-S03 DONE，提交 `7b6636df` 已合并推送 main：预占/查询/释放、固定原预算日、有界到期 worker、V003 和完整恢复说明。74 项钱链路通过（本片真实 PG 12 + worker 5），最终窄回归 17 项通过；见 ER-S03_RESULT。
- ER-S04 DONE / PASS，提交 `9c95f2dc` 已合并推送 main：纯候选、签名准备、engine 整次持久化恢复、双模式 RPC/ADMIN API、ad V004 + engine V001。103 项钱链路与 25 项审查后窄回归通过（本片新增 29，PG 14）；见 ER-S04_RESULT。
- ER-S05 DONE / PASS，提交 `7834ad3b` 已合并推送 main：签名可见回执、原子捕获/账本/Outbox、RPC/HTTP 与 gateway 权限路由完成；126 项钱链路通过（新增 23，真实 PG 13），见 ER-S05_RESULT。仅持久化事件，S06 继续可靠投递。
- ER-S06 DONE / PASS，提交 `3955e29f` 已合并推送 main：持久化租约/fence/重试/审计重放、broker 确认发送、逐账本 Inbox/投影与手动位点完成；144 项钱链路通过（新增 18，PG 9、Kafka+PG 4），见 ER-S06_RESULT。
- ER-S07 DONE / PASS，提交 `8b84b4684dbe0c6daad6178bc7a1ce67b4c11690` 已合并推送 main：81 项前端单测、4 项新浏览器 + 真实 PG/Kafka 闭环、3 项旧页面浏览器回归；八张截图已查看；见 ER-S07_RESULT。修正 OIDC 刷新误清队列、pagehide 时序与窄屏溢出。
- 已核验 S06 CI `38668818550` 成功；S05 CI run `38667200021` 成功；S04 CI run `38662648548` 成功；S03 CI run `38660364391` 成功；S01/S02/S02A 的 GitHub Actions 也均成功；S02A run `38659158459`、S02 run `38658267901`、S01 run `38655809696`。
- S07 补丁 `b9a6d8dc` 已推送 main；CI 38674973337（build、money-chain、sandbox browser、security）和 console-ci 38674973227 均 SUCCESS。
- ER-S08 / ER-S08A DONE / PASS：自然资格 nq1 签名/固定 wire golden、engine 原子交付、behavior 幂等可见回执/唯一事实/Outbox、双路由和配置默认关闭。50 项专项（23 项真实 PG）+ 12 项旧回归通过，见 ER-S08_RESULT；用户已确认自然 natural-text-v1 政策，C08-C 已记录。整个计划仍未完成。
- 运行说明分别写入 SANDBOX_BUDGET_RUNBOOK 与 NATURAL_EXPOSURE_RUNBOOK；只操作过临时 Testcontainers PG/Kafka，没有迁移共享数据库。

## 已修改文件

ER-S08A 本片：

- `recsys-common/.../exposure/`：资格、签名、回执/事件及固定 wire golden。
- `recsys-rec-engine/.../exposure/`：自然交付模型、短事务租约/冻结、服务/HTTP/配置；RecommendOrchestrator 纯候选分支；V002 新迁移。
- `recsys-behavior/.../exposure/`：原子自然回执、HTTP/配置、V001 自有迁移；默认关闭。
- 新 common/engine/behavior/gateway 测试、网关两模式专属路由、CI natural-exposure-gate。
- C08-A/B、切片子任务 ER-S08A、C08_DESIGN、ER-S08_RESULT、NATURAL_EXPOSURE_RUNBOOK、S07 CI 结果更新。
- 六个用户文件与已有未跟踪/忽略产物不纳入提交、不清理。

## 未完成

- 当前 `feat/natural-exposure-receipts`，基线 `b9a6d8dc`；S07 主提交 `8b84b468` 及可访问性修复 `b9a6d8dc` 均已正常合并推送 main。S08A 不依赖政策数值的机制已验证，当前待完成 Git 交付；C08-C 已确认，待 Git 后进入 S09。不要重复实施 S01～S07。
- ER-D0/CPM 的交付、资格、回执和事件契约逐片冻结；ER-S09～ER-S25 尚未完成。依赖及验收以 IMPLEMENTATION_SLICES / PLAN 为准。
- ER-S02～ER-S07 的最小 sandbox 曝光资金闭环已完成；继续自然行为、CPC/CPA、可靠事件、恢复和效果门禁。
- CPA 归因/迟到窗口、正式来源与期初账务、容量和恢复目标仍待确定；不阻碍无关切片，不重复询问已确定 CPM 政策。

## 当前问题

- 历史响应即曝光/扣费、Redis 预算、尽力发送等风险仍在旧链路；新预算入口默认关闭，未声称整体整改完成。
- 根 Maven 未配置 formatter/static-analysis；Code Hygiene 为 IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS（无 formatter），保留现有风格，不新建工具链。
- 新预算入口需要显式可信 advertiser 服务身份，旧通用身份 `recsys` 无权调用；详细配置见 runbook。共享签名方案未升级成独立服务密钥。
- 六个用户既有文件须原样保留、不纳入提交：`docker/docker-compose.yml`、`docs/08-本地运行.md`、`docs/09-权限接入auth-platform.md`、`docs/skills/19-工程化-容器编排与可观测.md`、`scripts/dev-local.sh`、未跟踪 `PROGRESS.md`。已有哈希保护通过。
- `console/test-results/` 为可重建的未跟踪测试产物，S07 初次运行使用默认输出目录并更新了该目录；最终 S07/旧页面证据已改存 `recsys-rec-engine/target/browser-evidence` / `legacy-browser-evidence`，不纳入源码提交。忽略的本地配置、数据、模型和构建目录不清理。只有原项目工作目录，无新增 worktree。

## 下一步建议

1. 当前 `feat/natural-exposure-receipts` 的 S08A 机制验证已通过；完成文档/用户文件哈希检查，限定本任务路径提交，正常合并推送 main，核对远程 CI。不要夹带六个用户文件。
2. 自然图文已确认沿用 50%/1s/hidden 重置/5min，C08-C 已冻结并通过正式政策真实 PG 验收，不再重复问。Git 后继续 S09，复用 S07 测量/队列边界接自然推荐/搜索页面。
3. S09 按既有前端规划核对 API/队列/页面及隔离浏览器联调；随后 S10 接 feed。真实收费及生产部署保持关闭。

S06 恢复要点：

- V006 不改 V001～V005；Outbox READY/SENDING/PUBLISHED/DEAD，单行领取提交后再发 Kafka，ACK 后 fence/有效租约标记。每笔最多 20 次，租约 30 秒，退避最多 60 秒；DEAD 原事件审计重放。
- Inbox/事实和日汇总同库提交后提交单条位点。按 eventId 且已知字段摘要去重，不用账户最大版本。并发多唯一键冲突必须 ON CONFLICT DO NOTHING 后再验证原 eventId/负载，不能只以 event_id 为冲突目标。
- consumer 普通失败最多三次，错 schema 立即停止并保留位点；修复后重启。低基数指标与 topic/partition/offset 日志可排查；没有静默 skip。
- events.relay-enabled/projection-enabled 默认 false，需 sandbox/owner；不依赖 receipts-enabled，停新回执仍可续发。客户端沿用宿主 Spring Kafka 3.1.4 / Kafka 3.6.2 BOM，强制可靠性参数；隔离测试 broker 与 dev_infra recsys 一致为 Apache 3.7.1。
- 日志 `/tmp/recsys-er-s06-final.log`（144）与 `/tmp/recsys-er-s06-concurrency-fix.log`（9）。三个提交窗口、暂停测试 broker、消费者重建均有真实 PG/Kafka 证据；未做整个 JVM kill 或生产副本容灾。
- Code Hygiene 复用依赖须传 --approved-dependency spring-kafka；无 formatter 限制保留。S04/S05 身份/签名/资金协议见对应 RESULT 与 CONTRACTS。

## 恢复 Prompt

请读取 `CODEX_PROGRESS.md`、`docs/design/enterprise-reliability/IMPLEMENTATION_SLICES.md` 与 `CONTRACTS.md`，从未完成片继续。实施已获授权，sandbox、金额/时区、硬预算和图文曝光政策已确认，不重复询问。保留既有用户改动，逐片验证、更新进度并正常 Git 交付；不因完成一个切片就停下，不将整体计划宣称完成，不执行真实收费切换或生产部署。

## S08 恢复检查点

- 任务分支 `feat/natural-exposure-receipts`，基线 `b9a6d8dc`；C08-A/B/C 已冻结并实现，ER-S08 与子片 ER-S08A DONE。用户明确自然推荐/搜索沿用 50%/1s/hidden 重置/5min，稳定版本 natural-text-v1；正式装配要求完整显式元组相等，拒绝测试政策和漂移。
- 最终 /tmp/recsys-er-s08-approved-policy.log：50 项专项全部 PASS（common 10、gateway 2、engine 18、behavior 20；真实 PG 9+14），包含正式政策签发五分钟与临界接受/阈值不足/hidden 拒绝；12 项旧回归 PASS（/tmp/recsys-er-s08-legacy.log）。底层测试政策 6000/1200/120000 仅 test-only，正式装配只能使用已批准元组。
- engine 原请求/政策→30s DB lease/fence→纯候选/内容→最终去重位置/资格→完整冻结提交；默认关闭，不写旧曝光。真实 PG 已证原结果恢复、丢 ACK、旧 fence 拒写、不同操作不复用资格。
- behavior receipt 唯一键→qualification guard 锁→锁后 DB 时间→唯一自然事实+Outbox+原决定，REQUIRES_NEW/READ_COMMITTED/5s；锁4s、statement5s；延迟完整性/FK/不可变约束。真实 PG 已证 4 写点回滚、同/异 ID 并发一事实一事件、锁等待过期拒绝、重建和 key/政策/开关变更后原结果恢复。
- Outbox 仅可靠落表，S15 才发布；当前禁止改 payload/published_at，未来版本迁移演进。engine V002 与 behavior V001 均未迁移共享库，独立 owner，完整表/字段中文注释与 SHA 校验。
- HTTP 异常响应曾在无 Accept 时协商为 XML，已显式 JSON 并保留断言；两端自身 ADMIN 校验和 strict JSON，大 ID 十进制字符串。gateway 两模式 natural-impressions 路由先于 engine 通配。
- Code Hygiene 只有根 Maven 未配 formatter 的限制；事务与资源数值 advisory 已源码+真实数据库复核。没有新依赖、没有跨服务表写入，没有 S08 浏览器/跨进程联调证据（S09 再做）。运行说明与限制已写 NATURAL_EXPOSURE_RUNBOOK / ER-S08_RESULT。
