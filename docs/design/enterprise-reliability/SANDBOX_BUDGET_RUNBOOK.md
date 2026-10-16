# sandbox 预算命令运行说明

范围为 [CONTRACTS C02～C11B](CONTRACTS.md)。这是 sandbox 预算、报价、预占与可恢复交付能力，不是充值、真实收费切换或生产部署。默认关闭，旧预算/收费链路不读取本表。CPM已接通浏览器与可靠投递；点击当前为S11B内部原子结算，双模式API和点击事件实际发布由S11C接入。

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

第二条仅适用于尚无点击消费的账户；有点击后账户charged包含两种模式，不能拿CPM投影单独对齐总消费，须使用下文S11B两类账本合计。第三条在异步投递途中出现记录是预期现象；不得因为瞬时差额修改资金。修复依赖后 DEAD 只按原 eventId/负载重放，并同事务记录操作人/原因。以下模板与 Repository 的受控重放语义一致，psql 变量须由管理员显式提供，未在共享库执行：

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

## S07：浏览器 sandbox 入口与验收

console 构建变量 `VITE_SANDBOX_EXPOSURE_ENABLED=true` 显式选择 `/search-ads` 新页面；默认关闭，重新构建才生效。仅 ADMIN，仍须启用 C04/C05 的服务端 sandbox 资格/回执与密钥配置。页面不会把后端拒绝降级为旧广告接口。新交付冻结用户/场景；准备中或超时使用“恢复原操作”，不能改 ID 绕过未知结果。

实际文本创意至少 50% 连续一秒可见才入队；hidden/pagehide、离屏、打开历史/对比均停止重新计时。报价不等于消费，只有回执原结果确认后显示消费。刷新同认证主体使用会话原操作/receiptId 查询；不同主体、退出会清理队列并取消后续发送，已经到达服务端的请求仍可能记账。同主体 OIDC 续期保留恢复信息。

会话最多 100 条/512 KiB/一小时，自动最多六轮、并发二条、退避最多三十秒。未知结果到期后只查，不补扣；可手工“查询原回执”。存储禁用会提示刷新不可恢复。关闭终端、清会话或一小时过期会丢失客户端恢复信息，不承诺浏览器零丢失。普通历史、JSON 复制/下载均脱敏票据；资格仅在当前响应及未确认队列中用于提交。

可重复隔离验收（Java 21、Python 3、Node 22+、本机 Docker、console 已 npm ci 并安装 Playwright Chromium）：

~~~sh
python3 scripts/test-sandbox-exposure.py
~~~

脚本启动专用测试类、临时 PostgreSQL 16 / Kafka 3.7.1、独立 seed/schema/topic/group，以及 Vite 127.0.0.1:4183；端口冲突失败，不终止其他进程。所有候选来自测试数据库，只有召回/模型及测试登录适配；生产 HTTP/资格/预占/捕获/Outbox/投影代码真实执行。它不代表真实网关、生产认证或完整召回模型端到端通过。原生 Chromium 标签页 hidden 验证与受控可见状态注入分开记录。

日志 `recsys-rec-engine/target/sandbox-browser-backend.log`；截图/失败 trace 在 `recsys-rec-engine/target/browser-evidence/`。宿主 finish 时核对所有 ledger 已投影、账户消费和账本总额相等，再释放自己创建的容器；失败仍退出非零。CI 新增 sandbox-exposure-browser-gate 同样执行，不向共享库写测试数据。测试宿主只存在于测试 classpath，随机测试凭据不提交。

回退：先暂停服务端新增接受，保留查询、到期回收和事件续发。关闭前端构建开关会恢复既有调试页，该旧页仍有历史收费语义；因此暂停 sandbox 测试时应退出该页面，不把切回旧页视为安全恢复账务。已记账效果不因前端回退撤销，仍按账本/投影对账。

## S11B：点击内部结算与到期恢复

