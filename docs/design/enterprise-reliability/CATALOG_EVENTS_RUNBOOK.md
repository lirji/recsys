# 目录可靠事件运行边界

依据[C16正式设计](C16_DESIGN.md)，S16A已交付；S16B状态/fanout/内部批次已完成必要真实验收；以下描述代码入口与处置边界，不代表共享环境已部署或启用。唯一状态见[IMPLEMENTATION_SLICES](IMPLEMENTATION_SLICES.md)。本回合不执行共享迁移、开关切换、清库、重放或部署。

## 数据与开关

advertiser目录归原写侧owner；advertiser/ad按advertiser_id分库，词/创意按ad_id分库，可能不共址。新治理表和迁移必须在两个实际PG database各自安装与校验，并在显式可靠profile配置shard_id路由；放公共库或交给SINGLE默认ds_0不成立。保留原SQL/fixture，新增表和每列有中文注释。

默认`sharding.yaml`不改；`ADVERTISER_SHARDING_RESOURCE`默认仍指向它。启用可靠能力需显式选择新增`sharding-catalog-reliable.yaml`并先安装两库迁移；两个资源的业务路由/连接模板必须通过结构对比，不能为了新表让无迁移的default-off旧环境无法启动。

reliability的enabled、admission-enabled、worker-enabled、relay-enabled、recovery-enabled全部默认false。已有publish-events默认false；新enabled与旧publish互斥。停止admission只停止新命令，在途step、命令、Outbox和查询仍有独立恢复路径。新status请求进入ROOT_STATUS完整fanout；已持久化的A命令仍按原kind和原字节查询续办。内部新批次也受admission控制，新取消/重试审计受recovery控制；关闭后仍可查询、推进原命令和返回原审计结果。

S16是生产者恢复能力。S17版本化Inbox/墓碑/Redis刷新及读模型切换验收完成之前，不能因S16发出了Kafka消息就启用共享reliable写或宣称暂停已在所有在线缓存生效。legacy默认行为保持；新模式不再向旧topic/Redis尽力双写。

| 配置 | 作用 |
|---|---|
| `ADVERTISER_SHARDING_RESOURCE=sharding-catalog-reliable.yaml` | 可靠装配必需；默认仍为旧资源 |
| `AD_CATALOG_RELIABLE` | 装配可靠协议；默认false |
| `AD_CATALOG_ADMISSION` | 接受新业务命令；默认false，关闭仍可同ID续办 |
| `AD_CATALOG_WORKER` / `AD_CATALOG_RELAY` | 两个固定串行槽推进原命令/发送事件，分别默认false |
| `AD_CATALOG_RECOVERY` | 装配内部初始化、审计恢复、全shard重验；默认false |
| `AD_CATALOG_SOURCE_ID` / `AD_CATALOG_WRITER_NAME` / `CATALOG_WRITER_ID` | 固定源UUID和登记实例/0～1023唯一Snowflake ID；启用时必须显式提供 |

`CatalogSchemaInstaller.install(physicalDataSource, shardId)`只接受真实物理PG，逐库核对不可变V001并追加V002任务扩展；不是Bean，不在启动时自动迁移。安装与校验使用同一资源SHA256，原已发布迁移不改写。MyBatis ScriptRunner逐版提交；每版重设4秒锁/5秒语句预算、重新取得物理库安装事务锁并刷新历史视图。V001成功而V002失败时保留V001，下次只续装缺失版本，不承诺跨版本一个事务。B可靠启动要求两库V001/V002摘要均正确；无治理迁移的default-off旧模式保持。可靠profile关闭sql-show，可靠Mapper日志禁用；物理路由/txid证据从隔离测试采集，不打开产品SQL参数日志。

## 初始化与兼容准入

仅owner在明确获授权的隔离目标调用内部`CatalogInitializationService.initialize/advance`。记录sourceId、真实数据库身份、拓扑、两份schema checksum、topic、writer/worker-id清单、可信actor/reason和稳定commandId；不记录口令。源数据/关系/归属/数组大小/原主键合法性由真实只读核验决定，失败不得自动搬数据或删除“坏行”。

恢复专用装配允许enabled/recovery-enabled开启而admission/worker/relay关闭；迁移/profile必须正确，未登记/未完成control可以只进入INITIALIZE和查询，不能偷开普通写。topic预先在获授权环境创建，初始化只读核对clusterId/topicId，换同名topic不能静默采用。

初始化先持久占有固定home的control，再改变其他shard；不同command不得同时操作。两库逐一QUIESCED，guard与旧事务control锁互斥；部分成功保留原步骤，不回退LEGACY重试。关闭writer后冻结成员清单、读真实parent/children基线、持久v1原字节；每个BASE_SEND步骤经真实Kafka确认并保存原坐标，两库同源/同协议、全部目标完成并读回后才READY。基线发送失败保留原初始化ID/阶段/预算，不先开放普通写。不能因没有活动成员就省略跨command持久所有权。

