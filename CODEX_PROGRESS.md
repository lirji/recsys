# Codex Progress

## 任务目标

连续实施企业搜推广“曝光可信、钱算对、事件不漏、故障可恢复、效果可验证”，依据 `docs/design/enterprise-reliability/PLAN.md` 原 DAG 逐片验证、正常 Git 合并推送 main。用户已明确开始实施/继续，不能完成一片就停止。不启用真实收费或生产部署。

## 已完成

- 用户政策：sandbox；硬预算预占；CNY 六位 HALF_UP；Asia/Shanghai 预算日，跨日义务归预占日，调低预算不得低于消费+预占。广告 CPM 与自然图文均 50% 连续可见 1 秒、hidden 重置、资格 5 分钟且服务端到期前接受。自然 `natural-text-v1` 完整元组 5000/1000/300000 已批准，不重复询问。
- 原评估、完整 PLAN、五类前端只读角色与独立规划审查已完成并获授权。覆盖四在线路由；不重开总体规划。
- S01 `9cad79fc`、S02 `f05eb60b`、S02A `56259727`、S03 `7b6636df`、S04 `9c95f2dc`、S05 `7834ad3b`、S06 `3955e29f`、S07 `8b84b468` + `b9a6d8dc` 均已合并推送 main。预算/定价/预占/交付/回执/资金 Outbox/Kafka 以及广告浏览器闭环完成；每片 RESULT 有真实证据。
- S08 / S08A `a8701bf88a02023ab0252e698ee3d2de59e1112e` 已合并推送：common 自然 nq1 签名/golden、engine 原子冻结交付、behavior 独立 PG 幂等可见回执/事实/Outbox、双路由、显式政策默认关闭。50 专项（23 真实 PG）+12 旧回归通过；详见 ER-S08_RESULT。
- S08 CI 38677748671 后端全部通过，浏览器因独占 Chromium 临时目录 ENOTEMPTY 失败。修复 `4b09d44e021d33480af798e1d485770fa033511a` 已合并推送：等 close 后有界清理自身目录；4 浏览器/PG/Kafka 验收通过，远程 CI 38678472988、console-ci 38678472943 均 SUCCESS。
- S09 DONE / PASS，提交 `e070b157` 已正常合并并推送 main。已建 `feat/sandbox-feed-exposure`；当前先在 `fix/natural-browser-reactor-package` 修复交付后 CI 打包失败。自然推荐/搜索默认关闭新分支、显式交付/原意图恢复、冻结正文可见测量、类型化自然结果；共享队列抽取，广告兼容。C09_DESIGN / CONTRACTS C09 / ER-S09_RESULT / runbook 已记录。
- S09 最终：93 前端单测，5 自然浏览器+真实 PG（唯一事实+Outbox），4 广告浏览器+PG/Kafka 资金对账，5 旧页面浏览器，50 natural-exposure 后端专项全部通过。7 张桌面/窄屏/历史/对比/空/202/错误截图已实际查看。typecheck/build/lint 0 errors、既有16 warnings，Code Hygiene 只有根级无 formatter 限制。

## 已修改文件

S09 当前范围：

- `console/src/api/naturalExposure.ts`、`api/exposure.ts`：正式 C08 类型、路径、身份守卫、完整响应校验。
- `console/src/exposure/receiptQueueCore.ts`、`receiptQueue.ts`、`naturalReceiptQueue.ts`、`identity.ts`；Scope 注入、自然状态组件。
- `SandboxNaturalConsole.tsx`、Recommend/Search 包装、AppLayout 窄屏、HistoryDrawer 可选禁用重跑、env 类型/示例。
- 新自然 API/队列单测、自然 E2E/配置、旧页面兼容 E2E。
- `recsys-behavior/pom.xml` 与 `.../exposure/NaturalExposureBrowserAcceptanceTest.java`：仅 test-scope 引用 engine、隔离真实 PG 宿主；生产依赖不变。`scripts/test-natural-exposure.py`、CI 浏览器门禁扩展。
- C09 设计/契约/实施状态、ER-S09_RESULT、ER-S08 CI 更新、自然 runbook、README/路线清单链接、此进度文件。

## 未完成

- S09 原 CI 38681250358 的自然/资金/浏览器门禁成功，但 build 的 behavior 测试编译因可执行 engine JAR 类布局失败；console-ci 38681250352 成功。当前最小 POM 修复已通过完整 clean verify（23模块、463测试/3原有跳过）、5自然浏览器/PG，待提交合并推送及远程核对。
- S10～S25 待做；下一主路径 S10 feed（deps S04/S07/S08 已满足）。按原 PLAN 冻结该片 C10，不重做总规划或要求再次授权。继续剩余切片，整体目标未完成。
- CPA 归因/迟到窗口、正式来源/期初资金、容量与恢复目标仍待后续决定；不阻碍 S10 或独立片。

