# C11：CPC/OCPC 点击结算

2026-10-15；原计划 ER-S11，用户已确认 Q-03：sandbox 每资格最多收费一次，资格/预占/点击窗口从签发起5分钟；点击先到持久化等待可见曝光，二者须在到期前被服务端接受；已释放不补扣。原图文50%连续1秒、hidden重置政策、CNY六位HALF_UP、上海预算日和硬上限均保留。

## 实施拆分与取舍

S11A 先实现实际竞价到不可变点击报价的内部纵向片；S11B 再实现预占、签名资格、曝光依赖/点击接收与原子资金事实；S11C 接入双模式服务与HTTP、恢复及可靠事件验证。父 S11 只有所有必需链路完成才DONE，不把报价已冻结误称点击已收费。不改变S12 CPA窗口，不启用真实账户。

当前 CPM quote/reservation/receipt/ledger 有专属外键、单位和事件类型。把点击塞进旧CPM票据会使旧实例按曝光捕获；原API仍只CPM。采用独立显式点击契约/票据用途，精确报价与最终结算共用同一个ad owner和账户；具体S11B账务扩展必须保持旧读写兼容和一个账户额度权威。先不对所有模式做通用收费框架。

## C11A 正式报价契约

新 `ClickPrice` 明确 bidType=CPC/OCPC、priceUnit=PER_CLICK、版本 CLICK_GSP_CNY6_V1。数学与CPM共用一个纯 `GspPrice` 值：threshold/billFactor精确十进制，不提前按金额舍入；increment/floor/cap六位；先比较有理数上下界，再最终除法HALF_UP到6位。CPM的五字段JSON、既有版本/单位和方法签名保持不变，委托同一数学实现，不升级其业务规则。

`auctionSandboxClick` 只在新显式入口接受CPC/OCPC，其他模式不参与本场；标准GSP，VCG/Listwise拒绝。复用既有校准、质量、相关性、探索排序、pacing与OCPC有效出价；OCPC使用现有pCVR/目标CPA/系数，不接旧uplift分配写入。已有double源输入只能冻结其十进制表示，不声称还原上游浮点丢失精度。排序分仍非货币，展示chargedPrice不是收费权威。

`AdPipeline.sandboxClickCandidates` 纯候选入口：召回/定向/排序、上述精确竞价和DCO，不写报价、预算、曝光、旧事件、GD或uplift assignment。候选未被最终选择前零资金副作用。当前仅内部用例，不将此方法自动接上旧搜索/feed接口。

新 ad V008 `ad_click_quote`：quoteId、operationId/最终slot唯一、subject/user、广告主/广告/creative/item、CPC/OCPC、原精确依据、金额/币种/单位/版本、内容title、payload hash、DB签发与五分钟期限。每个报价为单一最大可收点击义务，不按pCTR折算预占。字段及整行不可改删；首次创建须候选deadline未过，已存在同命令恢复原报价/原期，原主体/原字段变更拒绝。题名限定2000字符，slot1～10，long均在RPC/HTTP后续用字符串；owner内部Java用long。

报价持久化仅adDb，不创建预算或预占；只有S11B准备成功后才签发可展示资格。配置 `recsys.ad.billing.clicks-enabled=false` 默认关闭，另需sandbox owner；本片开启仅装配报价owner且核验V008，不能收费。迁移保留V001～V007，旧CPM能力不需V008即可运行。

## C11A 文件与验证

common-ad billing 的 GspPrice/ClickPrice/FrozenClickQuote 与CpmPrice委托；ad BiddingService和AdPipeline纯入口、ClickQuoteRepository/Configuration、V008；纯计价/实际竞价边界和真实PG测试。无前端/runtime组件变化，无新依赖。

验收：半微元、极小概率因子、金额极值/非有限拒绝；CPC/OCPC单位与mode过滤、OCPC有效出价、旧CPM和旧浮点竞价回归；实际PG并发同ID、同位置改ID、原主体/金额/类型冲突、重启与期限不刷新、到期首次拒绝/过期原结果可恢复、全表字段注释/不可变约束。新开关默认关闭，缺迁移/错误owner拒绝装配。钱链路完整回归后交付S11A，立即推进S11B。

## S11B/C 后续必须满足

