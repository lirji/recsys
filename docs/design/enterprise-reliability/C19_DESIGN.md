# C19：实时窗口与可恢复投影

状态：2026-10-18，用户明确授权 ER-S19～S21；本文件细化原 F18。现有 Flink 1.20.0 / Kafka connector 3.3.0-1.20 / Jedis 5.1.5 不升级，不改变行为接受、计费或历史恢复契约。

## 事实与取舍

旧作业使用 processing-time、latest 起点、无 checkpoint，逐成员 HSET/ZADD 续整键 TTL；这不能证明重放不重复或旧成员退出。选择显式 `--reliable true` 的新管线，旧入口保留兼容但不能作为可靠模式验收证据。单并行度沿用现状；采用有界全窗口快照，避免各 item/user 独立窗口无法清除已消失成员。大规模分片、Redis Cluster、自动生产切换不在本片。

## 运行与事件契约

- 必填独占 projection-id UUID、Kafka group、持久 checkpoint-dir、Redis namespace；namespace 不得为空，隔离新旧键。读取方新增 `recsys.streaming.namespace`（默认空）选择已核对namespace；HotRecaller/TagRecaller/UserProfileService共用RedisKeys解析，线上读取切换需另行授权，默认不写旧 `recall:rt_hot` / `rt:user:*`。启动/恢复身份包含逻辑流、topic、group、projection、窗口参数、namespace，checkpoint 恢复不兼容时拒绝。
- LEGACY 沿旧格式，以 topic/partition/offset 去重；不能把不同 offset 的相同旧事件猜成业务重复。LIVE 复用原 LegacyBehaviorCodec，限 BEHAVIOR_POST、绑定 source-id，以 eventId 在可影响窗口的保留期内去重（依赖上游事件内容不可变，已终结时间重放走late）；HISTORY/自然曝光流禁止进入实时正反馈。
- Kafka 从最早保留位置开始新建；恢复以 checkpoint 的 source offset 为准，禁止用 committed offset 启一个无窗口状态的新作业。Kafka 保留不足不能承诺重建完整历史；恢复前按 runbook 检查保留区间。坏 schema/key/来源/非有限值/不合法时间失败关闭，最多3次重启，不吞毒消息。日志不含原 payload。
- event time 使用行为 ts；LIVE 相对不可变 capturedAt、LEGACY 相对 Kafka record timestamp，最多允许30秒未来偏差。默认窗口10分钟/步长20秒/乱序容忍30秒，均可配置且窗口整除步长。水位之前的事件计 late 并排除实时窗口（原始可靠记录不删除）；最终窗口不补写，历史数据留给离线重建。
- 正反馈权重保持 CLICK/PLAY=1、LIKE=2、RATING=max(value,1) 的旧实际规则（value>0时保留value）；IMPRESSION 不计分但可以推进水位。空闲分区显式 idle；无新事件时不把处理时间冒充事件时间，输出按绝对新鲜度到期。
- 单实例 active event 上限100000、类目快照上限100000；超限失败背压而非悄悄丢事件。并行度固定1；配置为技术初值，不是业务容量承诺。类目快照随 checkpoint 冻结，恢复不能重读新目录改写旧窗口。刷新目录采用新 projection 与独立重建。

## 状态与 Redis

- 显式固定 operator UID，EXACTLY_ONCE checkpoint、filesystem storage、保留取消后的外部 checkpoint、单并发 checkpoint、超时60秒；手动指定 restore path，禁止默默猜最新目录。Kafka offset、去重身份、窗口内容、最后终结水位、类别快照和最后完整输出一起保存。
- 每个已终结窗口生成完整 hot 与 user-category 快照，按排序后内容计算摘要。Redis Lua 原子删除上次成员/用户键并写新快照；按 windowEnd 单调更新，同版本不同摘要失败，旧版本无副作用。同版本相同摘要允许重建丢失的投影。
- Redis 元数据保留最后版本/摘要/配置，数据按 windowEnd+freshness（默认120秒）绝对到期；重复/重放/心跳不能延长有效期。整窗替换删除消失类目/用户/item。处理时间心跳重发 checkpoint 中的最近完整快照，可修复输出键丢失。
- 独占租约与每次 Lua token 校验阻止旧执行器写；丢租约让作业失败并恢复，不在原执行器重新抢占。Redis IO有连接/读超时，异常传播使任务失败。Redis元数据与全部状态一并丢失后须先停止其他写者再恢复/重建，不宣称跨任意Redis灾难的exactly-once。
- 单批快照有最大字节数及成员数限制；Redis standalone 的原子脚本不是 Cluster 协议。checkpoint文件需要持久存储；本机file URI仅证明同盘进程重启，不证明节点丢盘恢复。

## 文件与顺序

1. `recsys-streaming/.../reliable/` 配置、Kafka解码、状态窗口、Redis快照，`RealtimeFeatureJob`显式路由；只为复用既有codec依赖platform artifact并排除全部传递依赖，既有Jackson/common显式供应，不引入Web宿主。
2. `run-streaming.sh`沿现有入口、持久配置/恢复参数；测试使用已有GenericContainer启动Kafka。
3. 真实Kafka/Redis与持久checkpoint的Flink重启、重放、晚到、旧成员退出、Redis故障/丢键修复、状态/config身份拒绝；旧解析与PG类目回归。
4. 结果/运行手册/CI/计划同步、源码指纹验证、独立只读审查与Git交付。

## 验收 C19-01～08

01 配置非法/历史串流/来源不符拒绝；02 event time边界、乱序/迟到、重复/冲突和类目冻结；03 原子整窗替换、旧版/同版/绝对过期与空窗口；04 实际Flink+Kafka+Redis checkpoint后终止/恢复无重复；05 恢复在checkpoint之后已写Redis窗口不倒退；06 Redis故障后有界重启与丢投影重建；07 容量上限、checkpoint不兼容与运行参数；08 旧入口/PG/shade资源回归。

恢复/回滚：停止新作业，保留checkpoint与独占namespace；旧输出未触及，可独立恢复旧演示入口。任何真实读取切换另行批准。正式生产QPS、RTO/RPO、跨主机存储与Redis Cluster未验收。

机制依据：[Flink 1.20 checkpoint](https://nightlies.apache.org/flink/flink-docs-release-1.20/docs/dev/datastream/fault-tolerance/checkpointing/)、[savepoint与UID](https://nightlies.apache.org/flink/flink-docs-release-1.20/docs/ops/state/savepoints/)。本机还须以真实恢复结果验证，文档不替代证据。
