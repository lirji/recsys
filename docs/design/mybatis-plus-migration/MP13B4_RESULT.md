# MP13b4：广告事件读仓同步

DONE/PASS。8源文件指纹`d124be7baec0a5351c77d18d60d29fbf84b147f8ce549b96c978a0a1311ab261`，清单`/tmp/recsys-mp13b4-source-paths.json`。

AdEventLogMapper/XML负责主源目标读仓水位、同库复制、full清空、跨库有界批次和计数；AdEventCopySourceMapper/XML只在独立源会话加载，AdEventLogRow显式映射原16列。原事件价格等DOUBLE PRECISION保持，不把该历史事件读仓混同精确资金账本。源ID/可空Long/NULL浮点值/微秒Timestamp均保留，所有值参数绑定。原MAX(id)、按ID读取、全量List、1000条提交边界及冲突忽略不变，原源库环境装配复用OfflineSourceSessions。

bootstrap/ad-event-log.sql保留原建表和三索引，补齐16列注释；不修改14_ad_event_log.sql历史。bootstrap与目标Mapper使用实际factory的同一原DataSource，外层事务继续可回滚full和复制结果。

旧实现仅开放包内源工厂替换点后，4项独占PG保护全部通过（`/tmp/recsys-mp13b4-before.log`）：全部16列逐值比较、NULL/Long.MAX_VALUE/微秒/带引号文本、重复执行与水位/full、1002行跨库源连接关闭、第二批约束失败仅首批持久化、外层事务回滚。迁移后全部离线85项/19类通过，0失败/错误/跳过（`/tmp/recsys-mp13b4-after.log`）；增加源Mapper不被主源装载及16列注释检查。全仓24 reactor测试编译通过（`/tmp/recsys-mp13b4-compile.log`）；hygiene无阻塞、无发现（`/tmp/recsys-mp13b4-hygiene.json`），Java formatter/独立静态分析未配置的既有限制保持。

45历史SQL和8保护文件摘要不变。MP13b1=6b96d0d3、b2=dcac4a8d、b3=37c51498均已正常ff/push main。继续广告种子四源路径，真实ShardingSphere路由/生成主键待该片验证，不能以本片双schema测试替代。
