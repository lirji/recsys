# C16：广告目录物理分片写入与可靠事件

状态：ER-D0/C16 正式设计稿；产品实现及 C16-01～C16-20 运行验收尚未执行。范围是原 [PLAN](PLAN.md) F15 / ER-S16，依赖已交付 ER-S06；S17 只约定接口，不在本设计回合实施。唯一实施状态见 [IMPLEMENTATION_SLICES](IMPLEMENTATION_SLICES.md)。本轮仅独立本地设计提交，下轮沿同分支实施后统一正常交付。

## 1. 已核实事实与目标

基线 `686454dc132da7831dfdc197cb6fdfa9e6348db0`。源码索引、SHA-256、JAR API 证据在 `.dev/codex-flow-reliability/round-301afed4/`；父 `S16_S17_SOURCE_NOTES.md` 仅作定位。S15 已完成必要本地验收和正常 Git 交付；其新 CI 状态另存，不将设计 PASS 冒充源码 CI PASS。

| 实际入口/文件（仓库相对路径） | 现状及必须保持/修正的边界 |
|---|---|
| `recsys-advertiser/.../AdvertiserDataSourceConfig.java`、`AdvertiserPersistenceConfiguration.java`、`application.yml` | primary Mapper 绑定 Boot `dataSource`；该源使用 ShardingSphereDriver 5.5.0，派生 embedding 源另建 MyBatis factory。未发现 advertiser 自定义事务管理器；服务注解沿 Boot 主源事务。primary 不是物理单库的证明。 |
| `recsys-ad-common/src/main/resources/sharding.yaml` | advertiser/ad 按 advertiser_id % 2；bidword/ad_creative 按 ad_id % 2；其他 SINGLE 默认 ds_0。两个逻辑 shard 即使位于同一 PG 服务器的两个 database，也没有一个 PG 本地事务。adId=102/advertiserId=1 是必须覆盖的不同库样例。 |
| `AdvertiserService.createAd/updateAd/setAdStatus/reviewAd/submitForReview/deleteAd`、`AdController` | 新建可带竞价词；更新保持 null/定向清空、长整数、机审/人审规则；删除当前依次删子资源、ds_0 向量及 ad。现有注解不能保证上述跨库故障原子性。 |
| `add/update/deleteBidword`、`BidwordController` | 子资源可能不与广告同库；部分 SQL 只按子资源 ID 广播定位，可靠写必须补齐 ad_id。规范化 keyword、matchType、bidMode 沿原契约。 |
| `add/update/deleteCreative`、`CreativeController` | 当前没有目录发布调用；新可靠路径必须捕获三类变化。保留其 active/paused 语义，不另发明创意人审接口。 |
| `create/updateAdvertiser`、`AdvertiserController` | name/dailyBudget/status 更新；状态改变同步遍历全部广告。广告主没有删除 API，不凭空增加。开户的外部 authz 关系写不属于 PG 原子保障。 |
| `AdIndexSyncService`、`AdCatalogEventPublisher` | 多次跨库读取后构造快照；Redis/Kafka 尽力双写，embedding 失败为 null；发布开关默认 false。错误日志可能包含业务文本，新路径仅记录错误类别/标识摘要。 |
| `recsys-offline/.../SeedAdsJob.java`、`SeedAdMapper.xml` | 直接写四张目录表；`--clear` 先清 adDb 再清分片/Redis。可靠模式必须在任何此类副作用前拒绝种子作业，不能只依赖分片 DELETE 失败。旧进程退出仍是切换前提。 |
| `recsys-ad/.../PacingService.java` | 已查看实现只读取预算并计算过滤；未发现它写 advertiser.status。旧注释中“自动置 over_budget”不能当作实际 writer。将来新增 writer 须接入同协议。 |
| `AdCatalogEvent`、`ad-serving/.../AdCatalogEventConsumer` | 旧事件没有版本，消费者吞错/last-write-wins；同 key 不能抵抗迟到 relay/重放。S17 才实现版本化消费，旧消费者不能接收新 topic。 |
| `recsys-offline/sql/{02_ad_schema,04_ad_sharding,04b_ds1_bootstrap}.sql`、`ad/.../AdCatalogMapper.xml` | 已有分片DDL移除跨库外键/IDENTITY；04部分旧注释仍写ad_id路由，以当前sharding.yaml为准。真实creative读取要求status active且review_status approved，新快照须保留该源字段，不能只复制旧CreativeView。旧SQL不改，启用前核实两库实际列/约束。 |
| 两个既有 `*PostgresTest` | advertiser 与 ad 模块已有真实双 PG、原 ShardingSphere 规则的路由测试；没有证明 C16 的原子 Outbox、持久续办或跨库快照。保留并在实现时做受影响回归。 |

目标：每个已提交目录业务变更，在**实际发生变更的物理库**同时留下不可变事实和待办；跨库操作失败后可查询、有限推进；最终每个 ad 产生有持久单调版本的完整权威目录事件。允许跨库中间态，不能以事务注解、Kafka 成功或一个公共 Outbox 声称跨库原子。

非目标：资金/自然/Seen 政策，搬迁现存表，更改分片算法，XA/Seata/CDC，新服务/调度基础设施，生产切换，全历史重建，S17 消费者及可靠 Redis 投影。吞吐、传播延迟、生产 RTO/RPO 未知；下列数值是首版保护预算，不是性能承诺。

## 2. 方案比较与选择

| 候选 | 能保证什么 | 代价/结论 |
|---|---|---|
| 同一逻辑 DataSource + 业务写后向 SINGLE Outbox 插入 | 正常路径可能成功 | 多库部分提交、ds_1 业务与 ds_0 Outbox 分离；否决。 |
| 把 children 重分片到 advertiser_id，或启用 XA | 前者完成搬迁后可减少跨库；后者可协调资源提交 | 涉及既有数据迁移/路由/恢复或新事务运行设施，超出必要范围；本片不选择。 |
| **显式单物理分片事务 + 每库 Outbox + advertiser owner 持久串行命令** | 单库业务/事实原子；跨库逐步可恢复；稳定切面的完整事件 | 选择。一个广告主同时只推进一个目录变更，未决会阻塞该广告主后续写；其他广告主可独立推进。不持有跨网络长事务，不声称整个用例原子。 |

沿用 Java 21、Boot 3.2.5、Spring Kafka 3.1.4 / client 3.6.2、PG JDBC 42.7.3、ShardingSphere 5.5.0、现有 PG16/Kafka3.7.1 隔离测试。使用现有 Mapper XML、事务化 owner SQL/schema history、Testcontainers GenericContainer；不新增第三方依赖，不升级版本。不抽取资金 Outbox 成跨 owner 通用写库框架。

