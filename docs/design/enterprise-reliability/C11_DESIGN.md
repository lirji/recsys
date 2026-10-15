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
