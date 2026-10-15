# MP13b3：物品目录副本同步

DONE/PASS。8源文件指纹`9315f90a39fe9d502886074b7b261c3569341544623eb91017b8dfc46a6787ae`，清单`/tmp/recsys-mp13b3-source-paths.json`。

ItemLocalMapper/XML负责同库复制、full清空、全列upsert和计数；独立ItemCopySourceMapper/XML读取六个基列，ItemCatalogCopyRow保留可空值。跨库NULL tags沿用原空数组规则，同库INSERT SELECT继续保留SQL NULL；数组顺序、重复项、逗号/中文与NULL元素均保留。共享TextListTypeHandler在源读取后释放Array，目标连接重新构建数组，避免跨连接复用。原全量List与1000条批次保持，不改为新水位策略。

原防御性建表及GIN进入bootstrap/item-local.sql，生成列title_tsv继续由PG english配置计算，新增表/七列注释齐全，不改写11_item_local.sql历史。原主DataSource同时用于Mapper和bootstrap，full与upsert继续参与外层事务。

旧路径先仅开放包内源工厂替换点，4项真实PG保护全部通过（`/tmp/recsys-mp13b3-before.log`）：全列覆盖/普通同步保留源删除项/full删除、数组与NULL路径差异、源连接释放、第二批失败保留首批且失败批无部分行、外层事务回滚。迁移后全部离线81项/18类通过，0失败/错误/跳过（`/tmp/recsys-mp13b3-after.log`），额外确认源Mapper隔离与七列注释；全仓24 reactor测试编译通过（`/tmp/recsys-mp13b3-compile.log`）。隔离双schema/独立DataSource测试不冒充真实分片或跨集群验收。

45历史SQL和8保护文件不变。MP13a2的远程CI38744006253五门禁全部SUCCESS，接下来正常交付已验证MP13b1～b3本地提交，再继续广告事件读仓同步。

Hygiene无阻塞、无发现（/tmp/recsys-mp13b3-hygiene.json）；统一Java formatter/独立静态分析未配置的既有限制保持。
