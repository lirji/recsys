# sandbox 预算命令运行说明

范围为 [CONTRACTS C02](CONTRACTS.md#c02sandbox-预算命令er-s02)。这是新预算账户入口，不是充值、真实收费切换或生产部署。默认关闭，旧预算/收费链路不读取本表。只有后续预占、资格、回执与账本切片验收后，才形成曝光资金闭环。

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

迁移在一个事务中创建带中文注释的表和约束，版本记录包含脚本摘要。重复执行因版本唯一键冲突退出并回滚；不可改写已经执行的 V001，后续新增 V002。应用启用时比对随包 V001 摘要并检查必要表字段，缺失/漂移拒绝启动；不会自动迁移。

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

本片可通过关闭 sandbox 开关停止新增操作并保留表与命令历史；回退代码不删除数据。当前只有额度命令，没有真实资金副作用。后续出现预占、账本和 Outbox 后，关闭签发与处理在途义务必须分开，不能照搬本片回退方式。

命令历史暂不自动删除，避免破坏幂等；长期保留/归档期限由业务风险政策确认，后续清理必须有界且保留去重证据。此片未测试生产吞吐或给出 RTO/RPO 承诺。
