# ER-S13A：账户预算日一致对账快照

状态：DONE / PASS。实现原S13的只读基础片；父S13仍IN_PROGRESS，持久化任务B及受控续办/RPC/offline C均未被本片替代。

## 实现与验证范围

同adDb、单SANDBOX/CNY广告主原预算日，REQUIRES_NEW/REPEATABLE_READ、数据库强制只读、事务与语句5秒截止。三种模式分别核验held、账本、Outbox、事件事实与日汇总；账户差额和投影延迟分开。零元按笔数和身份核验，缺账户明确标记，缺表/超时抛出未知，不伪装0。到期未释放仍计held。

SQL集中ReconciliationRepository，固定枚举选表、参数绑定；V016只追加三个账户日索引及注释，不改旧迁移。默认reconciliation-enabled=false，暂停新增收费不影响历史核查。无RPC、HTTP、定时器、新依赖或自动金额修正。

## 证据

基线b8cf737b15ef41b063c97bb4d7146df6a4168947；6源文件清单`/tmp/recsys-s13a-source-paths.json`。最终源码指纹`9f3d8e1753aa89dd580a5860c416432e14c7c5a38d1251c40dea40321059a2bb`（排序路径+NUL+内容+NUL的SHA256），验证后未改源码。

| 验证 | 结果 |
|---|---|
| ReconciliationPostgresTest，独占真实PG schema | 11项，0失败/错误/跳过；`/tmp/recsys-s13a-postgres-final.log` |
| 完整资金链路：`mvn -B -e -pl recsys-rec-engine,recsys-ad-serving,recsys-advertiser,recsys-gateway -am test -Dgroups=money-chain -Dsurefire.failIfNoSpecifiedTests=false` | 366项/68类，0失败/错误/跳过，BUILD SUCCESS；`/tmp/recsys-s13a-money.log` |
| Code Hygiene，base=b8cf737b | IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS，无阻塞；`/tmp/recsys-er-s13a-hygiene.json` |
| 用户文件保护及源码revision fence | 7项指纹不变，最终源指纹相同 |

真实PG覆盖：三族捕获/等待/到期释放、广告主和原预算日隔离、缺账户/空日、零元笔数、乱序重复投影、同金额笔数但错误事件、账户与daily偏差；实际capture插入两次查询间隙仍保持旧MVCC快照，下次查询才见新事实；数据库只读拒写、错误资源/外层事务/错误隔离拒绝；查询5秒取消、缺表与迁移漂移失败；默认关闭与三族收费开关独立。

首次专项唯一失败是测试预期的Spring异常子类不符合当前驱动实际映射，已改为精确DataAccessResourceFailureException并额外断言PSQLException SQLSTATE=57014；未放宽为任意异常，最终专项与完整回归均通过。没有本片UI变更，S12C2真实浏览器证据保持适用范围，不宣称本片新做了浏览器验证。

## 审查、限制和恢复

事务advisory已逐项核查：同DataSource，拒绝外层事务，强制只读RR，5秒截止，无RPC/MQ、资金写入或账户锁。两个新状态枚举与忽略venv中Python Status相似是不同领域/语言，不改动该环境。项目未配置统一formatter和独立Java静态分析，沿现有约定，无新工具。

observedAt只是本次观察时间，accountVersion不是全局水位；每个账户日的扫描规模仍受真实数据分布影响，超时保持未知，未证明生产容量或RTO/RPO。内部BudgetAccount保留BigDecimal；后续外部契约必须映射为金额字符串。索引扩展兼容旧程序；可停新核查并保留索引，不删账务历史。未触碰共享dev_infra或真实账户。

## Git交付

在原目录feat/sandbox-billing-reconciliation，仅stage本片6源文件及相关设计/验收/运行/进度文档，按持续授权正常提交、ff合并和push origin/main；实际SHA与远程CI由交付后进度记录，不预写成功。用户7项文件、console测试输出、忽略的环境/数据/模型/venv均保留；唯一原工作树，无清理操作。

下一步S13B冻结持久化任务、检查点、租约、取消和重试语义，再实现并真实PG验证；随后S13C受控故障续办与offline入口。
