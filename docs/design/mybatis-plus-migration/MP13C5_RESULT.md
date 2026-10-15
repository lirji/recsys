# MP13c5：语义ID CSV导入

DONE/PASS。6源文件指纹`7d0df28a39b598a70659692705a144bf8cefe08c308bd3de20fe745b1fbe1511`，清单`/tmp/recsys-mp13c5-source-paths.json`。

ImportSemanticIdJob的TRUNCATE、逐条upsert及结束总数进入derived SemanticIdMapper/XML；SemanticIdStore复用实际派生factory的DataSource执行bootstrap/原500行批次，不新建事务或池。任意整数码字、CSV额外列忽略、模型trim、默认rqvae与重复ID后写覆盖保持。建表/表注释/前缀索引提取item-semantic-id.sql并补5列中文注释，历史03_semantic_id.sql不改。

- 旧真实PG5项首次通过并测得第二批错误保留500；冻结精确断言后旧5项再次通过（`/tmp/recsys-mp13c5-before-{probe,final}.log`）。
- 新语义ID6项+双塔7项+实际四源装配1项共14项通过（after）：同批/跨批重复覆盖、表头/空行/坏数字/超INT范围跳过、额外列忽略、Long.MAX_VALUE物品ID及INT最小/最大码字、引号中文模型绑定均通过。最终唯一物品501行，前缀索引/5列注释和无跨库外键检查通过。
- 缺文件不建表不清数据，已有空文件清空；第700行CHECK失败保留首批500行，旧9800行已清除。外层成功/失败回滚恢复旧行；bootstrap+两批提交后原SIMPLE会话仍可读，真实客户端/服务端资源关闭通过。
- 完整离线145项/28类0失败/错误/跳过（offline），全仓24 reactor测试编译（compile）及hygiene通过；日志前缀`/tmp/recsys-mp13c5-`。Java formatter/独立静态分析未配置限制保持。
- 45历史SQL和8保护摘要保持，无新增依赖。

[9d7897b5完整CI38754987441](https://github.com/lirji/recsys/actions/runs/38754987441)五门禁全部SUCCESS，已验证773cf8c3连接释放探针修正，旧38749329611失败由后续完整成功关闭。本片与MP13c4=e527177c按独立逻辑提交后正常ff/push main，新CI另行核验。

下一片R9：先真实PG保护三组同版本原子替换、其他版本保留、可选缺组不删除、输入验证先于DDL和手动connection事务的实际边界；不能直接套用普通Spring BATCH假设事务归属。
