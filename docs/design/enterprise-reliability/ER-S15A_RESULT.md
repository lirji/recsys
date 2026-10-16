# ER-S15A：自然已接受事件可靠发送

## 结论与边界

本回合 `eb5feb61-26b8-4a9c-aedf-e4ec6fbbf858` 完成 C15A 自然事件发送的一个纵向子片；本地 A01～A10 **PASS**。父 ER-S15 仍 **IN_PROGRESS**，旧行为生产者 S15B、产品消费者 S15C 未实现。本报告不把测试 PG Inbox/effect 登记为产品自然投影，也不覆盖 S19 Flink。

基线 `d978e5686130610cc59a1f948970be40947a6169`，原目录分支 `feat/er-s15a-natural-event-delivery`。仅独占 PG/Kafka 测试，无共享迁移、开关启用、应用部署或共享基础设施重启。11 个受保护文件、V001 和 CODEX_PROGRESS 的原历史/部署正文保持原字节。

## 已实现

- V002 为原 Outbox 增加 READY/SENDING/PUBLISHED/DEAD、30 秒 DB 租约、递增 fence、最多 20 次领取、固定错误码、待发索引及不可变重放审计。保留原事实/回执/负载与旧 writer，异常旧 published_at 阻断升级，迁移失败整体回滚。
- primary MyBatis-Plus Mapper/XML 与同 DataSource 的短事务仓储；单条 SKIP LOCKED 领取、有效 fence 才可回写，每轮最多十条耗尽清算。审计与 DEAD 重开同事务，稳定 replayId 重试返回原审计，改内容拒绝。
- 专属 codec 校验原 v1 字段、元数据、类型、时间及身份，原 key/payload 字节发送；历史已接受政策不重新按当前配置/到期时间裁决。坏事件持久隔离；Kafka 不可用/未知发送保留原事件重试。
- 默认关闭的独立 relay 与单线程 fixed-delay worker，回执停新后可独立排空且不需要签名 key。网络在 DB 事务外，producer ACK 后才标记；固定发送/退避/资源上限、停机中断、持久租约恢复和未知指标 NaN。
- 沿已有 natural-exposure CI 门禁实际运行新增测试，并总是归档子 JVM 与 Surefire 证据。未新增依赖、公开 API、公共错误码、服务或运行基础设施。

源码：behavior/exposure 下 NaturalEventCodec、NaturalEventConfiguration、NaturalKafkaPublisher、NaturalOutboxRepository/Relay/Job；primary NaturalOutboxMapper 和 XML、V002；7 个新增测试/fixture 文件。部署与恢复步骤见 [NATURAL_EXPOSURE_RUNBOOK](NATURAL_EXPOSURE_RUNBOOK.md)。

## 验证与对应证据

独立验证为实现完成后的同一 Agent 验收/只读复核 pass，未声称另有评审者。Docker 为独占 `postgres:16-alpine` 与 `apache/kafka:3.7.1`，动态端口、唯一 schema/topic/group；Kafka 客户端沿项目既有 3.6.2。关键组件测试没有 disabledWithoutDocker、跳过或 Mock 代替。Mock 仅用于错误边界/调度资源预算，真实性由 PG/Kafka/子进程补足。

| 验收 | 真实执行和断言 | 结果 |
|---|---|---|
| A01 迁移兼容 | NaturalEventsPostgresTest：既有 V001 接受行升级；原 writer 再写；迁移末尾失败无半成品；V1 异常 published_at 拒绝；事实/负载不可变 | PASS |
| A02 原事件协议 | NaturalEventCodecTest 5 项：三种 kind、历史政策/过期积压/大 ID/微秒；坏版本/key/UUID/字段/类型/重复 JSON/超长拒绝；真实 PG 坏 payload DEAD 且无发送 | PASS |
| A03 领取与 fencing | 真实 PG 两线程竞争一事件；旧 fence 的成功/失败回写均拒绝；错 DataSource/外层事务拒绝；发送中观察事务已提交 | PASS |
| A04 耗尽与重放 | 12 行各真实领取 20 次；等待最终 30 秒租约，按 10+2 清算；同命令并发/同键不同事件/变更内容冲突；再次 DEAD 原命令不重开；审计或状态失败均回滚 | PASS |
| A05 broker 故障与重复回执 | 暂停本测试拥有的真实 broker，原回执/同资格不同回执重复不增加事实或事件；故障时无 PUBLISHED，恢复后原 payload 成功下发 | PASS |
| A06 进程崩溃窗口 | 独立 JVM 在 DB 提交后发送前 halt(71)、ACK 后标记前 halt(72)；新连接/仓储/producer 恢复，后者产生两条同 ID 原字节消息但测试效果仅一条 | PASS |
| A07 消费提交窗口 | 测试独立 PG schema 中 Inbox+effect 同事务；关闭未提交 offset 的真实 consumer；同 group 重建确实重投，单一效果，最终 committed offset=record.offset+1 | PASS |
| A08 装配与错误配置 | 默认关闭无 producer/worker；relay-only 无回执 key；receipt-only V001 可用；缺 V002/摘要漂移失败；危险 topic 拒绝；保留 SASL/TLS 并覆盖固定参数 | PASS |
| A09 有界运行/观测 | worker 最多 10 次逐条发送和 10 条清算；DB 故障 5 秒退避；采样失败保持未知且间隔 30 秒；停机停止后续领取；中断未决行保留 SENDING；单线程配置 | PASS |
| A10 既有语义/保护 | 自然全门禁 108 项/19 类零失败、错误、跳过；既有行为/错误契约回归 16 项/4 类通过；11 个保护 SHA-256、原迁移/历史正文一致；真实源/Outbox/Kafka/effect 对账 | PASS |

