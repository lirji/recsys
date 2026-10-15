# MP11c：只读一致快照对账

本片DONE/PASS，MP11整体完成。5源文件指纹 `5865eefbd1e7944dc9276d024869d1ff0efb4f0baa4ffb79efbd58675e9f73cf`。

ReconciliationRepository的9条语句进入独立addb ReconciliationMapper/XML。三族固定表、计数列和资金义务选择使用XML固定分支，不再Java formatted/字符串拼接。FamilyRow明确映射20个原始统计字段；NUMERIC仍由原decimal函数转为金额文本，两个可空时间保留，AccountRow仍使用查询scope的原预算日构造领域账户。

保留CPM不核验义务ledgerId、CPC按quote.advertiser、CPA按settlement.advertiser过滤的差异；资格/账本/Outbox/Inbox/汇总身份逐项比对，零元事件也校验笔数和缺失记录。账本身份是否必需由服务端布尔参数绑定，业务scope仍全部参数绑定，单个族的报告仍由一条聚合查询得到。

observedAt使用transaction_timestamp；9条查询均5秒超时，STATEMENT会话缓存范围保持。ReconciliationService及事务工厂没有改变：实际同一DataSource、数据库强制只读、REPEATABLE_READ、REQUIRES_NEW及5秒事务截止时间。配置与全部测试构造入口改用实际sessions；不改V016，也不恢复暂停的S13B任务。

## 验证

- 旧实现11项真实PG已在MP11b联合72项通过（`/tmp/recsys-mp11b-after.log`）。
- 迁移后11项PG全部通过，0失败/错误/跳过（`/tmp/recsys-mp11c-after.log`）：三族金额/笔数、广告主/原预算日隔离、零元与缺账户、积压/死信、投影身份漂移、并发捕获不制造假差额、实际只读拒写、错误资源/外层事务拒绝、超时SQLSTATE57014、缺表失败和默认关闭装配。
- 逐族展开XML聚合查询，与迁移前SQL忽略空白并归一化参数绑定后完全一致（三族均匹配）；结果构造字段和资源身份另由真实PG验证。
- hygiene无阻塞（`/tmp/recsys-mp11c-hygiene.json`），统一formatter/独立Java分析工具的既有限制保持。
- 全仓24 reactor测试编译及完整资金链378项/68类通过，0失败/错误/跳过（`/tmp/recsys-mp11c-money.log`）；45历史SQL及8保护文件、5源文件指纹不变，推荐宿主3项通过（`/tmp/recsys-mp11c-host.log`）；本片不改浏览器路径，MP11a及原生页签修复的14项浏览器已通过，MP15仍要求最终完整门禁。

## 后续

MP11b已交付50b677ae，CI38736158900四项门禁成功，build最后观察仍运行。MP11a CI38735452724最终被后续推送取消，已观察到其浏览器门禁SUCCESS，不能将整个取消运行标绿。完成本片后MP11整体完成，继续MP12候选频控与交付，再推进全部SQL和统一错误码目标。
