# MP07b：MMR/DPP候选向量查询

本片DONE/PASS；6个源文件指纹 `52aeef372c6de65f974a9cba294105c1da1677127ab025ea1acc1aac5bcef28c`。MP07整体完成。

MmrReranker与DppReranker原相同SQL归入派生EngineEmbeddingMapper.itemVectors/XML，以foreach绑定去重候选，ResultHandler逐行交回具名ItemVector。原向量解析、余弦、相关性归一化、候选池/参数、输出元数据及异常时保留部分向量的行为保持；未借迁移调整算法。

## 验证

- 旧实现7项通过：新增5项真实pgvector对照及原DPP2单测，`/tmp/recsys-mp07b-before.log`。
- 新实现与推荐宿主联合19项/5类全部通过，0失败/错误/跳过：上述7、EngineInputs8、归因1、RecommendIntegration3，`/tmp/recsys-mp07b-after.log`。
- 比对相同类目不同向量产生的[1,3]顺序、分数与召回来源/理由；NULL/缺失向量与缺表回退、候选池截断、候选不足/空列表。全无向量且同类目时MMR仍选两条、DPP原样提前停止，仅一条，不以“修正”算法改变原行为。
- 全仓24 reactor编译通过，`/tmp/recsys-mp07b-compile.log`；Hygiene无阻塞，`/tmp/recsys-mp07b-hygiene.json`，既有formatter/独立Java分析工具限制保持。
- 45个历史SQL与8个保护文件摘要不变；重排算法类不再包含业务SQL或JdbcTemplate。

## Git与后续

MP07a已正常提交/ff合并/push main：b686c38f。继续MP08a预算账户/命令；UPDATE RETURNING必须声明affectData/flushCache，保留准确事务数据源身份、行锁、幂等、条件更新、失败回滚及历史迁移摘要。存在多个测试/配置手工构造BudgetRepository，需同步所有引用并验证资金链路。之后MP08b～MP15及统一错误码目标仍未完成。
