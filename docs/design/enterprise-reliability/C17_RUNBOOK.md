# C17 目录投影运维入口

本片交付代码与隔离验收，不代表已在共享环境安装或启用。源协议保持 C16；`ad-serving` 自有 PG、独占 Redis 逻辑库、独立 Kafka group 共同组成一个数据集。

## 首次登记

1. 停止并排空旧目录消费者和写入器。确认目标 `ad_servable`、`ad_embedding` 和 Redis 的 `bidword:inv:*`、`catalog:projection:version:*` 为空。程序只检查，不清理。已有非空副本需要另行准备迁移清单，不能用 TRUNCATE 绕过。
2. 在受控维护进程中，使用既有 `adDbSqlSessionTemplate` 和 `ObjectMapper` 构造 `CatalogProjectionStore`，显式调用 `install()`。此入口以事务安装 `db/catalog-projection/V001__catalog_projection.sql`，登记脚本 SHA-256；重复安装验证摘要。应用启动只 `verifySchema()`，不会自动 DDL。
3. 配置总开关和恢复开关为 true，消费/Redis worker 仍为 false；旧 `recsys.ad.catalog.consume=false`。Kafka 连接/安全参数来自既有 Spring Kafka 配置，topic 默认为 `ad-catalog-v1`，group 默认为 `recsys-ad-catalog-v1`。
4. 由可信内部维护调用 `CatalogProjectionOperations.initialize(commandId, sourceId, baseline, actor, reason)`。所有 ID 必须保留；baseline 记录 C16 源清单及历史保留核对结果。程序从 broker 获取 cluster/topic UUID、分区和最早保留位置，拒绝已有提交/活动成员的消费组。重复同一命令返回原登记结果；不能换参数复用命令。
5. 若基线早于保留起点，先让源 owner 续发原事件；登记成功本身不证明历史完整。核对后设 `source=replica` 并打开 `consume-enabled`、`redis-enabled`；恢复开关按维护窗口关闭。四个开关默认均 false。

维护入口为受信任的 Java Bean/Store API，没有新增公网 HTTP 或接受任意连接串的远程接口。独立测试进程使用相同 Store 与 Kafka 执行器，见 `CatalogProjectionKafkaTest.CrashProcess`；生产维护宿主/登录权限不属于本片交付。

## 查询与有界恢复

| 观察 | 原因/操作 |
| --- | --- |
| control PAUSED | 查固定 `error_code`、原分区 next_offset 和原投递；修复依赖后 `resume(commandId, actor, reason)`，再重启消费者。重启本身不会清预算 |
| QUARANTINED | 查 receipt 的坐标、摘要和固定原因；原字节保留在库中，不输出到公共日志。协议修复后 `recheck(commandId, partition, offset, actor, reason)`，不能替换内容或回退位点。仍不合法则仍隔离 |
| Redis task PAUSED | 修复依赖/错误 key 类型后 `retryTask(commandId, taskId, actor, reason)`；只重开该原任务预算。尚未完成的同广告前驱继续阻塞后继 |
| Redis 数据丢失 | 先停并排空旧 Redis worker，确认 PG 快照完整。对明确广告清单逐个 `refresh(commandId, adId, actor, reason)`，单次一个广告；它追加当前快照任务，不能跳过失败前驱 |
| topic 重建、分区变化、保留缺口 | 停消费并核对源清单，不能改现有登记或 reset latest。需要独立迁移/重建方案，不使用 resume 掩盖缺口 |

Store 的 `control()`、`positions()`、`projection(adId)`、`task(taskId)` 为内部查询；原始隔离证据可由受限只读 SQL 查询 `catalog_projection_receipt`。审计表记录稳定命令、动作、对象、actor、reason 和原结果。同命令不同参数拒绝，成功恢复命令重放不会再次清预算。

固定指标：`recsys.catalog.projection.tasks{state}`、`oldest.seconds`、`quarantined`、`running`（实际名称共享 `recsys.catalog.projection.` 前缀）。PAUSED、隔离增长、最老待办增长或预期运行但 running=0 应触发排查；数据库不可达返回 NaN，不能当作零积压。阈值须按环境容量设置，本片没有吞吐或 RTO 承诺。

## 一致性与回退

PG 效果、Inbox、版本/墓碑、Redis 待办及分区位置原子提交；Redis 是可观察的最终一致投影。ACK 不能作为 Redis 已更新的证据。每任务每恢复轮最多三次，租约30秒，数据库和 Kafka 调用有超时，Redis命令2秒；Redis 检查空命名空间最多100个 SCAN 页/模式、5秒，无法完成时拒绝登记。

暂停广告退出可服务表和在线向量，PG 仍保留同 item 最近有效向量的原事件；恢复遇到 UNKNOWN 时使用它，换 item 或删除则清除。墓碑、原投递、任务、审计均不自动过期。

回退先关闭并排空新消费者和 Redis worker，保留表/原文/任务。已登记数据集上的数据库触发器阻止旧无版本写入；仅关闭新开关不能放行旧消费者。不得恢复一个缺少版本标记的 Redis 备份后继续运行旧在途请求；不支持 Redis Cluster 或多数据集共库。
