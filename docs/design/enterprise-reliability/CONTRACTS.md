# 企业可靠性正式实施契约

2026-10-15：用户已授权开始实施，并确认本文件第 1 节首版业务政策。按切片冻结正式契约；尚未冻结的接口继续留在 CONTRACTS_DRAFT，不得直接实现。

## 1. 首版已确认政策

- 服务端 sandbox 闭环先行，真实账户切换关闭；生产来源及期初账务另行核准。
- CNY，金额 6 位小数，每次收费按 HALF_UP 量化；预算业务日为 Asia/Shanghai，跨日回执结清预占日义务；额度调减不得小于已消费加预占。
- 首个图文 CPM 政策：创意至少 50% 可见、连续 1 秒；页面隐藏重置；资格和预占 5 分钟到期，服务端须在到期前接受回执。
- CPA窗口Q-04已于2026-10-15确认统一签发起5分钟、一资格最多一次收费、先到转化等可见曝光、超时/释放后非计费；正式账户迁移、容量/恢复目标仍未确认，不阻止无关切片推进。

## C01：既有预算展示与竞价报价（ER-S01）

保持既有 advertiser API 路由和字段名。`dailyBudget` 为元；`spentToday`、`remainingBudget` 为元，扩展为可空。Redis `ad:budget:{advertiser}:{yyyyMMdd}` 现有 writer 存储整数分，读取必须除以 100。此片不改变 writer、日期选取或收费算法；新版时区和六位账务由后续新链路承接。

仅非负整数计数可解释为有效旧计数；缺 key、非法/负计数、Redis 错误均返回两个字段 null，不能冒充 0 和完整预算。有效计数 0 显示 0，余额下界为 0。该值仍为旧计数估计，不是持久化账本证据，UI 明示“计数估计”。

兼容：仓库前端类型与消费者同片接受 null/缺失，未知时显示“待确认”且不画 0% 进度；旧数值正常路径不变。其他外部消费者若严格要求 number，需先支持 null 才升级此响应；本轮不部署。

`SponsoredAd.chargedPrice` 保持现有数值及字段，展示含义是竞价给出的单次收费报价，不是实收。当前实现 CPC/OCPC 为元/点击、CPM/OCPM 为元/曝光、CPA 为元/转化；未知类型显示单位未确认，不从名称猜“每千次”。`ecpm` 是当前实现的排序分，不与单次报价直接相减、比较或画成同一价格单位。不同计费单位报价不能求和为收入。缺失/非有限价格显示未知，不转为 0。

验收：125 分 → 1.25 元，100 元预算 → 98.75 元剩余；0 分 → 0 元；缺失/断连/非法计数 → null/待确认；超预算计数余额为 0；不同模式报价按单位显示，禁止 Σ实收和排序分价差推断。

## C02：sandbox 预算命令（ER-S02）

新接口仅在 `recsys.ad.billing.sandbox-enabled=true` 时装配，缺省关闭。广告管理端只允许已认证 ADMIN 进行此首版 sandbox 操作，且目标 advertiser 必须存在；不依赖可关闭的方法安全来放行匿名。广告主自助授权留待明确的 enforce 归属策略接入。ad-serving gRPC 边界要求已校验的内部调用方为 advertiser 服务；即使旧 nosec 开关关闭拦截器，新的预算命令也因没有可信 caller 而拒绝。

HTTP：`PUT /api/advertiser/{advertiserId}/sandbox-budget/{budgetDate}`，body 为 `{commandId, currency, limitAmount, expectedVersion}`。commandId 为 UUID，currency 固定 `CNY`，日期为 ISO 本地日期（预算含义固定 Asia/Shanghai）；金额为非负普通十进制字符串，最多 14 位整数、6 位小数，不接受指数/NaN/Infinity，不静默舍入额度。actor 取认证主体，不能由客户端传入。

查询：同路径 GET 读取当前账户；`GET /api/advertiser/{advertiserId}/sandbox-budget-commands/{commandId}` 读取原命令结果。新增独立 `billing.v1` proto service，与现有 `ad.v1` 方法共存；金额均字符串，ID 在 HTTP 响应按字符串返回。

账户键为 `(SANDBOX, advertiserId, budgetDate, CNY)`。首次版本 0、占用/消费 0；合法命令生效后 version + 1。持久化数值 `NUMERIC(20,6)`，数据库约束非负及 `held + charged <= limitAmount`。可查询 limit/held/charged/available/version、业务日、币种及 SANDBOX 来源；此片只修改 limit，不写 Redis 消费计数。

命令键为 `(SANDBOX, advertiserId, commandId)`，请求摘要包含日期、币种、规范化额度、expectedVersion 和认证 actor。同键同语义返回原结果快照，即使账户随后变更；同键不同负载拒绝冲突。合法请求的 `APPLIED`、`VERSION_CONFLICT`、`BELOW_COMMITTED` 结果都持久化，后两者不改账户额度；不得重试后悄悄从拒绝变成功。非法输入不进入事务。

数据库同一 adDb 事务内先领取命令唯一键，再锁账户、校验版本/占用、更新账户并记录结果；只有提交后才能返回结果。正确数据源绑定由启动校验与真实 PG 回滚测试保证。gRPC/HTTP 超时映射 503（结果未知），调用方用原 commandId 查询/重试，不当作未执行或成功；不存在结果只表示当前未查到，不能据此换 ID。

HTTP 已应用/查询成功为 200；版本或额度冲突为 409 并返回持久化结果；ID 负载冲突为 409 错误，缺账户/命令为 404，非法输入 400，未认证/无角色为 401/403。错误沿用平台 ApiError，结果状态使用显式 enum 名称。

迁移：使用 ad owner 独立目录 `recsys-ad/src/main/resources/db/ad-billing/V001__budget_account.sql` 的事务化版本脚本和持久化 schema history，显式应用到隔离 adDb；新能力启动检查版本，不自动修改共享/生产库。执行记录保存脚本 SHA-256，已执行文件不可改写；重复应用以版本冲突失败并回滚，后续变化新增 V002。本轮不新增迁移框架或基础设施。

验收：真实 PG 并发同 ID 只生效一次；不同 ID 同 expectedVersion 只一个成功；改负载冲突；调低到占用以下稳定拒绝；账务写入后结果保存失败整体回滚；服务重建后原结果可查；错数据源/缺迁移启动失败。RPC 测试验证身份、字段精度、错误与未知结果传播。

