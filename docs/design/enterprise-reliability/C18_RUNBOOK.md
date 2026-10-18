# C18 广告日志核对与恢复

只在已授权的数据平台环境执行下列作业。本轮仅验证独占测试库，没有在共享库执行同步、安装或重建。

## 正常核对

为源数据集分配并长期保存一个 UUID；不要每次启动生成新值。普通调用保留原作业名，并增加必填源身份：

```sh
java -jar recsys-offline/target/recsys-offline-*.jar \
  --job=sync-ad-event-log --source-id="$AD_EVENT_SOURCE_ID" \
  --max-pages=100 --max-seconds=60
```

跨库增加 `--source-db=recsys_ad`（替换为真实库名）。连接沿用现有 `PG_HOST/PG_PORT/PG_USER/PG_PASSWORD` 环境配置；本说明不记录凭据。源只读，不再把全源行一次加载到内存。首次调用在目标事务中安装追加迁移并绑定实际源数据库、模式、表OID与服务端身份摘要；复用旧目标时会逐页核对既有内容，遇到冲突暂停。

每页最多1000行，默认每次最多100页；时间预算在页边界检查，在途页按数据库/网络超时收尾，不是硬墙钟截止承诺。`--max-pages`范围1..100，`--max-seconds`范围1..60。目标数据与cursor同事务提交；预算耗尽状态READY，使用相同source-id/source-db再次调用即可续办。一次调用只完成至多一个核对轮。

本轮COMPLETE表示冻结上界内的一次扫描完成，**不是提交高水位，也不表示全源实时追平**。下一次调用开启新一轮、从0重新核对，因此上轮扫描后才提交的低ID仍可补入。需要现有调度持续调用；本片没有新建定时器。源必须保持追加不变和足够保留期；源先删除且从未被扫描的记录无法恢复。

## 状态与恢复

受限只读查询 `ad_event_sync_checkpoint`：关注state、epoch、upper_id、cursor_id、attempts、error_code/error_id、scanned/copied、lease_until和completed_at。`scanned/copied`是本轮已提交计数；最近完成时间只说明最近完成的一轮，不承诺没有迟到记录。日志仅输出固定状态、计数和continuationRequired，不输出源行或JDBC约束错误的原文。

| 状态/原因 | 处置 |
| --- | --- |
| READY | 未完成页继续；available_at之前处于失败退避，不循环无间隔重试 |
| RUNNING且租约有效 | 保留当前执行器；第二实例拒绝领取，不杀共享任务抢占 |
| RUNNING租约到期 | 下一实例以新fence接管；若末次尝试已耗尽，则持久转PAUSED |
| PAUSED / DEPENDENCY、EXHAUSTED | 修复依赖后以新审计命令resume，保留原epoch/cursor |
| PAUSED / CONTENT_CONFLICT | 按error_id核查源与目标16列，不自动覆盖；核准后选择修复来源或显式重建 |
| SOURCE_CHANGED | 核查是否切错源、重建表或破坏追加约束；不要改checkpoint里的身份掩盖问题 |

审计恢复：

```sh
java -jar recsys-offline/target/recsys-offline-*.jar \
  --job=sync-ad-event-log --source-id="$AD_EVENT_SOURCE_ID" \
  --resume-command-id="$RECOVERY_COMMAND_ID" --actor="$OPERATOR" --reason="$RECOVERY_REASON"
```

跨库时始终附带相同source-db。保留命令ID；同ID同参数重试不会再次重置预算，同ID不同动作/操作者/原因拒绝。原始页失败每轮最多三次；进程重启不能清次数。数据库不可达导致无法写失败状态时，先前已扣次数和租约仍是恢复依据。

## 显式重建与兼容变化

普通补洞不需要full。只有已明确批准清空目标并核准源完整性时，才使用：

```sh
java -jar recsys-offline/target/recsys-offline-*.jar \
  --job=sync-ad-event-log --source-id="$AD_EVENT_SOURCE_ID" \
  --full --rebuild-command-id="$REBUILD_COMMAND_ID" --actor="$OPERATOR" --reason="$REBUILD_REASON"
```

`--full`仍会清空目标，但必须有稳定审计命令；先核验来源且无活租约，再将清空、新检查点和命令审计一起提交。之后按页重建，失败时可能已有部分页；用**原命令和原参数**重试只续办，不再次清空。新命令代表另一次明确重建，不能随意换ID来重试。full不能更换已经绑定的数据集。

旧脚本必须补source-id；旧裸full必须补审计参数；禁止把本作业包在调用方外层事务中。可靠分页的事务边界属于每页，入口会在副作用前拒绝外层事务。错误参数同样在DDL/清空前拒绝。

回退先停调度并排空执行器，保留checkpoint、audit和目标数据；不要删除检查点后继续旧MAX(id)算法。表内人为TRUNCATE/复用源ID不会自动产生新数据集身份，属于本协议禁止的源变化，需要独立迁移方案。没有源数据全丢失恢复、线上容量或RTO认证。
