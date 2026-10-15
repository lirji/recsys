# MP14：Flink非Boot类目读取

DONE/PASS。8源指纹 `c207f773be7a48405a128dd3a1c7e6d32094b097559496747bc3eb878d018fc8`，清单 `/tmp/recsys-mp14-source-paths.json`。

RealtimeFeatureJob.CategoryEnricher的最后一处生产业务Java SQL迁入ItemCategoryMapper/XML。ItemCategoryLoader负责非Boot工厂、原无池连接参数及一次性自动提交会话，作用域局限open；算子仅保留原String配置和transient类目Map，不持有factory/session/连接。不新增Repository透传层，不调整Kafka/Redis或窗口计算。

- 旧实现5项真实PG保护通过；初次测试误认为空JSON返回null，实际Jackson MissingNode产生空事件并被原filter排除，修正旧预期后通过。生产解析逻辑未改。
- 新路径6项全部通过：NULL ID→0、NULL/空类目、超过2^53及Long.MAX_VALUE、重复后写覆盖、缺失项、空表、reopen替换快照、缺表捕获、序列化前后重开、原正反馈/权重行为。ResultHandler第三行故障保留前两行并释放会话，真实pg_stat_activity有界观察无残留连接。
- `mvn -B -pl recsys-streaming -am -Dtest=CategoryEnricherPostgresTest -Dsurefire.failIfNoSpecifiedTests=false verify`通过；package后Failsafe启动独立JVM，classpath只有最终fat jar和探针test-classes。真实PG查询通过，生产类确实来自jar，XML及PostgreSQL驱动SPI可用，Spring ApplicationContext不存在仍成功；Flink connector base亦可加载。该项1测试，0失败/错误/跳过。
- XML COALESCE仅还原旧ResultSet.getLong(NULL)=0，不增加去重/排序/类目过滤；typed long记录与回调保留原逐行更新语义。
- Hygiene最终IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS，无阻塞。首次缺少已批准依赖标记产生5项提示；按PLAN MP14和TECH_SELECTION记录传入已有persistence、BOM测试依赖及同Surefire版本Failsafe后通过。统一formatter和独立Java静态分析未配置。
- 历史45 SQL和保护文件字节保持。无Spring/Flink/PG升级，无共享库操作。

日志 `/tmp/recsys-mp14-{before,before-final,after}.log`、`/tmp/recsys-mp14-hygiene-final.json`。MP15全仓clean verify正在执行，不能将该片测试称为全仓最终验收；统一错误码/魔法值仍在后续目标内。
