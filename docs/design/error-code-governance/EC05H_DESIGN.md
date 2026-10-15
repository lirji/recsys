# EC05h 共享来源参数与剩余CLI入口

基线2717c95687e5629901de620d2702b69ec6d9dd49。范围BehaviorQuery/ItemQuery/AdEventQuery、三Sync作业的source-db解析、SimAdUpliftData参数、公共目录和对应既有测试。复用JobArguments/JobRunner，不新增参数框架、SQL、schema、依赖或事务。

三个来源参数仍只接受原有owner/replica封闭集合，默认值保持，固定XML分支不变。参数缺值/多值/空白统一JOB_INVALID_ARGUMENT；未知来源有各自安全码，列出可用值但不拼入输入。BehaviorSource/AdEventSource内部枚举解析保留，CLI边界转换异常；ItemQuery明确switch。max-ts改复用完整long解析，缺省null、负值/0与已有截止语义保持，不引入精度损失。

三Sync目前在ensureTable/--full清除之后才解析source-db，裸参数会导致已清除后失败。结构校验移动到任何DDL/清除之前，显式空白/多值/裸参数拒绝；未提供仍走同库，合法单值仍走原跨库。真实源连接/读取或批次写失败沿原时机传播，不宣称已回滚旧清除或多批提交，不新增重试。

SimAdUpliftData复用严格文本/int解析，负users安全失败。users=0保留既有无副作用no-op，不能机械当所有空输入都是错误。完整默认5000用户/随机序列/双批次写入/部分事实失败/外层事务语义保持；run-id显式缺值不再默默采用默认值。

以新共享CLI测试验证确切码/默认/合法源/裸空多值/未知输入/Long.MAX_VALUE，原PG/Redis/CSV测试更新错误类型但保留全部输出和副作用断言。三Sync增加真实旧读模型在full+坏参数下保持、后续合法重跑；全offline及依赖回归、前端生成目录、24compile、守卫/hygiene后正常Git交付。按现有授权直接执行；EC06广告计算发布、EC07/08继续。
