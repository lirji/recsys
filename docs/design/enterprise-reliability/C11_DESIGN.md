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
