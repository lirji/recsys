# 行为可靠事件与三流 Seen：运行与恢复边界

本文件对应 ER-S15B1/B2/C1/C2；正式消息和业务契约见 [C15_DESIGN](C15_DESIGN.md)。代码交付不表示已部署或已切换。当前只在独占 PostgreSQL 16 / Kafka 3.7.1 / Redis 7 夹具验证，未连接共享业务数据。旧、新 Seen 产品恢复现已实现；验收与交付状态见 ER-S15C1_RESULT、ER-S15C2_RESULT。

## 配置与启用前提

强类型启动配置 `recsys.behavior.events`：

| 键 | 默认值 | 行为 |
|---|---|---|
| `capture-enabled` | `false` | 可靠接受新源行，并为命中旧 CLICK 唯一行的重复请求修复缺失历史义务 |
| `relay-enabled` | `false` | 仅排空 live 流 |
| `history-relay-enabled` | `false` | 仅排空 history 流；不会扫描历史表 |
| `topic` | `behavior-accepted-v1` | live 物理 topic，只接受本名或 `.<隔离后缀>` |
| `history-topic` | `behavior-history-recovery-v1` | history 物理 topic，只接受本名或 `.<隔离后缀>` |

任一新开关启用时，旧 `recsys.behavior.use-kafka`、`publish-events` 必须均为 false；同时开启拒绝启动。旧发布仍开启的兼容模式不能将 `recsys.behavior.topic` 指向新 live/history/natural 保留命名空间。所有新开关均关闭时不要求新 schema，旧 00/01/10/11 语义保持。关闭 capture、保留 relay 只排空旧义务，期间新请求不具备 B1 的可靠接受保证。

新功能仅在显式迁移后启动：`recsys-behavior/src/main/resources/db/behavior-events/V001__behavior_event_delivery.sql`。由有目标授权的数据库工具计算实际 UTF-8 脚本 SHA-256，设置会话 `recsys.migration_sha256`，再执行脚本。该脚本自己 BEGIN/COMMIT；中途失败回滚。不得自动对共享库执行，不能复用自然 schema history。应用启动只校验版本摘要、源身份、源表列类型/主键/CLICK 部分唯一约束，不自动升级。

迁移生成唯一 `behavior_event_source.source_id` 并禁止改删；独立新源库独立身份，逻辑库克隆恢复保留身份。不能重置 sequence 重用业务 ID。source identity、源快照、wire 原文与重放审计均不能改删；不对旧 `user_behavior` 加删除阻断 FK，后续原行删除不删除已接受事件。

topic 必须预建，禁止 compact。本次夹具显式 `cleanup.policy=delete`、`retention.ms=-1`、`retention.bytes=-1`，不构成生产保留期承诺。Kafka 连接复用 `spring.kafka` 的受控地址/TLS/SASL 配置；没有新增端口、账号、凭据或部署组件。不得将新格式送到旧 Seen/Flink。

## 业务与状态含义

POST `/api/behavior` 仍返回 `{ok:true}`，batch 仍逐条提交并返回原 count；失败时之前成功的条目保留。没有业务键的 LIKE/PLAY/RATING 等重复请求仍可产生多个源行和多个事件，禁止因超时而自动重试整个 batch。

可靠模式归因在事务前完成；每条写入使用 primary 同一 DataSource 的 REQUIRES_NEW/READ_COMMITTED（5 秒事务、4 秒锁、5 秒语句）。INSERT 与 Outbox 同提交；CLICK 并发冲突在下一 SQL 语句锁定赢家。已有事件原样保留，重复请求不重置 READY/SENDING/PUBLISHED/DEAD，不替代独立 relay。

无 Outbox 的匹配旧 CLICK 保存原行 `LEGACY_ROW/UNKNOWN` 快照，原因 `CLICK_DUPLICATE_REPAIR`，只走 history；不采用新请求的 ts、bucket 或 value。不同 user/item 碰撞保持旧忽略响应，固定分类 `CLICK_OWNER_COLLISION`，不修复他人的事件。归因失效的请求仍沿既有规则，不承诺跨归因变化的业务幂等。

每源行 eventId=`sourceId:user_behavior:behaviorId`；原 wire UTF-8 字节与 SHA-256 持久不变。LIVE 的毫秒 epoch 来自本次归一化；历史 `behavior.ts=null`，原 TIMESTAMP 以 ISO local 微秒保留。旧来源不推断流量类别，不升级为自然可见曝光或资金事实。

