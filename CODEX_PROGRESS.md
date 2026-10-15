# Codex Progress

## 任务目标

连续实施企业搜推广“曝光可信、钱算对、事件不漏、故障可恢复、效果可验证”，依据 `docs/design/enterprise-reliability/PLAN.md` 原DAG逐片验证、正常Git合并推送main。用户已明确开始实施/继续；不能完成一片就停止，不启用真实收费或生产部署。

2026-10-15：S11B/C1已交付，C2与父S11已完成实现及验证，当前正常Git交付后继续S12。原总体计划与五类前端规划已审查并批准，不重开规划或重复询问已确认政策。

## 已完成

- 已批准政策：sandbox、硬预算预占、CNY六位HALF_UP、Asia/Shanghai原预占预算日；跨日不改日，调低预算不得低于held+charged。广告/自然图文50%连续1秒、hidden重置，自然natural-text-v1=5000/1000/300000。
- Q-03点击：签发起5分钟，资格/预占/接收统一；每资格最多一次收费，点击先到持久化等可见曝光；窗口外/释放后不补扣。
- **Q-04 CPA已于本轮用户回答“按照你的建议来”确认**：归因/预占/转化接收统一签发起5分钟，每资格最多一次收费；转化先到持久化等待可见曝光，超时或释放后只记非计费结果。不再询问该政策。
- S01～S07已交付：S01=9cad79fc、S02=f05eb60b、S02A=56259727、S03=7b6636df、S04=9c95f2dc、S05=7834ad3b、S06=3955e29f、S07=8b84b468+b9a6d8dc。各ER-Sxx_RESULT保留真实验收。
- S08/S08A a8701bf8：自然资格/独立behavior PG事实Outbox、双路由与默认关闭；50专项（23PG）+12旧回归。浏览器关闭临时目录修复4b09d44e已交付且CI通过。
- S09 e070b157及打包修复58767a4f：自然推荐/搜索浏览器闭环、共享恢复队列；93前端、5自然PG浏览器、4广告PG/Kafka、5旧浏览器、50后端专项PASS。普通类test-support包解决可执行JAR不能作测试类库的问题。
- S10 b0b19432：冻结最终混排、自然/广告逐项恢复、不补位；485后端/98前端、5混排+5自然+4广告及6旧浏览器PASS，CI38687137107/console-ci38687136946 SUCCESS。
- S11A 365595e2：精确CPC/OCPC GSP、纯候选、V008；170钱链路PASS，CI38691727206 SUCCESS。
- S11B b2c2911a：V009点击资格/双条件/PG反作弊/共享硬预算/账本Outbox/回收；197钱链路（22新PG+5worker）PASS，CI38694445617全部SUCCESS。
- S11C1 e599b3e5：V010独立点击可靠事件/Inbox/共同回执身份索引及日投影；216钱链路（12新PG+5Kafka+2配置）PASS，CI38695851841全部SUCCESS。
- **S11C2及父S11 DONE / PASS**：ad V011/engine V004、稳定资格获取和原ID恢复、认证HTTP/可信gRPC及in-process同owner。最终249钱链路（0失败/错误/跳过），23模块clean verify=577项、0失败/错误、4既有按需门禁跳过；详见ER-S11C2_RESULT和ER-S11_RESULT。尚未共享环境启用。

## 已修改文件

当前原目录单worktree，分支`feat/sandbox-click-settlement`，C2基线`e599b3e524c19b7f277d3253293a20a4f13a5e7a`。当前C2待本次正常Git交付的范围：

- common-ad ClickDelivery.Offer与有限ChargeMode；ad ClickPreparationService/Configuration、PreparationRepository有限模式复用和V011，旧CPM装配显式Qualifier，ClickSettlementService资源身份。
- proto click.v1/ClickProtoMapper；ad-serving SandboxClickGrpcService；engine两类gateway、固定模式复用交付Repository/Service、ClickDeliveryConfiguration、两个HTTP controller、V004。
- 6个专项测试类及真实双owner/TCP fixture。ad-serving仅test scope复用engine test-support、既有Testcontainers junit-jupiter/postgresql/BOM，不改变生产依赖图。
- C11_DESIGN、CONTRACTS、TECH_SELECTION、IMPLEMENTATION_SLICES、运行手册、架构、ER-S11/C2结果和本进度；补齐C1远程CI结果。
- 源码清单`/tmp/recsys-s11c2-source-paths.json`，最终指纹`b4e5eec607c48b970bf9da2c8aba7380f87239d17f4e6fe9b185e9ff41bb51b9`。

## 未完成

- C2正常Git交付及其远程CI状态需按实际完成记录；父S11实现/本地验证已完成，不重做A/B/C1。
- **S12～S25未完成**，下一项S12 CPA：先细化正式契约和精确候选/不可变报价，随后经广告主认证的业务键/双条件原子捕获、迟到非计费结果、稳定交付/双模式/可靠事件。Q-04已定。
- 后续真实发布来源/期初资金、容量/RTO/RPO/保留目标仍待业务决定，不阻断独立sandbox实现。
- S15保持原范围（自然Outbox和旧BehaviorService可靠事件），不能因自然分支已存在而遗漏旧行为事件。

## 当前问题

