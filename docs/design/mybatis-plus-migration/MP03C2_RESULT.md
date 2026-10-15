# MP03c2：图、多兴趣和双塔查询迁移

本片DONE/PASS；源指纹 `ceaf97ef4eefbf81b1bcde756c5b2a19cdbc05252696d62303f2b44f448c346c`，之后只同步文档。

GraphRecaller、MultiInterestRecaller、TwoTowerRecaller 的5条SQL迁入派生源EmbeddingMapper/XML。图的用户/物品版本过滤、每兴趣有界检索后最大分合并、双塔候选去重及缺失候选不补零保持。NULL分值仍为零，图向量仍沿原PGvector解析逻辑；模型加载和编码不变。

新增极小测试ONNX固件（常量输出）及可复现生成器，独立于未跟踪训练模型；只证明SQL/推理协议的集成，不声称验证模型质量。原R9真实训练模型测试保留。gitignore仅放行该test资源目录的*-fixture.onnx。

## 验证

- 旧实现：新增固定模型3项+原R9 2项，5项全部通过。`/tmp/recsys-mp03c2-before.log`。
- 迁移后模块回归46项全部通过，0失败/错误/跳过。`/tmp/recsys-mp03c2-after.log`。
- 宿主回归12项全部通过：固定模型3+向量4+R9 2+推荐3。`/tmp/recsys-mp03c2-hosts.log`。
- 全仓编译/Hygiene见 `/tmp/recsys-mp03c2-compile.log`、`/tmp/recsys-mp03c2-hygiene.json`。统一formatter/独立静态分析的既有限制保持。
- 原SQL资源、公开协议、数据归属及用户文件未改；不生成或覆盖生产训练模型。

## 交付与下一步

MP03c1已正常ff合并/push main：0ee47314。继续MP03c3语义ID/TIGER，之后排序与后续全量工作包；SQL全量完成之后统一错误码及提示。