## C02A：首版 sandbox CPM 精确报价（ER-S02A）

沿用已确认的“首个图文 CPM 闭环”，新精确路径首版仅接收 CPM + 标准 GSP，其他计费模式不参与该场竞价；启用 VCG/Listwise 时新路径明确拒绝，不能退回旧浮点收费。旧 auction 方法及旧 ad.v1 数值语义保持兼容，CPC/CPA 与其他算法的新报价版本后续单独扩展。

计价版本固定 `CPM_GSP_CNY6_V1`，币种 CNY，单位 `PER_IMPRESSION`（元/单次曝光）。这是现有 CPM 的单次输入语义，不暗中除以/乘以 1000；改为每千次报价须另立版本及输入迁移。pCTR/排序分仍可浮点，但旧 SponsoredAd.chargedPrice 不能作为预占/结算输入。

BiddingService 的新方法在计算 GSP 价格前建立精确边界：旧目录 double 出价、pacing、保留价、增量、质量因子与次位排序阈值，先校验有限且非负，通过 BigDecimal.valueOf 取得其十进制表示；paced cap 以十进制出价乘 pacing，cap/floor/increment 按已定六位 HALF_UP 规范化。拒绝超范围或 cap < floor 的候选；质量因子须为正。由冻结的 numerator/denominator 精确比较与夹取，最后只在输出金额处 HALF_UP 六位，避免除法中间舍入。此桥接明确保留旧输入已存在的精度限制，不宣称从旧 double 恢复原始目录小数。

新输出包含展示广告与精确计价依据；展示 double 仅供兼容 UI，资金只引用精确报价。账户计价不重算模型、pacing 或 auction。冻结 owner 为 adDb：`quoteId` UUID 主键，`(SANDBOX, deliveryId, slot)` 唯一；slot 技术边界为 1～100，不构成容量承诺。记录广告主/广告/slot、精确单价、币种/单位/版本、规范化定价依据和摘要、DB 签发时间/5 分钟到期时间。同 ID 同输入返回原快照（到期后仍返回原到期时间），不同输入或同 delivery/slot 换 ID 拒绝冲突。

新增 V002 版本迁移，不改已交付 V001；仍默认关闭且仅显式迁移隔离 adDb。本片提供领域/持久化与 protobuf 快照往返，不开放客户端提交价格的接口；ER-S04 才把整次交付与可信 RPC 编排接通，ER-S03 仅凭服务端已保存 quoteId 预占。金额、来源和到期时间不得由终端指定。

验收：半微元、精确除法、上限/下限、极值与非有限输入；旧默认 auction 回归；新路径过滤非 CPM、拒绝未支持算法；真实 PG 同 quote 重试与并发、变负载/换 ID 冲突、重建后原金额/期限不变；protobuf 精确字段往返。不得用只有 NUMERIC 列而收费仍是旧 double 的方式完成本片。

## C03：sandbox CPM 预占与释放（ER-S03）

本片为广告 owner 内部用例与到期回收，不开放终端提交价格/广告主/预算日的接口；可信跨进程准备协议由 ER-S04 接入。请求由调用方持久化生成 reservationId（UUID）、quoteId、原交付主体 subjectKey。reservationId 为预占幂等键，quoteId 亦唯一，一份报价最多一笔预占；同 ID 改 quote/主体或同 quote 换 reservationId 均冲突。主体最长 200 字符，必须非空。命令非法/报价不存在不产生预占；已有预占重试返回首次 prepare 结论，查询另取当前状态。

账户、金额、到期时间取服务端冻结报价；预算日由预占命令首次进入 adDb 的数据库时间按 Asia/Shanghai 确定，跨日不挪动。账户须已由预算管理命令建立，不自动创建额度。首次 prepare 结论持久化为 HELD、INSUFFICIENT_BUDGET、QUOTE_EXPIRED 或 ACCOUNT_MISSING；即使之后补预算/跨日/状态变化，原结论不变。HELD 的当前状态独立为 HELD/RELEASED/EXPIRED，CAPTURED 留到 ER-S05 引入。

同一 adDb 事务内：领取预占唯一键 → 锁账户 → 再检查数据库当前时间严格早于报价到期 → 条件增加 held、递增账户版本 → 保存首次结论与金额/业务日/期限。可用额不够则拒绝并持久化结论；数据库约束最终保证 charged+held<=limit。结果只在事务提交后返回，服务重建可查询并同 ID 恢复。锁等待期间报价到期不能继续占用。

主动释放仅针对 HELD，记录服务端原因 DELIVERY_ABORTED；到期释放记 EXPIRED，由数据库当前时间判定，到期边界为 now>=expiresAt。释放事务先锁预占行再锁账户，held 减去冻结金额、账户版本+1，与预占终态/原因/时间一起提交；重复释放返回同终态，不重复减 held。到期后的主动释放也归 EXPIRED。当前拒绝记录不释放金额。

到期 worker 复用进程调度能力，缺省随 sandbox 开关启用；扫描间隔默认 1 秒、单轮最多 100 笔，均为受限技术配置，不作恢复时限承诺。每笔独立短事务，按 expiresAt/reservationId 选一条 HELD 到期记录 FOR UPDATE SKIP LOCKED，再按相同预占→账户锁序释放；并发执行器不重复，进程失败回滚后下一轮续办，不需要跨库租约。不得整批持有多个广告主账户锁，不做无界扫描或无界重试。

`recsys.ad.billing.reservation-admission-enabled=false` 停止新预占但保留查询/释放/到期 worker；开关通过受控重启生效，不宣称多实例瞬时 fence。worker 故障按有上限的指数退避加抖动，下一轮重新查询持久化待办，不吞错伪造已回收；永久数据不变量失败记录告警线索，停止本轮并保留行，不自动篡改金额。

新增 V003 的 reservation 表、唯一键/FK、状态约束、期限扫描部分索引与中文注释。沿用 C02 的显式 adDb 事务和版本校验；V001/V002 不改。查询是恢复证据，找不到不代表在途事务未执行。预占只调整 held，不写 charged、Redis 或旧 ad_event，不把预占当曝光/消费。

验收：真实 PG 余额只够一份的并发只成功一次；同 ID 并发/不同 ID 同 quote；调额和预占竞争不越额；拒绝结果稳定；数据库失败整体回滚；跨日义务仍归原预占日；提前回收不生效；双 worker 与主动释放竞争、重复回收、进程重建恢复不重复减 held。运行说明须包含暂停签发后继续回收的恢复边界。

