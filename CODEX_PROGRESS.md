# Codex Progress

## 任务目标

连续完成docs/design/enterprise-reliability/PLAN.md原企业可靠性DAG（曝光可信、钱算对、事件可靠、恢复/效果验证），逐片实施、验证、正常Git合并推送origin/main。用户已明确继续，原总体计划与五类前端规划已批准；不重开规划、不完成一片就停、不启用真实收费或生产部署。

## 已完成

- 政策已批准：sandbox、共享硬预算、CNY六位HALF_UP、Asia/Shanghai原预占预算日；跨日不改日、预算不得调低到held+charged以下。图文50%连续1秒、hidden重置；自然natural-text-v1=5000/1000/300000。
- Q-03 CPC及Q-04 CPA：均为签发起5分钟，资格/预占/接收统一，一资格最多一次收费；点击/转化先到持久化等可见曝光；窗口外或释放后仅非计费。Q-04本轮用户回答“按照你的建议来”，已落PLAN/CONTRACTS，不再询问。
- S01～S10已交付，详见各ER-Sxx_RESULT及Git。S10=b0b19432；保留原DAG后续范围，尤其S15同时含自然Outbox和旧BehaviorService可靠事件。
- S11A=365595e2精确CPC；B=b2c2911a双条件原子资金；C1=e599b3e5独立可靠点击事件；C2=f914f571稳定交付/双模式API，249钱链路及全仓577项通过（4个既有按需fixture跳过）。父S11业务已完成。
- C2旧浏览器fixture Bean名称问题6904889c已修复：广告4+自然5+混排5=14真实浏览器全部通过。其独立CI38699358192被后续main并发取消，继承修复的2f5c3dab之CI38699749896全部5门禁SUCCESS，原CI38698175497浏览器失败已关闭。
- S12A=2f5c3dabbd9697a91d522c3c14df3371d10f8cbc已合并推送main：CPA精确GSP、纯候选、独立V012不可变报价；14专项+262完整钱链路，CI38699749896全部成功。没有公开CPA入口。
- **S12B内部CPA结算 DONE / PASS**：V013独立资格/可见/广告主业务事实/账本/捕获Outbox，同一账户硬预算，两条件任意先到，迟到非计费、原事件结果恢复、有界到期工作器。23真实PG+5worker专项及最终290钱链路/57类0失败/错误/跳过；详见ER-S12B_RESULT，待本次正常Git交付。

## 已修改文件

原项目目录唯一worktree，当前feat/sandbox-conversion-settlement，S12B基线2f5c3dab。本批8源文件：

- common-ad ConversionSettlement领域契约，区分VisibleCommand/ConversionCommand、原决定/当前状态与独立捕获事件。
- ad ConversionSettlementRepository/Service/Configuration、ConversionExpiryJob、V013__conversion_settlement.sql。
- ConversionSettlementPostgresTest、ConversionExpiryJobTest。
- C12_DESIGN、CONTRACTS、IMPLEMENTATION_SLICES、BACKEND_ARCHITECTURE、SANDBOX_BUDGET_RUNBOOK、ER-S12B_RESULT及CODEX_PROGRESS；补S12A/R1真实Git和CI记录。
- 源清单/tmp/recsys-s12b-source-paths.json；最终指纹07a894e209c52d3cbbb73684a7bf4112d671733e633169e3f79a99e96ec3777d。完整日志/tmp/recsys-s12b-money.log；hygiene=/tmp/recsys-er-s12b-hygiene.json。

## 未完成

- S12B完成本批Git/远程CI记录后立即继续S12C1（独立CPA可靠事件、Inbox/日投影/V014，正式契约已细化且依赖就绪）。
- S12C2：稳定CPA交付、严格广告主授权转化接收/查询、可信独立caller、HTTP/gRPC/in-process闭环。**B内部acceptAuthorizedConversion只用于已授权边界，不能冒称公开认证完成**；父S12保持IN_PROGRESS，C1/C2均必需。
- S13～S25按原DAG全部继续，不收缩为当前片。正式发布来源/期初账务、容量RTO/RPO/保留目标仍需业务决定，不阻断独立sandbox实现。

## 当前问题

