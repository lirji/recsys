# ER-S03：sandbox CPM 预占与到期回收

日期 2026-10-15；基线 `56259727`；分支 `feat/sandbox-budget-reservation`；[契约 C03](CONTRACTS.md#c03sandbox-cpm-预占与释放er-s03)。内部用例范围 DONE / PASS；公开交付 RPC、曝光资格及捕获收费尚未接通。

## 行为

新增预占命令的首次结论与当前状态，reservationId 与 quoteId 都具有唯一约束。subjectKey 绑定查询/释放；金额、广告主、到期时间读取服务端冻结报价，预算日固定为数据库首次受理时的 Asia/Shanghai 日期。锁等待后再次检查实时时间，过期不再占用。

同 adDb 短事务领取命令、锁账户、条件增加 held 并保存结论；不足、过期、缺账户均持久化拒绝，重试不改变结论。主动释放/到期回收使用预占→账户锁序，held 减少与终态同事务；重复释放不再减钱。没有修改 charged、Redis 或旧事件，没有把预占当曝光消费。

独立单线程 fixed-delay 回收，每笔一事务，按到期索引范围和 SKIP LOCKED 领取一条，单轮最多 100 笔。失败回滚后保留待办，以有上限的指数退避加抖动续办；日志保留恢复标识，两个无高基数标签的计数器记录提交的回收及失败。关闭 admission 可暂停新增而保留恢复能力。V003 只扩展表/约束/索引，V001/V002 未改。

## 验证证据

```sh
mvn -B -pl recsys-ad-serving,recsys-advertiser -am test -Dgroups=money-chain -DfailIfNoTests=false
```

74 tests，0 failure/error/skipped，其中本片 17 项（真实 PG 12、worker/配置 5），旧钱链路 57 项回归通过。随后补充故障日志标识、把扫描截止改为语句时间后，重跑 ReservationPostgresTest / ReservationExpiryJobTest 17 项通过。日志 `/tmp/recsys-er-s03-final.log`、`/tmp/recsys-er-s03-recovery-final.log`；最终源码与报告同次交付。

| 验收 | 实测方法与结果 |
|---|---|
| 硬预算并发 | PG：12 个不同报价并发争用仅够 1 份的预算，1 HELD、11 INSUFFICIENT，held=limit；调低预算与预占竞争仍满足不变量；PASS |
| 幂等和主体 | PG：12 个同 ID 并发只占用一次；同报价换 ID/同 ID 换主体冲突；其他主体不能查询/释放；PASS |
| 原结果恢复 | PG：补预算不改变旧拒绝，释放后重试 prepare 仍返回原决策但不重占；重建 Service 能查询/释放；PASS |
| 事务原子性 | PG：账户 held 增加后结果写入失败，账户与命令全回滚；账户 held 减少后终态写失败也全回滚，解除故障后恢复；PASS |
| 到期边界 | PG：缺账户/过期稳定拒绝；通过真实账户锁及 pg_stat_activity 确认等待窗口，到期后解锁仍不能占用；提前回收不生效；PASS |
| 双执行器 | PG：两个到期执行器与主动释放同时竞争十笔，均只到一个 EXPIRED 终态，版本只增加一次，held 清零；PASS |
| 跨日 | PG 历史 fixture：原义务预算日释放归原账户，当前日账户不变；不依赖应用默认时区；PASS |
| 停止新增 | admission=false 拒绝新预占，保留已有命令恢复、查询和释放；PASS |
| DB 约束/注释 | PG 拒绝空决策的终态和缺原因的释放；全部表/列有注释；错报价数据源装配拒绝；PASS |
| worker 资源与故障 | 单轮批次上限、异常立即停止和退避、指标只计提交、默认关闭不建 scheduler；真实单线程 scheduler 可运行/停止；PASS |
| 迁移兼容 | 隔离 PG 顺序 V001/V002/V003 后运行；V001/V002 与基线无差异；不接触共享数据库；PASS |
| 文档和差异 | 本地链接/围栏/DAG、diff --check、六个用户文件哈希保护；PASS |
| Code Hygiene | IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS：根 Maven 无 formatter；未配置静态分析不冒充执行成功，无 blocking finding |

跨日用例在隔离库构造历史预算日，期限用例在隔离库构造短剩余期限；没有篡改产品时钟或等待真实五分钟。worker 单测中的领域替身只验证调度资源/退避，金额与并发证明均来自真实 PG。

## 审查及运行边界

独立验证 pass 核对全部写入影响行数、单一数据源事务、无事务内远程/MQ 调用、预占→账户统一锁序；拒绝记录无 held 副作用；查询不能将 PENDING 异常行当成功。数据库 CHECK 显式处理 NULL，避免 SQL 三值逻辑绕过终态完整性。扫描取 statement_timestamp 形成固定范围，拿到账户锁后用 clock_timestamp 判定真实到期，不能用陈旧事务起始时间接受晚请求。

运行/暂停/恢复与只读对账 SQL 已同步 [runbook](SANDBOX_BUDGET_RUNBOOK.md)。永久不变量错误保留义务并停止本轮，需要人工核查，不自动删行、改金额或制造补偿。暂停 admission 是受控重启配置，不声称瞬时多实例 fence。尚未验证完整进程 kill/restart、生产负载、RTO/RPO；这些仍属于后续故障验收。此处“重建恢复”是进程状态不参与结果的持久化用例验证。

后续 ER-S04：engine 持久化整次交付与最终条目/位置/子操作，接通纯候选和可信准备；不能把单预占幂等当成整次广告结果幂等。ER-S05 再接入回执、捕获与释放竞争、账本和 Outbox。