命令和结果（日志根为 `.dev/codex-flow-reliability/round-eb5feb61/`）：

| 检查 | 实际结果 | 日志 |
|---|---|---|
| `mvn -B -pl recsys-behavior -am test-compile -DskipTests` | BUILD SUCCESS，仅编译不冒充测试 | compile.log |
| `mvn -B -pl recsys-rec-engine,recsys-behavior,recsys-gateway -am test -Dgroups=natural-exposure -Dsurefire.failIfNoSpecifiedTests=false` | 108 项/19 类，失败/错误/跳过均 0；新增 32 项/5 类（codec5、job3、config8、PG11、Kafka5）；总耗时 2:39 | natural-final.log / natural-final-summary.json |
| `mvn -B -pl recsys-behavior -am test -Dtest=BehaviorServiceTest,BehaviorServicePostgresTest,BusinessErrorContractTest -Dsurefire.failIfNoSpecifiedTests=false` | 16 项/4 类，失败/错误/跳过均 0 | behavior-regression.log / behavior-regression-summary.json |
| 完整 money-chain 门禁 | 462 项/79 类，失败/错误/跳过均 0；BUILD SUCCESS，5:00 | money-final.log / money-final-summary.json |
| persistence 边界/公开错误生成与检查 | 6+10 个脚本测试通过，632 个产品 Java/93 Mapper XML/45 历史 SQL 边界通过，错误契约生成物一致 | static-checks.json |
| Code Hygiene Gate | COMPLETE_WITH_LIMITATIONS；无 blocking finding；统一 formatter 不可用，独立静态分析未配置 | hygiene-final.json |

首批 16 项、Kafka/配置 13 项均成功，随后以最终自然全门禁复验。初次 Kafka 验证编译因 Container 通配导入歧义失败，修复为显式导入后重跑成功；保留 kafka-first.log，不隐去失败。未执行或引用本轮前端/浏览器测试，没有 UI 变化。

Hygiene advisory 复核：MAX_BYTES/MAX_ATTEMPTS/EXHAUST_LIMIT 为正式契约常量；codec 的统一 catch 用于脱敏隔离；relay 发送异常持久重试而 ACK 后 mark 异常继续上抛；worker catch 使后续调度保留，持久租约不丢事件。事务检查由真实 PG 回滚、并发、fence 和事务外发送证据支撑。没有自动格式化或静态分析完成声明。

## 原 CI 失败的必要前置修正

上轮设计提交 d978e568 的 CI 38915759801 为 failure，唯一失败 job 为 money-chain-gate。ConversionOnlinePostgresTest 第 158 行把时间字符串末尾 `Z` 换成 `123Z`：DB 时间恰好序列化为三位毫秒时，拼接后是合法六位微秒，服务返回 200 符合契约，测试错误期待 400。失败日志保留 design-ci-failed.log。

本轮仅修正该测试输入：先截到微秒再加 1 纳秒，断言非零纳秒余数，并仍要求 HTTP 400。没有放宽断言、产品精度规则或权限。此修正按独立逻辑单元提交，完整 money-chain 重新验证后才交付 S15A，不将旧失败标为成功。

## 恢复证据与限制

`recovery-markers.log` 与 `crash-evidence/` 记录实际故障位置/退出码、事件摘要和消息位置。ACK 后崩溃的事件 `22946ddc-2daf-4947-8423-bd5b1800fc94` 恢复为 PUBLISHED/attempts=2/fence=2，两个 Kafka offset=0/1 的 payload SHA-256 相同（`8be52b06c2e399234bbd4a815684b8fbb665c1020483927a407ea8d1b22acb97`），测试 effect=1。消费窗口的 committed offset 由真实 Kafka 查询断言，Surefire XML 保存执行案例。

此为至少一次投递；PUBLISHED 仅表示 broker 确认。DEAD 需受控人工恢复，未制定保留政策前不自动清理。A 只提供内部审计命令，无运营 CLI/公网恢复端点。单节点隔离演练不能证明副本容灾、生产容量或 RTO/RPO；关闭默认开关不撤销已经发送的事实。

## Git、CI 与下一回合

交付证据写入本地 `delivery.json`，绑定测试修正 SHA、S15A SHA、main/origin/main 及 CI；该记录不提交，避免提交自引用。18 个源码/测试/workflow 路径的验证 SHA-256 见 source-fingerprints.json，提交前逐项再核对；文档和暂存允许清单见 delivery-paths.json。保护终检见 protected-final.json。远程 CI 必须查询本轮交付 SHA，运行中记 PENDING，不把本地 PASS 或旧基线绿灯当作远程成功。

本轮 Git 交付后返回外层，下一操作是核对 CI 并执行 ER-D0/C15B 旧行为契约门禁，不自动进入第二片。无 A 业务决策阻塞；未完成 B/C 仍阻止父 S15 DONE。进程、日志和最新远程 run 记录以 CODEX_PROGRESS 的当前入口及本地 delivery/ci-snapshot 为准；所有 .dev、测试 target、用户未跟踪/忽略文件保留，不清理。
