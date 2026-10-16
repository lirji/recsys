# C15：behavior 已接受事件可靠发送

2026-10-16，基线 `1f77eb4d037844199c922cd2bf92ea230fc0a56f`。承接已批准 [PLAN](PLAN.md) 的 ER-S15/F14，依赖 S06/S08；本轮仅完成 `ER-D0/C15A` 必要设计门禁。**C15A 为正式实施契约；S15A 产品实现尚未开始。C15B/C 只冻结范围与必需验收，详细契约须在对应实现前补齐。** 父 S15 必须包含自然 Outbox、旧 BehaviorService 和适用行为消费者，不能因 A 完成而 DONE。

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

全部产品测试是后续 S15A 要执行的要求，**本设计回合未执行**。隔离 PG 使用既有 `postgres:16-alpine`，Kafka 复用 S06 `apache/kafka:3.7.1` GenericContainer，独占端口/topic/group/schema；不能 pause/stop 共享容器。依赖环境不可用或测试 skipped 不是 PASS。

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

上述是原 F14 范围内要求，未冻结 wire/迁移细节、不开放实现。B 完成后 C 继续；源发送通过、消费未通过时父 S15 保持 IN_PROGRESS。Flink 消费适配须与 B 事件契约协调并做兼容测试，checkpoint/事件时间/迟到和窗口对账仍归 S19，不能借此删除 S15 的已看消费验收。

没有事实依据将所有旧 POST（无事件 ID）视为业务幂等；C15B 应保留既有语义并明确保证边界，真正需要新业务去重政策时才报告外层。既有消息发送不确定的历史补发也不能静默触发正式特征重复累计；仅隔离验证，真实切换不在本授权内。

## 回退、风险与门禁输出

A 可先扩展迁移、保留旧 writer，完成 owner 升级和隔离验收后才在另获授权的环境开 relay。停止新增接受与停止 relay 分开；回退关闭 relay，待发行保留、租约到期可续办；不删除 V002/事件/审计，不回写原资格。旧只认识 V001 的 receipt-only 应用必须在 A01 验证兼容；不能声称代码回退撤销 Kafka 已发送事实。

主要残余风险：DEAD 必须有人处置；无清理政策会增长；至少一次必然可能重复；测试 subscriber 不能证明未建的产品下游；B/C 历史覆盖和确认契约尚未冻结。这些记录不阻塞独立 S15A 实施，但全部阻止父片提前完成。无需要用户新业务决定的 A 阻塞。

本门禁输出：正式 C15A、A01～A10 验收、文件边界和后续 B/C 必需项。设计核验见 [ER-S15_RESULT](ER-S15_RESULT.md)；下一具体操作是按 backend-implementation 实施 ER-S15A，从 V002 与同库持久化开始，在一个纵向片内完成真实 PG/Kafka 故障验证。当前回合按用户要求在设计交付后返回外层，不自行启动下一轮。
