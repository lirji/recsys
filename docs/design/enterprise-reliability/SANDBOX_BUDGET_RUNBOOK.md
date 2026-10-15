# sandbox 预算命令运行说明

范围为 [CONTRACTS C02/C02A/C03/C04/C05/C06](CONTRACTS.md)。这是 sandbox 预算、报价、预占与可恢复搜索交付能力，不是充值、真实收费切换或生产部署。默认关闭，旧预算/收费链路不读取本表。资格和回执捕获已接通；S06 已接可靠投递；S07 补齐浏览器采集后才形成端到端曝光资金闭环。

## 隔离库迁移

账务 owner 为 ad 服务的 `adDbDataSource`；不得应用到 advertiser 的目录分片或默认 primary 数据源。先为隔离环境配置 `PGHOST/PGPORT/PGDATABASE/PGUSER/PGPASSFILE`（凭据不写入仓库），核对目标数据库与服务的 `AD_PG_DB` 一致。以下只是操作说明，本轮自动测试仅操作 Testcontainers 临时 PG，没有向共享库执行迁移。

```sh
psql -X --set ON_ERROR_STOP=on --command 'SELECT current_database(), current_user;'
billing_migration='recsys-ad/src/main/resources/db/ad-billing/V001__budget_account.sql'
billing_sha=$(shasum -a 256 "$billing_migration" | cut -d ' ' -f 1)
PGOPTIONS="${PGOPTIONS:-} -c recsys.migration_sha256=$billing_sha" \
  psql -X --set ON_ERROR_STOP=on --file "$billing_migration"
psql -X --set ON_ERROR_STOP=on \
  --command 'SELECT version, description, script_sha256, installed_at FROM ad_billing_schema_history ORDER BY version;'
```

迁移在一个事务中创建带中文注释的表和约束，版本记录包含脚本摘要。重复执行因版本唯一键冲突退出并回滚；不可改写已经执行的版本。ER-S02A 增加 V002、ER-S03 增加 V003：按上面相同命令，将 billing_migration 依次改为 `recsys-ad/src/main/resources/db/ad-billing/V002__cpm_quote.sql`、`recsys-ad/src/main/resources/db/ad-billing/V003__budget_reservation.sql`，逐个重新计算摘要并执行。ER-S04 再依次执行 `recsys-ad/src/main/resources/db/ad-billing/V004__delivery_preparation.sql`。预算/准备 owner 启用时分别校验 V001～V004，回执能力另外要求 V005，缺失/漂移拒绝启动；旧应用仍只要求自身版本，扩展迁移支持版本共存，不自动迁移。

## 启用范围与身份

仅在隔离 advertiser 和 ad-serving 进程设置 `recsys.ad.billing.sandbox-enabled=true`。所有账户被服务端固定为 `SANDBOX`，终端不能指定真实来源。不要将该开关作为所有服务的全局动态配置；启停需要受控重启，首版无热切换。

advertiser 的 `recsys.security.enabled=true`，并显式设置 `recsys.security.service-name=recsys-advertiser`；旧默认通用身份 `recsys` 无权调用新账务方法。ad-serving 的内部身份拦截器必须开启，`recsys.ad.billing.advertiser-caller` 默认 `recsys-advertiser`，两端从受控配置取得同一内部签名密钥。首版沿用现有内部签名机制，不宣称实现了服务级独立密钥或 mTLS 隔离。

管理 HTTP 必须携带项目已有认证令牌，认证主体须有 `ROLE_ADMIN`。新控制器显式检查身份，不依赖可关闭的方法授权。广告主自助操作尚未开放，目录中不存在的广告主返回 404。对 nosec 请求，无可信身份时保持拒绝。

现有 `grpc.client.ad-serving` 决定目标地址，全局 `recsys.grpc.deadline-ms`（默认 800ms）限制每次调用；客户端不自动重试写入。adDb 事务超时 `recsys.ad.billing.transaction-timeout-seconds` 默认 5 秒、允许 1～30 秒，显式 REQUIRES_NEW / READ_COMMITTED；事务使用与 Repository 同一个 DataSource。RPC 先超时、数据库后提交是预期需要恢复的情况，不是未执行证明。