非法 ID/action/value、非有限数值或过长负载保存完整数据库源文本及摘要，建立 `DEAD/INVALID_SOURCE`、attempts=0 的义务，wire=NULL，不投递、不记发布。源文本超过 64KiB 不返回 JVM，数据库内 INSERT SELECT 保存；wire 发送上限 32KiB，claim 再用 SQL 长度保护。隔离义务必须人工核查来源，不能任意改字节沿旧身份重发。

状态为 READY→SENDING→PUBLISHED/READY/DEAD。每次领取 attempts/fence 增加，租约为 DB 时间 30 秒；最多 20 次，失败退避 1～60 秒抖动。最后一次领取进程退出，租约到期后清算 DEAD。PUBLISHED 只说明 broker ACK，不说明 Seen/Flink 已完成；ACK 后标记前退出允许同 ID/原字节重复发送。

## 排查与审计恢复

先查看状态、次数、fence、固定错误、源/负载摘要、创建与可领时间；普通日志和指标不得输出原 payload/源行/凭据。Repository 将可能含 PG 失败行的异常脱敏后交给统一 `INTERNAL_ERROR`，不能把提交结果未知当未提交。

每流指标前缀 `behavior.events.outbox.`，固定标签 `stream=LIVE|HISTORY`：published/failed/stale/worker_failures、pending/dead/oldest_seconds。积压每 30 秒采样，数据库观测失败显示 NaN，不能以 0 冒充健康。每流单线程/单 producer，每轮最多 10 次发送和 10 次耗尽清算；DB 故障退避 5 秒。停机停止新领并中断未决操作，保留租约，线程等待最多 10 秒、producer close 最多 2 秒。

恢复先排除 broker/权限/目标配置问题。内部受控 `BehaviorEventRepository.replay(replayId,eventId,actor,reason)` 只重开合法 DEAD：稳定 UUID 命令、可信 actor≤200、非空原因≤1000。审计记录 oldAttempts、previousFence/newFence 与 DB 时间；精确重试返回原审计，即使该事件后来再次 DEAD，也不第二次重开。改内容冲突拒绝；审计与重开原子提交。INVALID_SOURCE 不允许该命令重开，READY/SENDING/PUBLISHED 也不允许借此抢租约或重发。

## B2 显式清单与持久续办

历史命令另外要求按顺序显式执行 `db/behavior-events/V002__behavior_history_capture.sql`，使用该脚本自己的 SHA-256 与同一迁移会话设置；不重放或修改已发布 V001。V002 同事务创建 command、item、cancel 及中文注释、约束和不可变触发器，继续使用本 owner 的 schema history/sourceId。B1 接受和 relay 不依赖 V002；历史仓储同时校验两版摘要。回退不删除新增表或审计。

内部 `BehaviorHistoryRepository(sqlSessionTemplate, primaryTransactionManager, environment)` 必须由受控调用方显式创建，两个持久化组件必须属于同一 primary DataSource。旧 `use-kafka` / `publish-events` 必须均关闭。没有自动 bean、调度器、第四开关、HTTP 端点或全表扫描；本片未对共享业务库调用以下入口。调用方负责可信 actor 和源清单授权，不接受公网传入 actor 作为认证证明。

1. `captureHistory(commandId, sourceId, behaviorIds, actor, reason)`：稳定 UUID、当前 owner 源 UUID、1～100 个不重复正 BIGINT、非空 actor≤200/reason≤1000。先排序生成不可变清单及摘要，同事务登记所有项。排列不同的同内容重试返回原任务；改内容冲突。清单缺失源行也持久为 WAITING_SOURCE。
2. `advanceHistory(commandId)`：单次最多访问 10 个不同 ID，每项独立 REQUIRES_NEW/READ_COMMITTED，事务5秒/锁4秒/语句5秒。锁序 command→item→源行→Outbox，原事件和结果同提交。按持久 `last_visit,behaviorId` 轮转；前10项一直缺失也不挡住后项。缺失项不会删除或误报完成，后来提交可再次捕获；进程退出只回滚正在处理项，已提交项继续保留。
3. `historyReport(commandId)`：最多100项，返回原清单/审计和逐项结果、查询时发送状态。原事件已有时复用身份/路由/字节；源漂移标 SOURCE_CHANGED，不覆盖事件。源删除但合法原快照仍在可返回 ALREADY_CAPTURED；已知 DEAD/INVALID_SOURCE 即使源行删除仍为 INVALID_SOURCE；没有原快照保持等待。终态结果固定，若需重新检查后来漂移，登记新命令。
4. `cancelHistory(cancelId, commandId, actor, reason)`：独立稳定取消 UUID，审计和 OPEN→CANCELLED 同事务，与 advance 串行。精确重试返回首次审计，换内容冲突；原任务已终态返回 ALREADY_TERMINAL。已捕获/发送事件不会撤销，等待项保留并计入 `cancelledUnprocessed`。

