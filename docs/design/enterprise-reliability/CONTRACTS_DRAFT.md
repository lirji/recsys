# 曝光、账务与事件契约提案

状态：**DRAFT，非正式 CONTRACTS，不用于直接生成实现**。两项已确认业务决定见 [PLAN](PLAN.md)；本文件列出冻结时必须落定的字段语义、接口族和失败行为。当前 Java/proto/API 仍是现有实现事实；正式契约批准后再生成 `CONTRACTS.md` 与 `IMPLEMENTATION_SLICES.md`。

## 1. 交付与回执

| 契约族 | 必须表达的内容 | 幂等与权限 |
|---|---|---|
| CandidateQuery | query/user context/scene/slots、候选与报价解释；无曝光/收费副作用 | 只读可有限重试；输入权限及资源上限 |
| DeliveryOperation | 客户端稳定交付 ID、请求摘要、冻结条目/位置、子操作 ID 与准备结果、最终响应/终态 | engine 持久化整次操作；重试恢复原结果，不因候选或配置变化重算收费机会；已签位置不重排 |
| PrepareDelivery | 调用方稳定 operationId、最终位置、候选版本、主体和发布来源 | 操作 ID 在调用方重试外产生；同来源/作用域/ID 同负载返回原结果，不同负载冲突 |
| DeliveryQualification | deliveryId、qualificationId、item/ad、最终 placement、不可变业务快照、policyVersion、expiresAt、keyId 与票据 | 来源/环境/主体/用途绑定；广告预占关联只由服务端维护，客户端不能指定金额或广告主 |
| ExposureReceipt | 稳定 eventId、资格票据、观测起止/持续时间/比例及政策版本 | 去重键不能只有 itemId/adId；同资格的同一曝光事实最多接受一次；同 ID 改负载拒绝 |
| Interaction/Conversion | 资格标识、稳定事件 ID、点击或广告主转化业务键、原响应主体 | 点击不隐式制造曝光；转化必须广告主认证、作用域内唯一，重试不重新生成业务 ID |
| OperationResult | 原操作/事件、接受状态、拒绝原因、receiptId、独立 billingStatus | 仅授权主体可查询；超时查询原结果；不存在不等于绝对未提交，仍按同键重试与截止时间处理 |

最终路径/请求方法、批次上限、payload 大小、期限和数值枚举在 D0 冻结；前端不得自行假定 JSON。建议新增版本接口，保留旧消费者兼容窗口。自然与广告可共用 envelope，但不得共用虚假的跨库事务。

### 响应与错误语义

| 结果类别（候选名） | 持久化语义 | 客户端动作 |
|---|---|---|
| ACCEPTED | 事件及该 owner 必要业务效果/Outbox 已提交 | 停止重试；账务状态另看字段，不推断每个 accepted 都收费 |
| DUPLICATE_ACCEPTED | 已存在同语义事件，返回原 receipt/账务结果 | 停止重试，不创建新事件 |
| PENDING_DEPENDENCY | 事件已持久化，等待曝光/归因等依赖 | 查状态，由服务端续办；不能展示已收费 |
| REJECTED | 永久不合法：资格/主体/签名/到期/状态不符等 | 展示原因，停止相同请求重试；不改 ID 绕过拒绝 |
| IDEMPOTENCY_CONFLICT | 同作用域和 ID 的负载哈希冲突 | 候选 HTTP 409；告警/排查，不能当重复成功 |
| RETRYABLE / UNKNOWN | 未取得确定结果；网络超时不证明失败 | 有界退避，同 ID 同负载查询/重试；禁止生成新收费机会 |

批量回执逐项返回结果；仅重试未知/可重试项；批次成功不能掩盖单项拒绝。HTTP/gRPC 状态与业务状态的映射、`Ack` 替换和旧 fallback 测试需一起冻结。`sendBeacon` 返回成功只表明浏览器接管发送，不是持久化 ACK。

## 2. 钱与预算