先锁资格、再锁原预算日账户，点击接受与曝光依赖均持久化；首次回执/点击决定和当前结算进度分开，WAITING不是实收或零元成功。后到依赖在同事务捕获，崩溃不得留下“二者都接受但资金效果不明”半成品；到期/释放竞争只有一个终态。反作弊判定必须来自服务器且与命令幂等协调，不能直接复用旧Redis先标去重再扣费的崩溃窗口。收费账本和Outbox同库原子，事件不能伪装成CPM；消费者版本升级、在途恢复/查询与回退需独立验收。该段为后续约束，具体接口/迁移在实施S11B前冻结。

## C11B 正式结算契约（2026-10-15 冻结）

按原已批准S11和Q-03细化内部用例；不新增业务收费政策。采用独立 `ad_click_settlement / ad_click_receipt / ad_click_ledger / ad_click_outbox / ad_click_rate`（V009），只共享 `ad_budget_account`；旧CPM表、迁移及事件不改。资格ID固定等于不可变quoteId，一个报价只能准备一次；准备的首次HELD/INSUFFICIENT_BUDGET/ACCOUNT_MISSING/QUOTE_EXPIRED决定不可变，当前状态另查。HELD才签发 `click-qualification` 用途的证明，绑定报价全部上下文和预占日；原token持久化，重试不重签、不延长原五分钟。

内部用例 `prepare(quoteId, subject)`、`accept(Command)`、`findReceipt(receiptId, subject)`、`find(quoteId, subject)`、`release(quoteId, subject)`、`expireOne()`。主体来自可信边界。回执种类VISIBLE/CLICK共享稳定receiptId命名空间；VISIBLE沿用5000bps/1000ms/hidden重置及服务端签发至接受时长核验，CLICK不接受客户端自报金额/时间/用户/反作弊结论。摘要包含主体、完整token、种类、可见参数；原同ID同负载可在密钥轮换/停止新增后恢复，改字段冲突，跨主体拒绝。

首次有效VISIBLE或CLICK只保存该条件，返回不可变 `ACCEPTED_WAITING`；后到有效条件原子捕获，首次决定 `ACCEPTED_CAPTURED`。同资格同种类换ID返回 `ALREADY_ACCEPTED`并引用原canonicalReceiptId，不再计数/扣费；POLICY_NOT_MET可用新的有意观测重试。结果同时返回当前结算快照（WAITING_FOR_BOTH/WAITING_FOR_VISIBLE/WAITING_FOR_CLICK/CAPTURED/RELEASED/EXPIRED/REJECTED），原决定不能被后续捕获覆盖。只有CAPTURED有ledgerId/chargedAmount，WAITING金额为空，零元合法捕获仍有账本。EXPIRED/NOT_HELD拒绝不补扣；已接受条件可恢复但不使释放状态复活。

锁序统一：不可变点击quote父行 → 回执唯一键 →（首次CLICK）同用户rate行 → 原预算日账户。准备和回收也先锁quote；每用例最多一个资格/用户/账户，不批量交叉持锁。回收 `FOR UPDATE OF quote SKIP LOCKED`，每笔独立短事务。时间取DB，拿到所有必要锁后再次检查原expiresAt；等待账户跨到期不能接受。预算日取预占开始的上海日，跨日捕获/释放仍作用原账户。创建拒绝无占用，账户不存在不自动创建。

反作弊复用 `recsys.ad.anti-fraud.enabled` / `maxClicksPerMinute`（默认true/20），独立同库实现，不调用旧Redis fail-open。每个SANDBOX userId一行，持锁后按实际决策DB时钟的UTC固定分钟窗归零计数；一资格第一次有效点击计一次，最多阈值个接受，重试/同资格换ID/无效证明/到期不计数。超限保存 `FRAUD_REJECTED`，同事务释放该资格，后到曝光不能收费；计数及拒绝与回执一起回滚。无新框架，不修改旧路径政策，也不声称客户端观测可证明真人行为。

第二条件到达同一事务写入：原条件回执、HELD→CAPTURED、原账户held→charged、唯一点击账本与Outbox；资格/点击/曝光均有唯一键和关联约束。任何失败回滚本次全部效果，先前已提交等待条件保留。新事件 `AdClickCapturedV1` / topic `ad-click-captured-v1`，包含两种canonical回执、CPC/OCPC、PER_CLICK、CLICK_GSP_CNY6_V1、完整冻结身份、原预算日、六位金额及accountVersion，big ID为字符串，不含token/认证主体。Outbox预留READY/SENDING/PUBLISHED/DEAD、租约/fence/有界20次；S11B只原子落库，S11C负责实际发布、Inbox及重复/乱序验收，父S11不能提前DONE。