| 报告字段 | 含义 |
|---|---|
| `command.itemCount` / `items` | 已登记的全部显式清单项（含未访问项）；`lastVisit=0` / `checkedAt=null` 可区分尚未尝试 |
| `command.status` | OPEN / COMPLETE / COMPLETED_WITH_ISSUES / CANCELLED；只说明清单处理，不说明投递完成 |
| `resultCounts` | WAITING_SOURCE / CAPTURED / ALREADY_CAPTURED / INVALID_SOURCE / SOURCE_CHANGED 分别计数 |
| `covered` | CAPTURED + ALREADY_CAPTURED，表示已有原事件；不包含隔离、漂移或缺失，不是 broker 已确认数 |
| `deliveryCounts` | 查询时 NO_EVENT / READY / SENDING / PUBLISHED / DEAD；PUBLISHED 来自实际 Outbox ACK 标记，与捕获结果独立 |
| `cancelledUnprocessed` | 被取消命令中仍 WAITING_SOURCE 的数量；不得作为已覆盖或已发送 |

单次查询中逐项发送状态来自同一 SQL 快照；relay 可以随后推进它。历史旧 writer、原发布结果和无时区时间仍未知；LEGACY_ROW/UNKNOWN、`behavior.ts=null` 与原 local 时间保持。INVALID_SOURCE 继续使用 B1 的 DEAD 隔离义务，普通 replay 不能重开。报告只证明显式清单，不证明全表或封闭历史范围；不能用 MAX(id) 宣称追平。

边界整改9fb853b6：当前源仍在且摘要漂移时保持 SOURCE_CHANGED；否则已知 INVALID_SOURCE 不因删除变为 ALREADY_CAPTURED。两类问题均不计 covered，最终为 COMPLETED_WITH_ISSUES。本修正没有迁移或历史回填，旧终态报告不会自动改写；若旧版本报告把已知非法且已删除源记作无问题覆盖，保留原命令/审计，以新稳定commandId、可信actor/reason和原显式ID清单重新核验。不要尝试改payload、重置状态或删除审计来“修复”报告。

内部失败以 `BehaviorHistoryRepository.Failure.code()` 返回 INVALID_COMMAND、COMMAND_CONFLICT、NOT_FOUND、SOURCE_MISMATCH、MIGRATION_INVALID 或 PERSISTENCE_FAILURE。数据库/事务异常没有原始 cause 或源行文本。结果未知先用原 commandId/cancelId 查询或精确重试，不能换 UUID 掩盖原操作。审计与快照未定保留期，不自动清理。

## 旧消费者兼容与流式边界

旧 Seen `recsys.behavior.topic` 和 Flink `--topic` / `BEHAVIOR_TOPIC` 保持默认 `behavior-events`，启动时拒绝 `behavior-accepted-v1`、`behavior-history-recovery-v1`、`natural-impression-accepted-v1` 及其点号后缀。Seen listener 即使关闭也检查错误路由，Flink 在连接组件前拒绝。原8字段/缺 exposureId 格式保持；没有 eventId 的重复旧消息逐次处理，不使用内容 hash 合并。

C1已消除旧格式Seen吞错，C2接入新版两个真实listener；B2捕获和发布完成仍不等于Seen应用，须分别查看消费者隔离/暂停和实际集合。S19 独立承担 Flink 的窗口与 checkpoint 语义；history 不能混入实时累加。

## 验证与回退

B1/B2 必跑：`mvn -B -pl recsys-behavior,recsys-rec-engine,recsys-streaming -am test -Dgroups=behavior-reliability -Dsurefire.failIfNoSpecifiedTests=false`。CI 独立 `behavior-reliability-gate` 保存 behavior `target/behavior-event-evidence` 及三个模块 Surefire 报告；必需组件测试没有 disabledWithoutDocker 跳过。结果与明确限制见 [ER-S15B1_RESULT](ER-S15B1_RESULT.md)、[ER-S15B2_RESULT](ER-S15B2_RESULT.md)。

