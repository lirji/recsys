# C17：版本化广告目录消费与 Redis 恢复

状态：正式实施设计，2026-10-18 同一 Agent 分离只读核对通过；产品验收32项PASS，见ER-S17_RESULT。用户于 2026-10-18 指定先实施 ER-S17、ER-S18；本文件只细化原 F16，不扩展 S19～S25。父 S16 已交付，生产者协议 [C16](C16_DESIGN.md) 保持不变。唯一进度见 [IMPLEMENTATION_SLICES](IMPLEMENTATION_SLICES.md)。

## 事实、选择与边界

- 基线 c5649032。现有 AdCatalogEventConsumer 只消费旧 topic，按到达顺序覆盖，且仓储/Redis 维护吞异常；不能接收 VersionedAdCatalogEvent。
- 使用现有 adDbDataSource、Mapper XML、PG16、Spring Kafka/Redis 与 Micrometer，不引入依赖、服务或迁移框架。新链路默认关闭；旧事件 wire/topic 保留。
- 选择 PG 事务保存 Inbox、版本/墓碑、可服务副本、向量与 Redis 待办；Redis 在事务外应用。否决 DB/Redis 同步双写后直接 ACK，以及仅靠 Kafka 分区到达顺序。
- 新消费者使用独立组，PG 持久分区检查点作为恢复位置；一个受数据库租约约束的执行器手动分配全部已登记分区。Kafka 提交只确认已落 PG 的连续处理前缀；组提交滞后可恢复，不能从组位点跳过 PG 缺失效果。不承诺 Kafka 自动重平衡，多实例竞争由 PG lease/fence 处理。
- 初次启用只接受显式登记的 sourceId、Kafka cluster/topic 身份、保留起点、原目录基线说明与操作者审计。首次目标目录/向量及其独占 Redis 倒排必须为空；非空旧副本拒绝初始化，后续生产存量切换须另做清单核对，不自动清空。S16 基线早于 Kafka 保留起点时必须由 owner 续发原文，不能声称 earliest 等于完整历史。
- 共享环境启用/部署、旧进程在线混跑、全历史或源与 Outbox 同时丢失后的恢复、Redis Cluster、多数据集共用同一投影库均非本次范围。Redis 使用 ad-serving 独占实例/逻辑库；单机 Redis Lua 跨关键词原子脚本不冒称支持 Cluster。

## C17 正式协议

1. 复用 CatalogEventCodec 验证原 key、原 payload、sourceId、topology、完整快照与版本；不重序列化后计算摘要。同事件身份/同版本同 digest 幂等，同版本异 digest 持久隔离；较旧事件保留 Inbox 为 STALE，不覆盖较新状态；较新完整快照允许跳版本。
2. `(source_id, ad_id)` 保留最高版本与原摘要，删除后不删除版本墓碑。AD_DELETED 清可服务副本与向量；PRESENT+servable=false 为暂停/拒审，不伪装源删除。仅 active+approved 创意进入可服务副本。UNKNOWN 向量在同 item 保留已知向量，暂停时保留原向量事件但移除在线向量，恢复后可重建；换 item 移除旧向量；向量写失败使整个 PG 投影回滚。
3. 原始投递坐标/原字节/摘要、业务 Inbox、版本、高水位、数据库效果、Redis 任务和 PG next_offset 在同一事务提交。提交前故障不得前移 next_offset/确认 Kafka；提交后确认丢失重启从持久 next_offset 恢复。坐标重复但字节不同拒绝，不覆盖原文。
4. 非法协议/错误来源/同版本内容冲突在 PG 隔离成功后才可前移该消息位点；隔离库不可用则停消费。依赖错误不隔离成成功、不 ACK；重试预算跨重启保留，耗尽保持 PAUSED。恢复只通过内部带稳定 UUID、actor、reason 的命令，原字节重试；不改变原业务身份、不跳主消费位点。`recheck` 只重检隔离原字节，不接受替换内容，不回退位点；仍非法仍隔离，成功命令幂等。
5. Redis 任务存储应用前后精确关键词/member/score；同广告按已接受版本串行，未完成前驱阻塞后继，其他广告可继续。数据库领取短事务，持久 attempts、lease、fence，超时/退出耗预算，最终租约到期转 PAUSED。成功脚本后、任务确认前崩溃允许幂等重做，旧 fence 不得确认任务。
6. Lua 在修改前核对全部 key 类型，版本使用补零十进制字符串比较以保留完整 long 精度；每次删除旧 member 并写新 member，与版本标记同脚本完成。旧版本不能复活较新删除。PG 与 Redis 仍是最终一致；Redis 故障不能回滚已接受目录事实。不得扫描整个关键词 ZSet 来找一个广告，也不对全集 DEL/FLUSH。
7. Redis 数据丢失后通过有审计的指定广告重新刷新恢复当前可服务状态/墓碑，批量恢复使用有界明确目标；不凭消费 lag=0 声称 Redis 完整。外部恢复旧 Redis 备份或丢失版本标记时先停止并排空旧执行器，再核对/重建，不能让旧在途请求跨恢复窗口写回。
8. 新组从登记的最早保留位置开始，不使用 latest/reset。启动及分区轮询校验 clusterId/topicId/分区集合和保留范围；身份变化、分区增加、位点过期均停止，需明确重新登记方案。已有数据集身份不可静默替换。