## C04：sandbox 整次交付与准备（ER-S04）

本片先落搜索广告纵向入口，供 ER-S07 UI 使用；feed 最终混排调用同一准备能力，由 ER-S10 接入。旧入口保持兼容。新候选入口独立执行召回/相关性/定向/排序/CPM GSP/DCO，只读候选，不调用旧预算扣减、曝光日志、GD 交付计数或 uplift assignment；不可用须传播失败，不伪造成功空列表。正常无候选可完成为空。

HTTP 仅在 sandbox 开关打开时装配：已认证 ADMIN 使用 `PUT /api/sandbox/search-ad-deliveries/{operationId}`，body `{userId, query, slots, scene}`（userId 为正整数字符串；query 1～500 字符，slots 1～10，scene 1～100 字符）。subject 从认证上下文取，SANDBOX 来源服务端固定。GET 同路径读取已持久化进度；PUT 原 ID 原负载恢复执行。相同 ID 不同请求 409；不同主体 403；未找到 404 仅说明当前未观察到。COMPLETE=200，正在被其他执行器领取的 PREPARING=202；依赖/提交未知=503，保留同 ID 查询/重试。结果含不可变请求、最终 slot、候选展示快照及每项准备状态；拒绝条目保留原位置，不自动补位。

engine primary DB 独立拥有整次 operation，adDb 拥有报价、预占和准备结果，不跨库事务。先保存请求与首次实验桶/底价，再领取 30 秒有界租约（DB 时钟、递增 fence）；候选查询在事务外，完成最终去重/截断后，必须以仍有效 fence 原子保存整页计划及稳定 childId，才可调用 ad prepare。租约过期执行器不得保存旧计划或覆盖进度；已保存计划永不再查询候选。逐项保存结果并续租，网络调用不持有 DB 锁；重建进程按原计划和 ID 续办。GET 不产生副作用。单次最多 10 项、同步推进，不新建后台执行器；未完成操作由原 ID PUT 恢复。

候选证明由 ad owner 以专用 HMAC-SHA256 密钥签发，版本/用途分离；包含 operationId、认证 subject、模拟 userId、offerId、创意展示快照、精确 CpmPrice、实验桶及五分钟候选受理期限。engine 只转发已冻结证明，终端无候选/价格输入接口。prepare 校验签名、用途、主体、父操作和最终 slot；childId 等于 offerId，同一候选不得重复放入另一位置/操作。候选受理期限是技术防陈旧窗口，资格/预占期限仍严格取报价签发后的五分钟，不因恢复刷新。

ad owner 持久化 preparation 输入摘要（同 childId 变 slot/证明/主体冲突）后，按可恢复步骤冻结报价→预占→保存不可变准备结果。每一步提交后可独立重试；没有跨步骤原子性承诺。报价只有在候选期限内才能首次创建，已有报价恢复不续期；首次受理已过期则保存 OFFER_EXPIRED。预占与结果之间失败仍由原 childId 恢复，未回执占用由已有到期 worker 回收。准备结果为 QUALIFIED、INSUFFICIENT_BUDGET、QUOTE_EXPIRED、ACCOUNT_MISSING 或 OFFER_EXPIRED。QUALIFIED 保存签名资格，绑定父/子操作、subject/user、广告/创意、最终 slot、报价/预占 ID、CNY 精确金额、SANDBOX、可见政策版本（50% 连续 1000ms，hidden 重置）、签发/到期。重试查询返回原 token 和期限；已过期原结果不代表仍可收费，ER-S05 必须再查持久化资格、预占状态和服务端时间。

新增 delivery.v1 gRPC：Candidates、Prepare、GetPreparation，仅已验证的独立 engine 内部身份可调用，即使 nosec 仍拒绝无身份。两种 serving mode 使用相同领域 DTO；新方法不套旧空响应 fallback/吞错重试。grpc 模式 engine 不装配本地新账务 owner/回收 worker；ad-serving 和 in-process engine 装配 owner。此条件不重构旧广告模块连接池。

密钥只来自受控配置，当前 keyId 与至少 32 字节 base64 key 必填，无默认秘密；可同时配置旧验证 key，轮换时所有实例一致，旧 key 至少保留到未完成准备及资格恢复窗口结束，删除前查待办。token 不进日志、指标或 HTTP 候选输入，大小有界；数据库与备份按敏感配置权限管理。不在此片自动清理幂等/操作/资格记录，业务保留期限尚待确认，运维需监控增长。

迁移：ad owner V004 preparation（不改 V001～V003）；engine 独立 `db/engine-delivery/V001__delivery_operation.sql` 和 SHA 历史。默认关闭、显式迁移、缺表/错版本拒绝启用。验收覆盖纯候选无收费副作用、签名篡改/用途/主体、真实 PG 部分失败恢复/预算不重复/过期、不变整页计划、租约 fencing、双模式 DTO/RPC 权限与失败传播。没有部署或真实收费切换授权。

## C05：可见回执、CPM 捕获与账本/Outbox（ER-S05）

只实现已确认图文 CPM 政策和 SANDBOX。在 sandbox 开关之外增加默认关闭的 `recsys.ad.billing.receipts-enabled`；只在 owner 与 engine 都显式启用时接受回执。未安装回执能力时此开关保持关闭，不停止既有预占回收。启用后暂停新接受使用 `recsys.ad.billing.receipt-admission-enabled=false`（默认 true），保留原 receiptId 结果恢复/查询；不要用关闭整个能力代替在途恢复。OCPM/CPC/CPA、浏览器采集及 Kafka 投递不在本片，分别由后续切片承接。

HTTP `PUT /api/sandbox/ad-impressions/{receiptId}`：ADMIN 原主体，body `{qualificationToken, visibleDurationMs, minVisibleRatioBps, pageVisible}`。receiptId 为 UUID；时长为整数毫秒 0～300000，比例为整数基点 0～10000，pageVisible 必须 JSON boolean；报价/广告主/预算日/金额不接受终端输入。达到政策须 minVisibleRatioBps>=5000、visibleDurationMs>=1000、pageVisible=true，且 DB 接受时刻减资格签发时刻至少覆盖声明时长。隐藏重置的连续性由前端观测，服务端拒绝显然不可能证据，但不声称能证明真人可见或防伪终端。GET 同路径按原主体查首次结果。新 impression.v1 RPC 仅可信 engine 调用；两种 gateway 同行为，故障结果未知，禁止成功 ACK 兜底。

