# ER-S20 验收结果

2026-10-18：本地DONE / PASS。范围为原F19主召回和只读缓存故障保护，详见[C20_DESIGN](C20_DESIGN.md)、[运行手册](C20_RUNBOOK.md)。未部署、未引入新服务或中间件。

| 验收 | 结果 |
|---|---|
| C20-01/02 deadline与资源 | RecallResourceTest 5项：3慢路不累加等待，已完成HOT保留；热点100请求拒绝、正常200请求成功；慢池实际active=2/queue=0；取消运行中调用仍占user槽，取消排队立即移除，最终释放恢复；串行总预算/interrupt保留 |
| C20-03 预取 | 同测试：慢预取仅查一次，超时后I2I收到空种子，不在通道重新扇出 |
| C20-04 Redis与回源 | HotReadBudgetRedisTest真实Redis暂停，32并发+20正常请求，DB实际load=1、峰值=1；成功快照复用，恢复Redis读回新值 |
| C20-05 特征 | FeatureReadRedisTest 3项真实Redis：24请求各50IDs，故障期间实际pipeline=2、峰值IO=2，逐项fallback=0；正常批量/去重、超限拒绝、恢复、写异常仍传播、关闭拒绝 |
| C20-06 上下文 | execution.RecallContextTest：真实Micrometer Observation/MDC传播、嵌套异常恢复、后续复用无线程串号，Spring事务资源未跨线程 |
| C20-07 回归 | 默认归一化/RRF/来源优先级验证；PG召回输入5、关键词2、向量4、语义5、模型查询3、R9 2、实时namespace2、公共RecallContext2、FeatureAssembler7、真实PG/Redis推荐HTTP3。共45项零失败/错误/跳过 |

最新热点混合负载实测正常用户P95=174µs、P99=200µs（单实例、测试空计算通道，非生产延迟承诺）；80ms测试预算含慢调用等待，断言允许调度余量250ms。回源与feature故障等待分别验证不超过350ms余量；实测证明资源和请求次数受限，不推导线上容量。

最终受影响测试命令：`mvn -pl recsys-rec-engine -am test -Dtest=RecallResourceTest,RecallContextTest,HotReadBudgetRedisTest,FeatureReadRedisTest,RealtimeProjectionRedisTest,FeatureAssemblerTest,PreRankServiceTest,RecommendIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false`，融合补验使用`-pl recsys-recall -Dtest=RecallResourceTest`。PG/通道回归在同版相关通道源码上独立完成，命令及XML明细见本机`.dev/er-s19-s21/S20_TEST_RESULT.json`。CI新增recall-resilience-gate，不能以本地成功代替远程全绿。

分离只读复核确认：池/队列/准入有界，副作用写入不进入可丢弃读池；MDC/Observation恢复，不注册事务accessor；没有借本片重做架构。修正晚预取后仍开启第二查询、feature deadline未含提交开销、重复通道注册及指标缺口。测试曾在Redis fixture握手失败；完整复跑真实Redis和HTTP通过，原因未确证，不归咎产品。取消测试原用activeCount作为业务进入信号产生竞态，改为业务入口latch；融合测试改用能容纳两条成功通道的队列，过载断言保留于独立用例。

Hygiene为IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS，无阻断：仓库缺统一Java formatter和配置的静态分析器，沿周边格式、diff检查、编译和人工复核。新增context-propagation1.1.1沿既有Tracing/Core版本；未升级平台。单进程边界不隔离物理CPU/共享Redis/DB，原生模型不响应中断时仍占固定线程，生产负载SLO未验证。
