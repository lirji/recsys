# ER-S10：最终混排与双回执

2026-10-15；基线 `58767a4f9305aee8e2e07056eba2bcf142d65086`，分支 `feat/sandbox-feed-exposure`；[C10 设计](C10_DESIGN.md)、[正式契约](CONTRACTS.md)。**DONE / PASS**；整体计划尚未完成。

## 实现和验收边界

- engine 在任何广告准备前原子冻结最终混排、自然 FEED 资格、广告候选证明和位置。原请求/首次配置不可改，30 秒租约/fence 与旧计划防并发覆盖。恢复只补原 child 的准备结果；未知、预算拒绝或过期占位，不重排。候选阶段故障可完成纯自然，原操作后来恢复不追加广告。
- ad owner 新 FeedCandidates 只读接受账本频控，使用接受时间的上海日，阈值处停止计数。HELD 不计数，原预算日不替代接受日。它不承诺并发严格频次上限；硬预算仍由已有 reservation 保障。in-process 与 gRPC 都调用同一 owner 逻辑，无旧收费兜底。
- `/feed` 默认关闭新分支；显式新交付、原 ID 恢复/刷新、完整 long 字符串及不可变结果校验。自然与广告复用各自队列及卡片；自然只确认事实，广告只按原回执显示实际消费。未知占位不挂测量器，历史/对比遮盖暂停测量，退出清两队列及 feed 意图。
- common 新增稳定 FEED 名称，旧签名字节兼容；旧自然 HTTP 仍仅 RECOMMEND/SEARCH。behavior 无 schema 变化；新增 engine V003、ad V007 均有中文表/字段说明并核验摘要。依赖版本/服务拓扑不变，所有开关默认关闭。

## 验证证据

| 验收 / 方法 | 结果与证据 |
|---|---|
| 最终位置/唯一预占/捕获；失败冻结无副作用；部分未知；原参数和新候选配置隔离；并发与 fence | 11 项真实 PG 测试 PASS，`FeedDeliveryPostgresTest`，`/tmp/recsys-s10-pg-boundary.log`；engine/ad 分别独立 schema/DataSource，实际报价、预占、捕获、账本及频控 |
| 接受日频控与跨日 | 实际账本按日开始前1ms、开始、结束前1ms、结束四边界验证；测试专用时钟/报价夹具生成历史状态，不宣称真实等待午夜。第一条原预算日不同仍按接受日计数；隔离用户、广告主阈值、未接受预占不计 |
| 公共 FEED 字节、behavior 原子事实、HTTP 权限/严格输入/默认关闭、双模式 RPC | 初轮54项选择集 PASS，新增午夜边界后完整 verify 再覆盖；`/tmp/recsys-s10-backend.log`、`/tmp/recsys-s10-verify.log`。FEED 自然事实保留最终 slot=11，无资金写入 |
| 完整 reactor 构建及回归 | `mvn -B -e clean verify`，23 模块 SUCCESS，485 项、0 failures/errors、4 opt-in/条件跳过；`/tmp/recsys-s10-verify.log`。包括已有真实 PG/Kafka 钱链路与所有非 opt-in 后端测试，保留 S09 普通支持 JAR 打包验证 |
| 前端协议/队列/测量、类型/构建/静态 | 98 项单测 PASS、typecheck/build PASS；`/tmp/recsys-s10-{unit,type,build}-final.log`；lint 0 errors、16 既有 warnings，`/tmp/recsys-s10-lint-final.log`。恢复拒绝已冻结内容/位置/金额/资格变化 |
| 混排真实浏览器/PG | 5 项 PASS，`/tmp/recsys-s10-browser-final.log`：双回执与大 ID、广告回执已提交丢 ACK、prepare 真正提交后丢响应、候选故障自然降级、预算拒绝、频控、空结果、历史/对比/脱敏、跨主体403与退出、原生 hidden/离屏 |
| 混排最终资金与自然事实 | 宿主最终检查真实账本与账户金额一致、每条事实对应 Outbox，被混排剔除的广告101/201无报价/预占。混合列表2条广告只产生2笔0.123456，合计0.246912；自然事实为最终1/3/5位 |
| 共享自然卡片回归 | 5 项既有浏览器+真实PG PASS，`/tmp/recsys-s10-natural-regression.log`；测试断言未放宽 |
| 共享广告卡片回归 | 4 项既有浏览器+真实 PG/Kafka PASS，含实际账本、可靠事件和投影最终资金对账；`/tmp/recsys-s10-ad-regression.log` |
| 默认关闭/旧页面兼容 | 6 项浏览器 PASS（新增旧 feed 一项），`/tmp/recsys-s10-legacy-final.log`；旧 feed 调用一次，新 sandbox 零调用。响应夹具仅验证旧 UI，不冒充真实后端 |
| Hygiene | IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS，`/tmp/recsys-er-s10-hygiene.json`；仅根级 canonical formatter 缺失。前端新增/提取文件用原 Prettier；4MiB 已命名常量、测试等待超时 advisory 已复核；事务短且不跨远程，PG 验证回滚/fence |