使用专用 qualification 用途验签，并比对 adDb 已保存的 QUALIFIED 原 token、主体和签名绑定字段；不能凭未完成 preparation 或 offer token 收费。格式/签名/绑定非法返回 400/403，不写资金。receiptId 同负载返回原结果，同 ID 不同负载 409；摘要含主体、资格 token 和规范化观测信号。已保存同 ID 原结果可直接查询/恢复，不受后来 expiry 或 key 轮换改写；新事件仍须可验证有效 key。GET 404 仅表示当前未查到。

业务幂等键为 `(SANDBOX, qualificationId, IMPRESSION)`：每资格最多一笔 CPM 捕获账。不同 receiptId 对已捕获资格返回 ALREADY_ACCEPTED，引用唯一原账本/原 receiptId，不重复记曝光、钱或 Outbox。合法签名但未达政策返回 POLICY_NOT_MET；到期返回 EXPIRED；已主动释放/拒绝返回 NOT_HELD。这些结果都按 receiptId 持久化；后续新的观测意图使用新 receiptId，不改变原拒绝。ACCEPTED/ALREADY_ACCEPTED 返回 200，持久化业务拒绝返回 422，状态与实收金额明确；系统/提交未知 503，保留同 ID 查询。

同 adDb 短事务：领取 receipt 唯一键 → 锁 reservation → 检查既有 ledger → 锁原预算日账户 → 取新鲜 DB 时间，严格早于 expiresAt 且满足政策 → 条件 held 减 amount、charged 加相同 amount、账户版本递增 → HELD 变 CAPTURED → 插入唯一不可变 ledger → 插入 Outbox → 保存 receipt 结果。任一步失败整笔回滚；返回只在提交后。释放和捕获都按 reservation→account 锁序竞争，一笔仅到 CAPTURED/RELEASED/EXPIRED 之一。跨日用原 reservation.budgetDate，金额只取原精确 quote，不重算、不从客户端读取。

ledger 为不可变业务事实，唯一 reservation/原 receipt；记录原预算日、广告/创意/slot、用户、金额/CNY/SANDBOX、父操作/资格、接受时间和账户版本。使用数据库约束及禁止改删的触发器保护正常写入路径；更正以后走显式补偿事实，不改历史。Outbox 与 ledger 一一对应，事件 `AdImpressionCapturedV1` 使用 eventId=ledgerId，包含明确版本/来源/计价单位及关联业务 ID，金额为字符串、接受时间为 ISO-8601 字符串，不含 token 或认证主体。分区键为 advertiserId+预算日；S06 逐事件幂等投影，不因账户版本已前进而丢弃旧 ledger 事实。本片只持久化待发事件，禁止先发送 Kafka 后记账。

ad V005 扩展 CAPTURED 状态与约束并新增 receipt、ledger、Outbox；不改 V001～V004。滚动启用先完成扩展迁移及全部 owner 二进制升级，再打开 receipts-enabled；旧二进制不能读取 CAPTURED，启用后不可直接回退到只认识 S03 状态的版本。回退停止新接受，保留新版查询、回收及后续 Outbox 处理，数据历史不得删除。engine 无新表。gateway 的发现/静态路由均补 `/api/sandbox/**` 到 engine，并明确 ADMIN 边界，作为两个 sandbox API 的最小运行接入；不改用户已有 Docker/启停文件。

验收：真实 PG 同 ID/不同 ID 同资格并发只一账一事件；重复/改负载/错主体/错签名/未达政策/到期；账务写入、ledger、Outbox、结果任一步故障整体回滚；捕获与主动/到期释放竞争；锁等待跨过到期仍拒绝；跨日只动原账户；重建可查原 receipt/ledger/待发事件。RPC/HTTP 权限、精度、错误/未知结果和两模式一致，真实 broker 与浏览器由 S06/S07 证明。

## C06：账本 Outbox 与幂等消费投影（ER-S06）

沿 C05 不可变 AdImpressionCapturedV1；只由账务 owner 发送，逻辑 topic 为 ad-impression-captured-v1，分区键 advertiserId:budgetDate。不承诺跨分区或多 relay 实例的全局顺序。消费以 eventId=ledgerId 去重，每个独立账本事实都累加，不以 accountVersion 做丢弃水位。同 eventId 改已知业务字段、同 qualification/receipt 换 eventId 均拒绝；v1 允许新增未知字段，未知 schemaVersion 拒绝且不提交该位点。

ad V006 扩展 Outbox 的 READY/SENDING/PUBLISHED/DEAD 状态、尝试次数、数据库可领取时间、递增 fence、受限错误分类和重放审计。领取一行使用 SKIP LOCKED 短 SQL 原子更新并提交，30 秒租约；事务外等待 Kafka acks=all 确认，成功后按 fence 且租约未过期标记 PUBLISHED。发送/标记之间崩溃允许重复；超时未知不得当成功。单次客户端发送有截止，记录最多 20 次领取（崩溃也计次），指数退避加抖动至 60 秒，耗尽进入 DEAD 保留原负载。人工重放须原 eventId、原 payload，持久化 actor/reason/旧尝试次数；不改历史资金。状态标记失败后由租约续办，旧执行器不能覆盖新 fence。

投影位于广告 owner adDb 的独立只读模型：逐事件 Inbox/明细与 advertiser+budgetDate 日汇总在同一短事务提交，不读写账户/权威账本，不设跨服务 FK。消费手动提交每条 offset+1，只在事务成功后；DB 成功 ACK 失败后原位点重放，由 Inbox 保证不重复累加。投影明细为不可变事实，可重建，不是收费权威。broker ACL 必须限制来源，不把来自 topic 的任意 payload 当签名计费指令。

复用宿主已使用的 Kafka 客户端/BOM，不新增中间件或全局调度。owner 专用有界单线程 relay，单轮最多 10 条；投影消费者单线程、max.poll.records=10、禁止 auto commit/auto create。单条处理最多 3 次，每次退避；永久错误/重试耗尽停止该消费者并告警，保留未提交位点，禁止跳过坏事件或吞错 ACK。修复后重启恢复；不宣称可绕过毒消息继续消费。停机停止领取、wakeup 消费、有限等待，未提交由持久化租约/位点恢复。

