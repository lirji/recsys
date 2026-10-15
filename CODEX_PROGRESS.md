# Codex Progress

## 任务目标

连续实施企业搜推广“曝光可信、钱算对、事件不漏、故障可恢复、效果可验证”，依据 `docs/design/enterprise-reliability/PLAN.md` 原 DAG 逐片验证、正常 Git 合并推送 main。用户已明确开始实施/继续，不能完成一片就停止。不启用真实收费或生产部署。

2026-10-15 本轮已恢复并完成S11B内部结算/验证，正在正常Git交付；随后立即推进S11C双模式API与可靠事件。无需重新批准原计划或Q-03。

## 已完成

- 用户政策：sandbox；硬预算预占；CNY 六位 HALF_UP；Asia/Shanghai 预算日，跨日义务归预占日，调低预算不得低于消费+预占。广告 CPM 与自然图文均 50% 连续可见 1 秒、hidden 重置、资格 5 分钟且服务端到期前接受。自然 `natural-text-v1` 完整元组 5000/1000/300000 已批准，不重复询问。
- 原评估、完整 PLAN、五类前端只读角色与独立规划审查已完成并获授权。覆盖四在线路由；不重开总体规划。
- S01 `9cad79fc`、S02 `f05eb60b`、S02A `56259727`、S03 `7b6636df`、S04 `9c95f2dc`、S05 `7834ad3b`、S06 `3955e29f`、S07 `8b84b468` + `b9a6d8dc` 均已合并推送 main。预算/定价/预占/交付/回执/资金 Outbox/Kafka 以及广告浏览器闭环完成；每片 RESULT 有真实证据。
- S08 / S08A `a8701bf88a02023ab0252e698ee3d2de59e1112e` 已合并推送：common 自然 nq1 签名/golden、engine 原子冻结交付、behavior 独立 PG 幂等可见回执/事实/Outbox、双路由、显式政策默认关闭。50 专项（23 真实 PG）+12 旧回归通过；详见 ER-S08_RESULT。
- S08 CI 38677748671 后端全部通过，浏览器因独占 Chromium 临时目录 ENOTEMPTY 失败。修复 `4b09d44e021d33480af798e1d485770fa033511a` 已合并推送：等 close 后有界清理自身目录；4 浏览器/PG/Kafka 验收通过，远程 CI 38678472988、console-ci 38678472943 均 SUCCESS。
- S09 DONE / PASS，提交 `e070b157` 已正常合并并推送 main。S09 打包修复 `58767a4f` 已合并推送 main，该阶段已交付。自然推荐/搜索默认关闭新分支、显式交付/原意图恢复、冻结正文可见测量、类型化自然结果；共享队列抽取，广告兼容。C09_DESIGN / CONTRACTS C09 / ER-S09_RESULT / runbook 已记录。
- S09 最终：93 前端单测，5 自然浏览器+真实 PG（唯一事实+Outbox），4 广告浏览器+PG/Kafka 资金对账，5 旧页面浏览器，50 natural-exposure 后端专项全部通过。7 张桌面/窄屏/历史/对比/空/202/错误截图已实际查看。typecheck/build/lint 0 errors、既有16 warnings，Code Hygiene 只有根级无 formatter 限制。
- S10 `b0b194321c4f4124e77c311dceb3867066d9dad3` 已合并推送 main；CI 38687137107 全 SUCCESS，console-ci 38687136946 SUCCESS。
- S11A `365595e261d6018a2a430ee16ed1c65372f37767` 已正常提交、合并并推送 main。交接时已核对 HEAD/main/origin/main 均为该提交，远程 [CI 38691727206](https://github.com/lirji/recsys/actions/runs/38691727206) completed/success。精确点击报价完成，尚无点击收费入口；170 项钱链路 0 失败/0 跳过，含 6 项新真实 PG，见 ER-S11A_RESULT。

- S11B DONE / PASS：V009独立点击结算/回执/计数/账本/Outbox，共享原账户；用途隔离资格、双条件原子捕获、PG反作弊、到期回收已实现。完整钱链路197项、0失败/错误/跳过（新增22真实PG+5worker）；当前待完成Git交付，见ER-S11B_RESULT。

## 已修改文件

当前分支 `feat/sandbox-click-settlement`，基线 `365595e261d6018a2a430ee16ed1c65372f37767`，原目录单worktree。S11B待交付改动：

- common-ad `ClickSettlement` 契约；ad `ClickQuoteRepository.fingerprint`、ClickSettlementRepository/Service/Configuration、ClickExpiryJob。
- 新V009点击结算迁移、ClickSettlementPostgresTest（22）和ClickExpiryJobTest（5）。
- C11_DESIGN/CONTRACTS/IMPLEMENTATION_SLICES/BACKEND_ARCHITECTURE、SANDBOX_BUDGET_RUNBOOK、ER-S11B_RESULT与本进度。
- 上轮ER-S11A_RESULT交付CI记录随本次文档一起交付，不重做S11A。
- 未跟踪 `C15A_DESIGN.md` 仍仅备选草案，保护且不夹入S11B。

## 未完成

- S11B业务已通过验收；先核对并完成当前Git提交/合并/push main。S11C还需冻结并接入双模式API/整次交付恢复及可靠点击事件发布/Inbox，父S11未完成，不能只做Outbox落库后停下。
- S12～S25未完成；CPA Q-04、真实来源/期初资金、容量与恢复目标待后续决定。

## 当前问题

- Q-03已由用户确认：sandbox每资格一次收费；资格/预占/点击窗口从签发起5分钟；先点击持久化等可见曝光，二者到期前服务端接受，释放后不补扣。不再重复询问。
- 点击内部结算已具备预占/双条件捕获，但尚无HTTP/RPC入口、点击Outbox发布或消费者；默认关闭。V009独立表族共享原账户，不能把点击塞入CPM票据或更改V001～V008。
- 新路径默认关闭；仅sandbox，无真实收费/部署；自然Outbox仍仅落库。
- 六个用户文件哈希已核验不变、不可纳入提交：docker/docker-compose.yml、docs/08-本地运行.md、docs/09-权限接入auth-platform.md、docs/skills/19-工程化-容器编排与可观测.md、scripts/dev-local.sh、未跟踪PROGRESS.md。console/test-results/与忽略配置/数据/模型/产物保留。只有原目录一个worktree。
- S11A首次测试编译发现common-ad没有Jackson依赖，已把序列化兼容测试移至已有Jackson的ad测试侧，未新增依赖。Long.MAX_VALUE改用户的夹具用减1避免自身溢出。窄集已通过。
- 根级无canonical formatter为既有限制，Java沿周边风格，不新增依赖/全仓格式化。

## 下一步建议

1. 核对S11B Git交付现场；197项钱链路已PASS，源代码无需重复验证（除非进一步修改或CI失败）。使用显式允许路径提交，正常合并推送main，保护七个文件指纹和未跟踪产物。
2. 冻结S11C：实际纯点击候选到稳定交付/报价/预占、in-process和grpc同owner、认证HTTP/原ID恢复；新AdClickCapturedV1可靠投递与Inbox/点击投影、两个模式PG/Kafka验收。不得用旧CPM事件或projection.impression_count承载点击，不能只新增accept API而没有资格获取链路。
3. 父S11闭环完成后继续原DAG。S12 CPA Q-04确需用户窗口决策时早问并继续独立S15；不能丢弃旧BehaviorService可靠事件范围。其余已批准政策不重问。

## S11B 实现与恢复线索

- C11B正式契约在C11_DESIGN末节；新qualificationId=quoteId，原签发五分钟不续期。Proof用途click-qualification绑定报价摘要/主体/原预算日。准备原结果和当前状态分离，只有CAPTURED有chargedAmount（允许零元）/ledgerId。
- ClickSettlementService锁序quote→回执唯一键→PG rate→原日账户，持锁后重新读DB时钟。两条件任何顺序，第一次ACCEPTED_WAITING；第二次同事务扣款、ledger+outbox、终态及回执。原WAITING回执的查询可对应当前CAPTURED，不能单看原决定。
- 同用户PG固定分钟计数复用原默认true/20；跨资格共享、重试不计数。超限FRAUD_REJECTED并同事务释放；释放/到期后不补扣。迁移guard禁止改已接受条件、原决定和终态，延迟约束禁止提交PENDING回执。
- 安装开关click-settlement-enabled默认false，还需sandbox-enabled/clicks-enabled；click-preparation-admission-enabled/click-receipt-admission-enabled分别控制新增。正常回退保留回执/查询/回收，不能关掉owner遗留held。
- Outbox已有READY/SENDING/PUBLISHED/DEAD、attempts/fence/available_at等列，但当前没有实际点击relay/consumer，S11C必须完成。topic=ad-click-captured-v1，独立AdClickCapturedV1字段含visibleReceiptId/clickReceiptId、大ID字符串、原日精确金额，无token/主体。
- 保护文件指纹 `/tmp/recsys-s11b-protected.json`；新增源码/测试列表可从git status核对。S11B完整日志 `/tmp/recsys-s11b-money.log`（197 PASS），首轮PG `/tmp/recsys-s11b-pg.log`（20 PASS，后补2）；hygiene `/tmp/recsys-er-s11b-hygiene.json`，只有无canonical formatter限制。
- 专项22项真实PG包含并发同/异ID、双顺序、风控拒绝回滚、Outbox写后失败、账户锁等待过期、到期SKIP LOCKED、共享CPM预算、跨午夜和零元；5个worker测试。跨午夜/同分钟使用测试时钟控制，不冒称真实等待午夜或宿主kill演练。
- 原CPM日投影仅代表CPM，不能直接与新增点击后的账户总charged比较；runbook已新增CPM+点击账本合计对账。S11C应继续维护点击投影及组合查询。
- S15A未跟踪草案仅备选；自然Outbox V001禁止UPDATE，须新迁移保留payload不可变并允许投递状态；S15也包括旧BehaviorService可靠事件。

## 重要上下文与证据

- S10最终：完整 `clean verify`23模块/485项、0失败/4按需跳过；98前端单测/type/build/lint0错误；5混排PG浏览器、5自然PG、4广告PG/Kafka、6旧页面PASS。六张最终截图已实际查看。日志 `/tmp/recsys-s10-{verify,browser-final,natural-regression,ad-regression,legacy,unit-final,build-final,lint-final}.log`；`ER-S10_RESULT.md`为完整证据。
- S10初轮 `/tmp/recsys-s10-backend.log` PASS：54项=common11 + engine23（含feed PG10/HTTP4/配置2）+ gRPC5 + behaviorPG15；无跳过。新午夜项在单独 `/tmp/recsys-s10-pg-boundary.log`（11项PASS）。最初fixture问题仅ScriptUtils不识别PG dollar quote和Mockito重stub触发旧answer，已修为完整SQL statement与doReturn/doAnswer，未改业务断言。
- S10前端 `/tmp/recsys-s10-unit.log` 98项通过，typecheck/build通过（`/tmp/recsys-s10-build.log`）；上述均已完成最终验收，见S10最终记录。
- C10核心：1～50条，广告仅1～10位；先固定完整混排+FEED自然资格，再prepare；owner未知返回已冻结PREPARING及自然可显示，不补位、不重排。candidate失败冻结纯自然。新路径默认false，原资金政策不变。频控是已接受事实准入，不保证并发硬频次上限。
- `/api/sandbox/feed-deliveries`请求userId/size/scene/query，view.items为{slot,kind:NATURAL|AD,natural,ad}互斥载荷；natural原Entry、ad原C04 Item形状；adDisposition为NOT_REQUESTED/CANDIDATES_AVAILABLE/NO_ELIGIBLE_ADS/CANDIDATES_UNAVAILABLE或未冻结null。
- frontend新queue组合`feedScopeQueue`同时bind两条既有队列，没有第三条队列。sessionkey `recsys.sandbox.feed.v1`，默认开关VITE_FEED_EXPOSURE_SANDBOX_ENABLED=false；结果恢复校验使用canonical JSON防属性顺序误判。

- S09 CI 修复：`maven-jar-plugin` 原已在 effective POM，不新增版本；process-classes 生成 classifier=test-support 普通类包到子目录，不改变运行包或 Docker glob。`/tmp/recsys-s09-reactor-verify.log`、`/tmp/recsys-s09-reactor-browser.log`；原证据已备份到 `/tmp/recsys-s09-browser-evidence-archive/`。
- S09 日志：`/tmp/recsys-s09-unit-final.log`、`/tmp/recsys-s09-build-final.log`、`/tmp/recsys-s09-lint-final.log`、`/tmp/recsys-s09-browser-final.log`、`/tmp/recsys-s09-ad-browser-regression.log`、`/tmp/recsys-s09-legacy-browser-final.log`、`/tmp/recsys-s09-natural-backend.log`、`/tmp/recsys-er-s09-hygiene.json`。
- S09 浏览器最终5项含历史重跑新资格、进行中禁重跑；最后 literal→DeliveryState 等价提取后已重跑类型/93单测/build/5旧浏览器。截图 UI 未因该常量变化改变；图标修正后3张状态图再次查看。
- 新自然API仅 ADMIN `/api/sandbox/natural-deliveries/{operationId}`（engine）与 `/api/sandbox/natural-impressions/{receiptId}`（behavior）。natural-text-v1、big ID 字符串、自然无金额。Engine V002、behavior V001 均已交付，不改已执行脚本。C08 原资格二进制/hash/租约/期限细节见 CONTRACTS 与 C08_DESIGN。
- `ReceiptQueueCore` 保留算法，两协议各100/512KiB/1h/2并发/6自动轮；未知 GET→404且未过期才同ID/body PUT；过期仅查。退出清两队列及广告/推荐/搜索意图。同主体续期不清。
- S06 Outbox READY/SENDING/PUBLISHED/DEAD、DB fence、broker ACK 再标记，Inbox 逐事实幂等、不用账户最大版本；原结果/剩余事件不随能力开关抹掉。S15 可以复用模式但必须新增 behavior 迁移解除 V001 对 published_at 的不可变限制，不能跨库写。

## 恢复 Prompt

请读取 `CODEX_PROGRESS.md`、`IMPLEMENTATION_SLICES.md`、`CONTRACTS.md` 与 `C11_DESIGN.md`。本轮S11B内部结算已完成且197项钱链路全部通过，先核对Git完成提交/正常合并/push main，再从S11C双模式API/稳定交付恢复及可靠点击事件链继续。复用feat/sandbox-click-settlement，不重做S11A或S11B，不要求反复“继续”。Q-03及sandbox、硬预算、六位金额、时区、图文可见政策已批准，保护用户文件与C15A草案；S11C/父S11不能以Outbox落库代替完整链路。逐片验证、更新进度、正常交付后连续推进原计划；不执行真实收费切换或生产部署。