在获授权的隔离adDb依次完成V001～V008后，使用前文相同的显式迁移命令执行 `recsys-ad/src/main/resources/db/ad-billing/V009__click_settlement.sql`，每次计算该脚本SHA256。V009新增五张表和点击报价到期索引，复用V005/V006不可变账本/Outbox保护函数；不修改旧迁移或把点击写入CPM表。完整迁移仅在临时PostgreSQL 16容器验证，没有操作共享实例；没有依赖版本、端口或凭据变更。

只有ad owner可启用：`recsys.ad.billing.sandbox-enabled=true`、`clicks-enabled=true`、`click-settlement-enabled=true`；后两个默认false。既有受控交付密钥环继续用于HMAC，但purpose固定 `click-qualification`，CPM token不被接受。S11B无新增HTTP/RPC入口；内部服务只从不可变服务端报价准备，不能从调用方提交金额。

`click-preparation-admission-enabled`、`click-receipt-admission-enabled`默认true，仅在安装开关启用时生效。正常停止：先关闭新增准备，保留回执、查询、释放及到期回收；紧急暂停新回执时，仍允许原已提交命令和查询恢复。应保留支持V009的owner及表，不以停整个服务、删除资格/账本或回滚无点击回收的旧版本代替恢复。新准备/回执关闭不影响回收工作器。配置沿现有受控重启生效，没有运行时热刷新。

每资格quoteId只占一次完整点击价格。第一次可见或点击返回 `ACCEPTED_WAITING`，金额为空；第二条件在原期限内被接受才捕获。查询同时含不可变原receipt和当前settlement，因此原WAITING回执在后续查询时可以对应CAPTURED结算。ALREADY_ACCEPTED只表示该条件此前已接受，须看settlement；释放/到期不能补扣。零元CAPTURED仍有唯一账本。主体和可见参数沿原约定，反作弊并不证明观测是真人行为。

新反作弊复用 `recsys.ad.anti-fraud.enabled`（true）和 `max-clicks-per-minute`（20），启动时校验阈值为正数。每sandbox用户固定分钟窗口的首次有效资格点击在PG同事务计数，重试不计数，超限持久化FRAUD_REJECTED并释放该资格。PG失败回滚；不会调用旧Redis fail-open逻辑。查看回执与资格判断真实状态，不修改计数尝试“补扣”。

到期任务单线程、每轮1～100笔，复用 `expiry-interval-ms`（1000，范围100～60000）、`expiry-batch-size`（100）。每笔quote行锁SKIP LOCKED、原预算日账户锁和独立短事务；失败有界退避最多60秒，指标 `ad.billing.click.expired` / `ad.billing.click.expiry.failures`。错误日志只记录类型，避免SQL异常DETAIL泄漏签名资格。指标增长或到期held积压时先查数据库连接/迁移/约束和原账户，不手改金额。

只读诊断模板（避免 `SELECT *` 暴露qualification_token）：

~~~sql
SELECT s.qualification_id,s.state,s.decision,s.budget_date,q.expires_at,
       s.visible_receipt_id,s.click_receipt_id,s.ledger_id,s.close_reason
FROM ad_click_settlement s JOIN ad_click_quote q ON q.quote_id=s.qualification_id
WHERE s.state='HELD' AND q.expires_at<=clock_timestamp()
ORDER BY q.expires_at,s.qualification_id LIMIT 100;

WITH facts AS (
  SELECT advertiser_id,budget_date,amount FROM ad_billing_ledger
  UNION ALL
  SELECT advertiser_id,budget_date,amount FROM ad_click_ledger
), totals AS (
  SELECT advertiser_id,budget_date,sum(amount) AS ledger_amount FROM facts GROUP BY advertiser_id,budget_date
)
SELECT a.advertiser_id,a.budget_date,a.held_amount,a.charged_amount,
       coalesce(t.ledger_amount,0) AS ledger_amount
FROM ad_budget_account a LEFT JOIN totals t USING(advertiser_id,budget_date)
WHERE a.traffic_class='SANDBOX' AND a.charged_amount<>coalesce(t.ledger_amount,0)
ORDER BY a.budget_date,a.advertiser_id LIMIT 100;

SELECT delivery_state,count(*),min(created_at) FROM ad_click_outbox GROUP BY delivery_state;
~~~