## 请求与恢复

HTTP 请求声明 `Accept: application/json`，PUT 同时声明 `Content-Type: application/json`。

`PUT /api/advertiser/{advertiserId}/sandbox-budget/2026-10-15`：

```json
{
  "commandId": "79203187-3c07-4696-86c0-dbcf2fe2b601",
  "currency": "CNY",
  "limitAmount": "100.000000",
  "expectedVersion": 0
}
```

每次有意调额生成新 UUID；网络重试保持 UUID、主体、日期、币种、额度、版本不变。新账户 expectedVersion 为 0；已有账户先 GET 同路径读取版本。响应包含 `commandId/status/account`，account 中 ID/金额为字符串，版本为整数，来源固定 SANDBOX。PUT 的 VERSION_CONFLICT / BELOW_COMMITTED 返回 409 及首次结果快照；同 ID 改负载返回 409 ApiError。

超时/不可用返回 503“结果未知”。使用 `GET /api/advertiser/{advertiserId}/sandbox-budget-commands/{commandId}` 查询原结果，或以原命令重试。查询 200 表示已取得结果，结果自身可能是拒绝；404 只表示当前未查到，不得据此换 ID。请求金额支持六位小数，不接受 JSON 数字、指数、负值；期望版本必须为非负整数，不截断小数。

## 诊断与回退

schema 校验失败：核对 adDb 目标与迁移记录摘要，修正配置或应用缺失的正确版本；禁止修改记录伪造成功。错误数据库绑定在装配时拒绝。

版本冲突：读取当前账户并由操作人决定新的调额意图，使用新 commandId 和新 expectedVersion；不要修改既有命令。低于占用拒绝：等待真实释放/结清后发起新命令，禁止直接减 held/charged。资金不变量由 PG 约束兜底。

系统异常日志携带 operation、advertiserId、合法 commandId；命令表持久化 actor、请求摘要、时间、原结果，可关联排查。日志不包含令牌/密钥。查询缺结果时优先处理同 ID，不把响应丢失当作需要补偿扣款。

S02 阶段只包含额度命令；当前版本已有 held 义务，不能直接关闭整个 sandbox 后宣称完成回退。先暂停新增预占，保留查询和到期 worker，确认在途义务后再回退代码；所有历史表保留。

命令历史暂不自动删除，避免破坏幂等；长期保留/归档期限由业务风险政策确认，后续清理必须有界且保留去重证据。此片未测试生产吞吐或给出 RTO/RPO 承诺。

## 精确报价与后续预占

ER-S02A 的 `auctionSandboxCpm` 仅产生标准 GSP/CPM 候选与精确计价依据；`CpmQuoteRepository.freeze` 由服务端冻结报价，尚无开放给终端的提交价格接口。开启 VCG/Listwise 时新路径拒绝，不回退旧计费。币种 CNY、版本 CPM_GSP_CNY6_V1、单位 PER_IMPRESSION（每单次曝光元），沿用现有输入语义；没有把 CPM 名称隐式转换成千次价。

冻结数据位于 `ad_cpm_quote`，quoteId 和 delivery/slot 均唯一，服务器数据库时钟签发并在 5 分钟到期。重试只读原行，不重新计算出价/模型、不刷新期限；同 ID 改定价依据或同交付位置换 ID 冲突。已过期报价仍可查询，后续预占接口必须基于服务端时间拒绝过期报价。展示字段 double 不可用于收费，后续预占与结算只引用服务端持久化 quoteId 的精确金额。

代码回退保留 V002 和报价，不能删表释放业务义务；报价已连接内部预占，公开 sandbox 交付已接通，回执捕获由 S05 的独立默认关闭开关接入。报价历史不自动清理，后续归档须兼顾账务追溯与幂等。新路径槽位 1～100 是有界输入校验，不是吞吐容量承诺。

## 预占、释放与到期回收

