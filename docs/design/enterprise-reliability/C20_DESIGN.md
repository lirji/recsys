# C20 主召回deadline、资源和故障预算

2026-10-18：用户授权原ER-S20（F19），依赖D0满足。保留当前14路召回/归一化/RRF/排序与服务边界；不换栈，不改资金、事件交付或认证。

## 决定与预算

- 现状：固定线程池背后无界队列、各future顺序等待完整timeout、种子预取同步不计超时；Redis feature pipeline失败后N次逐读；HotRecaller两次Redis失败后每请求直接查DB。
- 采用单个monotonic deadline，默认沿现有150ms，包含预取/排队/通道等待；完成结果按原顺序融合。单路timeout不重置总预算。总deadline是召回依赖等待预算，结果融合只处理最多14×1000候选，机器调度/GC不是硬实时保证。
- 慢池12/队列24，快池6/队列12，预取池2/队列4；均显式拒绝，取消从队列移除、传播interrupt，运行中不响应中断的任务继续占实际配额至退出。false串行开关也在有界池内执行，不能绕回请求线程无限等待；false bulkhead共用有界慢池，默认仍隔离。
- 预取阶段最多40ms，失败/超时把seeds/sequence填空，禁止各通道再扇出自查。独立全局最多128个在途请求，一个user最多1个；子任务真实退出前仍保留该user槽，防止热点重试填满队列。不同user共享总上限；不承诺任意多主体过载都成功。
- 热门专用Redis读2线程/无队列，单次等待40ms、失败/超时冷却500ms；DB回源1线程/无队列，单飞合并、等待40ms，成功快照最多复用1000ms。调用返回空/部分候选沿既有降级契约，不无限重试。结果是公开候选，不用于权限/资金判断；下游内容读取继续校验实际可用内容。
- 特征读取4线程/无队列，每次100ms，批量最多1024 distinct IDs；故障直接降为空特征，不逐个回源Redis。写特征仍保留原同步错误语义，不进入可丢弃读池。
- 上述为每进程可配置技术初值（测试会采用小预算放大竞争），副本数乘以上限才是全局预算。未有业务峰值QPS/生产P99承诺，不编造SLO。

## 生命周期、上下文与观测

Spring管理资源对象与关闭，线程命名、拒绝/超时/失败/在途/队列/耗时指标明确；关闭先拒绝新任务，再取消排队并中断在途，有界等待。业务参数以不可变快照显式传入，不新增业务ThreadLocal；没有新自定义TL，因此不为了偏好额外安装TTL。已有Micrometer Observation/Tracing通过ContextSnapshot传播并恢复，MDC独立保存/恢复；不能复制Spring事务资源。只增加沿现有Tracing/Core传递版本钉定的context-propagation1.1.1与Boot BOM管理的micrometer-core直接依赖，不升级既有平台。

## 文件与步骤

1. RecallProperties/新的RecallExecution：验证资源参数、请求准入、真实子任务生命周期、context/metrics。
2. MultiChannelRecallService：统一deadline、预取受控、取消与合并、保留解释结果和RRF语义。
3. HotRecaller及专用cache/fallback资源；RedisFeatureService批量/单读受控；配置有安全默认值。
4. 真实Redis正常/暂停、热点用户和其他用户混合负载、慢通道不响应中断、队列饱和、串行模式、取消清理/恢复、上下文/事务隔离与既有召回回归；读负载不得放弃关键事件。
5. 结果/运行手册、CI和计划同步，分离只读复核、Hygiene、提交合并push后继续S21。

## 验收 C20-01～07

01 统一deadline不随通道数累加，完成的HOT结果仍返回；02 pool/queue/admission有界且一个热点user不能持续占新槽；03 慢预取/失败无下游重复查种子；04 真实Redis故障下单飞/回源并发有上限且正常/恢复流量可用；05 feature批量故障无N次逐读，过载/超时有界；06 MDC/Observation跨线程传播且无串号/事务传播，取消/退出有真实指标；07 原融合/配置/读取契约回归与实测负载记录。

回滚代码/配置仅改变只读候选降级策略；不删除数据、不撤销已发生业务效果。负载证据限定单实例隔离环境；CPU密集原生模型无法强制终止，已通过池/准入上限限制其剩余影响，不把Future取消写成底层已停止。

参考：[Micrometer 1.12 context propagation](https://docs.micrometer.io/micrometer/reference/1.12/contextpropagation/purpose.html)。
