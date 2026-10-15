# MP13b6：广告模拟大批次

DONE/PASS。8源文件指纹`64f742e08116c22b7f449431a698a83117abbfd556131633d3e540e38e8730b3`，清单`/tmp/recsys-mp13b6-source-paths.json`。

SimAdEventsJob的裸MAX与有序active创意扫描进入sharded AdSimulationCatalogMapper/XML，三类事件单行写语句进入addb AdSimulationMapper/XML。具名Impression/Action替代Object数组，原随机/延迟/UUID/NULL归因与日志算法保持。AdSimulationWriter按原曝光、点击、转化顺序独立执行三个JDBC逻辑批次，空列表不取得连接。

## 为什么使用独立BATCH会话

原默认20000条曝光若改一个foreach VALUES会超过PG单句绑定参数上限；若随意按1000条提交或加整作业事务会改变原失败边界。真实旧路径在第15000行CHECK失败时已提交14847条曝光，剩余曝光与后续点击/转化未提交；不能把原大批次描述为天然原子。此数量是冻结pgJDBC 42.7.3与该夹具下的实测边界，不是新增业务批大小，也不是对所有数据库/驱动/配置的承诺。

新增轻量SpringMapperBatches复用实际owner factory，仅接受Mapper回调；独立ExecutorType.BATCH会话逐条绑定，每个原逻辑批次一次flushStatements，try-with-resources关闭。没有SQL字符串入口、没有新事务/主动commit/连接池/依赖，也不改现有SIMPLE事务会话的executor。强制原SpringManagedTransactionFactory，沿用实际连接autoCommit/Spring事务归属，并在边界用MyBatisExceptionTranslator保留Spring数据异常分类。

依据冻结版本[pgJDBC执行器](https://raw.githubusercontent.com/pgjdbc/pgjdbc/REL42.7.3/pgjdbc/src/main/java/org/postgresql/core/v3/QueryExecutorImpl.java)、[MyBatis BatchExecutor](https://raw.githubusercontent.com/mybatis/mybatis-3/mybatis-3.5.19/src/main/java/org/apache/ibatis/executor/BatchExecutor.java)、[SpringManagedTransaction](https://raw.githubusercontent.com/mybatis/spring/mybatis-spring-3.0.5/src/main/java/org/mybatis/spring/transaction/SpringManagedTransaction.java)，提交/释放行为由原连接资源决定；该选择经下方真实故障路径验证，不仅依赖文档推断。

## 验证

- 旧真实双PG分片/独占广告schema6项通过（`/tmp/recsys-mp13b6-before-probe.log`），测得14847条部分提交后将精确断言加入保护，再跑旧6项全部通过（`/tmp/recsys-mp13b6-before-final.log`）。
- 新专项7项通过（`/tmp/recsys-mp13b6-after.log`）：默认20000曝光与关联点击/延迟转化、NULL列/创意归属、空分片MAX和零请求、缺创意表原降级、曝光晚失败精确保留14847条、CLICK失败保留5000条曝光、连接全部释放、外层事务整体回滚。额外验证外层已有SIMPLE会话后批处理可继续读取并提交；回滚路径也检查SIMPLE会话继续可用。
- 最终全离线98项/21类全部通过，0失败/错误/跳过（`/tmp/recsys-mp13b6-offline.log`）；全仓24 reactor测试编译通过（`/tmp/recsys-mp13b6-compile.log`），包括共享持久化模块及Flink消费者编译。
- hygiene无阻塞、无发现（`/tmp/recsys-mp13b6-hygiene.json`）。人工核对原DataSource身份、SpringManagedTransactionFactory、无主动commit和资源关闭；Java formatter/独立静态分析未配置的既有限制保持。
- 45历史SQL和8保护文件摘要保持，不触碰共享库；原批次不原子与全量物化均是本次保持的历史行为，未作为框架迁移顺手治理。

abe65238的CI38747583977五门禁全部SUCCESS；本片提交后新CI另行核验。继续SimRecEventsJob等其他大批次路径，复用已验证基础但各自补原业务保护。
