# MP04c：广告目录副本与自有报表

本片DONE/PASS；源指纹 `bb5c09610f4c494f9b9261589ef5bd11aa869fceafe814129d0d5d7e4f358364`。验证后仅同步文档，MP04整体完成。

AdServableRepository与AdReportStatsRepository的业务SQL全部迁至adDb Mapper/XML，精确绑定原adDbDataSource。逐行回调、NULL默认值、JSON失败回退、maxBid计算、失败时空结果/count=-1、事件到达顺序覆盖、墓碑删除和费用只计CLICK均保持。

原PostConstruct防御性DDL提取至新资源db/bootstrap/ad-serving-catalog.sql，继续通过同一DataSource执行，补齐两表19列及表注释；旧表audience_id/max_bid先补列再注释。连接和DDL资源runner属于基础设施例外，不留Java业务SQL。历史SQL不改写。

## 验证

- 旧实现6项真实PG保护测试全通过，`/tmp/recsys-mp04c-before.log`。
- 迁移后真实副本8项、事件JSON2项、广告目录双分片/向量8项、广告主双分片8项、推荐Boot3项，共29项全通过，0失败/错误/跳过，`/tmp/recsys-mp04c-after.log`。
- 新增验证重复初始化、旧表补列、两表全部列注释及Mapper/DDL同源；错误注入覆盖坏向量、坏JSON、缺表，验证原降级行为。
- 全仓24 reactor编译成功，`/tmp/recsys-mp04c-compile.log`。Hygiene无阻塞，`/tmp/recsys-mp04c-hygiene.json`；原formatter/独立Java分析缺失限制保持。
- 45个历史SQL与8项保护文件指纹全部不变。Java扫描ad-serving仅剩DDL资源runner的基础设施import，无业务SQL。

## Git与后续

MP04b已正常ff合并/push main：d8ad16eb20ff53e36ee8891bc8edf99efcfe758c；最新CI38719629042最后观察运行中，MP04a CI38718721292 SUCCESS。继续MP05广告事件/归因/uplift与辅助读取，再按PLAN完成其余全部SQL，之后统一错误码。