S11B只完成点击Outbox原子落库；S11C1现已实现下节的独立可靠事件/Inbox。双模式在线API/稳定交付仍属于S11C2，父S11尚未完成。保留期限仍待治理，当前不自动删除任何账本、回执、计数或Outbox，不承诺生产容量或灾备目标。


## S11C1：点击事件续发与投影

V010 `db/ad-billing/V010__click_event_delivery.sql`在V001～V009之后按原迁移SHA256程序执行；新增点击重放审计、逐事件Inbox/事实、跨种类回执身份索引及点击日投影。不修改已执行迁移、不在应用启动时隐式执行。没有本轮共享环境迁移或部署。

`recsys.ad.billing.click-events.relay-enabled`和`projection-enabled`默认false，启用需要sandbox owner、adDb的V008/V009/V010校验及原Kafka连接配置。点击事件不依赖`clicks-enabled`或准备/接收admission，停止新增后仍可续发已有资金事实。两个业务族共享有界传输实现，数据库表、验证器、消费组和指标独立；CPM老构造和默认值保持兼容。用独立的显式Qualifier避免Spring多bean歧义。

点击逻辑目的地固定`ad-click-captured-v1`；物理topic由`recsys.ad.billing.click-events.topic`指定，必须按原Kafka手册预建，不依赖自动建topic。默认group为`recsys-sandbox-click-projection-v1`，用`recsys.ad.billing.click-events.group`覆盖；与CPM分开配置。两者不可指向同一业务topic/group。消息key仍为`advertiserId:budgetDate`，不能据此假定多relay严格按账户版本发出。

发送前验证AdClickCapturedV1完整已知字段及账本ID/逻辑目的地。领取租约30秒、最多20次、退避上限60秒；发送拿到broker ACK后才按fence标记。发送成功但标记失败由原事件重发。毒消息/DB失败不提交位点，消费最多3次失败后停机，修复原因后用原group恢复，不能跳过金额事实。Inbox每笔事实、共同回执身份索引和click_count/charged_amount同事务，随后才提交offset+1；低账户版本但不同的合法账本仍计入。重复事件幂等，不同ID复用资格/任一种类回执或同ID变负载则失败。

指标在`ad.billing.click.outbox.*`、`ad.billing.click.projection.*`，与原CPM同名后缀有相同诊断语义。READY/SENDING最老年龄持续升高先查broker、schema和连接；DEAD使用`BillingOutboxRepository(..., EventFamily.CLICK).replay(replayId,eventId,actor,reason)`在受控工具中恢复，保留原ID/payload和审计；没有公开无鉴权重放HTTP端点。不得直接更改金额或payload。

只读对账：

~~~sql
WITH ledger AS (
  SELECT advertiser_id,budget_date,count(*) AS click_count,sum(amount) AS charged_amount
  FROM ad_click_ledger GROUP BY advertiser_id,budget_date
)
SELECT l.*,p.click_count AS projected_clicks,p.charged_amount AS projected_amount
FROM ledger l FULL JOIN ad_click_projection_daily p USING(advertiser_id,budget_date)
WHERE l.click_count IS DISTINCT FROM p.click_count
   OR l.charged_amount IS DISTINCT FROM p.charged_amount;

SELECT delivery_state,count(*),min(created_at) FROM ad_click_outbox GROUP BY delivery_state;
~~~

未排空前投影可有延迟；归零差额需同时核对Outbox已发布及消费位点追平。总账户金额仍按前节CPM+点击账本合计核对，不能把click_count计入impression_count。关闭新收费的回退保留两类事件续发/消费，代码回退不撤销已记账的事实。


## S11C2：在线点击交付、回执与恢复

当前仅sandbox后台接口，console原CPM分支没有被改成点击收费。上线准备需要在adDb按序追加V011 `db/ad-billing/V011__click_preparation.sql`，engine primary独立历史按序追加V004 `db/engine-delivery/V004__click_delivery.sql`；保留原V001～V010与engine V001～V003。显式迁移/存摘要的步骤沿前文，应用只验证，不自动执行。gRPC模式engine不能本地写adDb，ad-serving为唯一资金owner。

