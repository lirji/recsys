# MP13c1：文本向量回填

DONE/PASS。4源文件指纹`db2d4ecc748cee219916c019fc7828f9d0efa4c5d152ee48d7352a697d40e0b7`，清单`/tmp/recsys-mp13c1-source-paths.json`。

BackfillEmbeddingJob的存在检查、upsert、结束总数三个SQL进入derived ItemEmbeddingMapper/XML。内容来源仍由ContentService负责，逐物品调模型/维度检查/休眠/跳过/失败继续/配额耗尽停止保持。没有新增Repository、池或事务。原存在检查按行COUNT，NULL向量行仍跳过。

XML显式使用MyBatis [ObjectTypeHandler 3.5.19](https://raw.githubusercontent.com/mybatis/mybatis-3/mybatis-3.5.19/src/main/java/org/apache/ibatis/type/ObjectTypeHandler.java)调用setObject，保留原PGvector对象及PG非法向量校验；模型用VARCHAR绑定，upsert只覆盖embedding/model。没有把向量转成另一种文本编码或升级依赖。

- 旧真实pgvector4项通过；新4项+实际四源装配1项通过（`/tmp/recsys-mp13c1-{before,after}.log`）。
- 模型名含引号/中文、正负小数向量、原文本拼接及缺失内容、NULL向量行skip-existing验证通过；主源同名表标记保持不变，写入派生schema。
- 维度错误和真实PG NaN拒绝后继续到下一物品，随后quota停止且不读取后续物品；外层派生源事务回滚与前后SIMPLE会话可用。真实连接关闭与服务端归零通过。外部内容/模型仅使用确定性测试替身，不把它们当真实模型/远程内容服务验收。
- 全离线118项/24类0失败/错误/跳过，全仓24 reactor测试编译通过（`/tmp/recsys-mp13c1-{offline,compile}.log`）；hygiene无发现/阻塞，既有Java formatter/独立静态分析不可用限制保持。
- 45历史SQL及8保护摘要保持。原逐条异常降级明确记录失败并继续，是本次保留的业务语义，不是新增吞异常。

MP13b8=42b2de17已正常ff/push。最新CI38752676980尚未完成，38751843655/38751096791被后续并发策略取消，不记通过。下一片MultimodalBackfillJob复用派生ItemEmbeddingMapper，保留仅UPDATE、海报优先级、空向量、融合上限及部分失败边界。