## 资源、配置与迁移

- `recsys.ad.catalog.projection.{enabled,consume-enabled,redis-enabled,recovery-enabled}` 默认 false；旧 `recsys.ad.catalog.consume` 与可靠消费互斥。已登记可靠数据集时旧消费者必须拒绝写入，即使新开关关闭；旧二进制退出是切换前提。
- 追加 `recsys-ad-serving/src/main/resources/db/catalog-projection/V001__catalog_projection.sql`；新表/列中文注释、PK/唯一性/状态约束与领取索引齐备。显式安装，启动只核验，不改历史 bootstrap/已发布迁移。默认关闭不依赖新表，不新建 Kafka/Admin 连接。
- 单批最多 10 个 Kafka 记录、每轮最多 10 个 Redis 任务；每任务每恢复轮最多 3 次，30 秒租约、PG 事务/语句 5 秒、锁 4 秒；退避 1/2/4 秒加稳定抖动。Kafka poll/commit/Admin、Redis 调用均有界。数值为保护预算，不是吞吐/SLA 承诺。
- 状态、异常原因与 topic 使用具名类型/常量；内部恢复入口不新增公网接口。日志不输出原 payload、关键词、连接串；指标仅固定状态/阶段，包含 pending、PAUSED、隔离数量、最老待办及执行器状态。
- 保留版本/墓碑、Inbox、投递原文、任务和审计；保留期未知，本片不自动删除。回退先停消费与新刷新，保留新协议恢复能力，不能让旧消费者接管已启用的新数据集。

## 文件级步骤与验收

一个完整纵向 S17：配置/追加迁移 → Mapper/事务投影 → 真实 Kafka 消费与持久起点 → Redis Lua/有界 worker/审计恢复 → 独立真实验证 → 文档与 Git 交付。主要路径为 ad-serving 的 `catalog/`、`persistence/mapper/addb/`、对应 XML 与新迁移；旧 consumer/repository 只加必要写入准入；配置、CI 及说明只覆盖本片。

| ID | 必需观察 |
| --- | --- |
| C17-01 | 同 key 的 v3→v1→重复 v3、暂停/恢复/删除；PG 副本、高水位与墓碑正确 |
| C17-02 | 相同身份不同 digest、错误源/坏 JSON/未知版本持久隔离，原 Inbox 与数据不变；隔离失败不前移 |
| C17-03 | 真实 PG 事务回滚/约束失败时业务、Inbox、任务与检查点一起回滚 |
| C17-04 | 同 item UNKNOWN、换 item UNKNOWN、真实向量、删除；创意审批过滤与现有读方法保持 |
| C17-05 | 真实 Kafka 的注册起点、已发基线、PG 提交后未确认恢复、重复与跨进程重启 |
| C17-06 | 源/topic 身份错配、保留期缺口、陌生分区、两执行器竞争和过期 fence 拒绝 |
| C17-07 | 真实 Redis 断连后 PG 成功且任务保留，恢复后删除旧词/更新出价与 member，无脏复活 |
| C17-08 | 脚本成功而 DB 确认失败、旧执行器迟到、最后一次租约过期、有审计重放/恢复幂等 |
| C17-09 | 完整 long 版本、错误 Redis key 类型、同广告任务次序、不同广告公平推进 |
| C17-10 | 默认关闭/无新迁移兼容、开关互斥、已登记后旧消费者拒写、非空目标拒绝初始化 |
| C17-11 | 迁移重复安装/版本核验、全部表列注释、SQL/错误码/日志/依赖治理与受影响回归 |

使用独占 PG/Kafka/Redis，不停共享组件，不把 Mock 作为原子性或故障恢复证据。代码与测试完成后记录实际源码指纹、日志/XML及未验证边界；设计核对不等于产品 PASS。

## 设计复核

C16 版本/UNKNOWN/墓碑/起点逐项对应；单库事务、Redis 事务外、持久预算与旧写入准入均有反例验收。选用现有组件，首次非空目标拒绝是有意的上线边界，当前仅获授权隔离验证；不以初始化成功声称源历史完整。原始投递可先保存为待处理记录以计重试预算，业务 Inbox/效果/待办/next_offset 仍同事务提交。设计 PASS 不替代 C17-01～11 产品证据。

操作与回退步骤见 [C17_RUNBOOK](C17_RUNBOOK.md)。