- 无当前实现阻塞。S12B首轮装配残余FraudPolicy参数与PG提交期异常断言类型已修正，最终290项通过；没有放宽断言或跳过测试。
- 根级formatter和独立Java静态分析为既有未配置限制；hygiene COMPLETE_WITH_LIMITATIONS。事务/worker advisory已审查；枚举与忽略venv里的Python Status重名无关，不改venv或引入新工具。
- 七项用户文件必须保持原指纹（/tmp/recsys-s11b-protected.json）：docker/docker-compose.yml、docs/08-本地运行.md、docs/09-权限接入auth-platform.md、docs/skills/19-工程化-容器编排与可观测.md、scripts/dev-local.sh、未跟踪PROGRESS.md、未跟踪docs/design/enterprise-reliability/C15A_DESIGN.md。
- console/test-results及忽略env/数据/模型/venv/构建产物保留；无额外worktree、无清理授权。C15A仅备选草案，不代替原S15范围。

## 下一步建议

1. 核对S12B待交付真实Git状态，按task-git-delivery显式路径提交、正常快进合并并push main；不重复询问授权。
2. 复用原目录feat/sandbox-conversion-settlement执行S12C1。C12C1设计/CONTRACTS已细化；BillingOutboxRepository.EventFamily新增固定CONVERSION，复用ACK/fence/有界重试/offset规则，独立类型/表/指标。
3. 随后C2接实际广告主认证与稳定交付，完整验收后才完成父S12；继续S13～S25。

## 重要上下文

- B资格用途conversion-qualification，五分钟不可续期，报价ID=资格ID；原token只用于已验证资格。业务键(SANDBOX,advertiserId,eventId UUID)，occurredAt为UTC最多微秒、>=issuedAt且<expiresAt且<=DB决策时间；原actor审计但不纳入业务内容摘要，当前同广告主另一个有权主体可恢复同请求。
- B锁序quote→命令唯一键→原日账户，持齐锁后重读DB时间；原决定不可变，当前状态另查；只有CAPTURED有chargedAmount/ledgerId，合法零额也有账本。已有canonical时额外业务事件DUPLICATE_QUALIFICATION，自身非计费；没有canonical的迟到/释放只EXPIRED/NOT_HELD。失败全回滚。
- B外部入口尚未安装；默认conversions-enabled/conversion-settlement-enabled关闭。暂停新增用conversion-preparation-admission-enabled，紧急停新条件用conversion-receipt-admission-enabled，不能关owner遗留held。到期工作器单线程fixed-delay、每轮<=100、SKIP LOCKED和失败退避。
- C1捕获事件AdConversionCapturedV1 / ad-conversion-captured-v1，需携带内部outcomeId和广告主作用域conversionEventId、原日/金额/发生/捕获时间。投影按event/qualification/visible/outcome及(advertiser,conversionEvent)去重；跨广告主同event合法。VISIBLE与内部outcome属于不同命名空间，不能照搬点击的跨种类共同receipt索引。
- AdvertiserAuthz旧disabled/shadow会放行，C2必须专用强制方法；既有AuthzEngine SDK本地javap确认Consistency.fullyConsistent()存在。新回调每次认证/查询/恢复判advertiser edit，缺引擎或故障503、deny403、匿名401；网络授权在资金事务外。RPC仅独立advertiser caller，展示engine不能提交转化。旧AdPipeline.recordConversion/AdOutcomeService多次独立写不拼成新原子钱链路。
- S11C2显式Qualifier("deliveryOperationService")已与旧fixture对齐；新旧同类型bean仍须显式Qualifier。测试依赖只复用既有engine test-support + Testcontainers test scope；没有新依赖版本。
- 现有C1点击事件文件（ClickEventCodec/Configuration/ProjectionRepository/Service、ClickEventsFixture/PostgresTest/KafkaTest）可参考有界传输；CPA新增字段/身份语义必须适配，不能机械把clickReceipt改名就宣称正确。
- 测试只能使用独占临时PG/Kafka，不修改共享dev_infra。真实锁/事务验证不等于宿主kill或灾备；跨午夜测试时钟为参数化DB查询模拟，不能声称RTO/RPO。

## 恢复 Prompt

读取CODEX_PROGRESS.md、IMPLEMENTATION_SLICES和原PLAN，从真实Git状态继续。S12A已交付2f5c3dab且CI全绿；S12B已完成290钱链路验证，先完成/核对其本次Git交付，然后立即执行S12C1可靠事件、C2严格广告主认证与稳定交付，再继续S13～S25。Q-04五分钟/一次收费/先转化等待/迟到非计费已批准，不再询问。保护七项用户文件和本地数据，不重开已完成规划、不停下来要求“继续”，不启用真实收费或生产部署。
