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
- ER-S04 DONE / PASS，待本次正常 Git 交付：纯候选、签名准备、engine 整次持久化恢复、双模式 RPC/ADMIN API、ad V004 + engine V001。103 项钱链路与 25 项审查后窄回归通过（本片新增 29，PG 14）；见 ER-S04_RESULT。
- 已核验 S03 CI run `38660364391` 成功；S01/S02/S02A 的 GitHub Actions 也均成功；S02A run `38659158459`、S02 run `38658267901`、S01 run `38655809696`。
- 迁移说明、配置、查询未知结果与回退方式写入 SANDBOX_BUDGET_RUNBOOK；只操作过临时 Testcontainers PG，没有迁移共享数据库。

## 已修改文件

ER-S04 本片：

- ad-common `delivery/SandboxDelivery`；ad `AdPipeline.sandboxCandidates`、`delivery/DeliveryTokenCodec`、billing 的 Preparation/OwnerCondition、CpmQuoteRepository 限期签发、ReservationService 同源校验；V004。
- proto `delivery.proto`、DeliveryProtoMapper；ad-serving SandboxDeliveryGrpcService。
- engine 两种 AdServingGateway；`delivery/` operation 模型、Repository/Service/Configuration、ADMIN Controller；独立 engine V001。
- 八个新测试类（29 项）；C04、TECH_SELECTION、IMPLEMENTATION_SLICES、runbook、ER-S04_RESULT 与本进度。
- V001～V003 未修改。用户已有六文件与 console/test-results 原样保留。

## 未完成

- 当前分支 `feat/sandbox-delivery-operation`，基线 `7b6636df`；S04 实现/验证/文档完成，先完成本片 Git 交付，再推进 S05。不要重复实施 S01～S04。
- ER-D0/CPM 的交付、资格、回执和事件契约逐片冻结；ER-S05～ER-S25 尚未完成。依赖及验收以 IMPLEMENTATION_SLICES / PLAN 为准。
- 优先形成 ER-S02～ER-S07 的最小 sandbox 曝光资金闭环，再推进自然行为、CPC/CPA、可靠事件、恢复和效果门禁。
- CPA 归因/迟到窗口、正式来源与期初账务、容量和恢复目标仍待确定；不阻碍无关切片，不重复询问已确定 CPM 政策。

## 当前问题

- 历史响应即曝光/扣费、Redis 预算、尽力发送等风险仍在旧链路；新预算入口默认关闭，未声称整体整改完成。
- 根 Maven 未配置 formatter/static-analysis；Code Hygiene 为 IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS（无 formatter），保留现有风格，不新建工具链。
- 新预算入口需要显式可信 advertiser 服务身份，旧通用身份 `recsys` 无权调用；详细配置见 runbook。共享签名方案未升级成独立服务密钥。
- 六个用户既有文件须原样保留、不纳入提交：`docker/docker-compose.yml`、`docs/08-本地运行.md`、`docs/09-权限接入auth-platform.md`、`docs/skills/19-工程化-容器编排与可观测.md`、`scripts/dev-local.sh`、未跟踪 `PROGRESS.md`。已有哈希保护通过。
- `console/test-results/` 为 S01 可重建的未跟踪截图/测试产物，不纳入源码提交，暂保留作为本地观察证据。忽略的本地配置、数据、模型和构建目录不清理。只有原项目工作目录，无新增 worktree。

## 下一步建议

1. 按当前显式路径交付 S04（验证已 PASS），核对提交和远程 CI；继续 S05，不因子片完成等待用户。
2. 冻结 C05：可见回执输入与原主体、签名资格/数据库准备记录、政策证据及到期、幂等业务键；同 adDb 事务内预占捕获/原结果/账本/Outbox，先与释放统一锁序。
3. 实施 S05 后继续 S06 可靠投影、S07 sandbox 搜索 UI 的浏览器曝光回执；默认关闭真实收费与部署。

S04 恢复要点：

- 整次操作位于 engine primary，ad preparation/quote/reservation 位于 adDb。两个独立迁移历史，不能跨库假事务。
- 候选阶段只读及签名；engine 先冻结最终位置和 childId，再 prepare。lease/fence 防旧写；同 ID PUT 恢复，GET 只读。异常不会转成成功空列表。
- owner prepare 先存请求，再 quote→reservation→结果，故障间隙有已提交 held 也必须沿原 ID 续办。5 分钟 expiry 不刷新，失用 held 由 worker 回收。
- qualification 首次结果不可变，不代表当前仍 HELD；S05 接受回执必须查当前 reservation/期限。quoteId/reservationId/qualification childId 同一 UUID，父 operationId+slot 唯一。
- 专用 HMAC key 环无默认值，offer/qualification 用途分离；签名绑定主体/用户/位置/广告/创意/金额/政策/期限。gRPC 必须独立 engine 身份；in-process 同 owner 用例。
- S04 验证日志 `/tmp/recsys-er-s04-final.log`（103）、`/tmp/recsys-er-s04-review-final.log`（25）；hygiene 无阻塞（formatter 不可用），新编译无依赖增量。

## 恢复 Prompt

请读取 `CODEX_PROGRESS.md`、`docs/design/enterprise-reliability/IMPLEMENTATION_SLICES.md` 与 `CONTRACTS.md`，从未完成片继续。实施已获授权，sandbox、金额/时区、硬预算和图文曝光政策已确认，不重复询问。保留既有用户改动，逐片验证、更新进度并正常 Git 交付；不因完成一个切片就停下，不将整体计划宣称完成，不执行真实收费切换或生产部署。
