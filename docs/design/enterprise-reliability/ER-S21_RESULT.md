# ER-S21 验收结果

2026-10-18：本地DONE / PASS，52项零失败/错误/跳过。只完成原F19非关键shadow部分，默认保持关闭；无部署、无关键事件路径改写。契约见[C21_DESIGN](C21_DESIGN.md)，配置/指标/处置见[C21_RUNBOOK](C21_RUNBOOK.md)。

| 验收 | 实际结果 |
|---|---|
| C21-01 有界过载与混合流量 | ShadowResourceTest中worker真实进入慢模型后200个过载样本拒绝；ShadowRecommendIntegrationTest真实PG/Redis下201次影子提交，50次推荐HTTP全成功，P95=21ms/P99=22ms，shadow实际active=1、queue≤1，250条曝光全部落库 |
| C21-02 期限/取消/恢复 | 同资源测试：队列超时真实移除，不响应interrupt的worker仍占user/global票，重复user拒绝；迟到无success/overlap，实际退出释放并可再次执行；关闭中断/清队列且有界等待 |
| C21-03 快照/上下文/失败 | 5项资源测试覆盖候选修改后快照不变、真实Observation/MDC传入及后续复用恢复、禁用/零采样/同策略不调用、超限/非法输入拒绝、worker异常不落到调用线程、非法预算拒绝；原overlap 4项回归 |
| C21-04 主请求与关键事件 | 上述HTTP测试4项（含继承的3项原推荐回归）；NaturalDeliveryPostgresTest 10、NaturalReceiptPostgresTest 17、NaturalEventsPostgresTest 11、ExposureLoggerTest 1，验证真实PG冻结/原子事实-Outbox/并发重放/回滚等既有合同。可靠事件回归独立执行；混合负载中直接验证的是250条旧曝光落库 |

实际命令：`mvn -pl recsys-rec-engine,recsys-behavior -am test -Dtest=ShadowResourceTest,ShadowRankRunnerTest,ShadowRecommendIntegrationTest,NaturalDeliveryPostgresTest,NaturalReceiptPostgresTest,NaturalEventsPostgresTest,ExposureLoggerTest -Dsurefire.failIfNoSpecifiedTests=false`。本机原日志、XML摘要和源码指纹位于`.dev/er-s19-s21/{s21-final-gate.log,S21_TEST_RESULT.json,s21-sources.json}`。CI扩展recall-resilience-gate到engine，覆盖真实HTTP混合负载；本地PASS不等于远程全绿。

同Agent分离只读复核检查实际执行生命周期、提交/超时/关闭竞态、输入冻结、定时资源、上下文恢复、固定指标标签和关键事件调用边界。实现时已把登记和提交放到同一票的短临界区，防止维护扫描在Future赋值前释放票；worker启动前即超时也记录终态。既有ExposureLogger、自然回执/Outbox、资金和迁移代码未改。

Hygiene：IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS，无阻断；缺统一Java formatter和已配置静态分析器，以编译、diff及人工检查补充，不声称已运行不存在的工具。无新依赖/服务/中间件。独立worker/队列只隔离执行资源，CPU/模型/Redis/DB仍共享；不响应中断的模型可能持续占槽。50请求和模拟慢模型的隔离测试不构成生产负载或任意原生模型性能承诺，生产不满足主链路预算时应保持关闭。
