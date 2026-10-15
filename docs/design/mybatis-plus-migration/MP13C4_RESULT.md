# MP13c4：双塔向量CSV导入

DONE/PASS（本地）。6源文件指纹`8bdebbded8f54f5b84e60133656df7bec59c1a2cbb376b04ba43715444a2658b`，清单`/tmp/recsys-mp13c4-source-paths.json`。

ImportTowerJob的TRUNCATE、upsert、总数进入derived TowerEmbeddingMapper/XML。TowerEmbeddingStore集中原bootstrap和500行批处理适配，实际factory内DataSource同时供DDL/Mapper，复用SpringMapperBatches；同批重复ID逐条后写覆盖，不能换成单个多VALUES ON CONFLICT。成功处理行数沿原JDBC数组长度计为rows.size，与唯一物品总数区分。移除未使用ids列表和已过时跨库外键注释。

原CREATE EXTENSION、固定vector(64)表、表注释及HNSW顺序提取db/bootstrap/item-tower-embedding.sql，并补两列注释。--dim仍只校验CSV，不改变新表维度，不修改已有表类型；历史02_two_tower.sql字节不变。字段注释说明“新建默认64维”，不把已有三维夹具误称64维。

- 旧真实pgvector6项首次通过并测得晚失败已提交500；冻结精确断言后旧6项再次通过（`/tmp/recsys-mp13c4-before-{probe,final}.log`）。
- 新专项7项+实际四源装配1项通过（after）：503有效输入含同批/跨批重复ID最终501物品，后写覆盖；空行/坏ID/坏浮点/维度不符跳过，64维/表注释/列注释/HNSW/无跨库外键检查通过。
- 缺文件不建表、不清已有数据；已有空文件清表。--dim=3在新64维表触发原PG维度错误，但已有3维表保持原类型并可导入。第700行CHECK失败仍保留首批500行、旧行已清除。
- 外层事务可回滚成功重建或两批间写入错误，恢复旧行；新建表/索引与批次同源提交后SIMPLE会话可继续读取，客户端连接及服务端会话释放通过。
- 完整离线139项/27类0失败/错误/跳过（offline），全仓24 reactor测试编译（compile）、hygiene通过；日志前缀`/tmp/recsys-mp13c4-`。Java formatter/独立静态分析未配置限制保持。
- 45历史SQL和8保护摘要保持。无新事务、连接池或依赖，无共享库操作。

9d7897b5的CI38754987441浏览器/自然/安全三门禁SUCCESS，build与money-chain仍进行中。当前先保存本片本地提交，继续ImportSemanticIdJob，再正常ff/push已验证的逻辑提交；不把在途CI记全绿。
