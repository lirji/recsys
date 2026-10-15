# 共用正反馈可空ID兼容修复

DONE/PASS（本地）。2源文件指纹`cb6df4172efdd79e60db503cd869b218b881cf984ebda0f37dd96fb1530d32ce`；清单`/tmp/recsys-offline-null-id-source-paths.json`。

检查MP13b7的历史输入发现user_behavior.user_id/item_id允许NULL。原ResultSet.getLong(NULL)返回0，MP13a2的primitive record映射却会对全NULL投影返回null行。新增真实PG对照先跑原JDBC并确认三组0值，然后实际复现Mapper回调NPE（`/tmp/recsys-offline-null-identities-before-fix.log`），没有把该次失败算PASS。

scanPairs/scanWeighted仅对两个ID投影使用COALESCE(...,0)，保留原训练输入。来源、过滤、时间边界、顺序、重复行、权重和maxEpoch未改；不启用全局空行实例化，不修改历史数据/DDL。

- 全离线99项/21类，0失败/错误/跳过（`/tmp/recsys-offline-null-identities-after.log`）。上游依赖测试也通过，既有显式宿主按需跳过不计入这99项。
- 全仓24 reactor测试编译通过（`/tmp/recsys-offline-null-identities-compile.log`）。
- hygiene扫描对XML/测试变更判N/A，无阻塞；人工检查投影类型、参数绑定、作用域及diff。统一Java formatter/独立静态分析未配置限制保持。
- 45历史SQL、8保护文件摘要核验不变。

前序19592ca6远程CI38749329611四门禁SUCCESS、build失败于SimAdEventsPostgresTest关闭连接后瞬时pg_stat_activity仍为1；业务断言和部分提交14847断言通过。这不是本修复失败，但须独立核实并修复测试观察时序后再正常推送；不能宣称前序CI全绿。