配置独立默认关闭：recsys.ad.billing.events.relay-enabled、projection-enabled；须 sandbox-enabled，且为 owner。不依赖 receipts-enabled，以便停新增后继续待发处理；仍须 V001～V006 验证。连接复用 spring.kafka 的受控连接/安全属性，资金相关客户端参数由专用工厂强制固定。topic/group 可显式设置用于环境隔离，默认 topic 为上述逻辑名，group 为 recsys-sandbox-billing-projection-v1。开关受控重启生效，无动态原子切换承诺。Topic 显式预建，禁止 compact（分区键不是 eventId），开发单副本不能推断生产 RPO；保留期尚未确认，首次 sandbox topic 使用 retention.ms=-1/retention.bytes=-1 并监控增长，不自动删除 Outbox/Inbox。

验收：真实 PG/Kafka 的同库提交后未发送、broker ACK 后未标记、投影提交后未提交位点三种故障窗口恢复；重复/乱序/不同账户版本均逐事实正确；两执行器领取/过期 fencing/重试上限/审计重放；不可变投影、坏 schema/改负载不 ACK；断连恢复；默认关闭与两宿主 owner 边界。只在临时容器/唯一 topic 故障注入，不停止共享 dev_infra 或迁移共享库。CI 钱链路门禁涵盖这些测试及 RPC/HTTP/gateway。

## C07：sandbox 搜索可见曝光采集（ER-S07）

沿已评审 PLAN 的页面/组件/状态方案，仅 /search-ads。VITE_SANDBOX_EXPOSURE_ENABLED=true 显式构建启用新页面分支；默认 false 保留既有页面。新分支不挂载旧请求 hook、不调用旧广告 GET/点击/转化/outcome，后端未启用或拒绝时显示错误，不回退旧收费接口。无新路由、UI 库或状态框架；仍为 ADMIN 调试入口。

首个请求由用户主动点击“新交付”，生成 operationId 并冻结 userId（十进制字符串）、query、slots、scene；PUT/GET 完全使用 C04。202/PREPARING/503 显示原 ID 与“恢复原操作”，不自动换 ID。刷新只恢复同认证范围内的原意图并查询，不自动创建新交付；恢复 PUT 保留原参数。COMPLETE 后新交付才生成新的操作 ID；列表按服务端 slot 固定，不提供重排/分页。历史“重跑”是明确的新意图，历史/对比/原始 JSON 展示本身不采集。

实际创意文本区域由 IntersectionObserver 观测，至少 50% 且 document 可见，performance.now 连续一秒后冻结 C05 信号和 UUID receiptId。离屏、hidden/pagehide、卸载、已知遮盖层打开重置计时；不支持观测则明确提示，不以定时器假定可见。资格与回执绑定原响应 userId/childId，不读取后续编辑表单生成事件；当前已确认 CPM 政策固定版本，不接受客户端更换政策。

公共队列在同源会话内按认证范围（origin、认证模式、issuer、sub、tenant）隔离，最多 100 条、512 KiB、保留最多一小时，不存 Bearer/refresh token。只恢复结构有效且同范围条目；storage 不可用时内存发送并提示刷新不可恢复。并发最多两条，自动最多六轮、指数退避上限 30 秒；同资格始终复用 receiptId。同 ID 的未知结果先 GET，404 在未到本地期限时才按原 body 重试 PUT；到期后只查询，不把 404 当未收费。超过自动上限保持待确认并提供同 ID 手工查询；客户端期限只限制发送，服务端期限仍为权威。只在服务端 ACCEPTED/ALREADY_ACCEPTED 后显示“已确认消费”；422 显示原拒绝，超时不显示成功或零消费。会话关闭/清存储可能丢失未提交事件，不能据此宣称浏览器零丢失。

认证请求增加仅用于本片的 expected scope/epoch 守卫，放在实际解析当前 Bearer 后、发送前；401 单次续期也重新核对，防止身份切换后用新 token 发旧主体事件。退出/切换开始即撤销旧 epoch、取消在途请求并清理本片队列/意图；已经抵达服务器的请求仍可能完成，不能称取消已撤销费用。OIDC 同主体续期不丢弃队列。身份范围变化后迟到结果不能覆盖新界面。

使用现有 Ant Design 表单/Table/Tag/Alert、HistoryDrawer/ResultDiff/TracePanel；增加 ExposureScope、useVisibleExposure、ReceiptStatus 与 sandbox 页面组件，不修改其他页面采集行为。金额保持服务端十进制字符串，不用 JS 浮点重算；报价与已确认消费分列。历史 push 与 TracePanel 的显示/复制/下载统一递归脱敏 qualificationToken/offerToken/authorization/accessToken/refreshToken；历史只留脱敏快照，不把票据传入历史测量组件。

验收：Vitest 覆盖测量状态、队列边界/重试/恢复/身份切换和续期守卫；Playwright 覆盖实际滚动/hidden/重挂载、表单编辑原身份、刷新同 ID、历史与导出无票据，桌面/窄屏截图实际查看。真实后端的原子捕获与 Kafka 投影联调使用隔离数据库种子，页面不硬编码 mock；受控模拟响应的 UI 测试单独标识，不冒充真实联调。依赖和 UI 范围与已批准 F07 一致。

## C08-A：自然资格的共享值与签名协议（ER-S08 独立前置部分）

共享结构与下节 C08-B 的参数化机制冻结；自然业务政策已获用户确认并记录于 C08-C，能力仍默认关闭。政策使用显式元组 `(version,minRatioBps,minDurationMs,ttlMs)`，没有隐式沿用广告的默认值；behavior 接受时必须与其已批准的政策一致。参数化机制测试显式标识 test-only 政策；正式装配要求 C08-C 已批准元组，不能用测试政策开启能力。

`NaturalQualification` 固定 SANDBOX 来源、kind=RECOMMEND/SEARCH、operationId/qualificationId UUID、subject（非空最长 512）、userId/itemId 正 long、slot 1～50、scene（非空最长 100）、bucket（允许空最长 2000）、contentHash（64 位小写 SHA256）、Policy、issuedAt/expiresAt（UTC 毫秒精度）。日期跨度严格等于政策 ttl；文本长度按 Java UTF-16 单元计。政策 version 为 1～64 个 ASCII 字母/数字/点/下划线/连字符，比例 1～10000 基点、连续时长 1～300000 毫秒、ttl 从时长到 86400000 毫秒；这些是解码/资源边界，不是业务推荐值或默认值。隐藏重置是固定连续观测语义。