选择仍经原 ShardingSphere 源执行，但**每个短事务的全部 SQL 必须落到一个明确 shard**；这是待真实验收的路由约束，不是从 DataSource 名称推导出来的保证。新治理表全部在 ds_0/ds_1 各一份，在显式可靠profile中以 `shard_id` 路由 `ds_${shard_id}`；绝不交给 SINGLE 首次发现。默认`sharding.yaml`原样保留；新增`sharding-catalog-reliable.yaml`以它为业务规则/连接模板，只追加治理表规则及可靠writer-id配置，静态测试逐项比较这两个允许差异之外完全一致。业务 SQL 同时携带真实分片键。禁止借反射取得驱动内部连接；本机 5.5.0 DataSource 没有公开 getContextManager API。

## 3. 所有权、原子单元与写入口

所有新表与用例归 advertiser catalog owner。`recsys-ad` / `ad-serving` 不直接写这些表。derived item/embedding、adDb 账务表、Redis 不在目录事务内。

| 本地单元 | 实际目标 | 同事务内容 |
|---|---|---|
| OWNER | `advertiserId % 2` | advertiser/ad 业务行（如本步改变）、guard/command/aggregate 状态、本地不可变 Outbox、结果/检查点 |
| CHILDREN | `adId % 2` | bidword/ad_creative 的业务变化、本地 step 结果、完整 children 片段 Outbox |
| SNAPSHOT | OWNER | 已验证片段组合、ad aggregateVersion、最终完整事件 Outbox、原命令结果/释放占有；只有需要改 ad 的用例才同时写 ad |
| ENRICHMENT | 独立 derived 只读 | 事务外有界取向量；不改源 item、不把失败视为目录删除，不是业务原子单元 |

单事务不混 OWNER/CHILDREN（即使本例恰好同 shard，也按相同步骤协议，避免偶然共址形成另一套语义）。定位只提供 adId/childId 的旧 URL 时，可先有界读取路由归属；真正写前在目标 shard 重读并验证 advertiserId/adId/childId。不得在事务内以无分片键 SQL 广播 UPDATE/DELETE。广告 ID 归属不可改变；未知/重复归属是 CONFLICT，不随意取第一条。

建立 `CatalogWriteRouter`，四个真实 Controller 的写口路由至 legacy 服务或可靠命令服务；可靠分支不进入原 `AdvertiserService` 外层 `@Transactional`。复用/提取原校验和 DTO 组装，保留业务规则；legacy 分支的原事务和默认关闭行为不借机重构。新增 `CatalogLocalMapper`/XML 的可靠 SQL 始终含路由键；查询完整权威 ad 时不调用会顺带读 ds_0 embedding 的 `findAdRow/listAdRows`。不只新增内部仓储而遗留 Controller 吞错发布路径。

开户在 advertiser 本库写行、owner guard、开户事实 Outbox；原平台 administrate 判权、owner 关系语义保留。其特殊点是调用前还没有advertiserId：先在由commandId最低位确定的homeShard保存ROOT_CREATE命令及`catalog_identity`预留ID，使用**原ShardingSphere Snowflake keyGenerator**在新身份表生成，提交后读回；未知结果必须查同一预留，不能重新生成。然后按已冻结ID的模数进入业务shard，显式带该ID插入advertiser。两个同ID开户请求在同一home命令行竞争，不能分别生成两个广告主后才靠最终唯一键合并。其他ad/child新建仍在它们已确定的目标库中使用原生成键，并在同事务冻结生成ID/Outbox/结果。

开户权限关系用原actor和冻结ID执行原TOUCH语义，事务外有界调用；不能让后台worker的身份替换开户主体。失败/响应未知保留原命令，不返回开户完成；同ID重试只向同一资源TOUCH。外部authz成功而PG提交未知仍可能留下关系，**此设计不承诺PG与权限平台原子**，不自动删除关系。新增可靠分支的这一调用位置需对应权限回归，原legacy分支不改。开户没有ad，不制造ad墓碑。广告主仅name/budget变化留下root事实，不无意义fanout；status变化属于S16B。每日pacing计数/资金账户仍是各自owner。

## 4. 持久协议与快照一致性

### 4.1 身份、排他和版本

- `sourceId` 是登记时固定的目录数据集 UUID，两 shard 一致，重启不生成新值；sourceId/拓扑/hash/新 topic 绑定持久保存。不是进程 ID。恢复旧备份或换 sourceId 不能绕过 S17 墓碑。
- 普通命令键 `(sourceId, advertiserId, commandId UUID)`；开户返回生成 advertiserId，内部初始化/批次命令另带固定 `homeShard`。规范化请求字节/摘要、操作、原 actor/reason 不可变；同键同请求返回原记录，冲突返回 409。actor 来自可信上下文，不相信客户端 body。
- owner 的 `catalog_advertiser_guard.active_command_id` 在**任何远程 shard 副作用前**提交。不同 commandId 竞争由该行条件更新排他；租约过期只允许接管同一个命令，不释放给新命令。单命令幂等、最终唯一键、无活跃实例均不能替代此占有。
- `catalog_aggregate` 按 adId 持久维护版本，归 ad 的 OWNER。ad版本和advertiserVersion从首次基线/创建的1开始；后续根字段实际变化增加advertiserVersion。版本 bigint 正数、不可回退/复用，overflow 拒绝。预留版本允许间隙；`last_materialized_version` 区分事件已落库。DELETE 后保留该行与 deleted 标记，adId 永不复用。新建后第一个事件为 v1；冻结的旧广告基线也从 v1 开始，不根据 timestamp/Snowflake/MAX(id) 推断版本。
- 现有 Snowflake 生成与长 ID 保留；可靠部署须明确配置每个 writer 的唯一 worker-id，不能沿用多实例都为1的隐含假设。PK 冲突是失败/核验，不能覆盖已有广告或换 ID 重放未知提交。初始化冻结writer/worker-id清单，启动校验本实例声明与清单一致、清单内worker-id不重复；这不是自动发现所有运行进程的全局唯一性证明，旧进程/重复实例标识必须由切换owner排除。本片不建设新的ID服务。

### 4.2 每一步的执行和未知结果

`ADMITTED → RUNNING → COMPLETED`；暂态故障按持久预算退避，耗尽为 `PAUSED`；参数/版本冲突为 `REJECTED`，仅确认无任何已提交业务效果时可终结并释放。`COMPLETED` 指所有规定业务步骤及最终事件已持久化，**不等于 Kafka 已发布或 S17 投影已完成**。

