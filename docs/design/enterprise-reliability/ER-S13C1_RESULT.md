# ER-S13C1 验收结果

2026-10-16，PASS。基线600989e7；范围为C13C内部单目标受控恢复，C2可信RPC/offline尚未完成，父S13/C保持IN_PROGRESS。

## 实现与证据

- 新增V018不可变恢复命令/结果及审计、同adDb五秒事务、三族原事件校验后续发和指定原账户日到期释放。命令幂等、内容冲突与跨账户隔离由持久化结果约束；不改账本/事实，不创建新收费事件。
- 专项`BillingRecoveryPostgresKafkaTest`：7项，失败/错误/跳过均0；独占PostgreSQL16及Kafka3.7.1。日志`/tmp/recsys-s13c1-test.log`。
- 全资金链：443项/76类，失败/错误/跳过均0，命令`mvn -o -B -pl recsys-rec-engine,recsys-ad-serving,recsys-advertiser,recsys-gateway -am test -Dgroups=money-chain -Dsurefire.failIfNoSpecifiedTests=false`；日志`/tmp/recsys-s13c1-money.log`。
- 三族真实broker不可达形成DEAD→恢复原payload/key/event→真实Kafka发送→重复投影→重新inspect追平；PUBLISHED等待窗口与READY/SENDING不能抢占；坏fact返回NEEDS_REVIEW。
- 四线程同命令只推进一次fence、原ID丢响应查询、跨内容/账户冲突；目标跨账户/日期不可见；命令历史不可删除。
- 三族未到期不释放、已捕获不释放、到期仅一次；命令完成前注入失败证明重新排队及到期资金释放均与命令结果整体回滚。
- 首轮专项6项有1ERROR：PUBLISHED→READY保留旧published_at违反既有状态约束。修复为清空传输时间，保持原事件不变，未放宽DB约束；新增断言及第7项已捕获测试后专项与回归通过。
- 持久化边界617 production Java/92 XML/45 historical SQL通过；公开错误与diff检查通过。源文件11项，指纹`b59bd76859eb4cdac55dca8e1713d33a3c7c614e65a8b2ae5a54b2914d9e0e27`，清单`/tmp/recsys-s13c1-sources.json`，验证后未改源码。

## 独立复核与限制

按C13C逐条核对目标锁/原账户锁序、DB时间、原Outbox载荷/账本身份校验、数据源对象相同与事务回滚；同步短事务不调用Kafka，发布留给原relay。hygiene为IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS、无阻断；三项事务ADVISORY已由专项和源码复核覆盖。仓库无统一Java formatter、无独立Java静态分析，未伪造执行。日志`/tmp/recsys-s13c1-hygiene.json`。

默认关闭`reconciliation-recovery-enabled`，无共享环境写入/部署；未验证生产容量，C2协议权限/TCP/offline留待下一批。回滚关闭新入口，保留原Outbox排空/到期能力及不可变记录；不删除已完成业务效果。七项用户已有文件指纹不变，console/test-results及数据/env保留；原目录唯一工作树，无可自动清理目录。