票据格式 `nq1.<keyId>.<payload base64url 无填充>.<HmacSHA256 base64url 无填充>`，完整前三段 UTF-8 字节参与 MAC。keyId 1～40 个 ASCII 字母/数字/下划线/连字符；最多 16 个 key，各 key 解码至少 32 字节、最多 128 字节。资格专用前缀禁止与广告/内部认证票据混用，最大 16384 字符；先验签再解码，拒绝未知版本、非规范 base64url 和多余负载字节。常量时间比较 MAC，不接受算法字段/降级。

payload 顺序固定，用 Java DataOutputStream 的大端编码：两个 UUID 各写 most/least 两个 long；writeUTF(subject)，long userId/itemId，int slot；writeUTF(kind.name)、writeUTF("SANDBOX")、writeUTF(scene)、writeUTF(bucket)、writeUTF(contentHash)、writeUTF(policy.version)；int minRatioBps/minDurationMs；long ttlMs、issuedAt epochMillis、expiresAt epochMillis。writeUTF 明确是 JDK modified UTF-8；这是 engine→behavior 的 Java v1 不透明票据，客户端不得解析。将来换编码必须新版本并验证共存，不静默换成标准 UTF-8。

common 维持纯 JDK 契约，无 Jackson/web/ad 依赖。签发端必须提供 currentKeyId；验证端可只提供验证 key 环。轮换时旧 key 验证原资格、新 key 签新资格；移除 key 前核对在途窗口。codec 不判断当前到期，也不连接库；服务端接受时间/幂等恢复由后续 behavior 事务处理。`toString` 不输出原 subject/key/token，错误不回显输入。固定格式、作用域、篡改、轮换、边界、毫秒/大 ID 无损由纯契约测试验证。


## C08-B：自然交付与可信回执的参数化机制（ER-S08）

本节冻结 API、数据所有权及事务机制。自然能力默认关闭，签发/接收都必须显式配置完整且一致的 Policy，不提供隐式业务默认值。业务数值答复见 C08-C；底层参数化机制另使用明确的 test-only 政策作边界测试。现有 API、旧曝光/行为表、旧 Redis/指标不变。

engine：ADMIN `PUT/GET /api/sandbox/natural-deliveries/{operationId}`。PUT body `{userId,kind,size,scene,query}`；userId 正 long 十进制字符串，kind RECOMMEND/SEARCH，size 整数 1～50，scene 非空最长 100；SEARCH query 非空最长 500，RECOMMEND query 必须 null。UUID 采用完整规范字符串。主体从已认证上下文取，不能从请求指定。operationId 固定原请求与首次配置的政策；同 ID 改请求 409、异主体 403。PREPARING HTTP 202；COMPLETE HTTP 200；GET 未观察到 404；依赖或提交未知 503，保留原 ID 恢复，不生成成功空结果。

响应 `{operationId,trafficClass,state,userId,kind,size,scene,query,policy,bucket,traceId,modelLineage,items}`；大 ID 均字符串，SANDBOX 固定；modelLineage=UNVERIFIED，不捏造模型版本。PREPARING bucket/traceId 为空、items=[]；COMPLETE 可正常为空。条目 `{slot,itemId,title,category,description,score,recallFrom,reason,qualificationId,qualificationToken,issuedAt,expiresAt,contentHash}`；文本 snapshot 与签名绑定，title<=2000、category<=200、description<=16000、reason<=4000，recallFrom<=50 且各项<=100，非有限 score 拒绝。缺内容为依赖失败，不能签发空白/伪造创意。内容摘要为明确字段顺序 title/category/description 的 JDK writeUTF 大端编码 SHA256；tags 不用于首版展示。时间 ISO-8601 UTC 毫秒，响应总存储上限 4 MiB。

engine V002（依赖已交付 V001）保存原请求、政策、PREPARING/COMPLETE、30 秒 DB 租约/fence 与完整结果。候选计算、内容读取不持有 DB 事务；新候选路径跳过 ExposureLogger/Redis 曝光键/旧分母，缓存仅复用候选并清除旧 exposureId，不写回资格。最终按候选顺序去重、截断到 size 后固定 slot 和独立 UUID；DB 时间签发，在仍有效 fence 条件下单条更新原子冻结完整结果，提交后才能对外交付。旧执行器不能覆盖或释放新租约。失败允许原 ID 恢复；已完成绝不重算内容、桶、政策或期限。

behavior：ADMIN `PUT/GET /api/sandbox/natural-impressions/{receiptId}`。PUT 严格接收 `{qualificationToken,visibleDurationMs,minVisibleRatioBps,pageVisible}`，时长整数 0～300000、比例整数 0～10000、可见必须 boolean；不接受终端 item/bucket/来源/金额。结果 `{receiptId,qualificationId,status,eventId,canonicalReceiptId,acceptedAt,decidedAt}` 无金额字段；status ACCEPTED/ALREADY_ACCEPTED 返回 200，POLICY_NOT_MET/EXPIRED 返回 422；错误 400、401、403、404、409、503 使用稳定 `NATURAL_RECEIPT_<status>` code，未知不伪造拒绝。GET 返回已存结果（包括业务拒绝）的 200。验签、签名主体与完整 Policy 一致；未知 key、用途或 Policy 拒绝。每份新资格必须能覆盖声明时长，DB now >= issuedAt+visibleDurationMs 且 now < expiresAt；比例和连续时长达到 Policy 且 pageVisible=true。客户端连续观测不是防伪终端或真人认证。

同 receiptId 同负载先返回原结果，不受后来 key/Policy/接受开关变化影响；改负载 409、异主体 403。命令摘要为固定顺序 UUID/subject/token/观测值的 JDK 二进制 SHA256。新 receipt 先验签；behavior 本库短事务依次领取 receipt 唯一键、插入/锁 qualification 唯一上下文（C08-A 固定 payload 二进制 SHA256，不含签名 keyId/JSON 编码差异）、核验同 ID 同上下文、读取新鲜 DB 时间；已接受返回原 canonicalReceiptId/acceptedAt，其他按政策拒绝或原子插入事实+Outbox+首次结果。锁等待跨过期限必须拒绝；REQUIRES_NEW / READ_COMMITTED / 5 秒，禁止继承外层长事务或持锁访问 MQ/Redis。qualification 冲突、任一步持久化失败整体回滚。PK/UNIQUE/FK、提交时完整性约束和不可变触发器为最终防线。