1. owner 短事务：锁 guard，查询原命令，验证当前状态、可信归属、请求与版本；冻结输入、步骤、目标 shard、预留版本并占有。此后相同广告主其他写返回 CONFLICT（当前处理中），不在内存排无限队列。
2. 协调者领取 command：先持久递增 attempts/fence、30秒 lease、将要派发的 stepId/请求摘要。持久 `DISPATCHED` 先于外部步骤；禁止“先跨库写，后建清单”。
3. 目标 shard 先按 `(commandScope, stepId)` 查询。APPLIED 直接返回原结果/摘要，不能重新读取当前库冒充原结果；未查到只表示未观察到，先持久登记原输入，再领取本地 step 的 attempts/fence/lease。claim 与 apply 分别是短事务。
4. apply 事务锁本地 step，检查本地 fence/lease/原字节，执行该库业务 SQL、冻结完整片段、插入本库 Outbox、APPLIED 结果一起提交。锁序是本地 control → 本地 guard（有则）→ command/step → aggregate → 固定顺序业务行 → Outbox；不得持一个 shard 的事务再进入另一个。
5. 协调者事务外读回该 step 的 APPLIED 记录，核对 source/command/step/ad/版本/摘要；owner 当前 fence 条件下保存结果。响应丢失先查原 step，不再次发新业务操作。owner 提交未知也先查原 command/result，不能先释放 guard。
6. 最终 owner 事务组合已冻结片段，持久完整事件/原响应/检查点后释放 guard。外部 Kafka/Redis/向量调用不在此事务内。

协调者 fence 只限制 owner 检查点；物理业务由**该 shard 的本地 step fence/行锁/唯一结果**限制。一个旧协调者可能在另一库继续完成已获准的同一不可变步骤，不能声称 owner lease 到期瞬间可远程撤销它；它不能改变输入、重复业务效果或回写新 owner fence。释放前必须验证全部必需 APPLIED 结果，因此迟到执行器在新命令之后只能读到旧 receipt，不会重写业务。禁止删除这些 receipt 后继续声称此保证。

### 4.3 各用例及完整快照

| 用例 | 顺序和快照来源 |
|---|---|
| ad 属性/状态/审核/重新送审 | owner 冻结旧 ad/advertiser 状态；CHILDREN 步骤冻结两张子表的同库一致快照；OWNER 最终事务再验证占有/源版本，写 ad + 完整事件 + 结果。 |
| 新建 ad（可带初始 bidwords） | OWNER 同事务生成 adId、插入原机审结果的 ad、guard/aggregate、创建片段 Outbox/命令；CHILDREN 原子插入全部初始词并冻结；OWNER 组合最终 v1。中断时父行可能已存在但不会伪称命令完成；初始词不得逐条另发完整成功。 |
| 单词/创意增删改 | owner 冻结 ad/advertiser；CHILDREN 同事务更新目标 + 读取完整 bidwords/creatives + 片段 Outbox；OWNER 最终发完整快照并完成。创意三种变化都在入口表内。 |
| 删除 ad | OWNER 首先删除 ad 并写**明确 DELETE 墓碑**、版本和持久 cleanup 待办；后续 CHILDREN 删除词/创意并同库留事实，再完成命令。删除事件不依赖向量可达。source ad 不存在但没有已记录删除原因，不可推断为墓碑。 |
| 广告主状态 / 多主暂停 | §5 持久 fanout；不是长事务内调用 reindex。 |

删除后的旧 `ad_embedding`/Redis 清理属于 S17 投影职责，不能跨库写进目录原子事务。C16 reliable 模式不再调用旧 `AdIndexSyncService` 发旧 topic/同步写旧 Redis；S17 完成并验证其读路径前，可靠模式只允许隔离生产者验收，不能宣称可切换共享在线读模型。default-off 仍沿 legacy 语义，旧生产者/消费者未被无条件移除。

快照正确性的依据是**同广告主的持久写入串行化 + 每个 shard 冻结的原步骤结果**，不是跨库 REPEATABLE_READ。所有相关 writer 必须受 §8 的准入/DB guard 约束；owner 占有期间包括其他广告的普通写、状态写、新建/删除均不能绕过。ad/advertiser 读在 owner 本库一致事务内；两张 children 表在它们同一物理库事务中读取。冻结到事件期间不允许再改这些权威字段。只读旧查询可能见到跨库中间态，命令详情明确展示步骤，C16 不承诺它们得到全局原子视图。

所有快照数组按 ID 排序，规范化 JSON/UTF-8 字节只生成一次。每 ad 最多1024词、256创意、最终 payload≤512KiB；这是内存/消息保护预算，超过则在可知时前置拒绝，存量超限则 PAUSED/SNAPSHOT_LIMIT，不截断或发布空数组。已有目录数据没有这些约束，启用前核验是必要条件。单次枚举最多100行，不能读入整个广告主再切片。

### 4.4 派生 embedding

权威快照明确包括 ad、advertiser 状态、词与创意；embedding 是单列的 enrichment，不参与“完整权威目录”证明。事务外最多1次、1秒读取，按冻结 itemId 从现有 derived Mapper 获取 vector/model，格式/维度/有限值校验后保存原结果。失败、无行或非法数据分别记录 UNKNOWN 的安全原因枚举，不阻止权威状态/删除事件，不输出异常原文。

事件 `embedding={state:PRESENT|UNKNOWN|NOT_APPLICABLE,itemId,model,vector,observedAt,reason}`：PRESENT 才带有效向量；UNKNOWN 不能删除同 item 的已知向量；更换 item 时旧 item 向量因**关联已改变**不得沿用，即使新向量 UNKNOWN；DELETE 的 NOT_APPLICABLE 才是权威广告删除。无行不冒充向量的权威删除。重放原事件不再读向量；新 `REFRESH_ENRICHMENT` 命令产生新版本，已 deleted 广告不能因此复活。

## 5. 广告主暂停与多分片持久任务

S16B 接上真实 `updateAdvertiser(status)`。owner 同事务改 status、增加 advertiserVersion、持久 root 事实 Outbox/任务并持有该 advertiser 的 guard。更新到相同状态不制造重复 fanout，但相同 commandId 必须返回原执行结果。初始 status、当前版本及目标状态不可变。

持有 guard 后，已有 ad 集合被封闭：所有新建/删除/子资源/审核都不能进入。按 adId 的稳定有界分页**保存每页目标 ID/版本及页摘要**，再逐 ad 执行冻结 children→owner完整新版本事件。该 cursor 合法仅因此集合已由 durable guard 冻结，不是 MAX(id) 提交水位；页/检查点一起提交，低 ID 晚到的并发创建要么在取得 guard 前提交并被枚举，要么被阻止、结束后按新 advertiser 状态创建。不能跳过失败项又把任务记完成。