在engine和ad owner都显式启用`sandbox-enabled`、`clicks-enabled`、`click-settlement-enabled`、`click-delivery-enabled`（前缀`recsys.ad.billing`，默认未开启）。复用原`recsys.ad.delivery.signing.*`密钥环和独立engine内部认证身份，不能用通用`recsys`服务身份。公网入口仍经gateway `/api/sandbox/**` ADMIN规则，engine再次检查，nosec不免除新接口认证。gRPC新service为`recsys.click.v1.SandboxClickService`，旧owner未实现时保持未知失败，不能fallback旧点击收费。

1. 客户端保存新UUID operationId，PUT `/api/sandbox/click-ad-deliveries/{operationId}`，body形状为`{"userId":"123","query":"手机","slots":2,"scene":"search"}`。主体从认证上下文取得。客户端不能指定报价、候选、价格或source。200表示全部准备决定齐全，202仍准备中，503结果未知；GET原URL只读，PUT同ID同body续做。未知不能换ID或重排。
2. `items`冻结显示身份（大ID字符串）、最终slot、title、bidType=CPC/OCPC、priceUnit=PER_CLICK、pricingVersion=CLICK_GSP_CNY6_V1和原preparation。只有QUALIFIED有资格token；准备金额只是最大义务，**不是实收**。offerToken不发HTTP。拒绝不补位，原COMPLETE重取也不会刷新期限。
3. 保存独立receiptId与原body，PUT `/api/sandbox/click-receipts/{receiptId}`。点击body为`{"qualificationToken":"<原资格>","kind":"CLICK","visibleDurationMs":0,"minVisibleRatioBps":0,"pageVisible":false}`；可见body改kind=VISIBLE，使用真实连续观测整数毫秒、bps和页面状态。资格仍为签发起五分钟；前端不能伪造服务端期限或金额。
4. 返回`receipt`首次决定和`settlement`当前状态：先点击WAITING_FOR_VISIBLE，先可见WAITING_FOR_CLICK；第二条件才CAPTURED。200可表示等待/重复，422是已持久化的政策/状态拒绝。仅CAPTURED具有ledgerId及chargedAmount（允许0.000000）；其他情况金额为空，不用0假装实收。
5. 丢响应先GET `/api/sandbox/click-receipts/{receiptId}`，若404仅表示当前未观察到，可用原ID/body重试，不能换ID；GET `/api/sandbox/click-settlements/{qualificationId}`获取当前状态。原WAITING回执可以附带当前CAPTURED，不能只读取原决定。快照不回传token或认证主体，错误不输出SQL/异常详情。

400格式、401未认证、403权限/证明、409同ID变请求、404当前未观察到、503依赖未知分别处理。可见政策仍50%连续1秒且hidden重置，服务端复核从资格签发起已有足够时间；本片不声称浏览器观测能证明真人，也未实现新的点击页面。

回退首先设置`click-preparation-admission-enabled=false`，停止新候选及新预占，保留已提交准备结果恢复、原回执/当前状态查询、到期回收和点击事件续发。若预占已提交但准备结果未保存，可用原签名命令完成检查点而不再held。仅在紧急情况设置`click-receipt-admission-enabled=false`停止新回执，原已提交结果依然可恢复；不要关闭整个owner/安装开关遗留未决held。验证key须覆盖未完成准备/仍在途资格；已完成准备结果按原请求可在key轮换后恢复，但待验签的新条件仍需要原key。代码回退不撤销资金效果，不回退到旧Redis扣费。

只读诊断（不输出request_json/plan_json/result_json等含证明字段）：

~~~sql
-- adDb检查点中未完成的准备；关联held说明可能仅丢了准备响应。
SELECT p.child_id,p.operation_id,p.slot,p.created_at,s.state,q.expires_at
FROM ad_click_preparation p LEFT JOIN ad_click_settlement s ON s.qualification_id=p.child_id
LEFT JOIN ad_click_quote q ON q.quote_id=p.child_id
WHERE p.result_json IS NULL ORDER BY p.created_at,p.child_id LIMIT 100;

