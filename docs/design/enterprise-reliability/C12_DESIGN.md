# C12：经广告主认证的CPA结算

2026-10-15；原ER-S12，依赖父S11已完成。Q-04本轮用户明确批准：sandbox归因/预占/转化接收均为签发起5分钟，每资格最多一次收费；转化可先到，持久化等待合格可见曝光；超时或已释放后只记非计费结果。原CNY六位HALF_UP、上海预占预算日、硬预算与图文可见政策沿用，不再次确认。

## 实施单元与边界

S12A先做实际纯CPA竞价→独立精确报价；S12B实现广告主作用域业务eventId、授权转化事实、等待/捕获/迟到非计费及原子账务；S12C补齐稳定交付、双模式/HTTP认证与可靠事件/投影。父S12只有完整链路验收后才DONE。不连接旧recordConversion→recordOutcome的多次独立写入来假装钱链路幂等，也不将CPA塞进点击或CPM票据。

广告主认证复用既有auth-platform/AuthzEngine及内部主体传播；新收费入口必须强制归属/fail-closed，不允许AdvertiserAuthz的disabled/shadow模式使之放行。可见曝光主体与广告主回调主体是不同责任，不把模拟userId当回调权限。业务键及权限一致性、发生时间/收到时间、非计费事件的具体正式协议将在S12B冻结；这些不阻止S12A无资金副作用的报价实现。

## C12A 正式精确报价契约

新增ConversionPrice：bidType仅CPA，unit=PER_CONVERSION，version=CPA_GSP_CNY6_V1。复用既有GspPrice的精确十进制比较/最终一次HALF_UP六位，不拷贝定价数学；每资格最大义务是完整的一次转化价，不能用pCTR/pCVR乘价格缩小预占。排序计价因子沿现有CPA=pCTR×pCVR×quality×relevance，手动出价及pacing上限；纯新路径不使用uplift assignment写入。上游浮点概率/排序只能冻结当时十进制表示，不声称还原丢失精度。

BiddingService.auctionSandboxConversion仅标准GSP+CPA；未知模式严格拒绝，明确的CPC/OCPC/CPM/OCPM不参与本场；VCG/Listwise显式拒绝。AdPipeline.sandboxConversionCandidates只召回/定向/排序/DCO并返回真实ConversionPrice，不写报价/预算/曝光/旧转化/outcome/事件。最终选择前零资金效果。

ad V012新增ad_conversion_quote，与原V008点击报价同样冻结quoteId、operationId/slot唯一、认证展示主体、模拟user、advertiser/ad/creative/item、title、完整价格依据、金额/单位/版本、长度编码hash及owner数据库签发时间。不可修改/删除；首次冻结须候选deadline尚未过，签发五分钟；同ID同负载恢复原金额/原期限，换位置/主体/上下文拒绝。HTTP/JSON大ID为字符串，服务间protobuf int64可无损表示。本片只内部报价，没有CPA收费或回调入口。

价格/报价使用独立类型和表，保持旧CPC/CPM读写及滚动版本兼容。当前CPA后续权限与业务键区别较大，先复用定价数学而保留显式领域类型，不为三个模式重做通用结算框架。追加迁移不改V001～V011；每表/列有中文注释，启动只校验checksum。

配置`recsys.ad.billing.conversions-enabled=false`，须sandbox及正确ad owner才装配报价。此开关仅安装报价，不触发新预占或收费；后续结算/在线开关独立细化。无新前端、中间件、依赖或共享环境部署。

## S12A 文件、验证与回退

common-ad ConversionPrice/FrozenConversionQuote及纯测试；ad实际竞价和纯候选入口、ConversionQuoteRepository/Configuration、V012和真实PG/竞价测试；本设计/契约/切片/进度及结果文档。

验收：完整转化义务、半微元/极小因子/金额上界；pCTR×pCVR因子及手动出价上限、排除非CPA、未知/非有限/不支持算法；真实PG并发同ID、冲突、原主体/金额/内容、原五分钟不延长、历史已过期恢复、注释/不可变与迁移漂移/default-off/错误owner；旧CPC/CPM资金链完整回归。回退关闭新报价开关，保留历史；当前无新资金义务无需清表，不执行真实收费迁移。