- 当前无实现阻塞。根级无canonical formatter及独立Java静态分析为既有限制；不引入新工具或全仓格式化。
- 所有新链路默认关闭，仅sandbox；自然Outbox仍只有落库，实际投递属于S15。C15A未跟踪文档仅备选草案，不纳入当前交付。
- 必须保留七项指纹文件：docker/docker-compose.yml、docs/08-本地运行.md、docs/09-权限接入auth-platform.md、docs/skills/19-工程化-容器编排与可观测.md、scripts/dev-local.sh、未跟踪PROGRESS.md、未跟踪docs/design/enterprise-reliability/C15A_DESIGN.md。指纹`/tmp/recsys-s11b-protected.json`。
- console/test-results/及忽略的env、数据、模型、虚拟环境、构建产物保留；没有额外worktree，无清理授权。

## 下一步建议

1. 核对C2当前源码指纹/验证结果，完成明确路径提交、正常快进合并和push origin/main；用户已持续授权，不再问。记录提交及CI，保护上述文件。
2. 创建或复用S12任务分支（原项目目录，不另建worktree），按原DAG开始CPA。窗口政策已批准，授权模型可复用AdvertiserAuthz/AuthzEngine，但新收费入口必须强制归属/fail-closed，不能沿用disabled/shadow的放行。
3. S12内部宜按可审查单元推进：精确CPA GSP/报价；广告主作用域业务eventId与结算；双模式API/稳定交付/事件。不能只做报价或Outbox落库就把父S12标DONE。
4. 继续S13～S25，逐片真实组件验证、更新进度并正常交付；不执行真实收费切换或生产部署。

## 重要上下文与恢复线索

- C2最终钱链路日志`/tmp/recsys-s11c2-money.log`=249 PASS；全仓`/tmp/recsys-s11c2-verify.log`=23模块/577项/0失败/4跳过。4跳过是原PostgresIntegrationGateTest和三个专用浏览器fixture/acceptance，不是money-chain跳过。Hygiene `/tmp/recsys-er-s11c2-hygiene.json` COMPLETE_WITH_LIMITATIONS。
- C2专项初轮修复JSON guard下标/减字段括号优先级；第二轮20项PG/HTTP通过，在线fixture包内helper访问改为测试自建事务及SHA256，11项在线PASS；补2项ad恢复后249及全仓最终通过。未放宽断言。测试时钟仅独占schema INSERT回拨两秒，故障为提交后抛错，不冒称宿主kill/整站灾备。
- C2新HTTP：PUT/GET /api/sandbox/click-ad-deliveries/{operationId}；PUT/GET /api/sandbox/click-receipts/{receiptId}；GET /api/sandbox/click-settlements/{qualificationId}。ADMIN、原ID/原body恢复，Prepared金额非实收；receipt原决定+settlement当前进度，只有CAPTURED有ledgerId/chargedAmount。
- C2 ChargeMode只服务端装配，旧构造IMPRESSION。独立ad_click_preparation/engine_click_delivery表避免CPM实例误解释；SQL固定命名空间替换不接受外部表名。已冻结原请求/候选/位置和已保存结果不能改；先freeze再调用owner，30秒lease/fence，最多10项。
- 点击资金锁序quote→回执唯一键→首次CLICK用户rate→原日账户，持锁后重读DB时间；第一次条件WAITING、第二条件同事务捕获，风控拒绝同事务释放。原决定不可变，查询另给当前状态；PG反作弊默认true/20，重试不计数。
- 点击C1固定独立AdClickCapturedV1，原两个canonical回执与原日金额；Inbox每事实去重且跨两种receipt身份唯一，低账户版本独立事实仍累加。20次发送/30秒fence/ACK后标记；DB提交后offset+1，毒消息不跳过。CPM+点击合计账本才对账共享账户。
- 暂停新交付用click-preparation-admission-enabled=false，保留回执/查询/回收/事件续发；紧急停止接收用click-receipt-admission-enabled=false，不关闭整个owner遗留held。已完成准备可在key轮换后恢复；未完成待验签及在途新条件仍需保留旧key。
- S12调研：既有AdvertiserAuthz disabled/shadow会放行，不能用于新CPA收费边界；已有AuthzEngine按advertiser权限与internal-token主体，可添加专用严格入口复用。当前旧AdPipeline.recordConversion/AdOutcomeService存在独立写入，不能直接拼接成新的幂等钱链路。本轮尚未修改S12代码。
- S09/S10既有前端队列：每协议100/512KiB/1h/2并发/6自动轮；未知GET→404且未过期才同ID/body PUT；过期仅查，同主体续期不清。混排完整顺序先冻结，失败不补位；自然owner与ad owner独立。

## 恢复 Prompt

请读取CODEX_PROGRESS.md、IMPLEMENTATION_SLICES.md和原PLAN。S11C2与父S11实现及本地验证已完成（249钱链路、全仓577项），先核对本次Git/CI真实状态完成交付记录，然后连续执行S12 CPA及后续DAG。Q-04已明确批准统一签发起5分钟、一资格最多一次收费、转化先到等可见曝光、窗口外/释放后只非计费，不再询问。保留原总体计划和S15旧Behavior事件范围；保护七项用户文件及本地数据，不重做S11、不反复问“继续”、不启用真实收费或生产部署。