自然事实只允许 SANDBOX，qualificationId 唯一（同时 eventId），canonical receipt 唯一，保存签名上下文及接受时间；主体只在 owner 受限表，不写事件。Outbox 事件 `NaturalImpressionAcceptedV1` schemaVersion=1，destination=`natural-impression-accepted-v1`，partitionKey=userId。payload 字段 eventId/eventType/schemaVersion/trafficClass/operationId/qualificationId/receiptId/userId/itemId/slot/kind/scene/bucket/contentHash/policy/issuedAt/expiresAt/acceptedAt，大 ID 字符串、UTC ISO 时间、不含 token/subject。本片仅存待发，S15 才接可靠投递，不宣称已到 Kafka。

behavior 自有 `db/natural-exposure/V001__natural_receipt.sql` 及 SHA256 历史；engine 不写 behavior 表，behavior 不查 engine/ad 表。迁移显式执行，启动只校验，缺表/摘要漂移拒绝启用。两类端点分别用 `recsys.natural.delivery-enabled` / `receipts-enabled` 默认 false，`admission-enabled` 默认 true（关闭后仍查/重试原结果）；Policy 与 key 环经各 owner 的类型化启动配置校验，禁止运行中未审计热刷新。gateway 两模式将 natural-impressions 专属路由置于 engine sandbox 通配路由之前，仍受 ADMIN 校验。

不自动删除操作/资格/事实/幂等/待发记录，保留期未确定；开启前确认容量并监控增长/未发事件。暂停新签发/接受保留新版本查询及原结果恢复，不能用回滚旧页面或二进制撤销已存自然事实。没有真实收费/生产部署授权。验收按 C08_DESIGN 的真实 PG 并发、故障窗口、权限、时钟与旧路径回归完成后记录；政策答复与自然页面验收仍单列，不提前标全链路 DONE。


## C08-C：已批准自然图文政策（2026-10-15 用户回复）

用户已明确选择自然推荐/搜索（不计费、仅 sandbox）沿用“至少 50% 连续可见 1 秒、hidden 重置、资格 5 分钟到期且到期前服务端接受”。稳定政策版本为 `natural-text-v1`，完整元组 `(version=natural-text-v1,minRatioBps=5000,minDurationMs=1000,ttlMs=300000)`。不宣称行业认证或真人注意力证明。

common 的 TEXT_POLICY_V1 为已批准值；engine/behavior 正式配置装配都要求显式元组完全相等，缺字段、未知政策版本或数值漂移拒绝启动。底层服务仍可在隔离机制测试中显式注入 test-only 政策；该测试值不得用于正式配置。能力开关默认关闭不变，不自动迁移、启用共享环境或部署生产。

已签资格绑定原政策和期限；跨进程受理以 behavior 锁后数据库时钟为准。达到 5000bps/1000ms/pageVisible=true 且服务器经过时间覆盖声明、严格早于原 expiry 才接受；拒绝结果不因后来达标而修改。前端 hidden 重置/实际采样由 S09 实施，本后端不把声明当防伪终端证明。S08 验收增加已批准政策的真实 PG 签发期限、临界接受、阈值不足和 hidden 拒绝；S09 接浏览器，S15 接可靠投递，不能将这些后续验收提前声明完成。

## C09：自然浏览器（ER-S09）

冻结范围与执行决策见 [C09_DESIGN](C09_DESIGN.md)，沿用已批准 PLAN/REVIEW，不改变 C08-B HTTP 或 C08-C 政策。/recommend、/search 通过默认关闭构建开关接显式新交付、原操作恢复和自然可见回执；ID 字符串，冻结文本/原请求，历史对比只读脱敏。两协议共享有界恢复算法，独立类型/存储/100 条与 2 并发预算；自然无金额字段。验收覆盖共享广告回归与隔离 PG 的浏览器完整路径，S15 之前仅持久化自然 Outbox。

## C10：最终 Feed 混排交付（ER-S10）

正式冻结细节见 [C10_DESIGN](C10_DESIGN.md)。仅 sandbox ADMIN `PUT/GET /api/sandbox/feed-deliveries/{operationId}`；固定原请求/混排配置，最终位置持久化后才准备广告。自然资格新增稳定 kind=FEED，仍为原自然图文政策；旧自然交付端点只接 RECOMMEND/SEARCH。先升级验证端，再启用新签发。自然/广告混排去重，广告位沿用1～10，列表1～50。engine V003 固定完整计划及自然票据，只允许原广告准备结果单调补齐；PREPARING 可返回已冻结自然和已确认广告，未知项占位不采集。纯候选故障可冻结全自然，prepare未知只能恢复原child/slot，禁止重混排。

新增纯 `FeedCandidates` RPC 与同进程入口，ad owner 基于 sandbox 唯一接受账本进行当日频控；ad V007索引，准入过滤不冒充严格并发频次上限。预算硬上限、收费、资格/回执、自然Outbox归属均不变。新能力与页面默认关闭，复用两条有界队列和既有独立前端规划，不改变旧收费路径或生产部署状态。

## C11A：点击精确报价（ER-S11A）

用户已确认CPC/OCPC每资格最多一次收费、签发起五分钟资格/预占/点击窗口；点击先到持久化等待曝光，两者到期前接受，释放后不补扣。正式报价与分阶段边界见 [C11_DESIGN](C11_DESIGN.md)：独立CPC/OCPC标准GSP、PER_CLICK / CLICK_GSP_CNY6_V1、纯候选与不可变ad V008报价；不把点击接入旧CPM票据/曝光捕获。S11A只有报价、默认关闭，S11B/C完成前不能宣称点击收费闭环。

## C11B：点击预占与原子结算（ER-S11B）