各 ad 保留自己的 status/review，暂停 advertiser 不把所有 ad.status 改成 paused；`servable = advertiser active ∧ ad active ∧ review approved`。恢复 advertiser active 同样通过新命令、逐 ad 新版本；它不能恢复已删除/单独暂停/拒审的广告。任务在全部目标事件落库后才完成并释放；各事件可能提前发送，传播不是跨广告同时可见。

为覆盖原 F15 多物理分片暂停，内部 `submitStatusBatch(batchId, targets≤100, actor, reason)` 接受明确 `(advertiserId, expectedAdvertiserVersion, targetStatus)` 清单，按固定顺序冻结在指定 homeShard，逐广告主派生稳定子命令。两个奇偶 advertiser、其不同奇偶 ad 都要覆盖。一个执行槽每次只推进一个目标，不跨owner持数据库事务锁；每个子命令只占有自己的advertiser，暂停子命令的持久占有可与其他目标的占有并存，彼此不等待对方资源。一个目标暂停时记录独立结果，允许有界公平推进其他未处理目标，但父任务保持 PAUSED/PARTIAL，绝不标 COMPLETED。过后续办仍用原目标/expectedVersion；另一次合法恢复已推进版本的目标记 CONFLICT，不用迟到旧暂停覆盖新状态。跨多个广告主没有一个共同快照或原子总暂停。

重复 batch/子命令都校验原请求；已有子结果响应未知先查询。批次取消仅能以稳定审计命令将**尚未派发**的目标记 CANCELLED；已派发的目标保留占有和原任务继续收敛，已提交的暂停不撤销。父结果明确完成/冲突/取消/未决数量，部分完成不能返回全成功。

## 6. Outbox、恢复与 S17 事件边界

### 6.1 本库事实与外部事件

每库 `catalog_outbox` 有固定目的 `COORDINATOR` / `KAFKA`：前者是本地业务片段或 root 事实，供同一 catalog owner 编排器按固定 step 读取并核验，不发送给 S17；后者是完整 ad 事件。**孩子库的片段与孩子业务同事务；owner 库的最终事件与 owner 版本/完成状态同事务**。不能把最后一条 owner 事件冒称孩子库写入时已经原子产生的完整快照。中途任何库故障，片段待办/原命令仍可查，其他库已提交效果不回滚伪装成功。

对外新契约 `VersionedAdCatalogEvent`、topic `ad-catalog-v1`，不复用旧 `ad-catalog-events`，不把新版送入旧吞错消费者。字段冻结：

| 字段 | 契约 |
|---|---|
| schemaVersion / eventType | `1`；`AD_SNAPSHOT` 或 `AD_DELETED`，未知值拒绝 |
| sourceId / ownerShard / topologyVersion | 持久目录身份、真实 owner shard（0/1）、固定路由版 `advertiser-mod2-child-ad-mod2-v1` |
| eventId | `sourceId:ad:adId:aggregateVersion`，同身份永不更改 payload；不能用内容 hash 创造业务命令身份 |
| adId / advertiserId / aggregateVersion / advertiserVersion | 正 long、正版本；wire long 保持精确。Kafka key=adId 十进制；source 与 key/归属都验证 |
| commandId / occurredAt / capturedAt | 关联命令及数据库 UTC 时间，时间只审计、不排序 |
| lifecycle / servable | `PRESENT` / `DELETED`；paused/rejected 是 PRESENT+false，不与物理删除混同 |
| ad / advertiserStatus / bidwords / creatives | 完整权威快照，含原ad review/status/定向/出价语义；creative额外读取真实review_status（旧CreativeView缺此字段），只有active+approved可被S17作为可用创意。null/未知审核值不能自动approved；不增加创意审核API。数组有序；DELETE仅保留身份/版本/原因，正文不需要保留 |
| embedding / catalogComplete | §4.4；合法快照 `catalogComplete=true` 只覆盖权威目录，UNKNOWN enrichment 明示 |

完整 payload/key 字节、SHA-256、eventId、版本、source 原样持久化；原 event 不再重查源或重序列化。暂停/删除是更高版本，重复发送及乱序是正常输入。S17 必须以 `(sourceId,adId)` 维护持久高水位/墓碑；同版本同 digest 幂等，同版本异 digest 隔离，较旧不覆写，较新完整快照可跨版本间隙应用。Inbox、版本/墓碑、projection、Redis 待刷新任务须在 ad-serving 自有本库原子提交后 ACK。Redis 用持久版本保护删除/回填；S16 不实现或宣称这些消费保障。

S17 需提供新组首次保留起点/隔离/重放和受控基线衔接；不能 latest 启动漏掉 S16 先发事件。S16 保留 Kafka 原确认坐标和数据库原文，不因 PUBLISHED/lag=0 删除事实。Kafka 保留过期时可审计重发已知原 event；全库/Redis 全历史重建和源/Outbox 同时丢失不在此片保证内。

### 6.2 领取、发送与有界恢复

每 shard 公平扫描KAFKA目的的 READY/过期 SENDING，按 availableAt、createdAt、eventId 稳定顺序，短事务 `SKIP LOCKED` 领取最多10条；COORDINATOR片段只按原step由owner读取核验，不能被发送器当目录事件。冻结producer `acks=all`、`enable.idempotence=true`、`max.in.flight.requests.per.connection=1`、`retries=2`、`request.timeout.ms=3000`、`delivery.timeout.ms=10000`、`max.block.ms=1000`、`linger.ms=0`、`max.request.size=1048576`，业务payload仍限512KiB。发送每条一次且在事务外，send等待5秒，不以await超时推断broker没收到。收到成功metadata后本地有效fence条件标PUBLISHED；旧fence不能标成功。超时后的底层发送可能继续到delivery deadline，任何迟到回调不得绕过state/fence条件回写；未知ACK保留原字节，重试可能重复。

首版每条 Outbox 每轮最多5次发送领取；每物理 step/command 每轮最多3次外部尝试，DB时间30秒 lease，事务5秒/锁4秒/statement5秒，指数退避1/2/4秒（封顶30秒、稳定小抖动）。先计次后调用；进程退出也耗预算。最后一次领取退出须在租约过期后持久置 PAUSED，不永久 RUNNING，不重启清零。普通查询/核验已知终态不增加外部副作用次数；不可达查询本身也受单次超时和命令步数预算约束。