ER-S03 提供 owner 内部 `ReservationService.prepare/find/release`；跨进程可信准备入口已由 ER-S04 接入，终端没有提交价格/预算日/广告主的预占接口。调用方先持久化 reservationId/quoteId/原主体，重试保留这些值。prepare 返回首次决策，find 返回当前状态；已释放后的 prepare 重试返回原始 HELD 决策不代表仍持有金额，当前状态必须另查。

成功预占只增加 held，不是曝光或消费。预算日取首次命令受理的 adDb 时间（Asia/Shanghai），金额/期限继承报价；跨日释放回到该预算日，不借用今天的账户。拒绝结论会保存，同命令补预算后也不会偷偷占用。要发起新的有意准备，必须从新的交付/报价操作开始，不能换 ID 复用旧报价。

服务开启 sandbox 后使用独立单线程 fixed-delay worker：默认 `recsys.ad.billing.expiry-interval-ms=1000`（100～60000），`recsys.ad.billing.expiry-batch-size=100`（1～100）。一笔一事务，`FOR UPDATE SKIP LOCKED` 支持多实例竞争；扫描 HELD 到期索引，无待办结束本轮。异常停止本轮并指数退避加抖动，最长 60 秒；下次从数据库续办，没有内存队列作为权威。

暂停新增预占：设置 `recsys.ad.billing.reservation-admission-enabled=false` 并受控重启相关实例。它仍允许已存在命令恢复、查询、主动释放和 worker 回收。不要关闭整个 sandbox 或停掉所有进程后宣称回收仍在运行；多实例暂停需要逐实例核验及在途请求排空，当前开关不是瞬时全局 fence。

指标 `ad.billing.reservation.expired` 仅在释放提交后增加，`ad.billing.reservation.expiry.failures` 记录失败轮次；不使用广告主或 UUID 作为高基数标签。告警应关联 `sandbox reservation expiry failed` 日志、异常中的 reservationId 和数据库状态。数据库不可用时先恢复连接；账户/预占金额不一致时暂停新增、按标识核查，不直接改 held 或终态“修平”。永久不变量失败会保留该行并停止本轮，需要人工处置；本片不自动跳过或丢弃资金义务。

可在已核准的隔离 adDb 只读检查待办与占用一致性：

```sql
SELECT count(*) AS overdue, min(expires_at) AS oldest_expiry
FROM ad_budget_reservation WHERE state = 'HELD' AND expires_at <= clock_timestamp();

SELECT a.advertiser_id, a.budget_date, a.held_amount,
       COALESCE(SUM(r.amount) FILTER (WHERE r.state = 'HELD'), 0) AS reservation_held
FROM ad_budget_account a
LEFT JOIN ad_budget_reservation r ON r.traffic_class = a.traffic_class
  AND r.advertiser_id = a.advertiser_id AND r.budget_date = a.budget_date
WHERE a.traffic_class = 'SANDBOX'
GROUP BY a.advertiser_id, a.budget_date, a.held_amount
HAVING a.held_amount <> COALESCE(SUM(r.amount) FILTER (WHERE r.state = 'HELD'), 0);
```

ER-S03 恢复边界：停止进程不撤销已提交 held；恢复同版本服务后 worker 继续处理持久化 HELD。关闭签发/新增后保持回收，确认无 HELD 义务再决定是否停止整个能力。回退到没有回收功能的旧二进制不能充当预算补偿；保留 V001～V003，不删除账户、报价或预占。ER-S03 的预占/释放本身不修改 charged；ER-S05 捕获分支才修改 charged。


## 整次 sandbox 搜索交付与恢复

engine 的 primary 数据库须另行显式应用 `recsys-rec-engine/src/main/resources/db/engine-delivery/V001__delivery_operation.sql`，同样传入该脚本 SHA256。连接目标应核对为 engine 的 `spring.datasource`；它有独立 `engine_delivery_schema_history`，不可将该迁移误套到 adDb，亦不可把 adDb 的 V001 与此 V001 混为同一版本序列。本轮只在 Testcontainers 验证，无共享库迁移。

