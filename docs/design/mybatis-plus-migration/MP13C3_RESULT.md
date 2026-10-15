# MP13c3：用户向量聚合

DONE/PASS。6源文件指纹`ce7d862a633826dc32e1b49f24e5a7ad7242e1d953bdc61fea8118a2ca6fc77e`；清单`/tmp/recsys-mp13c3-source-paths.json`。

UserEmbeddingJob主源PositiveFeedbackMapper保持，剩余derived SQL迁入ItemEmbeddingMapper.scanVectors和UserEmbeddingMapper：逐行读取物品向量、TRUNCATE user_embedding、单条PGvector upsert、总行数。原算法、全量内存聚合、正反馈权重、半衰期、NULL值/ID、来源及maxTs不变；只有产出时才TRUNCATE，no-rebuild保留其他用户。无新事务、池或依赖。

- 旧真实双schema/pgvector7项通过（`/tmp/recsys-mp13c3-before-fixed.log`）。初测试夹具先修正PGvector解析的checked SQLException编译错误；随后时间边界断言暴露夹具用to_timestamp写TIMESTAMP导致会话时区偏移，改用明确无时区epoch字面量建数，原断言和产品SQL均未放宽/修改。前两次失败日志保留在before/before-final，不计PASS。
- 新用户向量7项+文本回填4项+多模态7项+实际四源装配1项，共19项通过（after）。验证RATING/LIKE/CLICK/PLAY权重、阈值排除、零向量、NULL身份归0、NULL物品向量跳过、严格微秒上界、半衰期及behavior_log来源；实际用户向量与手工可计算期望一致。
- 默认重建移除旧用户，增量upsert保留无关用户，主源同名用户向量标记保持。无物品向量、全NULL向量、无正反馈均提前返回并保留旧数据。
- 无外层事务时TRUNCATE与第一用户写入已提交，第二用户约束失败后不恢复旧行；同一派生源外层事务在写入失败或主动回滚时恢复原用户向量。SIMPLE会话前后可用，真实客户端关闭与服务端归零通过。
- 全离线132项/26类0失败/错误/跳过（offline），全仓24 reactor测试编译（compile）通过；日志前缀`/tmp/recsys-mp13c3-`。hygiene无发现/阻塞，既有Java formatter/独立静态分析未配置限制保持。
- 45历史SQL和8保护摘要不变。TIMESTAMP/extract(epoch)沿原口径，未借迁移修改历史时区业务语义。

前序38e404f1的CI38754040504仍进行中，不记全绿。下一片ImportTowerJob：原500行JDBC批次含重复ID时逐条覆盖，不能机械改成一个foreach ON CONFLICT（同一句重复ID会改变语义）；默认vector(64)DDL固定、dim参数仅作输入校验，DDL提取新bootstrap并补列注释，历史02_two_tower.sql不改。
