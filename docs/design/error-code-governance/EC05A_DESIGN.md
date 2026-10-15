# EC05a 作业状态生产、消费与失败传播

基线d3f15c62fd847e67c46d0f4a0a391e635d903b1f，接续父EC04 SystemOps及EC05。现有DagJob吞子作业异常，JobRunner误打印完成；Redis记录e.getMessage并被SystemOps原样公开，页面又把不可用显示成默认配置/无记录。按已授权错误治理修复，不更改SQL/业务作业算法或新建调度平台。

common增加纯Java作业状态codec及显式状态code枚举，沿用status@yyyy-MM-dd HH:mm:ss[:code]。旧ok/fail/skipped/running/success/failed/error接受且保留稳定编码，旧自由detail丢弃并按状态映射安全码；坏状态/日期显示UNKNOWN与JOB_STATUS_INVALID，不回显raw。对外raw字段保留但仅重建受控值。新code仅允许JOB_目录，其他目录或任意文本不能借状态详情展示。共享RedisKeys收敛job状态前缀。

JobRunner先验证全部job选项，未知作业不在已运行部分后才发现；异常归一为受控PublicErrorException并传播，原内部cause保留诊断。DagJob参数/未知目标/循环/占锁不再return假成功；dry-run也先验证已知作业。子作业失败记fail、下游skipped后向上传播。Redis缺依赖或取锁异常在执行前拒绝，不再无锁运行；状态写入失败停止下游，明确已完成的业务效果可能存在，不重试作业或宣称回滚。状态与业务效果本来不原子，本片只如实报告。原锁6小时TTL、无续租及释放实现暂不改，不宣称解决长作业过期和旧持有者删除新锁；该历史并发问题留独立可靠性范围。

SystemOps故障保持200对象但带CONSOLE_OPS_UNAVAILABLE、安全文案，页面不显示“默认配置”或“无记录”；可用的真实空仍沿用空态。已知状态显示固定标签/颜色；详情从本地目录选择，兼容旧响应但不显示任意历史文本。单个裸作业当前不写Redis状态，空态文案纠正为run-dag后产生，不借此新增调度器。

验证：codec旧/新/坏记录、真实Redis下DAG成功/失败/下游停止/锁占用/断连/状态写失败、JobRunner参数预检及失败传播、SystemOps故障和安全JSON；React页面未知/空/安全详情/旧版本。只用独占容器。当前各业务Job内部吞错、无数据return以及EC06计算/发布问题仍需后续逐项修复，不能以分发器归一化声称EC05父完成。streaming类目失败在后续片处理。
