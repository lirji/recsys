# MP08a：预算账户与幂等命令

本片DONE/PASS。27个源文件最终指纹 `cb8cf4cb62e62a84158e5da2ae4a3058956bc581ea1ae962c4916d8c203b7e3f`。

BudgetRepository的schema探针、命令唯一键/摘要、账户行锁、版本和已承诺金额条件更新、首次结果保存与查询均进入addb BudgetMapper/XML。预算领域校验与错误消息保持。Mapper返回账户值对象；命令先映射允许NULL结果的CommandRow，Repository先拒绝PENDING，再构造原快照，避免把半成品误映射为合法账户。PENDING以私有持久化常量表达，不扩展只允许最终结果的公开Status枚举。

Repository从SqlSessionTemplate取得实际Mapper和工厂DataSource，既有BudgetAccountService按对象身份核验事务管理器；不以相同URL或额外声明的DataSource代替实际连接资源。预算/点击/转化报价及三族事件装配的V001验证入口同步使用adDbSqlSessionTemplate；其他尚未迁移Repository维持原实现和同源事务。多个模块的手工测试/浏览器宿主构造同步更新，不增加连接池。

UPDATE RETURNING使用`<select affectData="true" flushCache="true" useCache="false">`，符合返回结果集DML的事务声明要求。affectData自3.5.12提供，本项目3.5.19满足。[MyBatis官方Mapper XML文档](https://mybatis.org/mybatis-3/sqlmap-xml.html)

## 验证

- 旧实现13项通过：预算PG11+配置2，`/tmp/recsys-mp08a-before.log`；初次迁移后同13项通过，且相关宿主测试编译成功，`/tmp/recsys-mp08a-after.log`。
- 新增RETURNING独立提交、事务回滚/同事务禁缓存读取及PENDING空结果拒绝，预算PG共13项。完整资金链路368项/68类全部通过，0失败/错误/跳过，`/tmp/recsys-mp08a-money.log`，包含真实PG/Kafka、报价/预占/回执/CPM/CPC/CPA/事件投影/对账和协议边界。
- Sandbox浏览器4项全部通过，真实PG账本、Outbox/Kafka投影与宿主资金对账通过，`/tmp/recsys-mp08a-sandbox-browser.log`。混排浏览器5项通过，真实PG最终位置/预占/捕获/自然事实/Outbox通过；该脚本不含Kafka投递，`/tmp/recsys-mp08a-feed-browser.log`。
- 新增预算Mapper后，默认关闭账务的完整推荐宿主3项真实pgvector+Redis测试通过，`/tmp/recsys-mp08a-host.log`。
- 首次Hygiene发现迁移沿用的PENDING字面量比较；改为具名私有持久化常量后，最终预算15项再次全通过（`/tmp/recsys-mp08a-final.log`），全仓24 reactor编译成功（`/tmp/recsys-mp08a-compile-final.log`），Hygiene无阻塞（`/tmp/recsys-mp08a-hygiene-final.json`）。未重复整套资金/浏览器测试，因为后续仅提取同值常量和清理1个测试未使用import；未修改条件、数据或协议。
- 统一formatter/独立Java分析工具的既有限制保持。45个历史SQL及8个保护文件摘要不变；只用独占PG/Kafka容器，不写共享环境。

## Git与后续

MP07b已正常提交/ff合并/push main：e2b4b572d22eaf87305e69b6e4dd07bb5e9dd250；CI run38725073212已观察全部SUCCESS。本片交付后继续MP08b CPM报价/预占，刚通过的完整资金链路可作为其旧实现基线。MP08b～MP15与统一错误码目标仍未完成。
