# MP03c1：普通向量与语义ANN迁移

本片DONE/PASS；最终源指纹 `199350a7bcdf9d3c58a32ba15f0b7162b1664ace3e304a7bfe82c15d13cdc316`，之后只同步文档。

VectorRecaller/SemanticRecaller 改用派生源 EmbeddingMapper/XML。PGvector沿原驱动对象绑定，未新增向量依赖；VectorMatch显式映射并由召回器确定通道。NULL相似度仍映射零，排序用原始距离，用户向量仍以文本单次读取。成功列表继续可变；语义服务的向量化、空输入与异常降级保持。

共享DerivedMybatisConfiguration复用宿主derivedDbDataSource，不新建连接池；独立XML路径与扫描包，默认源不能注册派生Mapper。

## 验证

- 旧实现：新增向量/语义3项+R9 2项真实PG测试全通过。`/tmp/recsys-mp03c1-before.log`。
- 模块迁移回归43项通过：common30、recall13，0失败/错误/跳过。`/tmp/recsys-mp03c1-after.log`。
- 最终版宿主回归14项通过：输入5+向量4+R9 2+推荐3。`/tmp/recsys-mp03c1-hosts.log`。此轮覆盖最后的列表可变性保留实现。
- 两schema互斥数据标记证明未误读主库，另显式断言两工厂的DataSource对象身份及Mapper注册隔离。
- 全仓增量编译/Hygiene见 `/tmp/recsys-mp03c1-compile.log`、`/tmp/recsys-mp03c1-hygiene.json`。formatter/独立静态分析的既有限制保留。
- 无历史SQL、数据库结构、外部协议或原用户文件变更。

## 交付与下一步

MP03b已正常ff合并/push main：50b2febd60d5ef6d9d072f14374afa8228f8b7e7。最新CI仍运行/排队；MP02b被后续并发策略取消，不能记为全绿。继续MP03c2图/多兴趣/双塔，再处理语义ID和TIGER。SQL全量完成后继续统一错误码目标。
