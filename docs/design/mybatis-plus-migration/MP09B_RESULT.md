# MP09b：点击结算事务

本片DONE/PASS；MP09c引用核对完成，MP09整体完成。12个源文件指纹 `ab3a4accb08bc102054796cc22c98f1eefc9a334c4c2696b614bdcabce24512b`。

ClickSettlementRepository的22条SQL已进入addb ClickSettlementMapper/XML，装配及全部构造引用同步使用实际SqlSessionTemplate。原Repository保留领域对象构造、主体/摘要/PENDING检查、影响行数检查和事件编码；不引入新的连接池。

回执原决定与当前结算Snapshot继续来自同一个JOIN结果集；两个原始不可变记录通过constructor arg resultMap映射，不使用nested select，不增加数据库往返。Snapshot与Receipt都明确列名和可空字段，stored沿用先构造receipt、再校验snapshot主体的既有顺序。[MyBatis官方Mapper XML文档](https://mybatis.org/mybatis-3/sqlmap-xml.html)及本地3.5.19 JAR DTD已核对constructor arg的resultMap支持，真实PG结果仍以本片测试为准。

quote→rate→account锁序、稳定排序LIMIT 1 FOR UPDATE OF q SKIP LOCKED、按决定时刻计数、跨广告主风控、原预算日held扣减、版本/状态/余额条件更新、四份事实同事务和5秒查询超时保持。账本/Outbox写入仍严格检查影响行数；JSON事件的日期与金额协议未变。

## 验证

- 旧路径基线为MP08c资金链372项及MP09a点击/转化联合85项。
- 新增未完成回执保护：最初直接提交被既有延迟约束正确拒绝；现断言数据库不能提交PENDING，并在同源事务中读取canonical和JOIN路径、检查拒绝后回滚无副作用。旧实现23项PG全部通过（`/tmp/recsys-mp09b-before-final.log`）。
- 迁移后49项/4类全部通过（点击结算23、点击准备8、点击在线8、转化在线10），`/tmp/recsys-mp09b-after.log`；真实PG验证嵌套record映射、可空值、同一JOIN快照、两条件并发、半成品拒绝、版本/风控和注入回滚。
- Hygiene无阻塞（`/tmp/recsys-mp09b-hygiene.json`），formatter/独立Java分析工具的既有限制保持；全仓24 reactor测试编译与完整资金链373项/68类全部通过，0失败/错误/跳过（`/tmp/recsys-mp09b-money.log`），含真实PG/Kafka、三种计费共享账户、事件投影和对账。默认关闭账务的推荐宿主3项通过（`/tmp/recsys-mp09b-host.log`）。
- 45个历史SQL和8个保护文件摘要不变，只使用独占测试容器。本片未修改CPM/混排浏览器路径，MP08c刚通过sandbox4/feed5；本片真实点击/转化在线49项和完整373资金链覆盖变更，最终全量浏览器仍在MP15。

## 后续

MP09a已正常提交/ff合并/push main：394fb6a24f6f84a0834bc9afecd46f8594df1f68。本片完成后核对MP09c点击准备/消费者引用，再继续MP10转化报价和结算，之后MP11～MP15及统一错误码目标。

## MP09c装配核对

点击准备的三模式持久化已在MP08c统一迁移，准备配置使用adDbSqlSessionTemplate；ClickPreparationService、ClickSettlementService、ClickExpiryJob与gRPC/HTTP消费者没有新增SQL，构造适配及真实在线测试覆盖原装配。ClickEventConfiguration的报价/结算探针已在MP09a/b切换；Outbox/投影SQL明确属于MP11，engine交付持久化属于MP12，不能在此提前核销。MP09b已通过最终验证，MP09c引用核对完成，无需为无变化的透传逻辑新建类。

MP09a远程CI38730596602已观察全部SUCCESS。本片Git后继续MP10a转化报价。
