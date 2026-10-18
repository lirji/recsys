# C20 召回资源预算运行手册

配置默认即生效，不需要新增基础设施；本轮未部署。阈值均为单进程技术初值，副本数需计入下游总预算。配置通过现有Spring Boot配置源提供，重启后生效，不在线调整池参数。

| 前缀 | 参数与默认值 |
|---|---|
| `recsys.recall.parallel` | total-timeout-ms=150，timeout-ms=150，prefetch-timeout-ms=40，pool-size=12，queue-capacity=24，fast-pool-size=6，fast-queue-capacity=12，prefetch-pool-size=2，prefetch-queue-capacity=4，max-requests=128，max-results-per-channel=1000 |
| `recsys.recall.cache-budget` | redis-threads=2，redis-wait-ms=40，database-wait-ms=40，failure-cooldown-ms=500，snapshot-ttl-ms=1000 |
| `recsys.feature.read` | threads=4，timeout-ms=100，max-batch-size=1024 |

`enabled=false`仍使用有界池串行运行；`bulkhead-enabled=false`共用慢池且仍有界。默认保留快慢隔离。一个用户已有在途子任务时新请求返回空候选；预取超时填空种子，特征批量故障填空特征，调用方沿现有降级处理。HOT回源快照只供公开候选，最多复用1秒，不能用作权限/资金依据。写特征不进入此读降级策略。

观测现有Actuator/Micrometer：`recsys.recall.duration`、`recsys.recall.execution{outcome}`、`recsys.recall.requests.active`，`recsys.executor.active/queued/queue.wait/run.duration{pool}`，`recsys.recall.hot{outcome}`、`recsys.feature.read{outcome}`及`duration/execution`。标签只有固定池/结果类型，不能加userId、itemId、异常文本。

排查：先比较活跃线程、排队和超时/拒绝，再看Redis/DB驱动连接/执行时延。取消Future只缩短等待；active长期不降说明底层未退出，不应通过扩大池/立即重试掩盖故障。热门DB在途只有一条，其他等待共享结果；检查database_load/coalesced/snapshot_hit，不应随请求数线性放大。恢复依赖后应重新出现正常结果且active回落。HOT冷却结束自动试读，成功快照过期自动刷新。

变更预算前在隔离环境重跑`recall-resilience`标签并记录正常用户P95/P99、热点拒绝、Redis pipeline/DB回源实际次数和最大活跃数。未验证生产峰值QPS，不把150ms当整个推荐HTTP链路SLO。关闭时拒绝新任务、取消排队并有界等待；非响应中断的原生计算/驱动仍占固定资源直至结束。

回退本片代码/配置不触及数据库与事件。回退后会恢复旧的无界排队/逐项重试行为，需同步评估负载；不能声称代码回退撤销了已发生业务效果。曝光、计费、Outbox不属于可丢弃读负载。