| 契约族 | 必须冻结的语义 |
|---|---|
| Money/Price | 币种、十进制字符串或定点值、scale/上限、每次/每千次单位、舍入模式/时点；报价与实际收费分开 |
| SettlementQuote | 精确货币输入及版本、模型分数转金额规则、算法/量化/舍入版本、冻结结算单价与每资格最大义务；禁止确认时重新竞价 |
| BudgetCommand | 账户/广告主/币种/预算业务日及 timezone、commandId、expectedVersion、limit、desired/effective 状态；低于占用的调减规则 |
| Reservation | reservationId、qualificationId、预算日、最大义务金额、状态、expiresAt、version；只在账务 owner 内写 |
| LedgerEntry | immutable entryId、资格/收费业务键、原始事件、currency/amount、budgetDate、账务版本、原账关联；capture/release/adjustment 可区分 |
| BudgetView | limit/held/charged/available、币种/单位、生效版本和 asOf；Redis estimate 不能冒充权威余额 |
| BillingResult | not_billable/pending/captured/released/expired/rejected、引用账本及原因；不可由前端竞价结果推算 |

候选唯一约束：`(owner_scope, operation_id)`、`(source_scope, event_id)`、`(qualification_id, billable_action)`、`(advertiser_scope, conversion_business_id)`。同义事件不同 eventId 仍需资格级业务唯一约束；预算命令和其他事件类型不能共用含糊的全局 eventId 空间。所有 ID 绑定环境、用途及适用租户作用域；具体 DB 键由 D0 与现有 schema 对齐。

CPM/OCPM、CPC/OCPC、CPA 各自定义触发、预占上限和有效窗口；GD 先保持禁用新结算通道，直到其交付/结算合同独立确定。不能凭 bidType 字符串自动把未定义模式当 CPM。

## 3. 事件 envelope 与投影

候选 envelope：`eventId/schemaVersion/eventType/aggregateType/aggregateId/aggregateVersion/sourceScope/occurredAt/acceptedAt/correlationId/trafficClass/payloadHash/payload`。不得携带可被复用的全量票据或 Bearer。时间统一明确 UTC 传输和预算业务时区，客户观测时间不是服务端接受时间。

- 每个 owner 独立产生 Outbox；聚合版本必须来自持久化条件更新，不能用机器当前时间代替可比较业务版本。
- 每个 eventType 必须声明 `snapshot/delta/immutable-fact` 语义。完整快照允许按版本替换；账本 immutable-fact 按 ledgerEntryId 独立接受每条合法事实，不能以聚合高水位丢弃晚到旧事实；delta 需要缺口检测、暂存/源端补查和追平，不能跳过版本后直接累加。新账务首版优先不可变账本事实，缓存另发完整账户快照。
- Kafka topic、partition key、consumer group、保留期限、ACL 由实际消费者和恢复目标确定；复用现有 Kafka，初次实现记录部署配置，不以客户端 send 成功代替持久化保障。
- 目录快照/暂停/删除、自然行为、广告账务事件各有 owner 和 schema；消费者去重使用稳定事件键，实体更新再校验聚合版本；删除也保留可重建的版本墓碑。
- 兼容采用可忽略新增字段、稳定枚举 code、未知版本隔离；改变金额/曝光/计费语义时新版本共存，不静默复用旧指标名。

## 4. 迁移与契约测试

需要覆盖 REST ↔ Java ↔ proto ↔ TS ↔ DB 的金额和身份无损传输；保留旧 proto tag，不重用字段号；测试旧调用方缺资格、未知 enum 和新旧版本共存。两种广告部署模式执行同样业务断言；新方法返回明确结果，不继续使用“超时只日志、正常返回”的旧语义。

查询/报表以版本区分 legacy/sandbox/production 及数据水位。历史指标保持历史定义，新增可信曝光分母不得与旧服务端候选曝光简单求和。正式样本只读经准入的数据，避免签发未见、调试点击进入新 CTR。

## 5. 未决业务参数

必须由 [PLAN 待决策项](PLAN.md#待决策项) 统一更新，不在前端、服务端、训练脚本分别设隐含默认。测量阈值不是反作弊证明；跨日、晚到、长预占和调减直接影响收费，未批准前只能跑隔离测试。