本片回退只关闭新增 capture/relay，或恢复已批准的兼容代码/配置；不可删事件、回写快照或假装撤销已发送消息。关闭 capture 后的新写保证退回旧模式，须明确记录。保留期未确认，不加自动清理；实际共享切换、历史清单、消费者启用和生产容量均须后续授权与验收。


## C15C 消费恢复运行边界（三流；共享环境未启用）

详细契约见[C15_DESIGN](C15_DESIGN.md) C15C，验证与限制见[ER-S15C1_RESULT](ER-S15C1_RESULT.md)、[ER-S15C2_RESULT](ER-S15C2_RESULT.md)。以下为内部owner调用顺序，未提供公共HTTP/RPC或运维CLI；本回合仅在独占测试环境执行。LEGACY/LIVE/HISTORY独立产品listener与组，共用可靠确认机制；所有消费/恢复开关仍默认false。

### 迁移、登记与启动

1. 保持`recsys.behavior.seen-consume=false`、`seen.live-consume=false`、`seen.history-consume=false`、`seen.recovery-enabled=false`（后三者前缀均为`recsys.behavior`）。确认旧吞错二进制已退出目标组；不能新旧混跑。默认seen-source=db不变。
2. 在明确授权的engine primary库执行`recsys-rec-engine/src/main/resources/db/engine-seen/V001__seen_recovery.sql`。计算实际脚本SHA256，在同一数据库会话先设置`SET recsys.migration_sha256 = '<实际SHA256>'`再执行脚本；脚本自带BEGIN/COMMIT、中文表/列注释及独立engine_seen_schema_history。不修改behavior/ad或旧迁移；应用启动只核对摘要，不自动建表。不要把文档示例当共享迁移授权。
3. 仅打开`recsys.behavior.seen.recovery-enabled`以装配内部恢复能力，尚不开始消费。沿现有spring.kafka地址/TLS/SASL、Redis≤2秒超时与primary数据源。用稳定UUID、可信actor/reason调用`SeenRecoveryService.initialize(id, mode, actor, reason)`。旧组ADOPT_COMMITTED只采用有效committed；确无登记和位点的新组用EARLIEST_RETAINED冻结当前最早保留起点；已有组扩分区仅ENROLL_NEW_PARTITIONS。所有模式要求无活跃成员；不能将原分区丢位点当作新分区。
4. 先`report(id)`再`advance(id)`；每次advance最多一次外部处理尝试。command READY/RUNNING/终态及3次预算、30秒租约均持久。初始化在任何alter前拥有流控制权，另一个command无权覆盖；同ID复用最先持久的清单，不能重新采样beginningOffset。响应未知先读原命令和真实broker位点；只补缺项，已有不等于冻结值就拒绝。初始化FAILED仍保留占有，原终态不改，须owner另行处置，不能靠新ID绕过。
5. 初始化SUCCEEDED且身份/所有分区/位点/整流义务检查通过后，才可由获授权owner开启该流消费开关或显式调用该流SeenListenerControl.start。offset reset固定none；缺表/摘要漂移、位点缺失/越界、topic重建、新分区未登记都拒绝。没有在共享环境执行上述步骤。

### 三流配置和调用归属

| 流 | 消费开关（默认false） | topic / group默认值 | 内部bean前缀 |
|---|---|---|---|
| LEGACY | `recsys.behavior.seen-consume` / `SEEN_CONSUME` | `recsys.behavior.topic=behavior-events` / `recsys.behavior.seen-group=recsys-rec-engine-seen` | `seen` |
| LIVE | `recsys.behavior.seen.live-consume` / `SEEN_LIVE_CONSUME` | `seen.live-topic=behavior-accepted-v1` / `seen.live-group=recsys-rec-engine-seen-live-v1` | `liveSeen` |
| HISTORY | `recsys.behavior.seen.history-consume` / `SEEN_HISTORY_CONSUME` | `seen.history-topic=behavior-history-recovery-v1` / `seen.history-group=recsys-rec-engine-seen-history-v1` | `historySeen` |

表中`seen.*`前缀均为`recsys.behavior`。新版topic/group环境变量分别是`SEEN_LIVE_TOPIC/GROUP`和`SEEN_HISTORY_TOPIC/GROUP`。新topic仅允许逻辑本名或合法点号隔离后缀，三group必须不同；即使全关也校验配置。全关不建立Seen Kafka/Admin/新表访问；单消费开关只装配对应控制器，缺迁移或登记拒绝启动。`recovery-enabled=true`装配三流内部能力，消费仍由各自开关控制，没有自动初始化或恢复循环。

