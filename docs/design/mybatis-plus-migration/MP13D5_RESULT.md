# MP13d5：物品与用户特征物化

DONE/PASS。5源文件指纹`46d2b5fda0c2d1097780a47c64f6230918528ddf13839e7a4d3411cfaa7e2b0b`，清单`/tmp/recsys-mp13d5-source-paths.json`。

BuildFeaturesJob五条评分聚合进入主源FeatureStatsMapper/XML，来源用BehaviorSource/CatalogTable固定分支。三个具名结果显式映射NULL身份/均值读取0；GROUP BY仍按原列，count包含NULL评分，stddev_pop保留原总体标准差。移除作业实例上的可变SQL表字段，单次运行来源显式传到helper；不新增Repository或全量结果列表，item继续结果回调内发布、user聚齐后发布。

- 旧真实PG/Redis7项通过（before），新特征7+实际四源装配1共8项通过（after）。完整离线191项/35类0失败/错误/跳过（offline），全仓24 reactor测试编译（compile）及hygiene通过。日志前缀`/tmp/recsys-mp13d5-`。
- 三类评级统计、NULL评分与NULL类目、缺失目录、NULL/Long.MAX_VALUE身份、四位归一化及stddev_pop、catavg/catcnt_norm/catratio完整数值通过；真实RedisFeatureService沿原Hash协议写入。
- 同一Job连续切换主/读仓目录与行为来源，更新本次字段并保留旧Hash字段；无RATING时不覆盖任何旧特征。
- 晚期目录查询失败保留已写item特征、旧user特征；恢复目录后重跑成功。真实Redis WRONGTYPE使item回调失败、user未发布，保留根因和Spring DataAccess异常家族，PG客户端关闭/服务端退出通过；不将MyBatis回调包装视为业务成功。
- MapperFactoryBean实际主源绑定通过，无新增依赖/DDL/事务；45历史SQL/8保护摘要不变。
- Java formatter及独立静态分析未配置限制保持，本片不替代MP15完整验收。

前批939a8ade已ff/push，[CI38762149367](https://github.com/lirji/recsys/actions/runs/38762149367)四专项SUCCESS，build在途；最近全成功为2d1712b5 CI38760632548。本片先本地独立提交，后续与相邻训练片正常ff/push。

下一片MP13e1 FtrlTrainJob的无排序负采样池读取：不能复用有ORDER BY的目录方法，需先冻结真实PG/Redis完整模型摘要、源/窗口/阈值/增量/失败，再迁移。