-- engine primary，租约超时后仍按原operationId和原请求续做；不得另建操作替换。
SELECT operation_id,state,fence,lease_until,created_at,updated_at
FROM engine_click_delivery WHERE state='PREPARING'
ORDER BY updated_at,operation_id LIMIT 100;
~~~

迁移和开关尚未施加到共享环境；本轮验证全部在隔离schema和临时broker执行。不承诺未测量的容量、RTO/RPO或账本自动保留期限。


## S12A：CPA报价准备（尚未接收转化）

Q-04已确认签发起统一五分钟、一资格最多一次收费、先到转化等待可见曝光、窗口外或释放后仅非计费。当前A片只包含精确CPA竞价与V012不可变报价，没有转化HTTP/RPC、预占或收费入口。金额单位PER_CONVERSION，完整单次转化价，不按pCTR/pCVR折小预算义务。

后续需启用时，在adDb追加V012 `db/ad-billing/V012__conversion_quote.sql`，沿前述显式迁移及摘要登记流程；应用不自动迁移。`recsys.ad.billing.conversions-enabled`默认false，另须sandbox和正确ad owner才装配报价。此开关只安装报价组件，不代表CPA已可用。当前不在共享环境开启；回退关闭新报价配置并保留历史，原CPM/CPC不受影响。


## S12B：内部CPA义务与恢复

V013 `db/ad-billing/V013__conversion_settlement.sql`增加CPA结算、可见回执、广告主业务转化事实、账本和捕获Outbox，沿原显式迁移/摘要流程；不能修改已执行V001～V012。当前没有公开转化HTTP/RPC入口，广告主外部授权和可靠事件投递由S12C验收后接入。不要用旧recordConversion调用代替新内部用例。

安装须同时显式开启`recsys.ad.billing.sandbox-enabled`、`conversions-enabled`、`conversion-settlement-enabled`，默认关闭。暂停新增用`conversion-preparation-admission-enabled=false`；紧急暂停新条件接收用`conversion-receipt-admission-enabled=false`，保留查询、原已提交请求恢复及到期工作器。不能关闭整个owner而遗留held。回收沿现有expiry-interval-ms/expiry-batch-size的有界配置，每轮<=100，SKIP LOCKED多实例分工，故障退避；指标ad.billing.conversion.expired / ad.billing.conversion.expiry.failures。

可见原决定和转化原决定均不可变，当前进度由结算状态解释。只有CAPTURED有ledgerId及chargedAmount，零元同样必须有账本。DUPLICATE_QUALIFICATION引用canonical outcome但该新业务事件自身非计费；EXPIRED/NOT_HELD/OUTSIDE_ATTRIBUTION保留事实，不重占或补扣。业务事件按advertiserId+eventId稳定恢复，不能因503换ID。授权边界必须每次重新判权，不能靠知道业务键或资格token就视为广告主有权。

对账总账必须同时包括CPM ad_billing_ledger、点击ad_click_ledger、转化ad_conversion_ledger；总held也须加三种未决义务。本片Outbox仅落库，尚未发送到Kafka，READY积压是未安装续发路径的真实状态，不代表已投影。只读诊断：

~~~sql
SELECT s.qualification_id,s.advertiser_id,s.state,s.budget_date,q.amount,q.expires_at,s.visible_receipt_id,s.outcome_id
FROM ad_conversion_settlement s JOIN ad_conversion_quote q ON q.quote_id=s.qualification_id
WHERE s.state='HELD' ORDER BY q.expires_at,s.qualification_id LIMIT 100;

SELECT advertiser_id,event_id,qualification_id,status,occurred_at,received_at,canonical_outcome_id
FROM ad_conversion_outcome ORDER BY received_at,event_id LIMIT 100;

SELECT delivery_state,count(*),min(created_at) FROM ad_conversion_outbox GROUP BY delivery_state;
~~~