engine 启用 `recsys.ad.billing.sandbox-enabled=true` 后开放新 ADMIN API。grpc 模式显式设置 `recsys.ad.serving.mode=grpc`、`recsys.security.enabled=true`、`recsys.security.service-name=recsys-rec-engine`；ad owner 默认只信任 `recsys.ad.delivery.engine-caller=recsys-rec-engine`。无已校验内部身份，即使 nosec 也拒绝。in-process 模式在 engine 内装配 ad owner 和到期 worker；grpc 模式只有 ad-serving 装配新 owner。旧内部共享认证密钥的信任边界保留，未升级为服务独立签名密钥。

在实际 owner 进程受控配置中提供：

```yaml
recsys:
  ad:
    delivery:
      signing:
        current-key-id: k1
        keys:
          k1: ${SANDBOX_DELIVERY_SIGNING_KEY_BASE64}
```

该环境值必须来自受控凭据，至少 32 字节密钥的标准 base64，无默认值，不在仓库/日志/终端历史写入真密钥。所有 owner 实例须有相同可验证 key 环。轮换先分发新旧 key，再切当前 key，旧 key 保留到原准备操作及资格恢复窗口关闭；查询 `ad_delivery_preparation` 的未完成行并核对输入所属 key，禁止通过删除历史记录来缩短窗口。

创建并持久化一个 operationId 后，请求：

```http
PUT /api/sandbox/search-ad-deliveries/6528195b-e95a-473b-9221-75a0a77814b1
Content-Type: application/json

{"userId":"1","query":"手机","slots":2,"scene":"search"}
```

响应 `trafficClass=SANDBOX`，state 为 PREPARING/COMPLETE；每条保留最终 slot、childId、广告/创意 ID、标题和 preparation。QUALIFIED 才附资格 token；它仍不是曝光/消费。请求不接受终端价格，认证主体不使用模拟 userId。200 表示准备结果已齐（也可能预算不足或过期）；202 表示其他执行器正在处理；503 为未知，必须使用原 operationId 和完全相同请求重试。GET 同路径只读进度，不触发预占。

engine 先保存整页候选、位置和稳定 childId，再调用 owner。未冻结时可重试纯候选查询，冻结后再也不重选、不补位；已拒绝条目占据原位置。先 GET 观察已保存进度，再用原 PUT 续办即可；进程崩溃遗留租约最多按数据库 30 秒期限等待可再次领取。每次租约递增 fence，旧执行器无法覆盖新进度。该期限不是 RTO 承诺，也不保证自动完成：本片同步推进，尚无后台 whole-operation 执行器。

准备结果保存失败可能已经存在 quote/held；按原 childId 恢复，不创建新广告操作冒充补偿。候选超过五分钟不能首次冻结报价；已有报价恢复仍沿原五分钟到期时间。重试返回已过期的原 QUALIFIED 不代表可以继续收回执，S05 接受时会再次校验当前预占/资格/服务端期限。charged 不因准备变化，只在开启的回执捕获路径变化。

只读排查（分别连接 owner 的数据库，不做跨库 JOIN）：

```sql
-- engine primary：未完成操作和租约状态；不输出含证明的 plan_json。
SELECT operation_id, state, fence, lease_until, created_at, updated_at
FROM engine_ad_delivery WHERE state = 'PREPARING' ORDER BY updated_at LIMIT 100;

-- adDb：准备检查点；原结果含敏感资格 token，日常诊断不 SELECT *。
SELECT child_id, operation_id, slot, created_at, completed_at
FROM ad_delivery_preparation WHERE result_json IS NULL ORDER BY created_at LIMIT 100;
```

engine 异常日志 `sandbox delivery incomplete` 仅含 operationId 与异常类型；owner RPC 日志仅含异常类型，不写证明、主体或 SQL 参数。积压告警结合上面待办年龄、数量与 S03 预占到期指标。持续永久错误暂停新增并人工核对配置/版本，不改金额或直接把状态标为 COMPLETE。保留期限待业务确认，当前不自动清理操作、资格或幂等记录；需监控表增长。