测试中修正了夹具执行 PG dollar-quote、Mockito 重 stub、午夜样本误用同 operation/slot 的问题；没有放宽唯一约束或业务断言。首轮浏览器4/5通过，失败因连续 Escape 未等上层弹窗关闭，改成逐层等待后5/5通过。完整构建后又重跑全部5项 feed 浏览器，最终代码证据见上。

## 实际视觉复核

逐张查看最终 `recsys-rec-engine/target/feed-browser-evidence/` 六张截图：`feed-mixed-desktop.png`、`feed-preparing.png`、`feed-rejected-narrow.png`、`feed-empty-narrow.png`、`feed-history.png`、`feed-compare.png`。桌面1280×900、窄屏390×844；自然/赞助与消费分区可读，长ID折行，窄屏实测 scrollWidth=390，未知/拒绝保留位置且有明确恢复说明，空状态沿用图标和操作层级。

首轮复核发现 Feed 顶栏有不参与当前请求的全局 userId 输入；最终新分支已隐藏，避免两个输入来源。截图先回顶部，并等待抽屉完成展开；最终历史/对比截图为实际视口，未把动画中间帧作为证据。弹层选择、关闭、脱敏和遮盖不曝光分别有行为断言。旧共享页面保留原布局，纯图文不依赖外部图片。

## 限制与恢复

浏览器宿主同 JVM、三个 owner schema、隔离 PG 种子和测试认证；不是生产 gateway/OIDC、多进程崩溃或 feed Kafka 联调。候选故障/prepare ACK丢失是适配器故障注入，业务持久化为真实数据库。自然 Outbox 尚未投递（S15），资金事件复用 S06。资格不证明真人注意力，浏览器未提交观测仍可丢失。不宣称效果提升、容量或灾备指标。

[运行与恢复说明](FEED_EXPOSURE_RUNBOOK.md) 规定迁移顺序、默认开关、原 ID 恢复及先停新建保留在途的回退边界。用户六个既有文件 SHA256 全部未变，既有 test-results/忽略产物保留；没有创建 worktree。尚未执行真实收费或生产部署。

下一项 S11 的 CPC 窗口 Q-03 已向用户提出明确方案，待答前不实现依赖该政策的计费迁移。原 DAG 的 S15 仅依赖已完成 S06/S08，可在等待期间顺序推进，不重排或改写整体范围。

验证版本：以本片非文档文件按路径排序，拼接路径与文件SHA256后再计算SHA256，结果 `51f5fb9a34bfde4439263104caabd60b57d303cabd8fc55185fd1c361a6ffb18`。最终前端格式检查、文档本地链接/代码围栏及 git diff --check 均 PASS。

SKILL_HANDOFF: backend/frontend-implementation → implementation-validation → update-progress-docs → task-git-delivery；slice=ER-S10，status=COMPLETED，gate=PASS。根级formatter缺失逐项记为限制；生产部署未执行。

交付：`b0b194321c4f4124e77c311dceb3867066d9dad3`已正常合并推送main；[CI38687137107](https://github.com/lirji/recsys/actions/runs/38687137107)全部SUCCESS，[console-ci38687136946](https://github.com/lirji/recsys/actions/runs/38687136946) SUCCESS。用户随后已确认Q-03，下一片转S11；S15草案未实现。
