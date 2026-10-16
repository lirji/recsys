# 自然 sandbox 资格与回执运行说明

2026-10-15；依据 [C08-A/B](CONTRACTS.md)。参数化后端与用户确认的自然图文政策已实现；当前默认关闭，不迁移共享库、不启用真实流量。自然政策为 natural-text-v1：50% 连续可见 1 秒、hidden 重置、资格 5 分钟且到期前服务端接受。

## 所有权和运行边界

engine primary PG 保存整次交付、原请求/政策、最终文本/位置/分桶和签名资格。behavior 自有 PG 保存回执、资格上下文、唯一自然事实和 Outbox。两者只通过签名票据协作，无跨库读写或事务；adDb、旧 user_behavior、Redis 曝光归因和旧统计分母均不被此新路径写入。依赖版本不变；测试使用隔离 PostgreSQL 16 Alpine，未验证生产拓扑或吞吐。

`PUT/GET /api/sandbox/natural-deliveries/{operationId}` 归 engine；`PUT/GET /api/sandbox/natural-impressions/{receiptId}` 归 behavior。网关 static/discovery 均先匹配 behavior 专属路径，再匹配 engine 的 sandbox 通配路径。边缘与 owner 都要求 ADMIN；模拟 userId 不能代替认证主体授权。

## 配置和迁移

| 配置（前缀 recsys.natural） | 责任和缺省 |
|---|---|
| delivery-enabled | engine，默认 false；启用才装配签发 owner |
| receipts-enabled | behavior，默认 false；启用才装配接受 owner |
| admission-enabled | 各 owner 独立进程，默认 true；false 暂停新意图，保留已提交原结果 PUT/GET 恢复 |
| policy.version / min-ratio-bps / min-duration-ms / ttl-ms | 完整显式元组 natural-text-v1 / 5000 / 1000 / 300000；两端须一致。启动时验证已批准元组，不接受测试政策或数值漂移 |
| current-key-id | engine 必填，匹配 keys 中一项；behavior 只验证，不签发 |
| keys | keyId → 受控 base64 key；1～16 项、每项解码 32～128 字节，专用自然用途。禁止在仓库/工单/日志贴真实值 |

配置为启动快照，没有热刷新承诺。新 key 先进入所有接收端验证环，再切签发 key；旧 key 保留到在途资格窗口与恢复要求满足。已提交同 receiptId 结果不需要重验现行 key；未提交的原资格仍需要其 key 可验证。政策升级同样需要在途协调；本版只接受一个完整 Policy，不承诺不同政策同时收单。

迁移仅在已确认的隔离 owner 库中显式运行；启动只核对脚本 SHA256，不自动建表：

- engine：已有 V001 后执行 `recsys-rec-engine/src/main/resources/db/engine-delivery/V002__natural_delivery.sql`；不要重跑或修改已执行 V001。
- behavior：执行 `recsys-behavior/src/main/resources/db/natural-exposure/V001__natural_receipt.sql`；独立 natural_exposure_schema_history，不使用 engine/ad 的版本历史。
- 执行前设置会话 `recsys.migration_sha256` 为该文件实际 SHA256，使用 `ON_ERROR_STOP`；SQL 自带 BEGIN/COMMIT。以受控连接引用指定目标，禁止凭默认连接猜库。测试通过 JDBC 在临时容器执行了完整脚本。
- 新旧 API 共存；开启需要所有目标实例升级、正确配置策略/key、迁移校验通过。当前没有执行环境迁移或开启；本次只交付与验证代码。

## 未知结果与故障恢复

交付 202 是原操作仍准备中；503 是依赖/提交未知。先 GET 原 operationId；若未完成，可原 ID、原 body PUT 续办。不得因表单编辑、候选变化或刷新重新解释原请求。同 ID 改请求 409，异主体 403。30 秒数据库租约到期可被新执行器领取，旧 fence 不能覆盖或释放新执行权。完整结果提交后资格才对外可见；提交后丢响应依然恢复原 token、内容、桶和期限。失败未提交不会产生可查询的“成功空结果”。

回执 PUT 只接受 token 和连续可见声明。ACCEPTED/ALREADY_ACCEPTED=200；POLICY_NOT_MET/EXPIRED=422 并保存首次决定；GET 200 返回原决定，包括拒绝。系统 503/网络超时均不得显示已接受；先 GET 原 receiptId，404 只代表当前未观察到，继续保持同 ID。改负载 409，异主体 403，非法签名/政策 400。正常拒绝之后的新观测意图使用新 receiptId；同资格最多一条事实。