两个固定 shard 各一个串行执行槽；单次 advance 最多一条命令的一步/一个外部调用，dispatcher 每轮最多10个候选，轮转避免头部故障饿死其他 advertiser。无公共无界线程池、无限递归重试或新调度平台。ShardingSphere启动可能需要两库元数据，因此不承诺一库不可达时另一库可独立重启；公平推进指已启动实例中不依赖故障库的目标，重启失败应记录并待原库恢复后续办，不偷偷换数据源。协调者领取预算按**每一步**统计，推进下一步才开始该步预算；不是给一个十万广告 fanout 总共3步。总工作量在磁盘累积，单轮有界。

内部 `CatalogRecoveryService`：`query`、`advance`、`RETRY_STEP`、`REPLAY_EVENT`、`RECHECK`、`CANCEL_UNDISPATCHED`。恢复命令自身有稳定 UUID、原目标/摘要、actor/reason、轮次/fence/有限预算及原结果。RETRY 新一轮须新恢复 ID，不清除历史计数/错误/占有；活跃有效 lease 不可抢。REPLAY 只发送原事件字节（含已 PUBLISHED 的受控续发），不改源业务、版本、payload或主消费组 offset；一次恢复命令一条 event，最多3次，未知先查本命令状态。RECHECK 只重新核验原结果与输入冲突，不“修正”旧 event。

PAUSED 命令不自动释放 advertiser 占有；同时存在多个未决时只有各自完成才能释放各自 guard，不能恢复一条后全局 start/resume。schema/路由/摘要冲突使该 shard worker 持久 STOPPED，显式 RECHECK 且无其他阻断才可 start；不自动循环 start。无原文/缺 step/非法历史状态报告 SOURCE_UNAVAILABLE/INCONSISTENT，由 owner 决策，不自动删记录、补造成功或绕过 guard。

## 7. 持久模型与迁移

新增 advertiser 自有 `src/main/resources/db/ad-catalog/V001__catalog_recovery.sql`，按两个**实际物理数据库**显式执行，各有 `advertiser_catalog_schema_history`。安装器向本连接提供经允许列表校验的shard_id=0/1，在同一安装事务中写schema history与一条LEGACY control；脚本字节不因目标不同而改变，缺安装参数/实际库身份不符则回滚。缺control行不能猜成LEGACY。不通过默认SINGLE自动迁移，不修改旧已发布SQL/fixture。业务现有表数据不搬迁；新表与必要guard trigger属于追加治理迁移。字段与表均写中文COMMENT，下面每组含义即最少字典，DDL须逐列落实，JSON内部结构遵循本节/事件契约。

| 表（每 shard） | 关键字段及中文含义 / 约束 |
|---|---|
| `advertiser_catalog_schema_history` | shard_id实际安装分片、version版本、description用途、script_sha256实际摘要、installed_at安装数据库时间；(shard_id,version) PK，逻辑查询明确路由到待核验库。只读核验版本/checksum。 |
| `catalog_shard_control` | shard_id本物理分片（PK且0/1）、source_id数据集身份、topology_version路由版、cluster_id/topic_id/topic_name目标Kafka身份、mode LEGACY/QUIESCED/ACTIVE、initializing_command_id跨命令初始化占有、writer_manifest登记实例与worker-id、stop_reason停止原因、revision控制版本、updated_at数据库时间；source/绑定不可静默更换。 |
| `catalog_advertiser_guard` | shard_id路由、advertiser_id广告主（唯一）、advertiser_version已提交根版本、active_command_id/active_command_home/active_command_scope持久排他命令及可查询归属、deleted/created_at/updated_at（删除预留，不新增删除API）；shard_id必须等于advertiserId%2。 |
| `catalog_identity` | shard_id固定命令home、source_id、command_id稳定开户命令、entity_id原Snowflake生成的预留ID、created_at预留数据库时间；(source,commandId)本home唯一、entity_id唯一，未知提交不再生成。该表是技术身份预留，不是另一份广告业务权威。 |
| `catalog_aggregate` | shard_id、ad_id广告（唯一）、advertiser_id归属、reserved_version已分配最大版本、last_materialized_version已落事件版本、deleted权威删除、last_event_id最近事件、updated_at；版本非负且materialized≤reserved，不可删除后重建降版。 |
| `catalog_command` | shard_id/home路由、scope_kind/scope_id作用域、command_id稳定键、kind操作、request_bytes原输入、request_sha256摘要、actor/reason可信审计、state/phase、active_step当前步骤、round/attempts/fence/lease_until/available_at、result_bytes原响应、error_type安全分类、created_at/updated_at；作用域+ID唯一。 |
| `catalog_step` | shard_id实际目标、source_id、owner_shard/command_scope/command_id/step_id原稳定步骤、ad_id/advertiser_id/aggregate_version关联、request_bytes/sha256、state/round/attempts/fence/lease_until、result_bytes/sha256、outbox_id、write_txid本库事务身份、error_type、时间；原步骤唯一。 |
| `catalog_outbox` | shard_id、outbox_id本地唯一ID、purpose/record_kind、source_id/ad_id/aggregate_version、command_id/step_id、event_id、topic/key_bytes/payload_bytes/payload_sha256、state/round/attempts/fence/lease_until/available_at、broker_partition/offset、consumed_by_owner、error_type、created_at/published_at；完整事件(source,ad,version)唯一，片段(command,step)唯一。payload只能插入。 |
| `catalog_task_item` | shard_id/parent_scope/parent_command_id、ordinal有序项、target_kind/target_id、expected_version、child_command_id、state/result/error_type、page_sha256、created_at/updated_at；父+目标唯一；冻结清单不能删项，未决/取消/冲突显式保留。 |

索引：command/step/outbox 的 `(shard_id,state,available_at,created_at,id)` 与过期 lease；task item 的父键/ordinal/状态；guard active command 查询。bytea 保存原始字节、bigint 版本、timestamptz UTC时间；write_txid保存PG `pg_current_xact_id()`的完整十进制文本，不用易绕回的32位xid。CHECK 状态枚举、计数非负/上界、key与payload大小/非空组合、路由模数、字段终态一致；DB唯一约束/影响行数构成最终完整性，不能只靠Java判重。

### 7.1 防漏写与实际同库证明

追加 PG guard：四张目录业务表的 INSERT/UPDATE/DELETE 在 ACTIVE 必须携带本事务的合法 step 上下文；其本地 step 与 Outbox 必须属于同一次实际 PG 事务（write_txid），目标表/对象/操作与冻结请求匹配。用行级 guard 和 deferred constraint trigger 在提交前检查，防止只写业务、Outbox误路由另库或旧 writer 绕过。LEGACY 模式保持旧路径；QUIESCED 拒绝普通写。TRUNCATE 使用 statement guard，在非 LEGACY 拒绝。触发器函数固定 schema/search_path，不拼接用户 SQL，无网络/远程库访问。

