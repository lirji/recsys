# MP07a：推荐输入与曝光日志

本片DONE/PASS；13个源文件指纹 `47a1dd35cd5dfe103dafbf853d26e261805b7edadfe2038af4a282f8a059a484`。

DbSeenItemsSource、ColdStartDetector、PersonalizationScorer及ExposureLogger移至主源EngineBehaviorMapper、派生EngineEmbeddingMapper/XML。引擎显式声明MP和共享持久化依赖，按包绑定两个既有会话工厂，不新建DataSource。行为正反馈口径、冷启动阈值与失败按非冷、向量NULL和无候选返回空、Redis同步归因、指标及原异步生命周期不变。

曝光通过不可变ExposureWrite保存一基position，XML多VALUES整页写入，保持旧PG JDBC批次中任一约束失败则整批不提交；不把此次框架迁移扩展为异步队列/可靠消息改造。向量亲和度使用具名结果；ANY数组改为参数化foreach IN，空集合在服务返回，重复候选不重复产生结果。

## 验证

- 旧实现8项全部通过：新增7项真实PG/pgvector保护+原1归因单测，`/tmp/recsys-mp07a-before.log`。
- 新实现12项/3类全部通过，0失败/错误/跳过：8项PG（新增精确DataSource身份）、1归因、3项真实pgvector+Redis RecommendIntegrationTest，`/tmp/recsys-mp07a-after.log`。
- 双schema互斥数据验证行为主源/向量派生源、正反馈去重及NULL ID、曝光不计冷启动、阈值边界、NULL向量行存在性、正/零/负余弦、无用户/NULL向量、重复/空候选、两库故障降级。
- 曝光排空通过executor终止等待，未用固定sleep；验证value/position一基、NULL曝光ID、同步归因、整批约束失败不留下首行，旧打点先于落库的口径保持。
- 全仓24 reactor编译成功，`/tmp/recsys-mp07a-compile.log`；Hygiene无阻塞，`/tmp/recsys-mp07a-hygiene.json`。统一formatter与独立Java分析工具限制仍在。
- 45个历史SQL和8个保护文件摘要保持；根CODEX_PROGRESS仅更新任务前缀，旧暂停交接原文SHA仍09ca9b…，不stage。

## Git与后续

MP06c已正常提交/ff合并/push main：782b3450。继续MP07b MMR/DPP共用派生候选向量查询，保留ResultHandler部分读取和算法/降级结果；MP08～MP15及统一错误码目标仍未完成。最新CI待最终核验。