正式内部契约、V009表族、锁序、反作弊、两个条件的原始决定/当前状态、签名用途、默认关闭和在途恢复见 [C11_DESIGN 的 C11B](C11_DESIGN.md#c11b-正式结算契约2026-10-15-冻结)。一quote一资格，共享原预算账户；不复用CPM回执。WAITING无实收金额，二者在原期限前接受才原子产生唯一账本和AdClickCapturedV1 Outbox。S11C可靠发布和双模式边界完成前父片保持IN_PROGRESS。

## C11C1：点击事实可靠传播（ER-S11C1）

S11C内事件验证单元，正式字段校验/Inbox幂等/投递与位点恢复/开关/回退见 [C11_DESIGN C11C1](C11_DESIGN.md#c11c1-正式可靠事件契约s11c内部第一验证单元)。新增V010点击重放审计、事实和日投影，复用S06有界可靠机制；两个独立事件/投影不能相互冒用。S11C2在线交付与双模式API仍必需，父S11不提前DONE。


## C11C2：点击稳定交付与在线边界

正式协议见[C11C2设计](C11_DESIGN.md#c11c2-正式在线交付与双模式边界)。独立click-offer/click-qualification、ad V011/engine V004检查点与固定模式复用；三组ADMIN HTTP路径和SandboxClickService RPC，原ID恢复、原决定与当前状态分离。C04/C05旧协议保持不变，新路径默认关闭，完整双模式验证后方可完成父S11。


## C12A：精确CPA报价

正式协议见[C12A设计](C12_DESIGN.md#c12a-正式精确报价契约)。ConversionPrice固定CPA/PER_CONVERSION/CPA_GSP_CNY6_V1，复用精确GSP；ad V012独立不可变五分钟报价，纯候选在最终选择前零资金效果。Q-04已批准，但本片未实现广告主转化接收或扣款。


## C12B：CPA业务转化与双条件资金

正式协议见[C12B设计](C12_DESIGN.md#c12b-结算实施契约承接已批准q-04)。转化eventId为广告主作用域UUID，原actor审计、同业务请求可由当前另一个有权主体恢复；occurredAt与接收时间都须在统一窗口。独立可见/业务事实表和一资格一账本共享硬预算，等待/迟到/重复结果可解释；回收及暂停独立。B只建立内部owner闭环，C必须通过严格广告主归属、独立caller及双模式公开入口与Kafka验收后才算S12完成。


## C12C1：CPA可靠捕获事实

正式协议见[C12C1设计](C12_DESIGN.md#c12c1-可靠捕获事件与投影)。AdConversionCapturedV1独立版本/目的地与唯一业务事实索引，复用固定EventFamily和有限投递；V014新增Inbox、重放审计和日投影。每广告主conversionEventId唯一，跨广告主可重复；两类身份命名空间不混同。只有本地投影事务提交后允许确认位点，C2公开授权入口仍是父S12必需工作。


## C12C2：CPA稳定交付与广告主认证

正式协议见[C12C2设计](C12_DESIGN.md#c12c2-稳定交付与外部授权边界)。独立conversion-offer、ad V015/engine V005和conversion.v1；展示/可见仅独立engine，转化仅独立advertiser caller。广告主PUT/GET每次强制edit/fullyConsistent，不受disabled/shadow或ADMIN绕过；业务事件自身实收与资格实收分离，迟到/重复不能被误读为再次收费。外部边界验证通过后才完成父S12。


## C13A：账户预算日一致快照

正式字段、差额方向、逐事件身份核查及状态定义见[C13设计](C13_DESIGN.md#c13a正式契约内部只读账户日快照)。单adDb只读REPEATABLE_READ事务，账户缺失/依赖未知/投影落后分别表达，零额也核对笔数。A只有内部查询；父S13仍要求B持久化有界任务、C受控续办/RPC/离线作业与PG/Kafka恢复验收。

## C13B：持久化只读对账任务

正式契约见[C13设计](C13_DESIGN.md#c13b正式契约持久化有界只读任务)。单广告主最多31原预算日，单日领取/只读快照/报告检查点原子提交，30秒lease与version fence、每日期20次上限、审计与幂等取消。内部接口、默认关闭、无收费/重发副作用。

## C13C：受控单目标续办及可信RPC/offline

正式契约见[C13设计](C13_DESIGN.md#c13c正式契约单目标受控续办与可信调用)。稳定命令、显式账户日/三族/目标，单事务原事件再投递或真实到期释放，不改历史；60秒PUBLISHED等待窗口。离线只走独立可信caller，advertiser仅获得Inspect读取权；全部金额精确字符串。C1与C2均必需。

C13C2协议细节见C13_DESIGN「协议冻结」及reconciliation.proto：v1显式typed Snapshot/Task/Day/Recovery，金额六位string、optional缺失值、日期/版本/派生状态双向校验；RPC调用方与离线命令均在入口严格校验。CLI独立启动上下文不初始化数据源，只经owner RPC，单动作单次有界调用。

## C14：严格只读账户日报表

正式HTTP、完整DTO/空值/开关/错误及身份缓存约束见C14_DESIGN「正式C14契约/实施冻结」。GET `/api/advertiser/{advertiserId}/sandbox-reconciliation/{budgetDate}`每次严格view/fullyConsistent，经C13C Inspect读取同一资金owner快照；金额/ID/版本/计数全部字符串，缺失与零独立，不开放任何任务或恢复写操作。


## C15：behavior 可靠事件的分阶段契约

2026-10-16：正式 [C15_DESIGN](C15_DESIGN.md) 冻结 C15A（自然已接受事件可靠发送），承接 C08 的原事件身份/负载及 behavior 本库原子事实。V002 仅追加发送状态、租约/fence、受限重试与审计重放；不改 V001。独立默认关闭 relay，暂停新回执后仍可排空，不需要现行签名密钥；原事件历史政策自洽即可，不因超过资格期限拒发。发送确认后条件标记，允许重复；三种自然 kind、原字符串 ID、UTC 时间和 SANDBOX 来源保持。

C15A 的 wire 校验、状态转移、资源/配置、兼容回退及 A01～A10 验收均以 C15_DESIGN 为准；本 producer 对当前 v1 使用字段允许列表，未来扩展需先升级 codec。物理 topic 仅允许 natural-impression-accepted-v1 及其隔离后缀，不投旧 behavior-events。测试 PG Inbox/effect 仅证明测试下游；不代表产品自然消费已实现。

父 ER-S15 仍须完成旧 BehaviorService 的本库业务+Outbox 原子写、重复/历史待发恢复及适用消费者的业务效果后确认。C15B/C 的正式 wire/身份/开关迁移/毒消息恢复细节尚未冻结；其必需范围和验收已在 C15_DESIGN 保留，不能在自然子片完成时把父片 DONE。流式 checkpoint/事件时间/窗口投影归原 S19，S15 的已看投影故障恢复不能转移掉。C15A 产品已按本契约实现并经隔离验证，见 ER-S15A_RESULT；运行开关默认关闭，无共享环境迁移或启用。C15B/C 尚未实现。