每个业务写事务先以带 `shard_id` 的 Mapper SQL锁定本地control并设置事务局部 step 上下文，再做业务；不能使用无 FROM 的函数查询默认落ds_0。control锁序与切换一致。新建ad/child在BEFORE检查操作类型和已知advertiser/ad归属，生成ID返回后在deferred检查中要求该实际ID存在于同txid原结果/Outbox，不能要求尚未生成的ID预先相等而导致所有创建失败。trigger与业务/Outbox都在目标PG执行，出现错库会在本库缺receipt而回滚，不因逻辑连接相同放过。测试需要实际物理日志/txid/两边行断言；若5.5.0解析所需SQL不支持，实施片先做最小SQL兼容修正和真实验证，不允许退化为跨库成功假设。

guard 不抵抗有DDL/superuser权限的管理员绕过，也不替代旧应用/离线作业的切换排除；seed的adDb/Redis副作用尤其不受这四张表trigger保护。C16A就必须给seed-ads增加**首次任何副作用前**只读模式检查。默认旧sharding.yaml不认识治理表，故这个检查不能通过SINGLE去读两份同名control：由offline现有连接配置解析出两个真实PG地址，使用非Bean、无连接池的只读`SeedCatalogModeProbe`逐库读取安装/control状态，每库连接/语句预算2秒；保留原逻辑源，不新增基础设施。仅明确表不存在（PG 42P01）或该库有合法LEGACY行可视为旧模式；缺control、身份错配、两地址意外同库、超时/其他SQL错误都拒绝。两个shard均确认后才允许旧seed继续。维护窗口还须排除已经运行的旧seed与SQL脚本，前置检查本身不声称消除了检查后到外部清理前的切换竞态。该修正是阻止越过目录准入，不把offline变为新业务owner。

## 8. 默认关闭、起点、兼容与恢复

新增 `recsys.ad.catalog.reliability.{enabled,admission-enabled,worker-enabled,relay-enabled,recovery-enabled}` 全部默认 false，已有 publish-events 保留false。enabled 代表已装配协议；admission停新命令仍可保留查询/续办/排空。enabled=true 与旧 publish-events=true 拒绝装配；S16A 尚未实现 fanout 时，开启 advertiser status 路径须明确拒绝，不能降回旧 reindex。无启用时不建立新worker/新Kafka/Admin连接、不做自动schema迁移；已有legacy基础设施行为原样。

启用只限获授权隔离实例。本片不执行共享初始化。初始化不是“空表即自动采用”：

允许`enabled=true,recovery-enabled=true`且admission/worker/relay均false的恢复专用装配：两库迁移/profile必须正确，但control可处于尚未登记或原初始化未完成状态，供受控INITIALIZE/查询继续；不能因普通admission所需READY尚不存在而让初始化入口永远无法启动。其余运行能力分别校验READY/源绑定，不偷开普通写。Kafka topic由获授权环境预先创建；INITIALIZE只读Admin核对并冻结clusterId/topicId/name，单次API5秒、最多3次，不自动创建/替换topic。重建同名topic或换集群不静默续发，需owner重新核验。

1. owner 显式 `INITIALIZE(commandId, sourceId, topology, topic, targets=[ds_0,ds_1], actor, reason)`，在catalog ds_0的系统control持久占有此初始化ID，跨command竞争失败；冻结原请求先于另一库任何副作用，lease过期不释放给另一个ID。
2. 两库依次置 QUIESCED，control更新与所有旧业务事务的control共享锁互斥；已写旧事务必须先完成，之后新旧普通写都拒绝。进程退出只继续原命令。未安装迁移/未知源/拓扑冲突停止，不以“组无成员/实例无流量”代替持久占有。
3. 原目录已存在的 ads 在封闭写入集合上逐页固化清单/归属；在原owner和children读取并冻结合法完整基线，每 ad 写v1及原字节Outbox。不能用空数据/失败读取生成 tombstone，非法/超限项阻塞准入并报告。source/旧种子ID冲突或不按模数分布不可自动搬正。
4. 两库schema/source/manifest及基线完成逐项读回，记录同一初始化命令READY后才开放admission；部分ACTIVE/QUIESCED仍不准入。第一次可靠写之前已有广告不能因新事件topic为空而被当不存在。此登记仅基线当前存活目录，无法恢复早已删且无历史事实的广告；S17还须旧读模型清单核对。

ShardingSphere治理规则仅由显式`sharding-catalog-reliable.yaml`承载：Boot主源仍是原驱动，通过`ADVERTISER_SHARDING_RESOURCE`选择资源（默认`sharding.yaml`）；任何reliability开关开启必须选择可靠资源且两库已安装迁移，否则拒绝启动。默认资源不引用新表，启用前核对两库业务列/既有外键布局与已发布分片DDL一致，缺列或仍有跨库外键则拒绝，不自动重写旧SQL。真实无迁移default-off启动不能依赖ShardingSphere“忽略缺表”。可靠profile保留原四表路由、绑定、SINGLE及物理连接参数，唯一允许增量为治理表/原Snowflake的writer-id显式参数；不重分片、不换DataSource。新规则全量一致，缺规则/错SINGLE/不同source拒绝启用。事务外读取元数据不宣称两库瞬时原子；最终本地guard是写入防线。初始化或最后一次尝试耗尽保留PAUSED/占有，需同ID核验或有审计的新恢复命令接管原输入；无自动删除占有入口。

回退先停新admission，核对所有active命令/本地step/Outbox/消费位置；保持新协议进程用于续办。ACTIVE库不能仅改应用开关false就重新放行legacy，trigger会拒绝；回到旧writer需另一份owner核验与版本/投影衔接方案，本片不提供自动“解锁回退”。跨源备份恢复必须保留source/版本/墓碑/待办一致性，不允许重设版本为1。旧事件原topic内容、旧迁移和fixture不改。

## 9. API、安全与可观测性

原URL/请求DTO与已完成成功响应保持。可靠模式支持可选 `X-Catalog-Command-Id`；现有广告主作用域写口使用该头时必须同时给 `X-Catalog-Advertiser-Id`（createAd的URL已有owner也须相符），缺一拒绝400；两者都缺失则沿旧调用生成服务端ID/解析当前归属。开户仅传commandId，按ROOT_CREATE固定home定位。owner头只帮助路由，必须重新授权并核验真实归属，不是可信身份。响应附原commandId和owner。

重试先按显式owner+command查原请求/结果并验证当前调用权限，再决定是否检查当前资源存在；这样已删ad/child的同ID重试仍返回原结果，不因先查已删行而变404，也不把错误owner当合法归属。后台恢复始终携带完整scope；缺稳定头的旧客户端重复POST仍可能是两个业务请求，不能按内容hash去重或宣称丢响应幂等。新调用方应预先生成稳定ID并在未知结果后查询/同ID重试。