回退保留 engine V001 和 ad V001～V005（CAPTURED 的二进制兼容限制见下一节），停止新入口流量并维持 ad 到期回收；已发生的 held 不随二进制回退撤销。恢复同版本可用原 ID 续办。没有验证生产容量、整进程 kill 演练、浏览器采集或 RTO/RPO；后续 S06～S07 和 S25 继续补齐。

## 可见回执与原子捕获

ER-S05 新增 ad owner `recsys-ad/src/main/resources/db/ad-billing/V005__impression_capture.sql`。按前述显式迁移流程重新计算该文件 SHA256，并在隔离 adDb 应用。它扩展 CAPTURED 状态，新增回执、不可变账本、待发 Outbox；不改 V001～V004，也没有 engine 新表。先完成全部 owner 二进制升级，再显式打开 engine 与 owner 的 `recsys.ad.billing.receipts-enabled=true`（依然须 sandbox-enabled=true）。未开启时接口不存在，不会因签发资格自动收费。

入口经 gateway 的 discovery/static 两种 profile 路由到 engine，边缘与 engine 都检查 ADMIN，owner RPC 再检查独立 engine 内部身份。使用交付响应中原 QUALIFIED token，客户端达到连续可见政策后生成并保存一个 receiptId：

```http
PUT /api/sandbox/ad-impressions/ed727b3d-cdc7-4331-870c-809c7762e10d
Content-Type: application/json

{"qualificationToken":"<交付返回的原资格证明>","visibleDurationMs":1000,"minVisibleRatioBps":5000,"pageVisible":true}
```

上面为协议示例，不能把它当作自动证明实际可见；S07 将接入浏览器连续观测与 hidden 重置。服务端要求至少 5000 基点、连续 1000 毫秒、页面可见，且资格签发至当前数据库时间足以覆盖声明时长。新回执须在五分钟期限内被 owner 接受；等待账户锁后重验期限。

200 的 ACCEPTED 表示首次捕获；ALREADY_ACCEPTED 引用同一 canonicalReceiptId/ledgerId，不产生第二份曝光、钱或 Outbox。响应 chargedAmount 为 CNY 六位字符串，budgetDate 为预占原日。422 返回已持久化 POLICY_NOT_MET/EXPIRED/NOT_HELD，chargedAmount=0；同 receiptId 重试仍是原拒绝，新的真实观测意图才可新建 receiptId。400/403 为格式、证明或主体不合法，409 为同 ID 变负载。

503 为账务结果未知，必须保存原 receiptId、token 和观测字段，GET 同路径查询原结果，或以完全相同 PUT 重试；不能换 ID 猜测原请求是否成功。不同 ID 即使撞到同一资格，也由唯一账本阻止重复收费，但仍应遵守客户端稳定事件 ID。GET 404 只表示当前未观察到。历史同 ID 结果恢复不因 key 后来轮换或资格过期改变；新事件仍须可验证签名。

捕获只把原预算日 held 转为同额 charged；跨日不扣今天账户。`ad_billing_ledger` 为唯一捕获事实，正常 UPDATE/DELETE 被数据库触发器拒绝；更正须以后设计的补偿事实，不直接修改历史。`ad_billing_outbox` 中 eventId=ledgerId，事件 AdImpressionCapturedV1，目标 ad-impression-captured-v1，分区键 advertiserId:budgetDate；金额字符串、接受时间 ISO-8601，不含 token/认证主体。本片没有发布器，published_at 为空是待发事实，不能宣称消息已经送达。

暂停新接受使用 `recsys.ad.billing.receipt-admission-enabled=false` 并受控重启相应 owner，保留 receipts-enabled、sandbox-enabled 与新版代码，使原结果查询/恢复、预占到期回收继续可用。多实例需逐一核验开关，不承诺瞬时全局 fence。必要时同时关闭 reservation-admission-enabled 停止新占用。已经捕获的金额不随开关或镜像回退撤销。

V005 启用后，不可回退到不能解析 CAPTURED 的 S03/S04 旧 owner；先停止新增、保留支持新状态的查询/回收/待发处理版本。保留全部 schema 与历史记录。真实账户/正式收费切换仍未授权。

只读核对（adDb）：

