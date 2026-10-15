# MP13d1：推荐热门榜聚合

DONE/PASS。5源文件指纹`26131843d1dc77ab4f0dd8ad9ee44b07eea31cd9d0040644cd0e0e79c0443c2e`，清单`/tmp/recsys-mp13d1-source-paths.json`。

HotJob动态SQL转为主源HotScoreMapper/XML：BehaviorSource固定表分支，recent-days以绑定天数乘interval，max-ts与limit绑定。权重与聚合在PG执行，ResultHandler直接构建原Redis元组集合；无新Repository、数据源或事务。原同分无第二排序键和Redis删除后写入窗口保持，注释不再误称原子替换。

- 旧真实PG/Redis6项通过（before），新热榜6+实际四源装配1共7项通过（after）。之后补NULL物品与实际0两组独立聚合的旧JDBC投影对照，完整离线167项/31类0失败/错误/跳过（offline），全仓24 reactor测试编译（compile）及hygiene通过。日志前缀`/tmp/recsys-mp13d1-`。
- CLICK=1/LIKE=2/PLAY=1/RATING原值、NULL评分1、未知行为0及负评分保持；NULL物品读取0，原GROUP BY不把NULL与实际0合组。默认1105候选取1000最高分及指定topn=2正确。
- user_behavior/behavior_log互斥来源、max-ts微秒严格上界、recent-days排除旧行/NULL时间且保留原未来行行为通过。未增加新的未来时间过滤。
- 空数据/topn=0保留旧榜；负limit/缺表失败/非法来源均不会覆盖旧榜。资源关闭、原值完整性与主库实际Mapper绑定通过。
- 无新增依赖或DDL；45历史SQL/8保护摘要不变。Java formatter及独立静态分析未配置限制保持；本片不替代MP15完整验收。

Lookalike=2075bcb2已本地提交，main/origin仍df2ca1a2，待本片及后续相邻统计片完成后正常ff/push。R9的[CI38758681589](https://github.com/lirji/recsys/actions/runs/38758681589)四专项成功、主build仍在途，不记全绿。

下一片ColdBanditStatsJob：真实PG/Redis冻结双来源关联、正反馈统计、可空类目与旧类目键保留，再迁移类目聚合Mapper/XML。整体目标未完成。