准入清单的worker-id唯一性只证明登记配置不重复，不是全局进程探测。须排除未登记旧二进制、重复实例标识、手工SQL和正在运行的seed-ads。seed-ads旧版本可能先清adDb和Redis，目录表guard保护不了那些副作用；新版必须在任何副作用之前检查模式，切换仍需维护窗口排除旧作业。本片不会实际停止共享进程或容器。

新版`seed-ads`必须在任何adDb、目录、Redis副作用之前，使用`PG_JDBC_URL`和`PG_DS1_JDBC_URL`两个真实连接做只读检查。正式C16允许明确未安装全部新治理表的旧库，或合法未登记LEGACY控制行；两库均确认后才能继续。部分治理表、已安装但control缺行/多行、身份错配、半登记、ACTIVE/QUIESCED、网络/权限/查询未知均为CATALOG_NOT_READY，两个地址实际同库也拒绝。此前文档所谓“最新用户覆盖缺表例外”无依据，已撤回；本轮18项真实seed测试已验证该修正：9张治理表全无才为未安装，部分存在拒绝；两只读会话使用事务局部标识与刷新后的PG活动视图识别同库，任一探测未知拒绝。检查不消除旧seed进程与检查后切换竞态，维护窗口仍由owner控制。

基线仅覆盖当前存在且合法的目录。历史已删但无事实的广告、旧投影残留、源/Outbox丢失、完整历史重建需S17或另行owner核验；不能从一次空查询补造删除。

## 查询与续办

业务状态、传播状态分开：命令ADMITTED/RUNNING/PAUSED/COMPLETED/REJECTED；Outbox READY/SENDING/PUBLISHED/PAUSED；多目标结果含PARTIAL/CONFLICT/CANCELLED。COMPLETED表示规定业务步骤和完整事件已落库，不代表Kafka、投影或Redis已完成。

先按原command查询冻结输入、home、step、lease/fence、预算及结果；未知提交优先查询相应物理库原step，不换ID。关闭/丢失HTTP响应时，稳定命令头还需同时提交owner头（开户例外），它只用于路由，仍验证权限与原归属；重试先查原命令再查当前业务行，已删ad/child不应把原结果变成404。如果旧客户端没有预先提交commandId且未收到响应头，就不能自动知道创建身份；通过owner审计核验，不能按正文hash假装幂等。开户有固定home的ROOT_CREATE与原Snowflake身份预留，后续权限TOUCH和业务写始终使用原ID/actor。

- 暂态故障：检查原step是否APPLIED；存在则读取原结果，继续未完成步骤。不能从当前源重读一份不同快照覆盖原结果。
- 预算耗尽/最后一次领取退出：租约到期清算PAUSED，保留advertiser占有。新受控RETRY_STEP开启审计轮次，历史尝试不清零；无自动无限重试或“重启解锁”。
- 多个阻断：只解除经核验的目标；shard持久STOPPED须无其他阻断才start，不能恢复一条就全局resume。
- 迟到执行器：owner fence保护owner结果，本地step fence保护该物理库业务；旧协调者可能完成已获准的同一不可变step，不宣称远程lease瞬间撤销。原receipt和未决占有阻止重复/串到下一命令。
- REPLAY_EVENT：一条原event/一次有限命令，原key/value/eventId/version发送；即使原PUBLISHED也不改变业务事实或消费位点。PAUSED原事件实际重发确认后，以原fence同库保存PUBLISHED及REPLAY_ACK事实，保留原发送预算和错误历史；原PUBLISHED的最初坐标不被重放覆盖。重放home完成未知时先查该ACK原文，已知成功不因Kafka此刻不可达而再发，包含末次尝试后的PAUSED清算。Kafka ACK未知可重复，不能把重复当新业务版本。
- 不一致/缺原文：固定SOURCE_UNAVAILABLE/INCONSISTENT，保留占有/审计，owner处理。没有“删历史/放弃未决/重设版本”的自动修复入口。

已实现的查询：`GET /api/advertiser/{advertiserId}/catalog-commands/{commandId}`和开户`GET /api/advertiser/catalog-commands/{commandId}`，返回`commandId/advertiserId`字符串、`state/phase/hasResult/errorType`及step/event摘要；`limit`为1～100，两个独立cursor携带物理分片。开户查询还要求原actor与当前administrate权限。成功写响应复用冻结DTO，JSON对象字段顺序不是幂等身份；数据库原结果与Kafka原字节不变。

末次RUNNING步骤租约到期且预算已耗尽时，worker按物理库每轮最多10条SKIP LOCKED清算为PAUSED，不释放原占有、不清计次。QUIESCED恢复专用模式没有普通worker时，新的审计RETRY_STEP按原round/fence清算已到期目标后续办；有效租约不抢占。显式缺command/step/event为NOT_FOUND，已有原文摘要损坏为INCONSISTENT。

内部恢复由受信owner调用`CatalogRecoveryService.submit/advance/query`；每次submit带新审计UUID、明确原目标、摘要、预期轮次、actor/reason。`CatalogShardRecheckService`逐页核验停止shard的原事件/receipt，再按原revision条件解除本shardSTOPPED；不解除任何PAUSED广告主持久占有。不存在公网初始化/重放/全局解锁接口。