普通HTTP的命令推进最多4个步骤、deadline5秒，认证授权另沿既有客户端超时配置；开户关系TOUCH也需显式有限客户端deadline且位于事务外，未知结果继续原资源幂等TOUCH，不用无界线程包裹。超预算/未完成返回统一 `CATALOG_RESULT_UNKNOWN` 503及commandId响应头，不返回伪成功DTO，不在请求线程无限续办。成功只在本命令业务完成后返回原冻结响应，Kafka状态单列查询。若响应丢失连commandId也未知，旧客户端不能自动确定创建结果；按owner审计核对，不建议换ID自动重试。

新增 `GET /api/advertiser/{advertiserId}/catalog-commands/{commandId}`（当前view权限）返回state/phase、原结果是否存在、steps/事件发送摘要、阻断原因；开户ID未知时用`GET /api/advertiser/catalog-commands/{commandId}`定位固定home的ROOT_CREATE命令，只允许原认证actor且当前仍有platform administrate权限，返回已冻结advertiserId或未完成状态。列表分页≤100，不返回原payload/词/凭据。不存在固定NOT_FOUND；异主作用域不可探测，参数400、无权403、同ID异请求/active冲突409、依赖未知503。新增具体错误码由`recsys-common`统一注册并同步生成契约，不能返回自由字符串错误。

原edit/manage/platform review/administrate判权位置保持；可靠写不因worker重放降为公共接口。恢复、批量状态和初始化仅内部受控service入口，可信actor/reason/原请求哈希持久审计；无匿名公网replay。日志只输出command/event安全标识、shard、phase和错误类别，不输出标题、关键词、完整payload或连接串。指标只按shard/kind/state低基数：积压/最旧年龄、PAUSED、过期租约、重放次数、步骤未知、源摘要冲突。adId/commandId仅结构化审计，不作为metrics标签。

数据保留期未知：本片不自动清理source版本/墓碑、命令、step/Outbox、审计；存储增长需观察，阈值告警和停新admission优先于丢弃关键事件。没有生产容量/保留/RTO承诺。

## 10. 纵向实施与文件级步骤

仅两条串行纵向片，保留原 ER-S16 父ID；不是仓储/接口/异常机械分层。

| 子片 | 完整业务结果 | 依赖/文件范围与顺序 | 验收 |
|---|---|---|---|
| ER-S16A | 一个广告（含初始词/创意/审核/删除）经真实Controller可靠变更，故障可查可续办，按物理本库原子留事实，最终原事件到真实Kafka | ER-D0/C16、ER-S06。①ad-common新增VersionedAdCatalogEvent/codec和显式可靠sharding profile/规则一致性测试；②advertiser追加V001+CatalogLocalMapper/XML+配置/guard/启动核验；③catalog模型/Repository/CommandService/RecoveryService、controller WriteRouter及校验复用；④relay/worker、查询DTO/controller/统一错误码；⑤offline seed准入拒绝；⑥真实路由/HTTP/崩溃/Kafka测试和runbook。包括初始化、原广告基线、root开户/name/budget记录；status fanout显式拒绝到B。 | C16-01～12、16～20适用项必须闭环，不只交仓储。默认关闭，S17未实施不能宣称在线投影可靠。 |
| ER-S16B | 单广告主暂停/恢复与跨两个owner的显式暂停批次，中断/冲突/取消均保留逐目标事实、可续办并最终发布正确版本 | ER-S16A。①catalog fanout/batch service与task item Mapper；②接通AdvertiserService/update入口，移除A阶段status拒绝；③批次恢复与fair worker；④完整原入口/多分片/并发反例回归、父S16验收结果与文档。如A的已发布V001需扩展只追加V002，不回写。 | C16-13～15及全C16-01～20适用项；A有效指纹不变部分复用。B完成才评估父S16 DONE。 |

拟新增/修改路径（是实施允许范围，**本设计回合不创建产品文件**）：

- `recsys-ad-common/src/main/java/com/recsys/common/ad/{VersionedAdCatalogEvent,CatalogEventCodec}.java`；旧`AdCatalogEvent.java`只修正误导性语义注释、保留旧wire；新增`src/main/resources/sharding-catalog-reliable.yaml`治理表规则与显式worker-id配置；默认`sharding.yaml`原样保留，测试强制业务规则/连接模板一致。
- `recsys-advertiser/src/main/java/com/recsys/advertiser/catalog/`：有限的命令模型、Properties/Configuration、WriteRouter、Repository、CommandService、RecoveryService、Relay/Worker、SnapshotAssembler；B再加FanoutService。职责可合并，不为每状态建空类。
- `.../persistence/mapper/primary/CatalogLocalMapper.java`、`src/main/resources/mapper/advertiser/primary/CatalogLocalMapper.xml`；`db/ad-catalog/V001__catalog_recovery.sql`；`application.yml`只新增默认false、预算和默认仍指向原资源的profile选择。现有AdvertiserService/Repository和四Controller仅可靠分支接入/校验复用，不动资金接口。
- `.../dto/CatalogCommandView.java`、命令只读Controller；`recsys-common`统一错误目录与现有生成产物。既有HTTP金额/ID展示保持。
- `recsys-offline/.../SeedAdsJob.java`及`SeedCatalogModeProbe`仅增加副作用前真实双PG模式检查；新的只读probe Mapper/XML绑定明确物理连接，不让默认SINGLE误读两份control，业务SQL仍集中Mapper。无记录/不存在与网络故障必须区分，不新增第三方库/池/部署单元。
- advertiser测试包`catalog/`的双物理PG夹具、事务/命令/HTTP/Kafka/独立JVM进程测试；复用既有PG/Kafka工具，GenericContainer足够，不加engine/ad-serving生产依赖。`AdvertiserRepositoryPostgresTest`、authz/CreativeReview及ad原分片读测试按受影响范围回归。必要测试装配不替代真实产品Mapper/Controller/relay。
- 本目录正式设计、契约/架构/选型/验证/切片、结果、`CATALOG_EVENTS_RUNBOOK.md`；CODEX_PROGRESS只维护入口与原历史正文。

## 11. 真实验收矩阵（当前全部 UNVERIFIED）

使用两个独占 PG16 **实例**，不能只换schema或mock路由；可靠链路加载实际`sharding-catalog-reliable.yaml`，默认兼容加载原`sharding.yaml`，夹具只替换各自物理连接。两份产品profile的业务规则/连接模板另做结构一致性检查，禁止测试自己拼一套不同路由。故意构造owner与child奇偶不同及相同两类。Kafka3.7.1独占broker/唯一topic，必要Redis独占仅用作default-off/禁止旧副作用负对照，S17 Redis恢复不在本片通过。所有测试断言实际物理表、txid/Outbox/command/事件key+bytes/metadata；保留版本指纹、原始日志/XML、运行状态。不得停共享容器。

