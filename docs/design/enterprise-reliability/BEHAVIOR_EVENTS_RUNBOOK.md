# 旧行为可靠事件：B1 运行与恢复边界

本文件对应 ER-S15B1；正式消息和业务契约见 [C15_DESIGN](C15_DESIGN.md)。代码交付不表示已部署或已切换。当前只在独占 PostgreSQL 16 / Kafka 3.7.1 夹具验证，未连接共享业务数据。B2 显式历史清单与 C15C 实际 Seen 消费恢复尚未交付。

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

B1 不提供公网恢复 API、自动历史扫描或补发命令。B2 将提供显式清单及逐项报告，必须验证前 10 项缺失时后续可见项不饥饿；C15C 必须同时处理旧格式 Seen Redis 失败吞错确认，单增新 listener 不算完成。S19 独立承担 Flink 的窗口与 checkpoint 语义。

## 验证与回退

B1 必跑：`mvn -B -pl recsys-behavior -am test -Dgroups=behavior-reliability -Dsurefire.failIfNoSpecifiedTests=false`。CI 独立 `behavior-reliability-gate` 并保存 `target/behavior-event-evidence`、Surefire 报告；必需组件测试没有 disabledWithoutDocker 跳过。结果与明确限制见 [ER-S15B1_RESULT](ER-S15B1_RESULT.md)。

本片回退只关闭新增 capture/relay，或恢复已批准的兼容代码/配置；不可删事件、回写快照或假装撤销已发送消息。关闭 capture 后的新写保证退回旧模式，须明确记录。保留期未确认，不加自动清理；实际共享切换、历史清单、消费者启用和生产容量均须后续授权与验收。