诊断不导出qualification_token/actor/payload_hash。签名key覆盖在途未提交请求；已完成请求原ID恢复不重验旧key，新业务键或可见回执必须仍可验签。回退保留事实，不删除历史或回滚资金效果；本轮没有共享环境启用或生产部署。

## S12C1：CPA可靠事件与独立投影

V014 `db/ad-billing/V014__conversion_event_delivery.sql`新增重放审计、Inbox明细和转化收费日汇总，显式追加且保存checksum；不改V013原捕获事实。通过独立`recsys.ad.billing.conversion-events.relay-enabled` / `projection-enabled`开启，默认false。topic默认`ad-conversion-captured-v1`，group默认`recsys-sandbox-conversion-projection-v1`，相同前缀下topic/group可按环境隔离；显式预建topic与权限，沿原可靠传输强制ACK/幂等/有界超时，禁用自动提交和自动建topic。

停止新CPA准备/接收后保持事件组件运行，无需签名key或conversions-enabled才能排空历史。三个事件族使用独立配置、表、指标前缀ad.billing / ad.billing.click / ad.billing.conversion；不把CPA事件送入点击或曝光消费者。现有原始载荷冻结逻辑目的地，publisher将其映射到明确配置的物理topic。

发送成功需broker确认再以有效fence标记，租约超时旧执行器不得写回；最多20次耗尽转DEAD并保留原事件。人工恢复沿原受控replay用例和审计，不改账本或eventId。消费先提交Inbox+日汇总，再offset+1；毒消息停止并保留位点，修复/审查后续跑，不能跳过当成功。

去重同时约束eventId/qualification/visible/outcome和(advertiser,conversionEventId)。不同广告主同业务UUID合法；可见UUID与内部outcome UUID属于两个命名空间，允许值恰相同。重复事件不累加，低账户版本的独立事实仍累加。只有捕获事件进入日conversion_count；业务事实表中的迟到、重复和等待不能直接累加实收。

~~~sql
-- 投影与原账本可能因发送/消费延迟暂时不同，先核对Outbox和位点追平。
WITH l AS (
  SELECT advertiser_id,budget_date,count(*) AS n,sum(amount) AS amount
  FROM ad_conversion_ledger GROUP BY advertiser_id,budget_date
)
SELECT coalesce(l.advertiser_id,p.advertiser_id) AS advertiser_id,
       coalesce(l.budget_date,p.budget_date) AS budget_date,
       coalesce(l.n,0)-coalesce(p.conversion_count,0) AS count_gap,
       coalesce(l.amount,0)-coalesce(p.charged_amount,0) AS amount_gap
FROM l FULL JOIN ad_conversion_projection_daily p USING(advertiser_id,budget_date)
ORDER BY advertiser_id,budget_date LIMIT 100;

SELECT delivery_state,count(*),min(created_at) AS oldest
FROM ad_conversion_outbox GROUP BY delivery_state;
~~~

这里仅CPA子账本投影核对；共享预算必须合计三类账本和未决义务。未测容量/RTO/RPO/保留期不承诺达标。验证在隔离PG和临时Kafka进行，暂停/恢复仅操作测试拥有的broker；没有共享环境启用。公开CPA接收与稳定交付仍待S12C2。


## CPA稳定交付与广告主业务回调（S12C2）

默认关闭。具备C12A/B/C1的ad V012–V014后，显式追加adDb V015及engine primary V005；保留旧迁移与历史，应用只校验摘要，不自动迁移。四项安装开关sandbox-enabled、conversions-enabled、conversion-settlement-enabled、conversion-delivery-enabled同时true才安装新HTTP/RPC；原事件relay/projection与到期排空分别控制。需要原签名密钥环、广告主AuthzEngine以及明确的内部caller配置，缺少权限引擎时新回调503，不允许用disabled/shadow绕过。

engine使用独立recsys-rec-engine caller；广告主使用recsys-advertiser caller（可由既有配置替换但不能相同或通用recsys）。内部认证沿现有受控凭据和deadline机制；此处caller区分不宣称共用凭据泄露后的服务级强隔离。公网请求仍须有效认证，展示API要求ADMIN，业务回调要求ADVERTISER/ADMIN加当前advertiser edit授权。