| ID | 必需可观察验收 |
|---|---|
| C16-01 | 实际广告Controller→Spring事务→primary Mapper→ShardingSphere→两PG；owner/child相反奇偶，本库业务与Outbox同txid，另库无误写；只写业务/Outbox故意走SINGLE负对照被guard回滚。 |
| C16-02 | 每物理步骤在业务写后Outbox前异常/进程退出，独立连接两者都无；提交后响应前退出原结果仍在，同ID续办不重写。不能以单库rollback测试覆盖跨库故障。 |
| C16-03 | createAd初始多词一库步骤原子；词/创意CRUD、属性、状态、机审/审核/重新送审全部实际入口产出对应版本事件（含真实creative review_status，不自动批准旧pending）；同command开户并发/身份预留后退出/关系响应未知只使用原ID和actor；旧null/定向/金额/长ID/权限断言保持。 |
| C16-04 | 源已commit Kafka未发杀独立JVM；重启真实relay发布原字节，实际消费者读取eventId/key/payload，与本库一致。 |
| C16-05 | Kafka成功ACK后PG标记前杀JVM；重复发送同eventId/bytes/版本，metadata和数据库事实可核对；不伪称只发一次。 |
| C16-06 | 独占Kafka停止/恢复，及一个独占PG不可达/恢复（记录连接端点是否变化）；业务本库事实保留，未知结果先查，预算/PAUSED跨进程不重置；不模拟成功sink。 |
| C16-07 | 两JVM竞争同/不同commandId、同advertiser不同ad、相同UUID不同scope；只有一个持久owner获准副作用。owner接管与本地step fence分别测试，迟到执行器不能写新检查点或重复业务。 |
| C16-08 | 第三次步骤/第五次发送领取后退出，租约到期可清算PAUSED；多个PAUSED/STOPPED共存时只解目标，未清所有阻断不全局start。新恢复命令有限，终态同ID只返回原结果。 |
| C16-09 | UNKNOWN响应后的原step查询/同ID恢复，删除ad/child后的原命令先于资源存在判断；稳定头缺owner/伪owner拒绝，跨主查询不泄露；同ID异payload冲突、原event不可修改、缺record固定NOT_FOUND；REPLAY_EVENT真实Kafka仍原事件，RECHECK不伪造成功。 |
| C16-10 | 另线程尝试改parent/child、广告主状态/删广告时，快照占有保持；最终事件取冻结片段，不能拼接两次不同命令状态。原SQL/旧writer/seed-ads被guard/前置检查拒绝。 |
| C16-11 | 明确删除先有新版本墓碑，children故障留下可查cleanup，恢复完成；迟到update/旧低版本事件不会改变源deleted状态。S17消费“防复活”此时仅契约，不宣称运行通过。 |
| C16-12 | derived断连/空/非法向量为UNKNOWN，权威完整事件仍正确；同item未知不要求清已知向量，换item旧向量不能绑定新item；DELETE不依赖derived。此项不冒充S17读模型验收。 |
| C16-13 | 单advertiser多页暂停/恢复，中间一children shard宕机/杀JVM，清单/进度/版本保留；源status和逐ad发布完成状态区分；恢复不复活单独paused/rejected/deleted。 |
| C16-14 | 两个owner奇偶广告主+不同children shard的显式batch暂停，一库故障后其他目标可推进但父PARTIAL；重启不重新采样清单，取消未派发项/旧expectedVersion冲突都如实报告。 |
| C16-15 | 低ID新建与封闭清单竞争、暂停和恢复并发、旧batch迟到；guard前提交者必被枚举，guard后请求被挡或结束后按新状态执行；拒绝MAX(id)水位。 |
| C16-16 | 两个不同INITIALIZE command并发，在任一外库副作用前排他；只初始化一库后退出、同ID续办/异ID冲突、已有数据源/规则冲突不覆盖；旧未提交writer与QUIESCED互斥。 |
| C16-17 | 真实存量跨库基线在首次可靠写/新topic消费者启动前已发布，原文可重放；缺源/非法/超限不空快照/假墓碑；Kafka过期时用已知DB原文审计重发，原文也缺失明确不可恢复。 |
| C16-18 | 无新迁移的default-off真实启动/旧HTTP和原分片读回归，可靠profile与原profile除允许增量外完全一致；错误配置、遗漏迁移/路由/不唯一worker-id准入声明被拒，旧topic与新topic隔离；不启动新worker/Kafka副作用。 |
| C16-19 | 限量/最大字节/公平轮转、异常脱敏、权限与审计、schema/列中文注释、旧迁移/fixture/11保护不变；不是生产压力测试。 |
| C16-20 | 保持高低版本乱序发送、重复、同版本异digest的原始Kafka证据与codec校验，确认S17可据契约实现；完整已实现S16链路/相关回归通过，未实现的消费/Redis效果继续UNVERIFIED。 |

真实停机/进程退出与测试注入须分别标注。注入异常只证明所处窗口；不声明已测网络丢包、broker副本容灾、ACL失败或生产吞吐。每次未知命令先查PID/日志/JSON，不盲目重跑迁移/测试/发送。

## 12. 设计复审及依据

分离只读 pass 检查 D16-01事实/真实writer；D16-02物理路由及同库约束；D16-03跨命令占有/本地fence；D16-04快照与版本/删除；D16-05暂停批次/低ID/取消；D16-06兼容/初始化/默认关闭；D16-07有界恢复/安全；D16-08纵向结果及真实测试映射；D16-09保护/文件/状态。结果见 [ER-D0-C16_RESULT](ER-D0-C16_RESULT.md)。是同Agent分离pass，不是第二位评审者。

机制参考：ShardingSphere官方[LOCAL事务限制](https://shardingsphere.apache.org/document/5.1.1/en/features/transaction/use-norms/local-transaction/)说明跨库故障边界（5.1.1资料，只作原理旁证，5.5.0实际机制仍以本地JAR/测试为准）；PG16[SELECT锁与SKIP LOCKED](https://www.postgresql.org/docs/16/sql-select.html)、[约束触发器](https://www.postgresql.org/docs/16/sql-createtrigger.html)提供本库机制；Spring[事务传播](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/tx-propagation.html)说明独立事务/连接成本，不能从逻辑传播推导多个PG原子提交。沿用已有组件，不把文档引用当运行验证。