```sql
SELECT receipt_id, qualification_id, status, ledger_id, charged_amount, budget_date, decided_at
FROM ad_impression_receipt ORDER BY created_at DESC LIMIT 100;

SELECT count(*) AS pending, min(created_at) AS oldest_pending
FROM ad_billing_outbox WHERE published_at IS NULL;

SELECT a.advertiser_id, a.budget_date, a.charged_amount,
       COALESCE(SUM(l.amount), 0) AS ledger_amount
FROM ad_budget_account a
LEFT JOIN ad_billing_ledger l ON l.advertiser_id=a.advertiser_id AND l.budget_date=a.budget_date
  AND l.traffic_class=a.traffic_class AND l.currency=a.currency
WHERE a.traffic_class='SANDBOX'
GROUP BY a.advertiser_id,a.budget_date,a.charged_amount
HAVING a.charged_amount<>COALESCE(SUM(l.amount),0);

SELECT r.reservation_id,r.state,l.ledger_id
FROM ad_budget_reservation r LEFT JOIN ad_billing_ledger l ON l.qualification_id=r.reservation_id
WHERE (r.state='CAPTURED')<>(l.ledger_id IS NOT NULL);
```

发现不一致时暂停新增并按 receiptId/qualificationId/ledgerId 核查。服务遇到“账本已存在但预占非 CAPTURED”等不变量破坏会拒绝新回执，不替损坏数据返回成功。owner 日志 `sandbox receipt dependency failure`、engine 日志 `sandbox receipt result unknown` 只记合法格式 receiptId 与异常类型，不输出证明或内部 SQL。永久错误需人工排查，不能通过删除幂等行或把状态直接改为成功解决。

## S06：可靠投递与逐账本投影

先按既有显式迁移流程应用 ad V006（db/ad-billing/V006__billing_event_delivery.sql，新的 SHA256）；保留 V001～V005。它只扩展投递元数据和本 owner 的只读投影，不回填历史假账；已有 Outbox 默认 READY，有 published_at 的兼容为 PUBLISHED。启动核对 V001～V006；旧 S05 捕获写入仍可使用新表默认值。Outbox payload、账本、投影事实和人工重放审计禁止直接改删。

在 owner（ad-serving 或 in-process engine）通过受控外部配置启用：

~~~properties
recsys.ad.billing.sandbox-enabled=true
recsys.ad.billing.events.relay-enabled=true
recsys.ad.billing.events.projection-enabled=true
recsys.ad.billing.events.topic=ad-impression-captured-v1
recsys.ad.billing.events.group=recsys-sandbox-billing-projection-v1
~~~

两个 events 开关默认 false，互不依赖 receipts-enabled；暂停新签发/预占/回执后仍可续发与投影。grpc engine 不启动本地 worker。沿用 spring.kafka.bootstrap-servers 和受控 spring.kafka.properties / producer / consumer 安全属性；宿主连接 dev_infra 的 recsys profile 时沿既有地址，不在应用中硬编码端口或秘密。producer 强制 acks=all、幂等、最多三次客户端重试、5 秒 delivery timeout、2 秒 max block；数据库领取最多 20 次、租约 30 秒，失败指数退避加抖动最多 60 秒。单轮最多十条，DB 故障停止该轮并退避五秒。Kafka 的单次重试和持久化领取总次数分别有界。

Topic 必须显式预建并限定 producer/consumer ACL；不允许非账务 writer 发布。topic/group 按环境隔离；开始写入后不要随意更换 topic/group 或将同一组指向另一个数据库。如下命令模板只面向已经确认的 sandbox，应用不自动执行：

~~~sh
kafka-topics.sh --bootstrap-server "$SANDBOX_KAFKA_BOOTSTRAP" --create \
  --topic "$SANDBOX_BILLING_TOPIC" --partitions 2 --replication-factor 1 \
  --config cleanup.policy=delete --config retention.ms=-1 --config retention.bytes=-1
~~~