1. 用稳定UUID `PUT /api/sandbox/conversion-ad-deliveries/{operationId}`，输入沿C04，取得CPA/PER_CONVERSION准备结果。202或503保留原operationId；GET只查询，不续签、不重排或重新预占。
2. 原展示主体 `PUT /api/sandbox/conversion-impressions/{receiptId}`，输入qualificationToken、visibleDurationMs、minVisibleRatioBps、pageVisible；图文50%连续1秒，窗口仍为签发起5分钟。GET同路径或conversion-settlements/{qualificationId}查当前状态。
3. 当前有权广告主主体 `PUT /api/advertiser/{advertiserId}/sandbox-conversions/{eventId}`，body仅qualificationToken和UTC occurredAt字符串（最多微秒）；广告主/事件ID来自路径，actor由认证上下文注入。每次PUT/GET都重新判权，原调用人被撤权不能恢复；另一当前有权成员可恢复同业务内容，原actor审计不变。
4. 转化先到返回ACCEPTED_WAITING，曝光完成后原status不变，chargedForThisOutcome变true。另一事件复用资格为DUPLICATE_QUALIFICATION，自身chargedForThisOutcome=false/chargedAmount=null；附带资格的实收不能当作该重复事件收费。窗口或释放拒绝422，格式400、权限/证明403、同业务键改内容409、未观察到404、依赖未知503；未知保留原ID查询/重试。
5. 暂停新增资格使用conversion-preparation-admission-enabled=false，停止新条件使用conversion-receipt-admission-enabled=false；保持owner/查询、到期工作器和可靠事件排空，不能关整个sandbox遗弃held。回滚按既有expand-only迁移保留新表，先停止新增并排空；旧程序无法继续新CPA义务，不能仅回退镜像就宣称业务恢复。

本片验证使用独占PG schema、真实TCP RPC和HTTP边界；候选/实验与权限引擎为隔离替身，不声称真实SpiceDB、生产收费、容量或灾备验证。未修改共享dev_infra、既有运行配置或真实账户。


## S13A：只读账户日对账

显式安装adDb V016索引迁移并保留摘要；需要三族既有资金/投影结构（V001/003/005/006/008/009/010/012/013/014）。应用仅校验，不自动建表。`recsys.ad.billing.sandbox-enabled=true`和`reconciliation-enabled=true`共同安装，默认关闭，不依赖新收费admission。当前只有内部服务，公开RPC/offline待S13C，运营接口待S14。

每次只核验指定广告主和原预算日，资金BALANCED不等于投影CAUGHT_UP。pending/DEAD/最老创建时间解释传输积压；PUBLISHED仍可能缺fact。0元也必须有相应事件笔数，due-but-held仍属于账户held。缺账户显示ACCOUNT_MISSING，缺表/数据库故障/5秒超时保持未知，禁止把异常转0。observedAt与accountVersion仅解释本次快照，不能当全局水位。

报告仅诊断，不自动改余额/账本/fact。资金差额或已有坏事实须保留证据；不能靠重新收费、删账或重建历史平账。停用本片只停止新核查，可保留V016索引。当前未证明生产规模、保留期或恢复承诺；测试使用独占PG。

### S13B持久化只读对账任务

显式安装V017并保存脚本摘要；开启 `sandbox-enabled`、`reconciliation-enabled`、`reconciliation-tasks-enabled` 三项才安装内部任务服务，默认均不因此改变。无需开启新收费。先创建含稳定UUID、广告主、1～31原预算日、原因和可信actor的任务，再每次advance一天；原ID查询可恢复提交未知结果，COMPLETED不代表账平。

30秒租约到期才接管，每日期最多20次领取，失败有1～300秒退避；FAILED需要排查后新建有意任务，不能清零原次数。CANCELLED保留已完成报告，相同取消命令/原因可重试；不能取消COMPLETED/FAILED。报告与审计不可变，暂不自动清理。当前内部无RPC/CLI，等待S13C；关闭入口保留证据，不能以删表作为回退。真实PG与资金回归见ER-S13B_RESULT。
