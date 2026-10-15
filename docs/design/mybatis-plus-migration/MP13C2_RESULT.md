# MP13c2：多模态向量融合回填

DONE/PASS（本地）。4源文件指纹`92f51aaf666049dcaddd886a6724389709b779d54128d20da29b74414d5e624f`，清单`/tmp/recsys-mp13c2-source-paths.json`。

MultimodalBackfillJob三处SQL复用derived ItemEmbeddingMapper/XML：无排序物品ID列表、单物品embedding::text列表及UPDATE-existing。与文本回填的upsert明确分方法，海报计算期间若向量行被删除，不重新插入。原List全量ID、NULL/缺失跳过、PGvector setObject、模型后缀、融合浮点及L2归一化不变。

- 旧pgvector初6项通过，再补“计算期间删除不能重新插入”后旧7项通过（`/tmp/recsys-mp13c2-before.log`、`before-final.log`）。
- 新多模态7项+文本回填4项+实际四源装配1项全部通过（after）。jpg优先于jpeg/png、NULL向量和无海报跳过、零向量、精确融合结果、含引号模型、维度不符跳过和limit只计成功融合均验证。
- 不支持多模态时停止：第一项失败无写；此前已成功的写入保留。真实PG NaN失败向上传播，保留此前autocommit写入；外层事务回滚恢复所有原向量且已有SIMPLE会话可继续使用。并发删除后UPDATE影响0行不重新创建，保持原处理。
- 完整离线125项/25类，0失败/错误/跳过（offline）；全仓24 reactor测试编译（compile）；日志前缀`/tmp/recsys-mp13c2-`。真实客户端连接关闭和服务端有界归零通过。
- 45历史SQL及8保护摘要保持；未新增依赖、事务、表或生产删除行为。hygiene及Java formatter/独立静态分析限制沿既有记录。

历史提示“本作业未改动任何数据”在后续物品才抛UnsupportedOperationException时不准确；测试按真实部分写入行为保护，本次SQL等价迁移不改该分支。后续统一错误提示阶段应纠正文案，不能把停作业描述为撤销已提交写入。

cd144c02的CI38753343012仍进行中，42b2de17的38752676980被后续并发策略取消；未完成/取消不记通过。下一片UserEmbeddingJob：主源正反馈保持，余下derived物品向量扫描、TRUNCATE用户向量、单条upsert及总数迁移；保留无输入提前返回、默认重建/增量与半衰期时间边界。

交付前diff --check发现Mapper文件末尾多余空行并修正；只改空白，未改变已验证逻辑，源指纹随之刷新。最终diff核验后才提交。