`click-settlement-enabled=false`是安装/owner开关，须同时sandbox-enabled/clicks-enabled及V009；`click-preparation-admission-enabled`和`click-receipt-admission-enabled`分别控制新增，默认true但受安装开关约束。回退先关闭新增准备，保留在途回执到期、查询及回收；紧急停止回执仍保留原结果查询/重试和释放。不得关闭整个owner或回滚到无点击回收的版本而遗留held。沿用同源REQUIRES_NEW/READ_COMMITTED、1～30秒事务；单线程有界回收/退避/失败指标。长期保留和归档政策沿原项目待治理，当前不自动清理任何事实/去重数据。

允许改动：common-ad点击契约；ad点击Repository/Service/Configuration/ExpiryJob、V009及专项测试；本设计/契约/切片/验收/运行文档。无前端/新中间件/旧Behavior范围变更。验收覆盖双顺序与并发、同/异ID、跨主体/用途隔离、反作弊并发/回滚、共享CPM账户硬上限、到期账户等待/释放竞争、原预算日、故障恢复、原结果查询、不可变账本及注释/迁移/开关。S11C具体RPC/HTTP/投递协议随后单独细化。

## C11C1 正式可靠事件契约（S11C内部第一验证单元）

S11C仍须完成可靠事件和双模式交付/API；将其按数据库事件闭环C1、在线交付/API闭环C2顺序实施，未改变原范围或重排父DAG。C1消费C11B已固定AdClickCapturedV1，不提前增加HTTP入口。新增ad V010：点击Outbox人工重放审计、逐事件Inbox事实及跨种类回执唯一索引、按广告主/原预算日累计click_count/charged_amount投影；点击投影和CPM投影独立，组合账目才与共享账户总消费比较，不把点击当impression_count。

发送复用S06有界领取/DB租约/fence/20次领取/退避/ACK后标记，固定namespace只允许IMPRESSION或CLICK，不接受调用方表名。共享publisher/worker/consumer的有限回调适配只隔离事件校验、指标和投影，不泛化业务结算。新旧默认构造器与CPM事件保持原行为；Spring装配显式qualifier避免两族歧义，两个能力同时开启必须验证。

点击codec严格字段类型、CPC/OCPC、PER_CLICK/CLICK_GSP_CNY6_V1、SANDBOX/CNY、规范UUID/日期/字符串ID/六位金额、slot1～10/accountVersion正数，两个canonical回执不同；payload≤16KiB。未知新增字段可忽略，已知字段的规范载荷参与摘要，重复字段/尾随JSON/错误版本/错partition key拒绝，不能默认为零或当CPM解码。

Inbox对eventId、qualificationId、visibleReceiptId、clickReceiptId唯一，重复仅当同eventId同已知负载时成功，不丢弃较低accountVersion的另一独立事实。Inbox与日投影同本地事务，提交后才确认offset+1；提交后ACK丢失可重放，投影写失败回滚Inbox。消费者错格式/版本停止且不跳过位点；其余故障单条最多3次，停止后由原group恢复。DEAD只按原eventId/负载受控重放并记录actor/reason/replayId，不直接修改账本。

`recsys.ad.billing.click-events.relay-enabled`与`projection-enabled`默认false，仅需sandbox owner，独立于新报价/预占/回执admission；暂停新收费仍可发送已提交事实。topic默认ad-click-captured-v1，group默认recsys-sandbox-click-projection-v1；新命名空间独立配置及指标ad.billing.click.outbox/projection，沿既有Kafka受控连接配置和有界参数，无新依赖。V009/10兼容旧CPM实例，回退保留支持点击续发/投影的owner和历史，保留策略不擅定自动删除。

验收：真实PG唯一冲突、乱序、两种回执交叉重用、Inbox失败回滚、租约过期/旧fence拒绝、耗尽及人工重放；真实Kafka+PG发送ACK后DB失败重投、消费提交后ACK失败重放、未知版本不提交、原CPM/点击配置共存与回归。C1完成后继续C2，父S11保持IN_PROGRESS。
