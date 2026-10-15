# MP13e1：FTRL训练物品池

DONE/PASS。5源文件指纹`07ded30b86c19e7a7d0c29745da368c042764307a8bcb44ea6ac2112e56f16ae`，清单`/tmp/recsys-mp13e1-source-paths.json`。

FtrlTrainJob最后一条SQL进入主源TrainingCatalogMapper/XML，正反馈继续复用PositiveFeedbackMapper。物品池保持原无排序/无去重/可空标量Long查询，未复用会排序的内容目录方法，也未把训练耦合到模拟写入Mapper。来源改为已有限定枚举；未新增Repository、事务、依赖或生产DDL。原负采样会采到已交互物品，已纠正注释而保留算法。

- 旧真实PG/Redis6项两次通过，新FTRL6+正反馈4+实际装配1共11项通过。完整离线197项/36类0失败/错误/跳过，全仓24 reactor测试编译及hygiene通过。日志`/tmp/recsys-mp13e1-{before,after,offline,compile}.log`。
- 旧默认完整权重/增量状态规范化JSON摘要`cd2da7d0353ea49dfe292bbf2c5b605a93301cafd5e0e9b1f77f347368b13b12`；增量续训`e247772706416d5c0cc99d8df24cc2def1e71965e237edf59f14f9be6d93258e`；双读仓/严格时间截点/NULL正例身份摘要`485f4975ab3fe33ceac272b0474467a8717f33b4171a6596d31b9cbcb8d462ae`。迁移后全部一致。
- 空物品池、99条正例保留两份已发布模型；100条及epochs=0沿原逻辑发布空模型；坏增量JSON回退从零训练。无效来源/缺表/NULL物品负采样失败不覆盖旧模型；客户端连接关闭及服务端退出通过。
- 先前新代码曾因CatalogTable包名错误编译失败，已改为现有content.persistence.model，随后所有门禁通过；没有通过弱化测试绕过。
- 45历史SQL/8保护摘要不变，Java formatter与独立静态分析未配置限制保持。本片不替代MP15完整验收。

前批939a8ade [CI38762149367](https://github.com/lirji/recsys/actions/runs/38762149367)五门禁全部SUCCESS。d5=374118ce与本片正常ff/push后继续三种自然样本生成。FTRL原writeJson吞写失败后run仍可能记已写，留后续统一错误码/提示阶段修复，不宣称本次已解决。
