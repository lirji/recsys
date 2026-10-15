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