这个单副本参数仅适用本地 sandbox，不能用于承诺生产高可用。无 compact 是因为 key 为广告主+预算日，compact 会丢掉独立资金事件。暂不自动过期只用于首版验收，需监控磁盘；正式保留期、复制/ISR 和灾备目标仍待批准。事件/Outbox/Inbox 保留策略变更须覆盖重放窗口，不能随意清空。测试使用同版 Apache Kafka 3.7.1 临时容器和唯一 topic；没有执行上述共享实例命令。

发送状态：READY → SENDING → PUBLISHED；broker 失败回 READY，耗尽变 DEAD。SENDING 的 available_at 是租约到期；旧 fence 或过期执行器不能标记。ACK 后标记失败会重复发送，属于正常恢复；PUBLISHED 只表示 broker 确认，不代表投影已完成。broker 故障不会回退已提交资金。保留支持 V006 的版本继续处理，不通过删除待发事件或回滚账本“恢复”。

消费：每条事实先在 PG 原子写 Inbox/日汇总，返回后提交该条 offset+1。重复不累加，账户版本较小的另一条事实仍累加。单条最多三次处理，错版本/格式直接停止，其他失败耗尽后停止；不能跳过坏消息。指标 ad.billing.projection.running=0 或 failures 增长应排查受控日志中的 topic/partition/offset，再修复服务/兼容 schema/来源权限并重启消费者。原记录留在 broker，已提交投影靠 Inbox 防重；不要通过“reset 到 latest”掩盖缺口。对已知同 eventId 改负载冲突，应调查来源，不能篡改 Inbox 让冲突通过。

关键监控：ad.billing.outbox.pending/dead/oldest.seconds（30 秒采样）、published/failed/stale/worker.failures/exhausted，以及 ad.billing.projection.running/applied/duplicate/failures。指标不以业务 ID 打标签；日志仅关联 eventId 或 topic/partition/offset。DEAD>0、消费者停止、积压持续增长都需处理；阈值是本地运行告警线索，不是已测容量/RTO 承诺。查询比单一进程健康更能说明恢复情况：

~~~sql
SELECT delivery_state,count(*),min(created_at),min(available_at),max(attempts)
FROM ad_billing_outbox GROUP BY delivery_state;

SELECT a.advertiser_id,a.budget_date,a.charged_amount AS authoritative,
       p.charged_amount AS projected,p.impression_count
FROM ad_budget_account a LEFT JOIN ad_billing_projection_daily p
  ON p.advertiser_id=a.advertiser_id AND p.budget_date=a.budget_date
WHERE a.traffic_class='SANDBOX'
ORDER BY a.budget_date,a.advertiser_id;

SELECT o.event_id,o.delivery_state,o.attempts,o.last_error
FROM ad_billing_outbox o LEFT JOIN ad_billing_projection_fact f ON f.event_id=o.event_id
WHERE f.event_id IS NULL ORDER BY o.created_at,o.event_id LIMIT 100;
~~~

第三条在异步投递途中出现记录是预期现象；不得因为瞬时差额修改资金。修复依赖后 DEAD 只按原 eventId/负载重放，并同事务记录操作人/原因。以下模板与 Repository 的受控重放语义一致，psql 变量须由管理员显式提供，未在共享库执行：

~~~sql
WITH locked AS (
  SELECT event_id,attempts FROM ad_billing_outbox
  WHERE event_id=:'event_id'::uuid AND delivery_state='DEAD' FOR UPDATE
), changed AS (
  UPDATE ad_billing_outbox o SET delivery_state='READY',attempts=0,fence=fence+1,
    available_at=clock_timestamp(),last_error=NULL
  FROM locked l WHERE o.event_id=l.event_id RETURNING o.event_id,l.attempts
)
INSERT INTO ad_billing_outbox_replay(replay_id,event_id,actor,reason,previous_attempts)
SELECT :'replay_id'::uuid,event_id,:'actor',:'reason',attempts FROM changed;
~~~

位点重放可停消费者、核对隔离 topic/group 后从 earliest 重放，Inbox 不应被删除；重新构建另一套投影应先设计新的投影命名空间/切换，不能把新 consumer group 当作已经重建成功。本片验证已有投影重复读取后不重记；未做生产级备份还原、整个宿主 kill 或副本故障演练。