存活publisher每次发送前有界核验原clusterId/topicId；错配记INCONSISTENT并持久STOPPED当前shard。同名topic重建不能作为恢复方式。Admin核验与Kafka Produce并非跨系统原子操作，环境owner仍须排除未经协调的主题管理操作；不据此承诺基础设施被同时重配时的无损保证。

API/actor与错误码以正式契约为准；这些内部恢复入口不开放匿名公网调用。查询不存在固定NOT_FOUND，不泄露原payload/关键词/权限凭据。

## 广告主状态与部分结果（S16B实现，验收状态见实施清单）

暂停先在广告主本库改status并持久fanout任务/事实，持有guard封闭广告集合，再按页冻结目标、逐ad发新版本快照。部分目标已发布而另一库故障时必须保留PAUSED/PARTIAL和逐项目标，不能回报“全部暂停”。相同状态重试不重新采样成员；恢复到active必须是新合法命令，不复活已删、单独paused或拒审广告。

实际`CatalogFanoutService`先冻结根变更，再以最多100个ad/页保存目标ID、原版本、页摘要和游标。每个ad使用独立step身份；目标最终事件与该目标COMPLETED同库提交，只有全部目标事实核对后ROOT_FINAL才完成父命令并释放guard。源status可先于事件传播变更，不承诺跨广告同时生效。原ad status/review和删除墓碑保持，重复同状态不制造重复fanout。

内部受控`CatalogStatusBatchService.submitStatusBatch(batchId, targets, actor, reason)`接受1～100个明确`(advertiserId, expectedAdvertiserVersion, targetStatus)`，按owner排序冻结，home由原batch UUID固定，child UUID由原batch/owner派生。actor必须由可信调用上下文提供；未增加公网批次API或变更原manage政策。查询`query(batchId)`/`targets(batchId, afterOrdinal)`只读原清单，每页最多100项，准确报告total/completed/conflicts/cancelled/paused/pending。

每轮只派发/观察一个目标，先持久DISPATCHED再访问其owner库；未知目标有限3次，known pending有界轮转，某目标PAUSED不阻断其他目标。成功观察不抹掉原child的step预算。最后一次协调领取退出则租约到期保存带原fence的独立耗尽receipt，将该目标PAUSED并转向其他目标，没有再次尝试该失败目标。多个耗尽receipt身份不同。全部目标COMPLETED才是父COMPLETED；已有完成/冲突/取消/暂停时查询报告PARTIAL，未决为零但仍有非成功终态时父持久PAUSED并保存计数。

迟到目标仍使用原expectedVersion；版本冲突保存CONFLICT，不能改写新状态。新稳定审计`cancelUndispatched(auditId,batchId,actor,reason)`仅将READY置CANCELLED，已DISPATCHED或已提交效果保留。`retryTarget(auditId,batchId,advertiserId,expectedRound,actor,reason)`只重开一个PAUSED原目标，保留childId/原版本和历史预算事实；原child若PAUSED，先按既有RETRY_STEP恢复该child，不能借批次重试释放owner guard。重复审计返原结果，不能使用新ID覆盖原批次。


## 向量、保留和回退

派生embedding失败/空/非法均为UNKNOWN，有安全原因；不是删除广告或同item已知向量的授权。关联item改变时不能把旧item向量套给新item；明确DELETE有独立高版本墓碑。原事件重放不刷新向量，新REFRESH_ENRICHMENT必须是新版本且不可复活deleted广告。

暂不自动清理命令、step receipt、版本/墓碑、Outbox与审计。Kafka过期不抹掉已知数据库原文；受控续发不等于全历史重建。只有Kafka无DB原文或两者都丢失的场景须报告实际缺口，不能宣称RPO=0。容量、保留期、备份与生产RTO/RPO未验证。

V002保留V001原事实与摘要，但新增ROOT_STATUS/STATUS_BATCH命令须由识别B协议的执行器推进。存在未收敛B命令时，不能把只支持A的旧worker当作兼容恢复路径；混合版本执行未作为安全切换验收。不删除V002、不改写任务状态来回退，保留B协议查询和恢复能力。

回退先停admission，保留新协议排空/查询/核验。ACTIVE库改应用enabled=false仍应被DB guard拒绝legacy写；回旧writer需要额外owner核验与版本/投影衔接，不能自动把control设LEGACY。无权限时仅记录阻断，不绕过分支保护/沙箱/数据库guard。

## 证据要求

按[C16-01～20](C16_DESIGN.md#11-真实验收矩阵当前全部-unverified)使用两个独占PG实例、真实ShardingSphere规则、真实Kafka及独立JVM。保存物理库/txid、稳定命令/版本、原字节摘要、实际offset、逐目标状态及源指纹。注入断点只证明对应窗口；实际网络断连、broker副本容灾、ACL故障和生产吞吐若未测必须标UNVERIFIED。未知运行操作先查PID/日志，不因会话恢复就重跑。

ShardingSphere启动可能要求两库元数据；一库不可达时不保证另库可以单独重启。公平处理仅保证已启动实例中无故障库依赖的目标不被队首长期饿死，重启后的恢复仍须等待实际必要依赖。