## C12B 结算实施契约（承接已批准Q-04）

本单元新增ad V013，独立CPA资格、可见回执、业务转化事实、不可变账本及捕获Outbox；与CPM/CPC共用ad_budget_account并持同一adDb短事务。不新增消息组件或全局结算框架。B片尚无公开接收入口；C片必须接上真实广告主权限和可信RPC后才允许对外使用。

资格等于quoteId，用conversion-qualification独立用途绑定完整报价摘要、展示主体、advertiser及原预算日。准备恢复原决定/原token，不续期、不重占；原预算日为首次预占开始时的上海日期。最大义务为完整ConversionPrice。只有HELD签发资格。

可见回执仍由原展示主体提交receiptId及观测信号，50%连续1秒且hidden重置；同ID相同负载恢复，不同负载冲突。转化命令由经授权的广告主边界提供actor和advertiserId，业务eventId为UUID，输入仅含原资格token及occurredAt（UTC Instant，最多微秒）；禁止注入金额或展示用户。幂等作用域固定(SANDBOX,advertiserId,eventId)，不同资格/时间复用同业务键为409；同广告主另一位当前有权主体可恢复相同业务请求，原actor只作不可变审计，不纳入业务内容摘要。展示主体与广告主actor不能互相替代。

接收边界必须先认证并检查该advertiser的edit权限，使用已有AuthzEngine fullyConsistent；disabled/shadow不能跳过，缺引擎/故障503，拒绝403，匿名401，ADMIN也需显式归属或平台关系授权。每次原结果恢复和GET也重新授权；RPC只接受独立advertiser服务caller，engine不能提交转化。授权网络调用在资金事务外完成，不宣称权限撤销能撤回已经开始的合法事务。

转化原事实独立于资金状态：首次合法业务键持久化occurredAt、接收DB时间、原actor与原决定；原决定不随后到曝光改写，查询同时返回当前结算。可见/转化任意先到皆可等待，第二条件在持有quote及原账户锁后重读DB时钟，严格早于issuedAt+5min才捕获。occurredAt必须>=issuedAt、<expiresAt且<=当前DB时刻；否则持久化OUTSIDE_ATTRIBUTION非计费事实。新转化在到期/释放后记录EXPIRED/NOT_HELD，不能恢复预占；同资格另一业务事件记录DUPLICATE_QUALIFICATION，引用原事件但不能视为它自身计费。合法零元仍产生唯一账本。

锁序quote→命令唯一键→原日账户；每命令仅引用一个资格，不跨资格取第二把父锁。原命令优先恢复并比对摘要，签名key退出后仍可查已提交事实；新事件必须验签且匹配保存的原token/报价/广告主。两条件齐备同事务完成held减、charged加、唯一账本、捕获Outbox和当前状态；任一步失败全部回滚。不调用旧recordConversion/AdOutcomeService，不用旧成功结果掩盖资金未知。

V013数据库唯一键约束每广告主事件、每资格账本及canonical可见/转化；外键绑定同一资格。准备字段/原决定/已接受条件不可变；事务结束不能残留PENDING命令。终态不能再捕获，期限回收SKIP LOCKED每轮<=100、固定延迟和失败退避，暂停新增与到期回收独立。所有表列中文注释；增量迁移不修改V001～V012。

B验证：真实PG两种到达顺序、重复/并发/跨资格同eventId/跨广告主同eventId、金额和原日、跨主体/错用途/篡改、未来或窗口外转化、捕获与释放/到期竞争、持锁等待越期、账本/Outbox/命令落库失败全回滚、零额、不可变/默认关闭/错数据源、CPM/CPC共享账户不超额。B只验证内部已授权命令与归属绑定，完整外部认证、HTTP/RPC及Kafka/投影属于C的必需验收；父S12在C通过前保持IN_PROGRESS。
