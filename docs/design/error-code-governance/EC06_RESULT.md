# EC06 离线错误语义治理总验收

DONE/PASS（本地）。原PLAN的9个Job全部有源码与失败链路证据，父EC05的35个Job审计仍有效；合计44个OfflineJob。SQL迁移结果保持，无额外schema/依赖/基础设施变化。

| 作业 | 验证片 | 本次修正 |
|---|---|---|
| AdCalibrateJob / AdReportJob | EC06A_RESULT | 校准坏参数/无效数值/样本不足；未知风控计数不伪零 |
| AttributionJob / QualityScoreJob | EC06B_RESULT | long身份不经double失真；无输入/无效质量/部分发布错误明确 |
| DataQualityJob | EC06C1_RESULT / EC06C2_RESULT | 三检查真实状态，后台及页面消费；缺数据/检查失败/旧报表不假通过 |
| OcpcCalibrateJob | EC06D_RESULT | 缺失、损坏、依赖故障区分；合法保持与真实部分结果 |
| MultimodalBackfillJob | EC06E_RESULT | 晚unsupported/坏向量/零影响行数不假完成或假称未改动 |
| BanditStatsJob / FtrlTrainJob | EC06F_RESULT | 发布失败不吞；读故障不当缺失；安全从零回退、模型有效性与部分发布 |

正常数学/排序/源过滤/窗口/随机训练和已冻结输出按各片基线保持；有意修复坏数据、身份失真、假成功均单独记录。Redis多键非原子、外层事务边界、重复多模态融合等原限制保留，未引入隐式重试或宣称可回滚全部业务效果。

最终完整offline及依赖497项/84类，0失败错误，1原CI-only探针skip；实际PG/Redis/pgvector测试运行。24模块compile、全前端182、目录/SQL守卫通过；巡检页面71专项及2Chromium、3截图见C2。目录241，所有既有错误码/HTTP族保持。具体命令、源指纹与日志在各*_RESULT，父片不重复统计为更多用例。

最后Job审计未见剩余Job getMessage透传或原直接非法参数文案。DataQuality的数值越阈值明细属于诊断指标，前导目录警告仍有code；Bandit/FTRL解析回退显式目录警告，非静默吞依赖故障。完整全仓/资金/自然/三浏览器与最终SHA CI归EC08，不以本片局部PASS替代。EC07剩余前端/实验错误遗漏与文档/防回流继续。