使用相应`seenRecoveryService/liveSeenRecoveryService/historySeenRecoveryService`与`seenListenerControl/liveSeenListenerControl/historySeenListenerControl`（显式Qualifier）。命令、record查询、隔离游标、advance和resume都限定绑定流；跨流返回NOT_ELIGIBLE且不消耗目标预算，未知recordId固定NOT_FOUND。初始化和恢复仍须稳定UUID及可信actor/reason，不开放HTTP入口。

新版严格复用LegacyBehaviorCodec，LIVE只接BEHAVIOR_POST/NOT_ATTEMPTED，HISTORY只接LEGACY_ROW/UNKNOWN；key必须匹配原userId。非法UTF8、版本、来源或key先持久隔离，不能变成NO_EFFECT。不同offset即使同eventId也各自登记并真实SADD；返回0仍成功。历史ts=null/local原文保留，不进入旧Flink。三流会写同一用户Seen集合，但暂停/停止/组/恢复权限和指标隔离；某流暂停不阻断其他流。

### 暂停、停止与单记录恢复

真实SADD成功（返回0或1）→PG APPLIED→listener线程ACK；合法IMPRESSION为NO_EFFECT。非法数据持久QUARANTINED后可ACK，隔离不等于Seen成功。Redis异常/响应未知不ACK，最多3次持久预算后PAUSED并暂停整流；PG/可观察commit未知或代码错误停止。停止原因尽力写PG，PG不可达时无法承诺标记已保存，但本地仍停止，重启必须重新核查。

内部调用`requestRecovery(commandId, recordId, mode, expectedDigest, actor, reason)`，随后按原ID查询/有界advance。RETRY仅PAUSED依赖恢复；RECHECK仅QUARANTINED原字节重判，仍非法返回STILL_INVALID；RESTORE仅APPLIED，必须再次真实SADD，不能被原APPLIED短路。不接受任意payload/user/key；重放不发新Kafka消息、不移动主组offset。同ID改内容冲突，终态重试返回原结果；新一轮须新命令。最后一次领取退出后，下次advance在租约过期清算FAILED并释放占用，不能永久卡RUNNING。

先处理全流所有PAUSED/耗尽/活动恢复义务，再`authorizeResume()`核对真实身份/位点并清除持久停止原因，最后显式`SeenListenerControl.resume()`或start。只有一条恢复成功不能放行另一条；启动新进程也不重置次数。暂停容器继续poll心跳。查询使用report/record与隔离游标(createdAt,recordId)，limit≤100，只返回脱敏技术状态。

### 保留、观测与回退

ACK后Redis集合丢失不会由正常重启、APPLIED或lag=0自动修复。已知记录RESTORE仅代表局部恢复；未捕获且仍可捕获的历史需B2明确清单和实际HISTORY链路。B2 ALREADY_CAPTURED/PUBLISHED不等于重新发送；仅Kafka保留但本库无record的全量/另组重建没有本片入口，须owner另行方案。不能凭本片推断全部历史完整。

key≤1KiB、value≤32KiB保存原字节；超限只保存坐标/长度/摘要，不能截断冒充原文。完整PG原文可脱离Kafka保留重判；缺完整原文按同cluster/topicId/partition/offset有界重取，过期/缺项SOURCE_UNAVAILABLE，相邻记录不能替代。不自动删除记录/审计，生产保留/存储容量/RTO/RPO未知。

现有Micrometer暴露固定维度`recsys.seen.records`（READY/APPLYING/APPLIED/NO_EFFECT/QUARANTINED/PAUSED）、`oldest.pending.seconds`、`paused`、`stopped`、`attempts`、`recovery`、`commit.failures`、`stops`；PG读失败为NaN，不冒充零。broker committed仍需Admin核对，指标不是位点权威。每流单consumer、单poll记录，三流共用停止线程1/队列3、PG事务5秒、Redis≤2秒；生产吞吐未测。

回退先关闭Seen消费/恢复能力，保留全部表、原文、审计和位点；回退旧二进制仍须保持旧consumer关闭，避免恢复吞错入口。无共享切换/部署/清理授权。history不转投旧Flink实时累计，S19仍独立完成checkpoint/窗口恢复。
