# ER-S18 验收结果

2026-10-18 本地实现、分离验收和只读复审 **DONE / PASS**。19项真实双PG专项，另30项作业/会话/行为同步回归，共49项、零失败/错误/跳过。未在共享环境安装、同步、清空或部署。

## 可追溯验收

| 验收 | 实际观察 |
| --- | --- |
| C18-01 | 同库及两个独立PG容器：小ID事务先插入并保持未提交，大ID被同步后再提交小ID，下一核对轮两行全部纳入；原16列逐行比对一致 |
| C18-02 | 1002行只准一页时提交1000行并保留READY/cursor；新实例续办原冻结上界，新高ID留到下一轮；Long.MAX_VALUE不取整、不溢出 |
| C18-03 | 第二页真实约束错误使该页新增和游标一起回滚，首1000行保留；三次/跨重启预算最终PAUSED；真实源容器暂停后超时、目标预算保留，恢复完成 |
| C18-04 | 两线程同时领取只有一个成功；过期新fence拒绝旧提交；第三次租约到期持久PAUSED；独立JVM halt(18)在1000行页提交后退出，接管续至1002行 |
| C18-05 | 原ID同内容幂等；内容变化整页回滚并记录冲突ID；错源/源表OID变化拒绝；源连接关闭，目标写事务不跨源库 |
| C18-06 | resume/full稳定命令重放不再次清预算或清空；full页失败后保留原重建epoch续办；参数/外层事务在副作用前拒绝；源行约束原文不进入CLI异常链 |
| C18-07 | 迁移重复安装、摘要核验和全部新表列注释PASS；作业公开错误、CLI、会话绑定、既有行为同步回归PASS；SQL集中XML、显式code、无新增依赖 |

命令：`mvn -B -pl recsys-offline -am test '-Dtest=SyncAdEventLogPostgresTest,SyncBehaviorLogPostgresTest,Job*Test,Offline*Test' -Dsurefire.failIfNoSpecifiedTests=false`。最终日志 `.dev/er-s17-s18/s18-gate-release.log`，结构化验收 `S18_TEST_RESULT.json`，源码清单 `s18-sources.json`。源码指纹 `125cdaffc1dc4812d1709df73aef6a340a9bb4e05655af87e9ecdf7968a38184`（含本片CI变更）。目录CI门禁加入SyncAdEventLogPostgresTest。两应用定向package通过（`package-final.log`），JAR中迁移、Mapper XML及S17 Lua资源存在。

## 复审、兼容与边界

同一Agent分开实现、执行验证与只读复核；没有改低ID迟到、原子性或故障断言以掩盖失败。第一次18项PASS，补充完整long/非正ID与日志原文保护后，最终19项专项及30项相关回归PASS。旧“低ID只能full恢复”与“外层事务覆盖整个作业”的测试按正式C18新契约替换为更强补洞/副作用前拒绝断言，保留16列和1000行失败原子性断言。

新source-id必填；full增加稳定审计命令、actor/reason；外层事务不再受支持。普通同步不TRUNCATE、不覆盖历史。扫描上界只封顶一轮，完成后下一轮从零核对。源需追加不变且保留；表内人为清空/复用ID无法仅靠表身份识别，不能声称CDC或源历史全丢失恢复。

调用时间预算在页边界检查，在途页按既有连接与查询超时完成；每页最多1000行，不声明单行内容字节上限或线上容量/SLA。Hygiene为IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS：仓库未配置formatter/static-analysis；固定预算、内部原因枚举及单库事务人工复核，无阻断Finding。XML解析、CI YAML语法、diff与保护指纹已核对。

原用户12项文件保持（CODEX_PROGRESS只添加本任务前缀，原文后缀不变）；S17源码/已发布V001未改，CI仅追加S18测试。实际Git交付与CI见 `.dev/er-s17-s18/delivery.json`、checkpoint。启动/恢复/回退见 [C18_RUNBOOK](C18_RUNBOOK.md)。
