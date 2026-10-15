# MP13d3：Bandit曝光上下文

DONE/PASS。5源文件指纹`a2858616a5db581488d3d70fe777c4813b020d76897c9e0e702bfa755288cc3f`，清单`/tmp/recsys-mp13d3-source-paths.json`。

BanditStatsJob剩余曝光SQL进入主源BanditImpressionMapper/XML；正反馈继续复用MP13a2的PositiveFeedbackMapper。封闭来源与绑定days/max-ts，原long[]改具名Impression，避免下标含义。曝光仍先全部物化，模型算法、在线FeatureAssembler和Redis JSON协议未改，无新通用框架或Repository。

- 旧真实PG/Redis6项通过（before），新Bandit6+正反馈4+真实四源装配1共11项通过（after）；完整离线178项/33类0失败/错误/跳过（offline），全仓24 reactor测试编译（compile）、hygiene通过。日志前缀`/tmp/recsys-mp13d3-`。
- 特征服务替身固定x=(1,0,0,0,0)，独立逐项断言5×5 A、b、n、lambda和order；PG查询与Redis模型真实，不用模拟SQL证明结果。
- 重复曝光保留，NULL身份0及Long.MAX_VALUE完整，用户特征仍按身份缓存；读仓/主源隔离、微秒严格上界、自定义评分阈值、默认30天曝光下界保持。奖励没有新增days下界；原未来曝光仍保留。
- 增量沿已有模型lambda累计；坏JSON或维度不匹配按原逻辑重建，空曝光/非法来源/缺表不覆盖模型。SQL失败分类、连接关闭及实际主源Mapper绑定通过。
- 原loadOrFresh/writeModel两个宽catch仍属既有降级/日志行为，本片未新增；后续统一提示阶段处理两个已见问题：warm-start成功日志的参数lambda可不同于实际加载lambda；writeModel捕获写失败后run仍可能输出已写。不能把SQL迁移成功解读为这些历史提示已修复。
- 无新增依赖/DDL；45历史SQL和8保护摘要不变。Java formatter及独立静态分析未配置限制保持。

最近完整CI成功为R9=df2ca1a2的[38758681589](https://github.com/lirji/recsys/actions/runs/38758681589)。Lookalike/Hot/ColdBandit已正常ff/push main=2d1712b5，[CI38760632548](https://github.com/lirji/recsys/actions/runs/38760632548)在途；本片先本地独立提交，随后与IDF相邻片正常ff/push。

下一片IdfJob：保留真实PG英文stemming/df、两种目录、2000词text[]批次与Redis临时Hash/RENAME发布和失败清理，先旧测试再迁移。整体SQL与统一错误码任务仍未完成。