## 当前问题

- 新能力默认关闭，旧响应即曝光/扣费、Redis 预算及尽力发送风险仍在旧路径；不能宣称全项目整改完成。自然 Outbox 仅落库，S15 才可靠发送；没有生产部署或真实账户切换。
- 根 Maven 无 canonical formatter；前端改动已用现有 Prettier/ESLint。Hygiene 的数值 advisory 是已批准队列/政策/测试等待边界，已复核；状态比较已复用 DeliveryState。
- 浏览器宿主仅 loopback、随机测试身份签名，两个 owner 在同 JVM、不同 schema/DataSource；候选内容读隔离 PG 种子。不冒充真实 gateway/OIDC、跨进程故障或 Kafka 自然事件投递。
- 六个用户既有文件须原样保留、不纳入提交：`docker/docker-compose.yml`、`docs/08-本地运行.md`、`docs/09-权限接入auth-platform.md`、`docs/skills/19-工程化-容器编排与可观测.md`、`scripts/dev-local.sh`、未跟踪 `PROGRESS.md`。已有 SHA256 基线保存于会话校验脚本，交付前必须核验。
- `console/test-results/` 是用户已有未跟踪测试产物，S07 初次用默认目录更新过；此后证据输出到 engine target，不能清理/提交。忽略的本地配置、数据、模型、构建产物均保留。仅原项目目录，无新增 worktree。

## 下一步建议

1. 交付 S09 最小打包修复（两个 POM、ER-S09_RESULT 与本文件）；通过后回到 `feat/sandbox-feed-exposure` 并 fast-forward main。C10 尚未冻结，尚未修改 S10 产品代码。
2. 开始 S10：读 FeedOrchestrator、AdMixer、FeedEntry、DeliveryOperationService/Repository、NaturalDeliveryService/Repository、FeedConsole 与 C04/C08。明确先最终混排再签资格/预占；被剔除项不能计费，恢复不能重混排或改最终位置，自然降级与广告未知恢复分清。沿既有前端审查，不新增导航/总体框架。
3. 继续验证双模式和真实浏览器；后续 S11 CPC、S12 CPA 等按 DAG 推进，不因一片交付停止。

## 重要上下文与证据

- S09 CI 修复：`maven-jar-plugin` 原已在 effective POM，不新增版本；process-classes 生成 classifier=test-support 普通类包到子目录，不改变运行包或 Docker glob。`/tmp/recsys-s09-reactor-verify.log`、`/tmp/recsys-s09-reactor-browser.log`；原证据已备份到 `/tmp/recsys-s09-browser-evidence-archive/`。
- S09 日志：`/tmp/recsys-s09-unit-final.log`、`/tmp/recsys-s09-build-final.log`、`/tmp/recsys-s09-lint-final.log`、`/tmp/recsys-s09-browser-final.log`、`/tmp/recsys-s09-ad-browser-regression.log`、`/tmp/recsys-s09-legacy-browser-final.log`、`/tmp/recsys-s09-natural-backend.log`、`/tmp/recsys-er-s09-hygiene.json`。
- S09 浏览器最终5项含历史重跑新资格、进行中禁重跑；最后 literal→DeliveryState 等价提取后已重跑类型/93单测/build/5旧浏览器。截图 UI 未因该常量变化改变；图标修正后3张状态图再次查看。
- 新自然API仅 ADMIN `/api/sandbox/natural-deliveries/{operationId}`（engine）与 `/api/sandbox/natural-impressions/{receiptId}`（behavior）。natural-text-v1、big ID 字符串、自然无金额。Engine V002、behavior V001 均已交付，不改已执行脚本。C08 原资格二进制/hash/租约/期限细节见 CONTRACTS 与 C08_DESIGN。
- `ReceiptQueueCore` 保留算法，两协议各100/512KiB/1h/2并发/6自动轮；未知 GET→404且未过期才同ID/body PUT；过期仅查。退出清两队列及广告/推荐/搜索意图。同主体续期不清。
- S06 Outbox READY/SENDING/PUBLISHED/DEAD、DB fence、broker ACK 再标记，Inbox 逐事实幂等、不用账户最大版本；原结果/剩余事件不随能力开关抹掉。S15 可以复用模式但必须新增 behavior 迁移解除 V001 对 published_at 的不可变限制，不能跨库写。

## 恢复 Prompt

请读取 `CODEX_PROGRESS.md`、`docs/design/enterprise-reliability/IMPLEMENTATION_SLICES.md` 与 `CONTRACTS.md`，从未完成部分连续实施。S09 已交付 e070b157，当前从 S10 继续。已批准 sandbox、金额/时区、硬预算和广告/自然图文政策，不重复询问；保留用户已有改动，逐片验证、写进度、正常合并推送 main。不要求我反复说继续，不执行真实收费切换或生产部署。
