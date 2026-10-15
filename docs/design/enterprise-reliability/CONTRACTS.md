# 企业可靠性正式实施契约

2026-10-15：用户已授权开始实施，并确认本文件第 1 节首版业务政策。按切片冻结正式契约；尚未冻结的接口继续留在 CONTRACTS_DRAFT，不得直接实现。

## 1. 首版已确认政策

- 服务端 sandbox 闭环先行，真实账户切换关闭；生产来源及期初账务另行核准。
- CNY，金额 6 位小数，每次收费按 HALF_UP 量化；预算业务日为 Asia/Shanghai，跨日回执结清预占日义务；额度调减不得小于已消费加预占。
- 首个图文 CPM 政策：创意至少 50% 可见、连续 1 秒；页面隐藏重置；资格和预占 5 分钟到期，服务端须在到期前接受回执。
- CPA 窗口、正式账户迁移、容量/恢复目标仍未确认；不阻止无关切片推进。

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