行为事务按 receipt 唯一键→qualification 锁排序，锁后取 clock_timestamp，5 秒事务/语句上限、4 秒锁等待上限。资格锁竞争跨过期限仍拒绝；事实、Outbox、原结果整体提交。提交时约束拒绝 PENDING 半成品及缺失 Outbox，复合 FK 绑定 canonical receipt/acceptedAt。事实、资格和终态不可 UPDATE/DELETE；不能通过改历史补偿。单条外部持久化失败/未知由原 ID 恢复，不访问 MQ/Redis。

S08 的 V001 只持久化不可变 Outbox；S15A 通过追加 V002 和独立默认关闭 relay 推进发布，操作与恢复见下节。事件落表、broker ACK 和下游业务效果仍是不同状态。S09 已接入 /recommend、/search 浏览器采集；S10 已实现 feed 最终位置与双回执，见 [混排运行说明](FEED_EXPOSURE_RUNBOOK.md)，构建开关均默认关闭。

## 检查与保留

运维只查询关联 ID/计数，避免导出包含 token 的 engine result_json 或含认证主体的 guard context_json。以下只读 SQL 在各自 owner 的受限连接执行：

```sql
-- engine：未完成数量与最早进入时间；不暴露请求/票据。
SELECT state, count(*), min(created_at) FROM engine_natural_delivery GROUP BY state;
-- behavior：所有接受事实必须有事件；应为零。
SELECT count(*) FROM natural_impression_fact f
LEFT JOIN natural_impression_outbox o ON o.event_id=f.qualification_id
WHERE o.event_id IS NULL;
-- behavior：待发规模和最早创建时间，不把数据库就绪当事件发布成功。
SELECT count(*), min(created_at) FROM natural_impression_outbox WHERE published_at IS NULL;
SELECT status, count(*) FROM natural_impression_receipt GROUP BY status;
```

尚无批准的保留/归档周期，本版不清理操作、幂等、资格、事实或待发事件；扩量前确定容量、保留策略和告警阈值。异常接口日志只记录 UUID 和异常类型，错误 JSON 不回显内部 SQL/凭据；实际定位结合受限数据库及 trace，不能临时把完整请求打入日志。

回退先暂停新签发/接受，保留新版本查询和历史恢复。旧页面/旧二进制不会撤销已经提交的新事实。需要纠正事实时另走明确补偿契约，禁止删除行“重做”。本片没有生产 RTO/RPO、浏览器零丢失、真人注意力或行业认证承诺。

## 复现验证

```sh
mvn -pl recsys-rec-engine,recsys-behavior,recsys-gateway -am test -Dgroups=natural-exposure -Dsurefire.failIfNoSpecifiedTests=false
```

需本地 Docker，测试容器生命周期由 Testcontainers 管理，不操作 dev_infra 的实例。完整结果见 [S08 参数化机制验收](ER-S08_RESULT.md)；当前任务不授予真实账户收费或生产部署。

## S09 浏览器启用与恢复

仅在两个 sandbox owner 都完成本手册显式迁移、政策/key 配置和 ADMIN 接入后，用 `VITE_NATURAL_EXPOSURE_SANDBOX_ENABLED=true` 构建/启动 console。该前端开关不是后端来源授权；服务端仍独立验证身份、签名和期限。默认 false 保留旧页，不自动打开共享环境。

用户点击新交付才发 PUT；推荐页空词是 RECOMMEND，有词是 SEARCH，搜索页词必填。原请求未确认时用“恢复原操作”，不要换 ID 猜测成功；刷新只查同认证范围的原 ID。当前表单编辑不会改变已交付卡片的用户或内容。大 ID 保持十进制字符串。

正文实际达到 50% 连续 1 秒后进入自然队列，离屏/hidden/pagehide/打开历史或对比重置；历史重跑明确是新交付。自然状态只表示曝光事实接受，无金额或收费含义。UNKNOWN 同 ID 查询后才尝试原请求；过期后只查，404 不表示一定未接受。原操作进行中时历史重跑按钮禁用。

自然与广告使用独立 sessionStorage 队列，各 100 条、512 KiB、1h、最多 2 并发/6 自动轮；两者总上界 200 条/1 MiB/4 并发。只保存达标原信号与同 receiptId，终态移除资格票据，已知结果字段白名单；不保存 Bearer。退出/切主体清理两队列及三个在线页面意图；同主体续期保留恢复。浏览器关页/存储失效仍可能丢失未提交观测，事件可靠性承诺从服务端 accepted 起。

本地验收：`python3 scripts/test-natural-exposure.py`，需要现有 Docker/JDK21/Node/console 依赖及 Playwright Chromium；自动临时 PG、两个 owner schema、数据库内容种子、测试登录适配器，宿主仅 loopback，结束核对事实/Outbox。不会修改共享库。证据在 `recsys-rec-engine/target/natural-browser-evidence` 与 `natural-browser-backend.log`；使用 behavior 测试宿主，test-scope 复用 engine，生产依赖不变。此宿主同 JVM、不代表跨进程故障、生产 gateway/OIDC 或 Kafka 已投递。详细结果见 [S09 验收](ER-S09_RESULT.md)。

