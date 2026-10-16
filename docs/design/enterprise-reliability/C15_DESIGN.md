# C15：behavior 已接受事件可靠发送

2026-10-16，基线 `1f77eb4d037844199c922cd2bf92ea230fc0a56f`。承接已批准 [PLAN](PLAN.md) 的 ER-S15/F14，依赖 S06/S08；设计回合已完成 `ER-D0/C15A` 必要门禁。**C15A 为正式实施契约；S15A 已实现并通过本地 A01～A10，见 [ER-S15A_RESULT](ER-S15A_RESULT.md)。B1/B2及非法快照删除边界整改已实现并验收。C15C 的正式增量契约见本文末节，设计验证见 ER-D0-C15C_RESULT；消费者产品验收仍未执行。** 父 S15 必须包含自然 Outbox、旧 BehaviorService 和适用行为消费者，不能因 A 完成而 DONE。

本文件为 [CONTRACTS C15](CONTRACTS.md#c15behavior-可靠事件的分阶段契约) 的规范性细节，状态唯一来源为 [IMPLEMENTATION_SLICES](IMPLEMENTATION_SLICES.md)。原 `C15A_DESIGN.md` 是受保护历史草案，未修改、不纳入交付。用户允许常规技术决定；不改变已批准自然政策、HTTP 语义、收费、正式样本或数据保留政策。

## 源码事实与问题

| 证据 | 基线事实 | 影响 |
|---|---|---|
| E15-01：`recsys-behavior/.../exposure/NaturalReceiptService.java` 的 `accept`；`NaturalReceiptRepository.appendFact/appendOutbox` | 事实、Outbox、回执在 behavior 同一 DataSource 的 REQUIRES_NEW/READ_COMMITTED 短事务提交；无发送器 | 已接受不等于已发布，不能再要求终端重新提交以触发发送 |
| E15-02：`recsys-behavior/src/main/resources/db/natural-exposure/V001__natural_receipt.sql` | 事实/Outbox 双向延迟 FK；Outbox 有 published_at，但 UPDATE/DELETE 均被全不可变触发器拒绝；无发送进度 | 必须追加 V002，不能直接复用更新语句或修改 V001 摘要 |
| E15-03：`recsys-common/.../exposure/NaturalReceipt.java` 的 Event；`NaturalQualification.java` | 固定 v1/SANDBOX；eventId=qualificationId；userId/itemId 字符串；kind 为 RECOMMEND/SEARCH/FEED；已批准 natural-text-v1 | 不投到旧 behavior-events，不把自然事实转换成旧 IMPRESSION |
| E15-04：`recsys-behavior/.../BehaviorService.java` 的 record/sendKafka/publishEvent；`mapper/behavior/primary/BehaviorMapper.xml` | 首次 INSERT 后 best-effort 发布；CLICK exposure_id 冲突直接返回，不续发；没有统一事件 ID | S15B 必需；不能将自然可靠发布等同旧行为可靠发布 |
| E15-05：`recsys-rec-engine/.../filter/SeenItemsConsumer.java` 的 onEvent | 正反馈 SADD 到 seen 集合，catch 后只记日志；Redis 失败可被当正常消费返回 | S15C 必须让消费确认依赖可恢复的业务效果；Set 幂等不能修复吞错漏消费 |
| E15-06：`recsys-streaming/.../RealtimeFeatureJob.java` | Kafka latest、processing-time 窗口、无 checkpoint 配置；不是自然 v1 的现成消费者 | 流式 checkpoint/事件时间/窗口投影属于 S19；S15 不提前宣称其通过 |
| E15-07：`recsys-ad/.../billing/BillingOutbox{Repository,Relay,Job}.java`、`BillingEventConfiguration.java`；`BillingEventsKafkaTest.java` | 已有短领取事务、fence、受限重试、专用 Kafka 配置、隔离 KRaft/PG 故障测试 | 复用机制和已选技术，不令 behavior 依赖 ad 实现类 |
| E15-08：`recsys-behavior/pom.xml`；`BehaviorPersistenceConfiguration.java` | 已有 spring-kafka、MyBatis-Plus、PostgreSQL 和 Testcontainers GenericContainer 所需传递依赖；Mapper 绑定 primary sqlSessionTemplate | A 不需要新第三方依赖、部署服务或消息框架 |

以上 `...` 均指模块内 `src/main/java/com/recsys/<模块包>`；验证记录给出完整源路径。Facts 来自当前文件；本文件后续内容是目标契约，不是已实现事实。

## 决策与非目标

| 决策 | 候选与取舍 |
|---|---|
| D15-01：推进已有自然 Outbox | 选择短事务领取→事务外发送→fence 标记；HTTP 重试或只扫描事实没有持久化投递进度；请求内同步双写会留跨系统失败窗口 |
| D15-02：复用 S06 模式，归 behavior 实现 | 不新增框架或跨 owner 写库；少量有业务名称的类/Mapper 优于把资金模块变成通用消息库 |
| D15-03：原自然 topic 和 payload 保持 | 独立来源语义，禁止混入旧训练/实时消费；测试订阅端仅证明传输和去重机制，不能冒充产品下游 |
| D15-04：有限重试后可审计重放 | READY/SENDING/PUBLISHED/DEAD，不无限重试、不删失败记录；DEAD 表示需要处置，不是“已完成投递” |
| D15-05：仅 A 可直接实施 | 旧行为的身份、开关迁移、已有行覆盖和消费确认需要独立契约；用明确后续门禁保留工作，不在本轮替旧请求发明去重业务政策 |

A 不新增 HTTP/RPC，不改变自然回执验证与返回，不回填 user_behavior，不启动 Flink、不新增产品自然投影、不改现有应用部署或公共基础设施。无新增业务参数、RTO/RPO 或吞吐承诺；下列时间/批次是技术资源上限。保留期仍未批准，不能自动清理事件/审计。

## C15A：事件与来源契约

发布对象只能是已提交的 `natural_impression_outbox` 原始行。Kafka key 是该行原 partition_key，value 是原 payload_json 的 UTF-8 字节；校验后不重新序列化、不补字段、不用现行用户/候选/归因替换历史。

| 字段 | v1 校验 |
|---|---|
| eventType / schemaVersion / trafficClass | 分别严格为 `NaturalImpressionAcceptedV1`、整数 1、`SANDBOX`；与行 event_type/schema_version 一致 |
| eventId / qualificationId | 规范小写 UUID，相等，且等于行 event_id |
| operationId / receiptId | 规范小写 UUID；receiptId 是原首次接受身份 |
| userId / itemId | 1～Long.MAX_VALUE 的规范十进制字符串，无正号/前导零；userId 与 partition_key/Kafka key 相等 |
| slot / kind | 整数 1～50；RECOMMEND、SEARCH、FEED 之一 |
| scene / bucket / contentHash | scene 非空且最长 100；bucket 可空串、不可 null、最长 2000；contentHash 为 64 个小写十六进制字符 |
| policy | version、minRatioBps、minDurationMs、ttlMs 完整且类型严格，范围与 NaturalQualification.Policy 一致 |
| issuedAt / expiresAt / acceptedAt | UTC ISO-8601 字符串；签发/到期毫秒精度且 expiresAt=issuedAt+ttlMs；acceptedAt 保留 PG 微秒精度，满足 issuedAt+minDurationMs≤acceptedAt<expiresAt |

必须是单一 JSON 对象、UTF-8 ≤32768 字节，拒绝重复属性、尾随第二个 JSON、缺字段、null、浮点代替整数、整型溢出。本 producer 的 v1 允许字段仅为上表及 policy 的四个成员，拒绝未知字段，避免将凭据字段原样带出；这是与历史草案不同的显式决定。新增字段需先发布兼容 codec 再升级 writer，不静默改变现有 v1 输出。事件不得包含 subject、token、认证凭据、金额。错误仅记录分类/关联 eventId，不回显 JSON 或异常原始消息。

发送时验证的是**原接受事件的自洽性**，不是重新受理回执。当前正式 writer 仍只接受已批准 `(natural-text-v1,5000,1000,300000)`；relay 不要求现行签名密钥或现行政策相等、不用当前时间判事件过期。否则关闭接收、密钥轮换或积压超过五分钟会错误地丢掉已经接受的事实。机制测试中的 test-only 政策必须与正式接收测试区分。

`destination` 仍固定逻辑名 `natural-impression-accepted-v1`。配置物理 topic 只允许该名字或 `natural-impression-accepted-v1.<隔离后缀>`；后缀限 `[A-Za-z0-9_-]+`，总长≤249。禁止空白、旧 `behavior-events` 或任意资金/目录 topic。物理映射为受控部署配置，改变映射前须核对积压与消费者位置；本轮不执行环境切换。

## C15A：持久化与迁移

追加 `recsys-behavior/src/main/resources/db/natural-exposure/V002__natural_event_delivery.sql`，owner 为 behavior。V001 字节和摘要保持。迁移显式执行，启动只验证；V1 receipt-only 应用仍可插入原六列，新列以默认值兼容。relay 启动同时检查 V001/V002 脚本摘要及所需表/列。迁移预检若存在非空 published_at 的异常 V1 行，拒绝推进并报告，不能自行推断其已发成功。

| 对象/字段 | 类型与不变量 |
|---|---|
| natural_impression_outbox.status | VARCHAR，READY 默认值；仅 READY/SENDING/PUBLISHED/DEAD |
| attempts | INTEGER 默认 0，范围 0～20；每次领取先持久化加一，崩溃也消耗次数 |
| available_at | TIMESTAMPTZ NOT NULL 默认 DB 当前时间；READY 时为下次可领时间，SENDING 时为租约到期时间 |
| fence | BIGINT 默认 0、非负；领取与重放严格递增，不回退 |
| last_error | 可空 VARCHAR，固定受限分类；不存异常消息/负载 |
| published_at | 复用 V001 字段；只有 PUBLISHED 非空，其余为 null |
| natural_impression_outbox_replay | replay_id UUID 主键，event_id FK、actor、reason、old_attempts、previous_fence、new_fence、replayed_at；不可改删 |

重放审计 actor 非空≤200 字符、reason 非空≤1000，无凭据；完整表/列中文注释、主外键、CHECK、工作队列索引必须随迁移提交。工作队列按 status/available_at/event_id 选择，排序仅用于调度公平性，不能当提交顺序水位；不使用 MAX(id)。历史事实/资格/回执触发器和双向 FK 保持。

同一迁移事务中，把 Outbox 的全不可变触发器替换成“身份/原 payload/created_at 不可改、不可删除、只允许合法发送状态迁移”的触发器。禁止修改 event_id/event_type/schema_version/destination/partition_key/payload_json；PUBLISHED 终态不可回退。重放只对 DEAD，审计与状态重开同事务提交。迁移前的正常旧行全部 READY；不制造新的事实或重发记录。

Mapper XML 集中全部 SQL；新 Mapper 绑定既有 behavior `sqlSessionTemplate` 与**同一 DataSource 实例**。claim/mark/exhaust/replay 为 REQUIRES_NEW/READ_COMMITTED 短事务，最多 5 秒，配合 DB 锁/语句超时；relay 入口不允许外层活动事务包住网络。DataSource 错配要失败，不能以 URL 相同代替资源相同。各语句影响行数必须判断；零行旧 fence 只能记 stale，不能记发布成功。

## C15A：状态、重试与恢复

| 原状态 | 条件/动作 | 提交后状态 |
|---|---|---|
| READY | DB now≥available_at 且 attempts<20；SKIP LOCKED 领取一行 | SENDING；attempts+1、fence+1、available_at=DB now+30s |
| SENDING | 租约到期且 attempts<20；新执行器重新领取 | SENDING；attempts+1、fence+1、新租约 |
| SENDING | broker ACK 成功；同 eventId/fence 且 DB now<available_at | PUBLISHED；published_at=DB now、清空错误 |
| SENDING | 可重试失败/结果未知；同 fence 且租约有效 | attempts<20 则 READY；否则 DEAD |
| READY/SENDING | attempts=20 且到可处理时间/租约已到期；有界清算 | DEAD，保留原行；不会永久卡最后一次 SENDING |
| SENDING | 不合法事件；同 fence 且租约有效 | DEAD，INVALID_EVENT，无 broker 发送 |
| DEAD | 受控新 replayId 命令且审计成功 | READY；attempts=0、fence+1、available_at=DB now、清空错误 |
| PUBLISHED | 任意再次领取、失败标记或重放 | 不变，拒绝非法迁移 |

领取先提交再发 Kafka；ACK 后再开短事务标记。发送成功/超时未知、mark 失败、进程消失都可能重发**相同 eventId 和原负载**；承诺至少一次，不承诺跨进程/DB/Kafka exactly-once，也不承诺两个 worker 对同一用户不同事件保持接受顺序。过期 worker 无权标记；fence 不能撤回已经进入 broker 的旧发送，下游仍须幂等。

失败退避：第 n 次失败的上限 `cap=min(60000,1000*2^(n-1))` 毫秒，随机延迟为 `[max(1000,cap/2),cap]`；技术参数沿 S06 上限，不构成恢复 SLA。DB 不可用时不忙循环、不继续领取；worker 退避至少 5 秒。内部错误分类固定 `INVALID_EVENT/BROKER_UNAVAILABLE/SEND_UNKNOWN/ATTEMPTS_EXHAUSTED`；mark/DB 故障只留可恢复租约并记 worker failure，不伪造落库成功。

重放内部命令 `replay(replayId,eventId,actor,reason)` 只开放给受控运维调用，不新增公网端点。以 replayId 锁定命令幂等；完全相同重试返回原审计结果，即使事件后来又 DEAD，也不能再次重置。相同 replayId 改 eventId/actor/reason 返回冲突；只有新 replayId 且当前 DEAD 才可重新打开。检查原审计优先于当前事件状态；审计/状态更新任一步失败均回滚。actor 由可信调用边界提供，不把任意外部字符串当授权。A 以内部用例和隔离测试验证，不声称已提供运营 UI/CLI。

## C15A：运行装配、资源和停机

`recsys.natural.events.relay-enabled=false` 默认关闭；独立 `topic` 属性默认上述逻辑名，类型化绑定并启动校验，不动态热切换。relay 开启不依赖 receipts-enabled、admission-enabled、签名 key 环或 ad sandbox 配置；它只处理 behavior 已接受 SANDBOX 数据。关闭 relay 时不创建 producer、worker 或连接 Kafka，也不因 V002 未装而破坏既有 receipt-only 启动。

单独 producer 继承 `spring.kafka` 受控连接/TLS/SASL 设置，再固定：字符串 key/value、acks=all、enable.idempotence=true、retries=3、max.in.flight=1、request.timeout=2000ms、delivery.timeout=5000ms、max.block=2000ms、linger=0、buffer.memory=1MiB、max.request.size=64KiB。移除 transactional.id。send 等待≤6秒（另计 send 调用 max.block≤2秒），不把 producer 幂等视为应用崩溃去重。资源参数必须在配置测试验证，不能被普通覆盖属性改成无限等待。

专属单线程 fixed-delay=1秒；每轮至多 10 次逐条领取发送，另至多 10 条耗尽清算；不预领十条后排队等待租约、不用公共异步线程池或无界内存队列。每条 DB/发送都有限时，下一轮在上一轮结束后启动；上述 1 秒不是整批完成时限。停止时先阻止新领取、取消/中断 worker，每条之间检查停止标记，producer close≤2秒、worker 等待≤10秒；未标记行依靠 DB 租约恢复，不能丢弃。

不创建/改写业务 topic；隔离测试显式预建。sandbox topic 禁 compact，沿 C06 `retention.ms=-1/retention.bytes=-1`，未有业务保留期前不自动清除；单节点 broker 只证明故障窗口，不证明副本容灾。指标复用 Micrometer：pending/dead/oldest_seconds、published/failed/stale/worker_failures；标签只取固定 family/error 分类，无 eventId/userId。积压查询至多每 30 秒一次、有语句超时，观测失败不得被解释为零积压。

## C15A：可执行实施顺序与文件边界

| 顺序 | 文件级改动 | 可观察结果 |
|---|---|---|
| 1 | behavior `db/natural-exposure/V002__natural_event_delivery.sql`；`persistence/mapper/primary/NaturalOutboxMapper.java`；`mapper/behavior/primary/NaturalOutboxMapper.xml`；`exposure/NaturalOutboxRepository.java` | V1 行可升级；原六列写者兼容；领取、fence、审计恢复原子且受限 |
| 2 | behavior `exposure/NaturalEventCodec.java`、`NaturalKafkaPublisher.java`、`NaturalOutboxRelay.java` | 严格验证后发布原负载；失败/崩溃可续办 |
| 3 | behavior `exposure/NaturalOutboxJob.java`、`NaturalEventConfiguration.java`；必要时 `application.yml` 仅新增默认关闭配置 | relay-only 和 receipt-only 能分别启动；默认无后台连接、任务有界 |
| 4 | behavior `src/test/.../exposure/NaturalEventsFixture.java`、`NaturalEventsPostgresTest.java`、`NaturalEventsKafkaTest.java`、`NaturalEventCodecTest.java`、`NaturalEventConfigurationTest.java`、`NaturalOutboxJobTest.java` | 以下 A01～A10 全部实际执行；测试 fixture 不进入产品包 |
| 5 | `.github/workflows/ci.yml` 仅必要的自然门禁补充；现有 natural-exposure tag 优先复用；本目录 runbook/ER-S15A_RESULT、实施状态 | CI 执行真实测试，记录数量/跳过/环境，不重复建立质量平台 |

同片串行，无并行 schema owner。沿原 MyBatis-Plus/XML、统一业务异常/错误码、中文注释规范；无 HTTP 改动不新增公共错误码。预计不改 pom；若实现发现确有缺依赖，先核对现有依赖树并更新 TECH_SELECTION，不能照历史草案凭空加 Kafka test 依赖。现有 S08 迁移与浏览器 fixture 不机械改为必须装 V002，分别测试 receipt-only 与 relay 必需迁移。

## C15A：必需验收

以下为 S15A 必需验收；设计回合没有执行，实施回合已完成本地验证，实际数量、边界和远程 CI 另见 ER-S15A_RESULT。隔离 PG 使用既有 `postgres:16-alpine`，Kafka 复用 S06 `apache/kafka:3.7.1` GenericContainer，独占端口/topic/group/schema；不能 pause/stop 共享容器。依赖环境不可用或测试 skipped 不是 PASS。

| ID | 输入/故障 | 必须观察的结果 |
|---|---|---|
| A01 | 真实 V001 接受后停在未发；执行 V002；旧 writer 再接受；中断迁移；异常旧 published_at | 原行/新行均可领；失败迁移无半成品；V001 摘要不变；异常行阻断而非静默认成功 |
| A02 | 错版本/key/UUID/大 ID/类型/重复 JSON/未知字段/超长；三种 kind；微秒 acceptedAt；历史政策和超过资格期限积压 | 坏事件不发；合法原字段和原字节保留；过期积压仍发送，不重验当前 key/policy |
| A03 | 两 worker 并发、领取后崩溃、旧 fence 迟到标记/失败回写、DB 外层事务 | 每次只有一个有效领取者；旧 worker 无权更新；网络不持 DB 事务；错 DataSource 拒绝 |
| A04 | 持续发送失败及第 20 次领取后崩溃；并发/重复/改负载重放；审计写入失败 | 有界进入 DEAD；原 payload/事实不变；同 replayId 不再次重开；审计和状态同成败 |
| A05 | 用真实回执服务接受，暂停独占 broker，重复同 receipt 和同资格不同 receipt，再恢复 broker | 一事实一 Outbox；broker 故障无 PUBLISHED；恢复后同 ID 原负载可读，不靠重复 HTTP 制造新事件 |
| A06 | 提交后发送前进程退出；broker ACK 后标记前退出；用新 worker/连接恢复 | 租约恢复和重发得到同 ID；保留进程/故障注入位置证据，普通异常测试不能冒充真实进程退出 |
| A07 | 测试订阅端 PG Inbox+一条 effect 同事务提交后、不 commit offset 即关闭消费者，再以同 group 重建 | broker 确实重投；一个 Inbox/effect；最终 offset 推进。此为测试下游，不是产品自然投影 |
| A08 | 默认关闭、relay-only、receipt-only、缺 V002/摘要漂移、危险 topic、安全属性、覆盖固定参数 | 关闭时无 producer/worker；relay-only 不要 key；迁移/配置非法拒绝；安全连接属性保留 |
| A09 | 队列积压、DB 失败、停止/中断及指标采样失败 | 批次/线程/等待有界，停止不再领取；未决行可恢复；指标不会把未知变成零 |
| A10 | S08 回执与统一错误码回归；前后保护指纹；事实/回执/Outbox 对账 | 原 HTTP 语义、政策、身份隔离和一一对应保持；不触碰保护文件/共享环境；CI 无关键 skipped |

A05/A06/A07 组合须展示：源事实 eventId、原 payload 摘要、Outbox 状态/attempts/fence、Kafka topic/partition/offset、fixture Inbox/effect 与已提交 offset；证据不输出凭据/主体。fixture 单一效果不允许登记成“父 S15 下游全部通过”。

## C15B/C：不可删除的后续范围与设计入口

| 子片 | 范围与输入事实 | 实现前必须补齐 | 父 S15 必需验收 |
|---|---|---|---|
| ER-S15B | 旧 BehaviorService、BehaviorMapper、BehaviorEvent、user_behavior 与本库 Outbox/relay | C15B：源行稳定事件身份/格式、同事务插入、原有 CLICK 唯一规则、无业务键事件的保证边界、use-kafka/publish-events 组合迁移、新旧消息共存；既有已接受行的显式覆盖/补发和去重策略，不把未知历史发送伪造为已发 | 真实 PG/Kafka：DB 成功 broker 失败；HTTP/DB 重复不漏发也不额外造业务事件；提交/ACK 崩溃恢复；后续消费者能使用稳定身份 |
| ER-S15C | SeenItemsConsumer 的已看投影，以及新旧事件解析调用点 | C15C：成功副作用后才确认、Redis 故障重试/停分区、毒消息持久隔离与受控重放、重平衡和 offset 提交窗口；若增加 Inbox，应说明外部 Redis 恢复，不能先 Inbox 已消费再 best-effort SADD | 真实 Kafka/Redis（若有 Inbox 则 PG）：断 Redis 不能跳过；重复/重启恢复后的实际 seen 集合和消费位点正确；新旧版本共存无静默丢失 |

上述是原 F14 范围内要求；C15B wire/迁移与 C15C 消费恢复细节分别见下方正式增量契约。B 完成后 C 继续；源发送通过、消费未通过时父 S15 保持 IN_PROGRESS。Flink 消费适配须与 B 事件契约协调并做兼容测试，checkpoint/事件时间/迟到和窗口对账仍归 S19，不能借此删除 S15 的已看消费验收。

没有事实依据将所有旧 POST（无事件 ID）视为业务幂等；C15B 应保留既有语义并明确保证边界，真正需要新业务去重政策时才报告外层。既有消息发送不确定的历史补发也不能静默触发正式特征重复累计；仅隔离验证，真实切换不在本授权内。

## 回退、风险与门禁输出

A 可先扩展迁移、保留旧 writer，完成 owner 升级和隔离验收后才在另获授权的环境开 relay。停止新增接受与停止 relay 分开；回退关闭 relay，待发行保留、租约到期可续办；不删除 V002/事件/审计，不回写原资格。旧只认识 V001 的 receipt-only 应用必须在 A01 验证兼容；不能声称代码回退撤销 Kafka 已发送事实。

主要残余风险：DEAD 必须有人处置；无清理政策会增长；至少一次必然可能重复；测试 subscriber 不能证明未建的产品下游；B 历史覆盖契约见本文件末节；C 消费确认契约尚未冻结。这些记录不阻塞独立 S15A 实施，但全部阻止父片提前完成。无需要用户新业务决定的 A 阻塞。

本门禁输出：正式 C15A、A01～A10 验收、文件边界和后续 B/C 必需项。设计核验见 [ER-S15_RESULT](ER-S15_RESULT.md)，A 实现与真实 PG/Kafka 证据见 [ER-S15A_RESULT](ER-S15A_RESULT.md)。C15B 设计门禁完成后的下一具体操作为 S15B1 原子接受与可靠发送；每回合完成一片后返回外层，不自行启动下一轮。

## C15B：旧行为可靠事件正式契约（ER-D0/C15B）

本节是原 F14 的正式增量契约，设计基线 `7fccd6fe`；原设计门禁仅冻结设计；后续B1实现与验证见ER-S15B1_RESULT，B2显式清单与兼容实现见ER-S15B2_RESULT。S15B 分为 B1 在线接受/重复修复与发送、B2 显式历史恢复/消费兼容验收，全部完成才关闭父 B。不会给无键旧 POST 增加业务幂等，也不执行共享切换。事件身份、来源和恢复决定如下；C15C 的独立门禁及消费确认/Redis恢复契约见本文末节。

### 源事实、不变量与取舍

| 编号 | 当前源码事实及影响 |
|---|---|
| FB01 | `BehaviorService.record` 先 normalize/resolveExposureBucket，再 `BehaviorMapper.insert`；无整体事务，DB 成功后的 Kafka 发送只是 best-effort，异常不回插 |
| FB02 | Mapper XML 使用 `ON CONFLICT (exposure_id) WHERE action='CLICK' AND exposure_id IS NOT NULL DO NOTHING`；冲突早返回，既不重发也不检查既有待发。这正是“CLICK 已入库未发送、重试仍不恢复”的缺口 |
| FB03 | `BehaviorEvent` 没有业务 eventId。LIKE/PLAY/RATING/IMPRESSION、exposureId 未验证或为空的 CLICK 无同曝光唯一保证；批量接口逐条执行且上限1000，可能部分成功；不能承诺整个 POST/batch 可安全自动重试 |
| FB04 | CLICK/LIKE/PLAY 使用服务端当前毫秒及现有 Redis→DB 归因规则；其余事件保留正 ts，否则补当前时间。归因失败清空未验证 ID/bucket；用户重复内容不是去重键 |
| FB05 | `user_behavior.id` 为 BIGSERIAL，`ts` 为无时区 TIMESTAMP，多字段可空。`EngineBehaviorMapper.appendExposures`、offline `BehaviorImportMapper`、`RecSimulationMapper` 也写表，后两者会按 scene 删除；旧行没有“由哪个 writer 产生/已发送/当时发布开关”证据 |
| FB06 | SeenItemsConsumer 用独立 ObjectMapper 解旧 BehaviorEvent，额外 envelope 不兼容；Redis 失败被 catch 后返回。Flink CategoryEnricher 从根字段宽松取值，没有 eventId/来源/事件时间；按处理时间累加，不能安全承接未知历史或至少一次重发 |
| FB07 | 自然事实/Outbox 是另一来源，不能把旧 IMPRESSION 升格为 C08 可信可见曝光，不能写自然表、资金表或修改样本标签政策 |

源文件清单与 SHA-256 见本回合设计验收记录。领域边界不变：behavior 是 BehaviorService 接受和可靠发送的 owner；engine/offline 现有写入不被本片偷偷改造成同一个事务。没有新增服务、MQ、CDC、分布式锁或自动全表扫描平台。

| 决策 | 候选与选择 | 代价/拒绝理由 |
|---|---|---|
| DB15-01 源行身份 | 选择持久 sourceId + user_behavior.id；不采用每次发送新 UUID、请求内容 hash | 重发/重复修复必须同 ID；内容 hash 会把合法多次 LIKE/PLAY 合并。sourceId 在源库持久保存，区分不同库相同序号 |
| DB15-02 原子写 | 选择应用服务在同 primary DataSource/Mapper XML 短事务中写业务行+Outbox | 同步 DB/Kafka 双写仍有窗口；全表触发器会把 engine/导入来源误当在线 POST；不引入 CDC |
| DB15-03 消息隔离 | 选择版本化新 envelope、专用 live/history topic；旧 topic 继续只承载旧格式 | 在旧 JSON 顶层加字段会使 Seen 解析失败；仅 Kafka header 加身份会被当前 Flink value-only 丢弃，无法校验来源；不双发新旧 topic |
| DB15-04 历史处理 | 选择稳定命令+最多100个显式源行 ID 的恢复清单、逐项持久结果 | 无法证明历史未发，故保留 UNKNOWN 并只发历史恢复通道；无全表自动重放/猜测 scene 来源。大规模清点清单需另有受控任务，不能用 MAX(id) 宣称无遗漏 |
| DB15-05 默认兼容 | 新 capture/relay/history-relay 均 false，旧开关语义在兼容模式保留；可靠模式拒绝同时开旧发布 | 不悄悄启用所有旧数据发布；完全退出旧写入后才能宣称新接受均有原子义务。开发完成不等于实际切换 |

业务不变量：对 capture 开启后的每个成功新源行，必须在同一提交中有唯一不可变事件快照；CLICK 唯一冲突不创建新业务行/新在线事件，不更新原行 ts/value/bucket/scene，不重复计数。已持久化事件的发送/重放不依赖客户端再请求；PUBLISHED 只是 broker ACK，至少一次允许重复。发布失败/未知不能回插源业务行。历史恢复只对原源行取快照，不制造新的 user_behavior 业务事实。

### 命令、事务与 CLICK 重复修复

保留 POST `/api/behavior` 的旧 BehaviorEvent 请求和 `200 {ok:true}`，保留 `/batch` 的逐条提交及 `{ok:true,count:n}`；不得伪装为整批原子或幂等。不增加业务 eventId 请求字段、业务去重时间窗、曝光规则或登录/授权规则。DAO/提交失败沿既有 `INTERNAL_ERROR` 脱敏500，不降级成成功；提交后连接中断仍可能结果未知，禁止自动重试无键业务请求。新内部恢复错误用闭合错误类型，不扩充公网恢复端点。

可靠 capture 流程：

1. 沿现有 normalize/归因完成事件值，Redis/归因远程读取在写事务前。随后在 primary 上用 REQUIRES_NEW、READ_COMMITTED、事务5秒、锁4秒/语句5秒进入单条写入；显式核对 Mapper 与事务管理器同 DataSource。拒绝包住整个 batch 的外层事务语义，网络发送不在写事务中。
2. Mapper `INSERT ... ON CONFLICT ... DO NOTHING RETURNING id`；新行返回 ID 后，按该 ID 创建 `origin=BEHAVIOR_POST` 快照。非预期序列化/Outbox 插入失败全部回滚，不能接受业务行后再补 Outbox。指标仅在实际新行提交后计一次，指标不作为业务完成或严格一次证据。
3. 冲突时用**下一条 SQL 语句**按原 `exposure_id + action=CLICK + user_id + item_id` 锁定原行，再查/创建事件；不能用同条 CTE 的 INSERT snapshot 假设必然看见并发赢家。已有 Outbox 原样返回，不管其 READY/SENDING/PUBLISHED/DEAD 状态都不因 HTTP 重试重开/重置次数；独立 relay 负责待发行，DEAD 走审计命令。
4. 没有 Outbox 的匹配旧行，按**原行快照**创建 `origin=LEGACY_ROW` 的历史恢复事件（`captureReason=CLICK_DUPLICATE_REPAIR`），`priorPublication=UNKNOWN`；即使测试已知 broker 失败，产品也不能从缺 Outbox 推断确定未发。该恢复义务持久保留，独立 history-relay 可发送；S15C 用历史模式验证最终 seen 效果。不能直接套用重试请求重新 normalize 的时间/桶制造 LIVE 事件。
5. 若唯一键碰到不同 user/item 的 CLICK，保持原“重复忽略”响应语义，固定分类记录碰撞，不修复/改写他人的事件。若预期同主键行在加锁前被外部删除、或无法确认本事务写结果，事务失败；不得以新 ID 补插绕过既有规则。暴露全局曝光 ID 的风险不由本片新增授权政策解决。

原有 LIKE/PLAY 等在重复 HTTP 后可继续产生多个不同源行，因此各自有不同 eventId。这是旧业务语义，不是投递去重缺陷。同一已接受源行的 Kafka 重试仍始终同 ID。归因可过期/变化，无法保证后来请求还命中同一 CLICK；保证仅绑定实际经过现有归因规则且命中原唯一约束的源行。

READ_COMMITTED 的 `DO NOTHING` 可能因当前语句快照不可见的并发提交而冲突，因此必须分语句读取并发赢家，且测试覆盖它；序列值不是提交顺序。[PostgreSQL 16 事务隔离](https://www.postgresql.org/docs/16/transaction-iso.html)

### 事件身份、来源与 wire v1

新增 `LegacyBehaviorAcceptedV1`，不修改 HTTP 的旧 BehaviorEvent record。common 中新增明确类型、platform提供严格JSON codec，wire 不复用自然 Event。规范：

| 字段 | 类型与语义 |
|---|---|
| `eventType` / `schemaVersion` | 固定 `LegacyBehaviorAcceptedV1` / 整数1；未知版本不猜测降级 |
| `sourceId` | 源库 metadata 持久 UUID，小写规范值；不得每实例/重启生成 |
| `behaviorId` / `eventId` | behaviorId 为原 BIGINT 的十进制字符串；eventId 固定 `sourceId:user_behavior:behaviorId`，一源行唯一；不是用户请求 ID |
| `origin` | `BEHAVIOR_POST`（可靠模式本次新行）或 `LEGACY_ROW`（没有原快照的已有行，writer 未分类） |
| `priorPublication` | BEHAVIOR_POST 固定 `NOT_ATTEMPTED`；LEGACY_ROW 固定 `UNKNOWN`；这是捕获时知识，ACK 后也不改 payload |
| `capturedAt` | DB UTC 时间字符串至微秒；只是快照捕获时间，不能用作旧行为发生时间 |
| `behavior` | 完整8个旧语义字段：userId/itemId 是十进制字符串、action 为显式枚举名、value 为有限 JSON number、scene/bucket/exposureId 可 null、ts 为 JSON long 或 null；不丢大 ID、不按 JS number 转换 |
| `sourceTsLocal` | BEHAVIOR_POST 为 null，behavior.ts 保留该次规范化的毫秒 epoch；LEGACY_ROW 的 behavior.ts 为 null，此字段保留原 TIMESTAMP 的 ISO local 字符串至微秒或 null，不擅自假定历史时区 |

Kafka key 为 behavior.userId 十进制字符串；合法事件 userId/itemId 必须为正 long，action 为原五种；原 scene/bucket/exposureId 不截断、不重归因，value 不重算。LIVE 的可靠快照即首次已提交 normalize 结果；历史不伪造已丢失的客户端时间或原发送字节。严格版本/字段允许列表、重复 JSON、非法数值/错 key/元数据拒绝；原 JSON 写入后所有重试发送相同字节。32KiB 是可发送 payload 技术上限，worker 先按 SQL 字节长度筛选，超长不可将整个 payload 领入内存。

旧行可能为 null/坏 action/非有限数值/不合法 ID/超长。保留可审计原快照（数据库文本表达及长度/摘要，不补默认业务值），Outbox 进入 `DEAD/INVALID_SOURCE`、attempts=0、payload不可发送；不把隔离记为已发布，也不让该项阻断后续合法项。HTTP 原本能接受但不能生成合法 wire 的特殊数值同样保留源行与 DEAD 义务，不悄悄修改原输入校验政策。大字段原快照由数据库内 INSERT SELECT/摘要处理，claim 仅返回固定元数据；禁止输出原负载/主体/票据到普通日志。坏快照不允许操作员任意改字节后沿旧身份发送，需后续明确的格式修复方案。

live 逻辑 topic=`behavior-accepted-v1`，history=`behavior-history-recovery-v1`；物理名只允许各逻辑名或 `.<隔离后缀>`（同 C15A 允许字符/249长度）。两个 topic 不同，拒绝旧 `behavior-events`、自然/资金/目录 topic。独立预建、禁止 compact；本轮隔离 retention.ms/bytes=-1，不编造生产保留承诺。不同 sender/重试可能改变到达顺序，key 仅提供分区边界，不承诺同一用户按业务发生时间或源行 ID 全局有序。

不能把 origin 转为 SANDBOX/PRODUCTION 标签：旧表没有 trafficClass；本轮 SANDBOX 是**运行环境限制**。LEGACY_ROW 可能来自真实旧 POST、engine 曝光、导入/模拟，不可根据 scene 单字段升级为可信在线来源；源库身份与恢复命令保留来源不确定性。旧 IMPRESSION 仍是旧行为，不是自然已接受曝光或收费事实。

### 持久化、状态与失败恢复

B1 新增 behavior owner 独立迁移目录 `db/behavior-events/V001__behavior_event_delivery.sql`、独立 schema history/checksum，不修改自然 V001/V002 或 offline 历史 SQL。预检真实 user_behavior 列/类型、主键及 CLICK partial unique 索引定义，不只检查索引名字；迁移/摘要不满足时可靠功能启动失败。旧兼容模式不要求新表，默认关闭仍可启动。旧use-kafka/publish-events开启时也须拒绝将旧topic键指向新live/history/natural保留命名空间，防止旧格式污染新流。

| 对象 | 约束/职责 |
|---|---|
| `behavior_event_source` | 单行 sourceId + schema history；迁移显式创建一次，sourceId 禁改/删，克隆/恢复保留同一逻辑源身份；独立新环境显式独立 namespace |
| `behavior_event_outbox` | eventId 主键、(sourceId,behaviorId)唯一，origin/逻辑destination、source_snapshot_json/source_snapshot_sha256、可空wire_payload_json/wire_payload_sha256、captureReason、created_at、状态/attempts/available_at/fence/last_error/published_at。原身份/来源/快照不可改或删 |
| `behavior_event_replay` | replayId 主键，eventId/actor/reason/oldAttempts/previousFence/newFence/replayedAt，不可变；精确重试返回原审计，改内容冲突 |

source_snapshot 取同事务锁定源行的完整列值（含position、原TIMESTAMP本地表示），LIVE也保存它，后续漂移比较使用此快照而非epoch与本地时间互转。源快照固定字段集合id,user_id,item_id,action,value,scene,bucket,position,exposure_id,ts，统一由同一Mapper SQL生成PG16 jsonb::text后按UTF-8/SHA-256取摘要；null显式保留，无时区ts用ISO local微秒表示，NaN/Infinity/-Infinity在源快照用同名字串保留，不能成为合法wire number。LIVE与history复用同一源快照SQL，wire由codec单独生成；算法与时间格式由golden fixture约束；写入后不能重新序列化既有wire。

不对 `user_behavior` 加全表不可变触发器或阻止其既有 offline delete 的 FK；事件是接受时的不可变快照，不因源行后来删除而丢弃。新业务 ID 不得通过 sequence reset 重用；原行被既有工具删除后同曝光键再次被原规则接受为新源行的情况，不偷偷增加跨历史快照的永久业务去重；历史同 ID 已有快照返回原快照并检测源内容漂移，记录 `SOURCE_CHANGED` 而不覆盖或再造事件。此保留快照不新增数据删除政策；实际删除传播需后续授权，不能宣称已覆盖。

投递使用独立领域 Mapper/XML/仓储与专属 producer，不直接调用只允许自然 topic 的 NaturalKafkaPublisher，不借机抽通用框架。READY→SENDING→PUBLISHED/READY/DEAD、30秒DB租约、每次领取 fence+1/attempts+1、最多20次、1～60秒抖动、最终领取崩溃耗尽清算、DEAD审计重开遵循 C15A；新增“初次非法源快照 DEAD/INVALID_SOURCE、attempts=0”的闭合迁移，不能发无效 payload。其不可重放为 READY；普通错误/依赖恢复的合法 DEAD 可按新命令重开。重试原命令不能在再次 DEAD 时第二次重开。

每操作 REQUIRES_NEW/READ_COMMITTED 5秒，锁4秒/语句5秒，Mapper/事务管理器同源验证。claim 按 destination 领取，live/history 独立单线程、各批次最多10发送+10耗尽清算；独立默认关闭；DB失败退避5秒，指标30秒采样/未知NaN，停止不新领/中断未决留租约，线程等待10秒/producer close2秒。Kafka 固定参数沿 C15A（acks all、幂等、inflight1、retries3、request2s/delivery5s/maxblock2s/get6s、1MiB buffer、64KiB max request），沿受控连接安全属性，无 Kafka DB 混合事务。隔离双流最多2个任务线程/2个producer，不能占用公共调度器或丢弃已接受义务。

ACK 后 mark 失败不是发送失败的证明，允许原事件重发；Kafka 幂等 producer 不能覆盖新进程重发和外部业务效果，消费去重/提交语义仍属 C15C/S19。[Kafka 3.7 消息交付语义](https://kafka.apache.org/37/design/design/)

### B2 显式历史恢复命令与完整性边界

内部命令 `captureHistory(commandId, sourceId, behaviorIds[1..100], actor, reason)`：规范化为排序且去重的正 ID 清单，拒绝空/重复输入（不静默改变调用内容），actor≤200/reason≤1000 非空；commandId 稳定 UUID。工具启动要求旧use-kafka/publish-events全false且V001/V002校验通过。只能操作本 owner sourceId，目标由 origin 固定为 history，不接受任意 topic/SQL/时间范围。没有后台全表扫描，没有公开 HTTP endpoint；受信任调用边界负责 actor，命令供隔离测试和未来受控工具使用，不在本轮连接真实业务库执行。

B2 追加 `db/behavior-events/V002__behavior_history_capture.sql`，持久 `behavior_history_command`（输入清单/摘要、actor/reason、创建/完成状态）和 `behavior_history_item`（commandId,behaviorId 唯一、状态、eventId/固定错误、审计时间）。输入和最终结果不可变。首次登记同 commandId 同内容返回原任务，不同内容冲突；缺失行也先持久请求，不能因“当时查不到”永久跳过。

公平续办要求（父审查补充，B2必需）：WAITING_SOURCE 不得固定占据最小前10项。B2门禁必须冻结持久公平轮转或等价扫描规则，保证后续可见项获得处理机会；验收包含超过10项且前10项缺失、后项存在，后项必须先取得结果，缺失项后来提交后仍可续办。不得通过删除缺失项或误标完成满足公平性。

B2实施细化（8395c172）：command保存单调`visit_sequence`，WAITING_SOURCE item保存`last_visit`；每次在command锁下按`last_visit,behaviorId`取最早待办并递增序号，同一advance最多10个不同ID。序号与结果同事务持久提交，回滚不前进；缺失项仍WAITING_SOURCE但排到未访问项之后，不依赖源ID提交顺序。最终item结果不重写，后续源漂移核查须新命令；报告同时列出固定结果和查询时Outbox状态（不重序列化payload）。取消保留未捕获项的WAITING_SOURCE，另列cancelledUnprocessed，不计入已覆盖；对已终态任务的取消审计返回ALREADY_TERMINAL，无重开/撤销。内部恢复仓储仅显式创建，创建时检查旧双开关关闭和V001/V002，不新增自动bean/线程/公网接口或第四开关。


每次显式 `advanceHistory(commandId)` 最多处理10项，逐项短事务；先锁command确认OPEN，再SELECT待办 FOR UPDATE SKIP LOCKED，锁序 command→item→source row→Outbox，确保事件创建与 item结果同提交。持锁无网络，进程退出事务回滚，其他调用可续办；无需要长期持有的扫描cursor/内存队列。源行查询采用新的 READ_COMMITTED 语句；若原事件已存在而源行已删除，合法原快照返回ALREADY_CAPTURED并保留原事件；已知DEAD/INVALID_SOURCE仍返回INVALID_SOURCE，不能因源行消失计入无问题覆盖；没有原快照的不可见/缺失项保留 WAITING_SOURCE，不标 DONE、不推进不可逆水位；调用次数不自动清空它。查询/续办本身幂等，旧 `MAX(id)` 不能说明提交先后。

结果分类：`CAPTURED`（本次建立历史事件）、`ALREADY_CAPTURED`（已有稳定事件，原LIVE/history路由不变）、`INVALID_SOURCE`（DEAD隔离）、`SOURCE_CHANGED`（已有快照与当前源行不符，保留原事件）、`WAITING_SOURCE`。只有所有项取得非WAITING_SOURCE的终态结果才结束本次覆盖；含非法/漂移时标 `COMPLETED_WITH_ISSUES`，不得写“全部已投递”。既有事件分类顺序：当前源行存在且摘要漂移时保持SOURCE_CHANGED；否则若原事件已知INVALID_SOURCE则保持INVALID_SOURCE（含源行已删除），其余返回ALREADY_CAPTURED。两种问题分类均不计covered并令无等待的命令COMPLETED_WITH_ISSUES。此修正不回写已有终态item/command或Outbox；历史误分类仍保留，受控调用方须用新稳定命令清单重新核验，不能把旧报告当已纠正。持久命令状态 `OPEN/COMPLETE/COMPLETED_WITH_ISSUES/CANCELLED`，不增加自动运行线程；取消需独立稳定取消命令及actor/reason审计（V002的不可变behavior_history_cancel表，cancelId唯一，同键改内容冲突，精确重试返回原结果），只拒绝后续未处理项，不能撤销已建事件或ACK，不能把缺失当已覆盖。取消与 advance 以 command 锁后 item 锁串行化，所有路径统一锁序，跨命令重叠 ID 由 Outbox 唯一约束去重。

“全部历史覆盖”必须由另行取得的封闭源清单及逐项核对证明；本片只保证调用方给定清单，不声称全表扫描或按最大ID已经追平。必测低ID长事务晚于高ID提交：先请求两ID，高ID成功，低ID WAITING_SOURCE；低ID后来提交，再续办捕获它。已被永久删除且无快照的源事实无法恢复，保留 WAITING_SOURCE 或经审计取消并报告，不发明原负载。原 writer/发布开关不可追溯，历史 priorPublication 永远 UNKNOWN。

### 开关、共存与消费者边界

新增强类型配置 `recsys.behavior.events.capture-enabled=false`、`relay-enabled=false`、`history-relay-enabled=false`、`topic=behavior-accepted-v1`、`history-topic=behavior-history-recovery-v1`。只在任一新功能开启或显式调用历史恢复工具时核对 owner migration/sourceId；history命令要求 V002。三个开关按受控启动配置生效，不新增热刷新承诺。

| capture / live relay / history relay | 旧 use-kafka / publish-events | 行为 |
|---|---|---|
| 全 false | 任意原组合 | 保持旧路径和旧 topic（00仅DB，10/01/11至多一次原best-effort尝试）；此模式没有新可靠保证 |
| 任一 true | 任一 true | 启动拒绝，防止新旧发布并存；不静默解释为双发或忽略旧开关 |
| true / false / false | 全 false | 新写与Outbox原子提交，重复CLICK修复历史义务；无网络发送 |
| true / 任意 / 任意 | 全 false | 对应独立流排空；history仍有独立开关，不因live启用自动补发旧数据 |
| false / 任一 true | 全 false | 仅排空已有义务；新请求沿DB旧模式，没有全量新写可靠保证，运行手册必须明确这一窗口 |

已有源行/旧客户端/旧DB写入保持兼容；没有“所有旧实例已升级”的数据库魔法判断。真实切换应先完成 S15C/需要的 S19 消费门禁，停止旧发布与新请求入口，等待旧writer在途提交，部署同一capture配置，核对显式遗留清单与消费者起点，再恢复入口/开relay；滚动混跑时旧writer的行只按历史未知补齐，不宣称无缝全覆盖。本轮没有执行该切换或授权生产 rollout；回退可关relay保留义务，回退到旧binary后新增行重新属于历史覆盖范围。保留迁移、事件、审计，绝不清表来恢复旧假设。

| 消费者/格式 | C15B必须交付/验证 | 不能宣称 |
|---|---|---|
| 旧 behavior-events + 原Seen/Flink | 旧8字段及缺exposureId格式保持；新生产者不向此topic写envelope；旧功能默认路由不变 | 旧消息没有eventId，不能靠内容hash去重；Seen吞错和Flink计数问题仍存在 |
| 新 live/history → common codec | 提供强类型解析与来源判定，错误/未知版本明确拒绝；完整身份与原字段round-trip | 测试consumer Inbox/effect不是产品Seen恢复 |
| SeenItemsConsumer | B2对旧listener增加受控topic检查：禁止将其旧topic键指到新live/history/natural流；旧解析回归。C15C新增独立默认关闭的版本化listener，显式处理live/history，成功SADD后再确认、Redis失败恢复 | B2不顺手实现C15C确认逻辑；版本化消息在C完成前只能由隔离fixture验收 |
| Flink RealtimeFeatureJob | B2对旧value-only入口明确拒绝新live/history/natural topic，保留旧输入回归；兼容测试证明新codec可无损提供identity/source/time给后续算子 | 不把新版数据喂给旧processing-time sum；S19前不启用新版live特征。history始终禁止混入实时累加，需独立重建政策 |
| 历史实际下游 | C15C在独占Kafka/Redis中使用新history监听验证同源同ID重复SADD、实际集合与offset，SOURCE_CHANGED/INVALID_SOURCE明确报告；live/history订阅和组分开 | 不恢复旧Flink缺失的身份，不把历史重新加权到当前窗口，不把seen集合验收当Flink完成 |

B 的文件边界含最小兼容guard/测试，不改变旧入口 Kafka group/offset，也不创建新产品投影。新消费端必须先部署再开新relay；任何将新topic写进旧topic配置的误操作必须 fail-fast，不能静默丢弃。

### 有界实施顺序与必需验收（B1结果见ER-S15B1_RESULT）

| 子片 | 文件/owner与可观察结果 | 前置/退出门禁 |
|---|---|---|
| S15B1 | behavior 新 `events/BehaviorEvent{Configuration,Repository,Codec,Relay,Job,KafkaPublisher}`（具体拆类可按职责精简）、primary Mapper/XML、`db/behavior-events/V001`；common `LegacyBehaviorAccepted` / platform `LegacyBehaviorCodec`；调整BehaviorService与BehaviorMapper写入口；单条原子接受、重复修复、两流可靠发送与审计重放 | C15B PASS；B01～B07、B10/B11中对应项真实通过；不把B2历史操作遗漏成父B完成 |
| S15B2 | history command/items V002、Mapper/内部恢复服务；Seen/Flink旧入口topic guard与兼容测试、运行手册；显式历史清单可重试续办/审计取消/报告真实覆盖 | B1；B08～B12及B1受影响回归全通过，才记S15B DONE |

共同边界：SQL只在XML/迁移，所有新表/字段中文注释；不改历史SQL、自然实现、保护Compose/运行说明、旧CPA时间修正，不新增第三方依赖。测试复用 S15A/S06 的独占 PG/Kafka，B2兼容guard测试不启动共享Flink或Redis。CI复用现有工作流新增清晰行为可靠性tag/门禁或纳入既有必跑命令，不能让新增测试落入默认排除而宣称通过；具体工作流修改只在实施片落地。

| 验收ID | 必须观察的行为（按子片结果分别核实，不将B1推广为父片完成） |
|---|---|
| B01 | 真实schema预检/迁移失败回滚/摘要漂移；V001旧writer仍能写；sourceId跨应用重启稳定，不同源库相同id不冲突；自然/历史SQL字节不变 |
| B02 | 新源行与Outbox同成败，注入序列化/Outbox失败不得残留新源行；新字段/大ID/null/时间/非有限/超长各有保留或隔离结果，错误脱敏 |
| B03 | 至少20线程并发同已验证CLICK：源行/事件各1，正常非崩溃指标1；新winner提交后其他事务读取原行；LIKE等重复两源行两事件；错误归属不修复他人事件 |
| B04 | 真实broker失败时DB已成功，同CLICK重复不丢待办/不新建业务；预置旧CLICK无Outbox后重复请求，确实创建history义务并恢复发布原行，不采用请求新ts/bucket；既有PUBLISHED/DEAD重复不重开 |
| B05 | DB提交发送前、领取后、broker ACK标记前独立JVM halt；新进程租约恢复，同eventId原payload；Kafka重复但测试Inbox/effect一次；不能用普通异常代替真实退出 |
| B06 | 两relay并发领取、旧fence回写无效、20次耗尽及最后一次崩溃清算；重放审计原命令幂等/改内容冲突/失败回滚；初始INVALID_SOURCE不冒充已发 |
| B07 | 旧开关00/01/10/11兼容、新开关组合与默认关闭/冲突拒绝、relay-only；同源事务验证、事务外网络、两流总资源上限和停机中断/未知指标 |
| B08 | 历史manifest并发/重复/改清单/重叠ID，已capture返回原事件；任务/item/Outbox同成败；advance中断可续办，取消有审计且不能撤销事件 |
| B09 | 低ID晚提交确实WAITING_SOURCE→续办捕获；缺失/删除不误报完成，源漂移不覆盖；导入/engine/旧POST混合历史一律LEGACY_ROW/UNKNOWN，无伪造时间/trafficClass |
| B10 | 旧Seen BehaviorEvent及Flink解析回归；新wire codec无损identity/source/time；旧入口拒绝新版/自然topic；未知版本拒绝，新旧不串流 |
| B11 | PG业务行→Outbox→真实Kafka topic/key/partition/offset→fixture单一效果完整证据；不把机制fixture当产品下游，S15C/S19仍未完成 |
| B12 | 历史清单准备/捕获/隔离/等待/取消/发送分别计数，不把CAPTURED当PUBLISHED；保护11文件、原迁移、HTTP/batch/归因/统一错误回归，必需测试无skip |

容量数值是有界sandbox技术参数，非吞吐/SLA承诺；历史批次100/advance10/事务5秒若实测不足，必须在原契约下缩小每次工作量并记录，不放宽一致性/丢数据。没有待用户选择的新增业务政策；真实环境的封闭历史清单、旧writer退出和消费启用属于后续运行前提，缺失时保持默认关闭并报告，不能阻塞隔离实现或伪装为已部署。

### 父审查补充：C15C旧格式失败确认边界

C15C必须覆盖已启用的旧格式 SeenItemsConsumer：Redis异常不能吞错后确认，须有明确重试/位点恢复和毒消息隔离契约，并以真实Kafka/Redis验证失败期间offset不越过未完成效果、恢复后SADD及确认。旧消息无eventId不妨碍幂等SADD后确认；不得按内容hash发明业务去重。仅增加新版listener不满足父S15。B1只记录该未来边界，不实现B2/C。

B1源码核对的文件边界细化：recsys-common是无JSON技术依赖的Shared Kernel，仅放LegacyBehaviorAccepted不可变契约；严格JSON codec放入已有依赖common与Jackson的recsys-platform/behavior，供behavior及后续Seen复用，owner元数据校验仍在behavior。保留既有Jackson版本，不新增第三方依赖或Maven依赖边；消息格式、身份与全部严格校验不变。首轮编译发现common无Jackson后采用此边界，不引入手写JSON解析器。

## C15C：Seen 消费确认、隔离和恢复正式契约（ER-D0/C15C）

设计基线 `6c868e7349ba72682a8b7707fe7acdfcc98c3ed3`，回合 `89a217e3`。本节是原 F14 的必要增量设计；只设计，不代表已实现、已迁移或已消费。适用旧 `SeenItemsConsumer`、新 LIVE/HISTORY 三条流。原 HTTP、batch、归因、事件身份和已发布迁移不变；自然曝光无正反馈 Seen 效果，不新增自然 listener；history 只恢复适用 Seen，不送旧 Flink。S19 独立。无共享启用、部署、自动全表回填或缓存平台建设。

### C-F：现场事实、假设和技术依据

| ID | 已核实事实及影响 |
|---|---|
| C-F01 | `filter/SeenItemsConsumer.java` 用新建 ObjectMapper 读 BehaviorEvent，正反馈 SADD 后 catch 所有异常并正常返回；`LegacySeenCompatibilityTest` 仅 Mock Redis/直接方法调用，没有 broker 位点证明。旧入口必须一起改。 |
| C-F02 | engine `application.yml:60～68` 为旧组 `recsys-rec-engine-seen`、`auto-offset-reset=latest`，未显式 ack-mode/auto-commit。专用配置必须覆盖这些默认；不改其他消费者 factory。 |
| C-F03 | Boot 3.2.5 BOM及本地 engine 实际 surefire classpath一致：Spring Kafka 3.1.4、Kafka client 3.6.2、Spring Data Redis 3.2.5、Lettuce 6.3.2.RELEASE。本机对应 JAR 的 javap 已核对 MANUAL_IMMEDIATE、nack(Duration)、setAsyncAcks、AssignmentCommitOption、停止处理器和 Admin 位点 API。无版本升级/新第三方依赖。 |
| C-F04 | `LegacyBehaviorCodec` 已严格校验新版32KiB wire、key、身份、版本及来源；common 保留无JSON依赖契约。旧7/8字段和长整数必须保留，不把新envelope交给旧解析器。 |
| C-F05 | `BillingProjectionConsumer` 有手动提交/3次重试/退出机制，但属于资金PG投影，无Redis隔离重放，不能直接复用其业务仓储。复用确认顺序与测试设施，不引入跨owner依赖。项目无可直接复用的 Seen 错误处理器或隔离库。 |
| C-F06 | engine已有primary SqlSessionTemplate和Mapper扫描、显式owner SQL/启动verifySchema惯例。Seen表归engine；不能写behavior/ad表，也不复用其schema history或业务幂等键。 |
| C-F07 | ReplicaSeenItemsSource 读 `seen:{user}`，失败返回空集；注释仍称只覆盖开启后事件。SADD无TTL，读模型不保证历史完整；普通消费者不能感知已提交位点后的Redis数据丢失。 |
| C-F08 | 父只读依据 `.dev/codex-flow-reliability/C15C_PARENT_NOTES.md` 五项逐项核实。共享组位点、Kafka保留期、Redis持久化/驱逐策略、生产吞吐及RTO/RPO未知；本回合不查询共享业务环境。 |

Spring 3.1.4 默认错误处理器可以有限重试后日志恢复并跳过，不能仅依赖抛异常；本设计不用默认 recoverer。[官方异常处理](https://docs.spring.io/spring-kafka/docs/3.1.4/reference/kafka/annotation-error-handling.html)。手动确认、nack和同步提交的API语义按[3.1.4 listener container](https://docs.spring.io/spring-kafka/docs/3.1.4/reference/kafka/receiving-messages/message-listener-container.html)核对。Kafka `latest` 在无位点/扩分区时可能漏掉已发布记录，fetch大小也不是绝对内存上限，按[3.6.2 ConsumerConfig源码](https://github.com/apache/kafka/blob/3.6.2/clients/src/main/java/org/apache/kafka/clients/consumer/ConsumerConfig.java)约束。SADD重复成员返回0仍是成功，[Redis SADD](https://redis.io/docs/latest/commands/sadd/)。这些是机制依据，不能替代C01～C18真实验收。

### C-D：方案比较与选择

| 决策 | 选定方案 | 备选及未选原因 / 成本 |
|---|---|---|
| C-D01 确认与暂停 | 三个独立单线程 Spring record listener，专用factory，MANUAL_IMMEDIATE；正常路径显式ACK；可重试失败nack；预算耗尽持久PAUSED并暂停整条流 | 只删catch+默认处理器会跳过；自写Kafka轮询可行但重复容器生命周期/重平衡机制。接受一条流内暂时阻塞其他分区，其他两流独立；无分区公平性承诺。 |
| C-D02 持久恢复 | engine自有技术记录：每个Kafka坐标在副作用前持久领取，保存有界原字节、尝试/租约/fence、结果；命令审计重放 | 仅内存重试会在进程退出后重置预算；仅DLT不能原子涵盖PG审计且增加新topic/投递失败窗口；通用任务平台无必要。代价是每条记录PG读写和存储增长，未测生产吞吐，不宣称零成本。 |
| C-D03 幂等边界 | 正反馈效果为SADD；无eventId旧消息按不同坐标逐条处理；技术键含Kafka来源，摘要仅校验字节 | 不按内容hash去重，不把事件eventId当Redis已存在证明。不引入业务Inbox去重库或更改behavior事件。技术APPLIED只证明此前成功，不证明集合永存。 |
| C-D04 起点 | 显式、稳定命令登记绑定和每分区起点；既有组只读ADOPT，新组只允许冻结EARLIEST_RETAINED；listener统一auto.offset.reset=none | 不默认latest，不无条件earliest重置旧组。增加一次受控初始化操作，换取区分新组与offset丢失；保留期外数据不伪造。 |
| C-D05 重放 | 同一engine内部命令重用原key/value和Seen处理器，单条目标、持久预算、审计；不重新发Kafka、不移动主组offset | 修改毒消息字节或伪造旧业务身份破坏证据；自动全量Redis重建超出本片。需要显式目标清单及权限边界。 |

### C-C：输入分类与ACK条件

通道枚举 `LEGACY / LIVE / HISTORY`。LEGACY沿原topic和seen-group；LIVE固定逻辑topic `behavior-accepted-v1`、默认group `recsys-rec-engine-seen-live-v1`；HISTORY为 `behavior-history-recovery-v1`、默认group `recsys-rec-engine-seen-history-v1`。允许原逻辑名的点号隔离后缀，不许regex/逗号多topic、通道串接、自然topic或三个group相同。旧topic guard即使关闭也保留。

| 分类 | 判定 | 效果/持久结果 | 是否可ACK |
|---|---|---|---|
| POSITIVE | 合法CLICK/LIKE/PLAY/RATING | 同步 `SADD seen:{userId} decimal(itemId)`；返回0或1均成功；随后PG有效fence写APPLIED | 只有SADD已成功且PG结果已提交；Redis异常、null返回、超时未知均不可确认 |
| NO_EFFECT | 合法IMPRESSION（包括history中的旧曝光） | 不访问Redis，记录NO_EFFECT；RATING即使value=0仍沿原正反馈集合口径 | PG分类持久后可确认，不记applied |
| INVALID_DATA | 非JSON、UTF-8非法、重复字段、未知字段/版本/动作、空value/tombstone、非法关键ID、新格式key/来源/topic不匹配等 | 记录QUARANTINED和固定reason、原字节或明确未保存说明 | 隔离记录提交后才可确认；这表示已隔离，绝不表示Seen完成 |
| DEPENDENCY_FAILURE | Redis连接/超时/WRONGTYPE、PG、Kafka可观察提交异常/权限等 | Redis失败仍是未履行正反馈，不能转毒消息“成功”；PG或Kafka失败保留未知结果 | 不确认；短退避、暂停/停止，恢复先查持久状态和真实位点 |
| INTERNAL_FAILURE | 未知代码/序列化/运行时异常 | 脱敏停止；不将所有异常粗暴归INVALID_DATA | 不确认，不走日志跳过 |

旧JSON保留7/8字段、可空scene/bucket/exposureId及完整64位ID；要求userId/itemId为正long、action为已知枚举，拒绝浮点/字符串向ID静默转换及重复字段。value为有限数字（缺省0），ts允许原有long值/缺省0，Seen不按时间滤掉历史；这两项不增加新的正反馈政策。旧key此前未参与解析，仍不以key缺失/不匹配丢弃合法旧value，只原样记录。未知字段沿旧ObjectMapper的拒绝行为。新流必须复用LegacyBehaviorCodec，额外验证 `destination()` 与具体逻辑流一致；不推断历史时区、writer或trafficClass。自然误路由必须隔离，不视为合法IMPRESSION。

### C-P：最小持久模型与所有权

新建engine owner目录 `db/engine-seen/V001__seen_recovery.sql`，与engine-delivery序列分离。显式事务迁移、独立 `engine_seen_schema_history` 和SHA256；全部表/字段中文COMMENT，PK/UNIQUE/CHECK与不可变输入触发器，启动仅校验不自动迁移。旧迁移、fixture和behavior源数据完全不改。

| 表/逻辑对象 | 最少字段、约束与访问路径 |
|---|---|
| `seen_stream_partition` | `(stream,partition)`主键；不可变clusterId/topicId/topic/groupId、bootstrapOffset、登记commandId；最多32分区/流；记录初始化状态及已观察committed offset（后者仅证据，不替代broker权威）。同一cluster/topic/group/partition唯一；topic重建或group换绑拒绝复用。 |
| `seen_record` | 技术键`(clusterId,topicId,groupId,partition,offset)`唯一，含stream/topic；独立record UUID供命令引用；key/value原bytea或null、长度/带null标记和长度分隔的SHA256、payloadComplete、firstClassification/firstError/首次时间不可变。状态READY/APPLYING/APPLIED/NO_EFFECT/QUARANTINED/PAUSED；attempts 0..3、epoch、fence、leaseUntil、activeCommandId、nextAttemptAt、lastError/lastSuccessAt，UTC timestamptz/DB时间。索引(stream,status,nextAttemptAt)，隔离查询用(createdAt,recordId)稳定分页。 |
| `seen_recovery_command` | UUID主键；kind=INITIALIZE/RETRY/RECHECK/RESTORE，规范化不可变request和摘要、可信actor≤200/reason≤1000、创建时间；READY/RUNNING/SUCCEEDED/FAILED和固定result；attempts 0..3、30秒租约/fence、前后状态/epoch、完成时间；相同ID改内容冲突，终态结果不可变。初始化最多32分区，其余每命令只一条record。 |

记录不是业务事件权威：没有对eventId建业务去重唯一键，原eventId可作受限查询信息；同内容/同eventId但不同Kafka坐标仍分别执行SADD。一个坐标重复投递只能复用完全一致的key/value摘要；不一致停止COORDINATE_CONFLICT，不能覆盖。

正常wire≤32KiB，key≤1KiB时完整存储，包含null标记；不存无业务用途Kafka headers，重放不得依赖headers。超过限额的毒消息只存坐标/长度/摘要，`payloadComplete=false`，不截断后冒充原文；隔离后若需重取，限定同cluster/topicId/partition/offset且校验摘要。broker过期/删topic/压缩缺项不可恢复时明确SOURCE_UNAVAILABLE，不能拿“下一个offset”代替。非法字节原样保存，不能先宽松UTF-8替换。日志/API摘要均无原JSON、key、userId或异常cause正文。

领取/标记均独立REQUIRES_NEW、READ_COMMITTED短事务，事务5秒/锁4秒/语句5秒，绑定primary同一DataSource。锁序command（若有）→record；初始化只锁command→stream_partition。冲突按唯一键重读校验。外部Redis/Kafka调用绝不持PG事务/行锁。副作用前提交attempts+1/fence+1/30秒lease，最后一次领取退出也消耗预算；有效lease+fence才允许结果写回。普通流READY→APPLYING→APPLIED，失败回READY或PAUSED；合法无效果/非法数据可直接持久NO_EFFECT/QUARANTINED且不计Redis尝试。恢复命令只在已声明终态上开启新epoch，原字节和首次分类/原因不变，当前结果可随受控重判改变；命令自身终态不可改。未过期旧租约不被抢占；过期后重新领取或预算耗尽PAUSED。stale执行器即使SADD成功也不得写回或ACK；幂等SADD使外部旧调用残留安全，不宣称fence能撤销Redis请求。

### C-O：确认、重试、暂停与提交窗口

专用 `seenKafkaListenerContainerFactory`（三个container复用相同硬配置、独立consumer/group/thread）：

- `enable.auto.commit=false`；record模式；`AckMode.MANUAL_IMMEDIATE`；`syncCommits=true`、syncCommitTimeout=5s、commitRetries=0、asyncAcks=false；`AssignmentCommitOption.NEVER`，禁止框架在分配时偷偷建立位点。
- key/value均ByteArrayDeserializer，解析在业务处理器内；max.poll.records=1、pollTimeout=500ms、max.poll.interval.ms=60000、session.timeout.ms=15000、heartbeat.interval.ms=3000、default.api.timeout.ms=5000、request.timeout.ms=5000；每流concurrency=1。继承spring.kafka地址/TLS/SASL，显式可靠性属性不接受被外部通用配置放宽。
- auto.offset.reset=none、allow.auto.create.topics=false、missingTopicsFatal=true、RangeAssignor；不使用Kafka事务、异步ACK、@RetryableTopic、默认DefaultErrorHandler或log-only recoverer。认证失败停止，禁止无界自动重新启动。
- fetch.max.bytes=1MiB、max.partition.fetch.bytes=1MiB；启用前校验最多32分区、topic cleanup.policy=delete及有效max.message.bytes≤1MiB。Kafka可能返回超过fetch限制的首批，压缩展开也影响内存；32KiB应用校验不是绝对堆上限，生产容量另测；topic配置不可核验则拒绝启用，不能自行修改共享配置。
- Redis沿现有StringRedisTemplate，启用时校验命令/连接timeout均≤2s（当前默认2000ms）；不改全局Redis配置。每次只SADD单成员，不加TTL、不调用DEL/SMEMBERS重建，不后台无界批量。

处理步骤：登记/校验原记录→分类→领取→Redis（正反馈）→PG状态提交→listener线程调用acknowledge。APPLIED/NO_EFFECT/QUARANTINED重复坐标只复用已存终态并ACK，不能重造新业务ID；APPLIED须先前确实在SADD成功后持久。新Kafka坐标即使内容相同仍走完整路径。所有ACK还须当前assignment有效；ACK调用后的提交异常不得改成正常返回。本机3.1.4字节码核对：doCommitSync会暂存RebalanceInProgressException对应提交，processAck会忽略WakeupException；所以ACK调用返回不能证明broker已提交。这两种框架内部窗口允许未确认终态记录重投，暂存offset只来自已完成/隔离项，不得包括失败项；其余可观察提交异常停止。C04/C05/C09须用Admin核查最终committed与后续交付，不以本地ack计数充当broker确认指标。

Redis失败：持久lastError及READY/nextAttemptAt，第1/2次使用nack(1s/2s加0～250ms抖动)，先seek回当前记录，由容器保持poll/心跳。第3次或过期的第3次租约转PAUSED：nack使当前位置可重读，再request整个该流container.pause（pauseImmediate=true）；暂停期间只poll心跳，不继续SADD/PG重试。人工恢复命令完成后才resume，不能只按定时器无限重试。暂停新分配同样查持久PAUSED；某个进程退出不解除暂停，其他流仍可独立工作。

PG不可用/标记提交未知、可观察的Kafka commit异常、未知代码异常走显式停止处理器：以 `CommonContainerStoppingErrorHandler` 的停止语义为基础，显式isAckAfterHandle=false、无recoverer，传入脱敏异常；专用单线程有界停止executor（队列最多3，拒绝必须安全停机，不能退回丢弃）。停止不ACK当前项，不自动循环start。nack/暂停调用本身失败也停止。framework日志不得带原record/解析cause。恢复先查询记录与broker committed，不能把超时当未提交。此路径无持久PG记录时也不能因重启而跳到latest。

| 故障窗口 | 恢复规则 |
|---|---|
| 领取提交前退出 | 无副作用/无ACK，原offset重读。提交结果未知先读行。 |
| 领取后、SADD前退出 | 租约后续办；次数持久，最多3次后PAUSED。 |
| SADD已执行但响应超时，或SADD后PG提交前退出 | 不ACK；过租约重做SADD，集合单成员；第三次未知仍暂停，不冒称未产生效果。 |
| APPLIED提交后、ACK前退出/commit超时 | broker位点可能旧或新；旧则读原终态ACK，新则继续。APPLIED只证明先前SADD成功，不保证Redis随后没有丢数据。 |
| 隔离事务提交前/后退出 | 前者原位重试；后者唯一坐标复用隔离，不重复审计、不重解析为成功，之后可ACK。 |
| rebalance/revoke/lost | 只提交已成功显式ACK，不在回调flush未完成位点；所有权丢失不再ACK，stale fence不能写回；新owner查有效租约并nack等待，查PAUSED则暂停。 |

已提交位点是“下一条offset”，不能跨过尚未成功或未隔离记录。max.poll.records=1和同步ACK避免同批后项越过前项；不同partition不宣称全局顺序。暂停整流是有意的保守边界，C1必须验证同流后项不越过；C2补证另一流仍可前进。

### C-S：新旧组起点、保留与恢复命令

应用默认关闭：保留`recsys.behavior.seen-consume=false`；新增`recsys.behavior.seen.live-consume=false`、`history-consume=false`、`recovery-enabled=false`。旧seen-consume一旦开启就使用新可靠路径，不保留能吞错的旁路开关；缺迁移/登记拒绝启用。四开关全关时不建Kafka消费者/Admin、不校验新表、不创建恢复后台线程；保留无连接的topic校验。seen-source仍默认db。新旧二进制不能混在同一Seen组运行，旧吞错成员会破坏不变量；升级必须先停旧成员，登记后再启新成员。本回合不执行该切换。

内部受控 `SeenRecoveryService` 不加公网HTTP、RPC或自动调度；caller负责可信actor和目标授权，不能信任消息里的actor。开启recovery-enabled只装配能力，不自动执行任何命令。普通已登记流在该开关关闭后仍能履行已接受记录；手工重放/初始化不能绕过开关。返回固定领域码，未来公开适配必须走项目统一错误映射，不先新造公开错误码。

1. `initialize(commandId, stream, mode, actor, reason)`：mode=ADOPT_COMMITTED、EARLIEST_RETAINED或ENROLL_NEW_PARTITIONS；显式绑定当前配置的cluster/topicId/topic/group，不接受任意broker/topic。锁定不可变分区清单及begin/end观测（≤32）。所有初始化模式均要求组无活跃成员；已终态同ID重试先返回原审计，不重新登记。ADOPT只读采用每分区有效committed，既有组无位点/越界拒绝，不写broker位点。EARLIEST仅允许无登记且无任何已提交位点、组无活跃成员；将当时各分区beginningOffset冻结为起点，不用latest。两个新组上线前先登记；即使先发布再启动也覆盖起点之后保留的记录。旧组沿原groupId先ADOPT；首次旧组确无历史位点时才显式EARLIEST。
2. 初始化通过单次`advance(commandId)`在事务外用Admin设置**全新组或已批准新增分区**的冻结offset，再逐分区读回验证，最后同PG事务保存绑定和SUCCEEDED。PG/Kafka不假装原子：退出/响应未知后先查当前offset；部分已写且等于冻结值的保留，仅补尚缺项；有任何不一致/新成员则CONFLICT停止，不覆盖现有位点。只有登记SUCCEEDED才启动listener；没有应用成员竞写的受控窗口是前置条件，不能承诺防住越权Admin同时改位点。新分区未登记时停流；ENROLL_NEW_PARTITIONS只允许已登记同一绑定的新增且无committed的分区，整组无活跃成员，旧分区登记/committed逐项核对但不修改；仅将新分区当时beginningOffset冻结并设置。已有分区缺位点不能冒充新增，仍为OFFSET_GAP。命令重试先读原request/冻结清单再访问Kafka，不重新计算beginningOffset；kind/mode/配置绑定或record目标/expectedDigest/actor/reason共同确定同内容，改任一项冲突。
3. 每次assignment核对cluster/topicId与已登记分区、broker committed在当前begin..end范围内。无位点、out-of-range、topic重建、记录PAUSED或未完成记录已被外部位点越过均停止/暂停并报告OFFSET_GAP/IDENTITY_CHANGED，不自动seek earliest/latest。没有记录的既往丢失不能检测为完整；旧吞错组早前已提交的漏Seen也不会自动重现。
4. `requestRecovery(commandId, recordId, mode, expectedDigest, actor, reason)`：一命令一记录；RETRY仅PAUSED依赖故障，RECHECK仅QUARANTINED（修复解析器版本后原字节重判），RESTORE仅APPLIED（显式补回已知丢失的成员）。无任意新payload/userId/目标key参数。已存在同ID同内容返回原命令；改内容COMMAND_CONFLICT，不再执行第二轮。不同命令不能同时占有该record有效lease；activeCommandId非空时普通listener只等待/暂停，不能领取，恢复claim同时推进command和record的fence。
5. `advance(commandId)`：一次最多一个外部处理尝试，30秒DB lease/fence，命令最多3次、退避1/2秒；每轮先领取后调用同一产品解析/Seen处理器，成功后record结果与命令结果同事务。RETRY开新epoch和3次预算，原次数/原因保留在命令审计；普通重启不新开epoch。失败命令终态不可重开，确需再试须新commandId/actor/reason。RESTORE不能因APPLIED短路，必须真实再次SADD；RECHECK仍非法时返回STILL_INVALID，隔离证据不变；不提供忽略/删毒消息的“修正”。
6. 缺完整payload时，用隔离fetch consumer手动assign到原坐标，enable.auto.commit=false、无主group、poll最多5秒，校验topicId/offset/摘要；不改变正常listener位点，取回后仍要通过当前codec。记录过期、压缩缺项、来源变化分别SOURCE_UNAVAILABLE/IDENTITY_CHANGED；不能改字节代替。完整PG原文仍在且来源身份已受控绑定时，重放不依赖Kafka仍保留该记录。
7. `report(commandId)`和`record(recordId)`只返回脱敏状态、固定错误、attempts、fence、时间、payloadComplete和结果；隔离列表cursor=(createdAt,recordId)、limit 1..100。APPLIED、QUARANTINED、NO_EFFECT、PAUSED分别计数；命令SUCCEEDED不等于整组lag=0/历史完整。恢复不提交主group位点；原暂停listener重新读终态后自行ACK。恢复成功与container.resume之间退出，重启查状态可继续，不丢义务。命令终态时同事务释放activeCommandId；第3次命令领取后退出由下次advance在租约过期后清算FAILED并释放占用，record保留PAUSED（仍非法则QUARANTINED），不可永久卡在RUNNING。

命令稳定错误：INVALID_COMMAND、COMMAND_CONFLICT、NOT_FOUND、NOT_ELIGIBLE、ACTIVE_LEASE、STILL_INVALID、SOURCE_UNAVAILABLE、IDENTITY_CHANGED、OFFSET_GAP、DEPENDENCY_UNAVAILABLE、INTERNAL_ERROR。初始化/恢复审计必须记录可信actor/reason、固定目标、前后状态与结果；不将这些内部码当已发布HTTP接口。

### C-R：Redis历史集合丢失、保留和运维边界

SADD重复安全依赖同一 `seen:{user}` 集合；APPLIED不是可重建的全量业务事实。Redis在ACK后flush/驱逐/持久化丢失时，正常消费只处理后续记录，已提交offset和PG的APPLIED不会自动补全历史。不得用消费者running/lag=0判断完整。ReplicaSeenItemsSource注释与手册必须同步：历史仅包括已捕获且实际消费成功的合法正反馈，SOURCE_CHANGED/INVALID_SOURCE、未捕获和保留期外未知仍有缺口；read fail-open政策不改变。

本片提供已知record的RESTORE和真实history消费两条受控入口，不新增自动全量扫描。已ACK记录的PG原文还在可显式RESTORE；behavior原行尚在而从未捕获的通过既有B2显式清单。若只有Kafka原文且本库没有目标record，本片不提供扫描/另组重建入口；另组重建需owner另行有界方案和独立绑定，不能绕过当前每流唯一绑定或reset运行组。B2 ALREADY_CAPTURED/PUBLISHED不保证再次发布，不能仅重交history命令宣称重建。全量完整清单、已PUBLISHED源事件的额外重发入口、已删源及Kafka/PG原文都失去时的恢复不在本片承诺内；输出缺口，需要owner另行批准/设计，不编造时间、事件或效果。

不自动删除技术记录、命令/隔离原文或设置Redis TTL；PG和Kafka保留期由运行owner后续明确，隔离测试topic无自动过期只为故障窗口。监控seen.record各状态、paused/stopped、oldest_pending、quarantine_count、attempts、commit_failures与恢复结果（固定stream/status标签）；存储增长和容量未测，不伪造生产SLO。日志只记技术坐标/固定码/commandId，不输出原消息或解析cause。关闭消费者只停止新领取，容器shutdownTimeout=10s，未完成lease/记录保留；不在finally ACK，不删除集合或回退迁移。回退旧二进制前必须保持seen-consume关闭，不能重新启用旧吞错路径。

### C-I：文件边界与有界实施顺序

原ER-S15C保留为父片，新增两个串行子片；设计提交与实现提交独立，下一回合复用`feat/er-s15c-seen-recovery`。无新worktree、CI配置修改或部署。

| 子片 | 可观察闭环 / 文件（engine主源码包`com.recsys.recengine`） | 依赖与验收 |
|---|---|---|
| ER-S15C1 | 旧Seen可靠消费、暂停、持久隔离及命令恢复。修改`filter/SeenItemsConsumer.java`接真实record+ACK并消除吞错；新增`filter/seen/SeenRecord.java`、`SeenMessageDecoder.java`、`SeenProjectionService.java`、`SeenRecoveryRepository.java`、`SeenRecoveryService.java`、`SeenConsumerConfiguration.java`、`SeenConsumerErrorHandler.java`、`SeenOffsetInitializer.java`；primary `SeenRecoveryMapper.java`及`mapper/recengine/primary/SeenRecoveryMapper.xml`；engine-seen V001；application.yml只增加默认false与明确配置说明。 | C15C门禁；C01～C12/C15～C18适用旧流部分。必须真实旧listener+PG+Kafka+Redis，含初始化、毒消息重放、持久次数/退出恢复；不是先搭未接入的仓储层。 |
| ER-S15C2 | 新增`filter/seen/VersionedSeenItemsConsumer.java`两listener复用C1，live/history独立组与配置；修改ReplicaSeenItemsSource/SeenItemsSource及配置历史注释；补全部产品与受影响回归。 | C1；C01～C18全部，特别live/history预发布/故障/重平衡与history实际集合；不改Flink产品或behavior Outbox。 |

测试新增在engine `src/test/java/com/recsys/recengine/filter/seen/`：`SeenRecoveryPostgresTest`、`SeenRecoveryKafkaRedisTest`、`SeenConsumerConfigurationTest`、`SeenRecoveryProcessMain`（只测试的独立JVM入口）及最小`SeenRecoveryFixture`。修改原LegacySeenCompatibilityTest以适配方法签名且保留7/8字段、长整数、16次调用断言；测试必须不削弱原契约。沿现有Testcontainers PG16、apache/kafka:3.7.1、redis:7-alpine，独占随机端口/topic/group/schema；不读共享数据。test group沿behavior-reliability，下一片不改CI配置即进入原门禁。如实现发现实际需要改边界，先补有界契约，不能顺手重构资金或fixture。

每片依次：核对设计/保护/前置CI→实现一条纵向闭环→真实组件负例/恢复窗口→受影响回归→分离只读验证/静态与文档→独立逻辑提交。C1实现验证完成后按父安排连同本设计正常ff合并push；C2再按原交付规则推进。未完成C2不得将父S15C/S15记DONE。

### C-V：产品必需验收（本设计回合均UNVERIFIED）

| ID | 必须观察的结果 |
|---|---|
| C01 | 真实旧7/8字段、长整数、四种正反馈与不同offset同内容均走产品处理；SADD返回0也可ACK；合法IMPRESSION无Redis调用但持久NO_EFFECT；非法关键字段不能伪装无效果。 |
| C02 | 真实Redis停止/超时/WRONGTYPE：位点不越过目标，持久attempts耗尽PAUSED；无吞错、log-only恢复、后续同分区越过或无限retry；恢复命令后集合与committed offset正确。 |
| C03 | SADD生效后响应丢失、SADD后PG标记前退出：原记录可重做、集合单成员，技术次数持久；测试触发真实Redis后断点，不用Mock代效果。 |
| C04 | PG APPLIED提交后ACK前独立JVM halt；broker commit返回未知；重新启动产品listener，用Admin查真实committed，验证原记录/字节和无效果遗漏。 |
| C05 | 真实两consumer同组重平衡、处理中revoke/kill、新owner续办，过期租约/fence防旧回写；PAUSED和预算跨进程不重置；同流后项不越过。 |
| C06 | 非JSON/UTF-8、未知action/version、新版来源或key错配、tombstone/过长数据都持久隔离；同坐标重复唯一，PG隔离提交失败不ACK；QUARANTINED不计applied。 |
| C07 | 稳定RETRY/RECHECK/RESTORE命令的同ID重试/改内容冲突、并发命令、lease/fence、终态不可变；旧原文/审计不可改；修复依赖后真实SADD，仍非法明确STILL_INVALID。 |
| C08 | 领取后进程退出、最后一次领取退出、恢复命令副作用后/结果前退出，次数和原payload不丢；新command才能重开预算；无共享调度器。 |
| C09 | PG故障/结果未知、Kafka权限或可观察提交异常、代码异常：显式停止/不ACK；恢复先查现状。框架内部暂存的重平衡提交/Wakeup窗口单独检查，只能包含已终态项，允许重投。容器ErrorHandler配置断言与真实offset验证必须同时有，不能只看listener抛异常。 |
| C10 | 旧组预置非零committed，ADOPT不改变位点；新组先发布再初始化EARLIEST并启动，全部保留事件可见；bootstrap写Kafka后PG前退出可核查续办，冲突不覆盖。 |
| C11 | offset被删/低于begin或超过end、topic同名重建、新增分区未登记、已有活跃组误初始化均拒绝；真实retention删段或受控DeleteRecords制造缺口，不能Mock范围检查替代。 |
| C12 | 完整隔离原文在Kafka删除后仍可受控重判；不完整payload坐标缺失明确SOURCE_UNAVAILABLE且不改状态为APPLIED；相邻offset不能顶替。 |
| C13 | LIVE/HISTORY产品listener各自消费原事件，两组独立；Redis断连恢复、同eventId不同offset重复SADD、history未知ts和原local时间不被改写。 |
| C14 | 使用B2原history发布流程→真实Kafka→产品HISTORY listener→真实Redis→ReplicaSeenItemsSource读集合；不合法/无正反馈单列，Flink不订阅history、旧topic guard回归。不能以测试Sink替代。 |
| C15 | ACK后独占Redis删除该测试key：常规重启/lag=0仍不自动补全（负对照）；RESTORE原APPLIED记录真实恢复；没有完整清单时报告局部恢复而非全历史完整。 |
| C16 | 四开关全关零Seen Kafka/Admin/新表连接，误配topic即使关闭也拒绝；旧开关开但缺表/摘要/起点拒绝；新旧组独立、未知配置不退回默认factory。 |
| C17 | 新迁移表/列注释、约束、checksum漂移拒绝、primary绑定/事务回滚、不可变原文/命令与无跨owner写；原迁移、11保护和fixture指纹不变。 |
| C18 | 实测有界线程/队列、单次poll/重试、停止与脱敏日志；生产吞吐/Redis容量仍UNVERIFIED。完整behavior-reliability及受影响旧回归通过，不能降低断言/跳过真实组件。 |

设计门禁D-C01～D-C09与本表不同：设计只核对事实/版本、分类与ACK、起点、持久/事务、恢复/保留、旧新全覆盖、文件切片、验收映射及保护/交付。具体结果见[ER-D0-C15C_RESULT](ER-D0-C15C_RESULT.md)。
