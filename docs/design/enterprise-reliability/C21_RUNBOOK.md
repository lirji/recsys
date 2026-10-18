# C21 影子排序运行手册

默认`recsys.shadow.enabled=false`，本轮未启用或部署。影子只提供排序比较样本，不用于返回结果或确认关键事件。先确定要对比的已加载策略，按真实模型/候选量/副本数跑混合负载，再通过现有配置源启用；未知策略丢弃并计invalid_config，不能把规则回退误作目标模型已评估。

| 配置（前缀`recsys.shadow`） | 默认 / 范围 |
|---|---|
| enabled / strategy / sample-rate / k | false / 空 / 0.1 / 10；sample-rate为有限[0,1]，k为1～100 |
| threads / queue-capacity | 1 / 8；允许1～2 / 0～32 |
| timeout-ms | 150；允许1～1000，含排队，维护扫描间隔10ms |
| max-candidates | 512；允许1～1024，超限丢弃整次影子，保持比较口径 |

池/队列/期限/候选上限在构造时冻结，改变后重启。采样沿userId确定性哈希；同用户最多一票，全局最多threads+queue票。候选与主top-K复制为只读快照，提交不等待结果、不在调用方运行慢工作。一个维护线程仅扫描有界票，队列满/关闭时不会另建任务保存待办。

Micrometer：`recsys.rank.shadow.tasks{outcome}`区分accepted、saturated、user_busy、oversize、invalid_input、invalid_config、timeout、failed、closed、success；`recsys.rank.shadow.inflight`记录尚未真正退出任务。`recsys.executor.active/queued/queue.wait/run.duration{pool="shadow-rank"}`记录真实占用；原`duration/runs/overlap{shadow}`保留，runs/overlap只包含期限内成功样本。期限是停止等待/比较的技术预算，调度或GC可能延迟取消，非硬实时保证。

处置：先看timeout与active是否持续占满，再看主HTTP P95/P99、共享feature/DB/Redis和模型时延。需要保护主链路时关闭enabled，或缩小采样/候选规模；不要通过不断加队列掩盖慢模型。关闭开关只阻止新增，已提交任务继续受原期限管理；进程关闭会取消队列、中断运行并有界等待。不响应中断的模型可能仍占worker，inflight要保留到实际退出，不能把Future取消解释为计算已停止。

独立worker和队列不能隔离物理CPU、原生模型线程及Redis/DB。真实负载下主链路预算不满足则保持关闭；本片测试不构成任意模型下的性能保证。S20读池继续限制feature故障，但它不是shadow专用物理后端。曝光、自然回执、账本和Outbox沿原链路，禁止投到本可丢弃池。

验证入口：`mvn -pl recsys-rec-engine -am test -Dgroups=recall-resilience -Dsurefire.failIfNoSpecifiedTests=false`；关键事件回归另见ER-S21_RESULT。回退先关闭shadow，无数据迁移/补偿；需要回退代码时回退本片提交，不回滚S19/S20或已接受的业务效果。