回退关闭前端开关并重建；已提交事实不能由关闭页面撤销。暂停新增应使用 owner admission 配置并保留查询/原决定恢复，不直接丢弃在途未知结果。


## S15A 自然事件可靠发布与受控恢复

正式约束见 [C15_DESIGN](C15_DESIGN.md)，验证与交付状态见 [ER-S15A_RESULT](ER-S15A_RESULT.md)。只处理 behavior 本库已经接受的 SANDBOX 自然事件，不投旧 `behavior-events`，不写旧行为/正式训练或收费链路。该能力默认关闭，本轮只在独占测试容器运行，没有执行共享迁移或应用部署。

在已明确授权的目标中，先核对 V001 和目标 owner，按前文 checksum/ON_ERROR_STOP 方法显式执行 `db/natural-exposure/V002__natural_event_delivery.sql`。V001 不得修改；V002 与触发器切换同事务，原六列 writer 仍兼容。异常 V1 published_at 非空会拒绝迁移，不能清空字段强行通过；须核查原记录来源。

| 配置 | 值和含义 |
|---|---|
| `recsys.natural.events.relay-enabled` | 默认 false；显式 true 才创建 sender/worker，独立于 receipts-enabled/admission-enabled，不要求签名 key |
| `recsys.natural.events.topic` | 默认 natural-impression-accepted-v1；只允许同名或 `.隔离后缀`，不热切换目标 |
| `spring.kafka.*` | 复用受控 bootstrap/TLS/SASL；acks、幂等、有界重试和发送资源上限由专用 producer 固定 |

事先显式预建自然 topic，禁 compact/自动建 topic；沿 sandbox 首版保留策略 retention.ms=-1、retention.bytes=-1 并监控空间。原 payload/key 发送，不因原资格已过五分钟、现行 key/政策更换而删掉已经接受的事实。单节点隔离测试不代表副本容灾/RPO。

每次领取一条，30 秒 DB 租约、递增 fence，最多 20 次（崩溃也计次）。ACK 后仅有效租约可标 PUBLISHED；超时未知或 ACK 后崩溃允许原事件重发。每轮最多十次逐条发送及十条耗尽清算，单线程 fixed-delay；DB 失败退避 5 秒。积压每 30 秒采样，观测失败为 NaN/未知，不当作 0。

以下只读 SQL 仅在目标 behavior 的受控连接执行，不输出票据、主体或整段 payload：

```sql
SELECT status,count(*),min(created_at),max(attempts)
FROM natural_impression_outbox GROUP BY status;
SELECT event_id,status,attempts,fence,available_at,last_error,published_at
FROM natural_impression_outbox WHERE event_id = :event_id;
SELECT replay_id,event_id,old_attempts,previous_fence,new_fence,replayed_at
FROM natural_impression_outbox_replay WHERE event_id = :event_id ORDER BY replayed_at,replay_id;
```

监测 `natural.events.outbox.pending/dead/oldest_seconds/published/failed/stale/worker_failures`（Prometheus exporter 按其既有命名转换）。DEAD 表示仍有未履行义务：先核对版本/原事件/连接，修复依赖；由受信任内部工具调用 `NaturalOutboxRepository.replay(replayId,eventId,actor,reason)`。A 提供内部命令、没有公网恢复端点或运营 CLI。actor 必须来自可信边界；保存稳定 replayId，完全相同重试返回原审计，相同 ID 改内容报冲突。相同命令即使再次 DEAD 也不再重开；确需再次恢复使用新的审计命令。不能修改原 payload、改成新 eventId 或手工把状态置为 PUBLISHED。

停止新增接受只关 admission；保留 relay 可继续排空。停 relay 先停领取/中断在途、有界关闭；未知发送行由租约接管。回退关闭 relay，保留 V002 与全部事实/事件/审计；不要撤销迁移或清队列。PUBLISHED 仅证明 broker 确认，不等于业务消费者应用；自然测试 Inbox/effect 仅作隔离验收，产品下游和旧行为分别按原计划继续。

复现沿既有 natural-exposure 门禁命令。测试创建独占 PG/Kafka、唯一 schema/topic/group，真实暂停测试 broker，子 JVM 在提交后/ACK 后 halt；不暂停共享 dev_infra。进程退出日志在 `recsys-behavior/target/natural-event-evidence/`；Surefire 输出含原事件摘要、状态/fence、Kafka partition/offset 和测试效果数。CI 的自然门禁总是归档这些证据。数据保留/生产切换/容量参数仍需独立批准，不因本片交付自动启用。
