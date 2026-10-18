# C19 实时特征启动、核对与恢复

本片默认不启用；既有 `run-streaming.sh` 无 `--reliable true` 时仍是旧演示管线。新管线写独占namespace，读取切换另行执行。不得让两个作业共享namespace；运行凭据从受控环境变量提供，不写文档或命令历史。

## 启动与配置

```bash
export RT_RELIABLE=true
export RT_PROJECTION_ID='<稳定UUID>'
export RT_GROUP='<本投影独占consumer-group>'
export RT_NAMESPACE='<本投影独占Redis前缀>'
export RT_CHECKPOINT_DIR='file:///持久磁盘/recsys/checkpoints/本投影'
# LIVE才填写以下三项；旧格式保持RT_LIVE=false并指定原topic。
export RT_LIVE=true
export BEHAVIOR_TOPIC='behavior-accepted-v1.隔离后缀'
export RT_SOURCE_ID='<行为owner已登记的source UUID>'
# KAFKA_SERVERS、REDIS_HOST、REDIS_PORT、PG_URL、PG_USER、PG_PASSWORD沿现有受控环境。
# PG_URL使用connectTimeout=5&socketTimeout=10限制目录启动加载；不能指向陌生库。
bash recsys-streaming/run-streaming.sh
```

首次从Kafka最早保留位置重建，不能从一个旧组的committed位点启动空窗口。默认窗口600000ms/步长20000ms/水位乱序30000ms/数据新鲜度120000ms/checkpoint10000ms/max-events100000；环境为 `RT_WINDOW_MS/RT_SLIDE_MS/RT_DISORDER_MS/RT_FRESHNESS_MS/RT_CHECKPOINT_MS/RT_MAX_EVENTS`。freshness必须大于乱序+步长，slide<30000ms、最多100个重叠窗口；这些是受限运行初值，不是生产容量指标。

构建：`mvn -pl recsys-streaming -am verify`；启动脚本使用PATH中的mvn或显式MVN，保留JDK21所需add-opens。Checkpoint存储必填；file URI只适用于同盘恢复，多机器需已批准且所有运行节点可访问的持久存储。没有注册新的远程存储组件。

## 输出与读取核对

- `${RT_NAMESPACE}:hot`：完整窗口item→权重；`:user:<userId>`：完整窗口类目→次数。
- `:control`：identity/end/digest；`:users`：本窗口用户索引；`:lease`：执行器token。
- 数据按windowEnd+freshness绝对过期；control保留，以防旧窗口回放覆盖新值。不可只凭Kafka lag=0宣称特征完整。
- 读取方engine（HOT/TAG）/console配置 `recsys.streaming.namespace`，环境形式 `RECSYS_STREAMING_NAMESPACE`，空值保持旧读取。切换前在隔离数据核对窗口分数、类目计数和过期；默认配置未改，当前共享环境未切换。
- Flink算子指标late/duplicate/snapshot_applied，结合checkpoint成功/失败、restart状态和Kafka积压观察；不使用userId作为指标标签。超过容量、同身份内容冲突、坏输入或Redis写失败会失败关闭，自动重启最多3次、间隔30秒。

## 重启与故障恢复

1. 核对原projection/source/topic/group、Kafka cluster/topic ID、namespace、窗口参数与PG目录基线；保存最近成功checkpoint的目录与全部数据文件，不能只拷贝`_metadata`。
2. 停止原作业，确认没有其他writer。若进程异常退出导致租约残留，等待其60秒上限，不能删除未知活跃writer租约。
3. 使用 `RT_RESTORE='file:///.../<job>/chk-N'`（或`--restore`）显式恢复；严禁忽略unclaimed状态。身份或参数不兼容拒绝恢复。目录类别来自checkpoint，不会被新PG类别改写。
4. 核对checkpoint source offsets仍在Kafka保留区间；topic重建/保留缺口应停止并用新投影重建及核对。旧Flink/source状态格式不兼容，不支持把旧演示作业直接当新状态恢复。
5. Redis仅丢输出键：心跳从最新完整窗口重建且不延长有效期。Redis不可达：异常触发Flink受限重启，恢复checkpoint。Redis连元数据都丢失：先停所有writer，确认唯一恢复者再恢复；跨灾难不宣称exactly-once。
6. 坏事件/身份冲突/容量超限：检查来源与窗口配置，不自动跳offset或伪装成功；需要修复输入或新投影重建时保留原Kafka/检查点证据。历史流不允许伪装成实时事件。

回退：停止新作业，读取namespace设回空，保留新namespace与checkpoint供核查；不自动清理Redis、Kafka或checkpoint。没有进行生产切换、RTO/RPO承诺、跨节点丢盘或Redis Cluster验证。
