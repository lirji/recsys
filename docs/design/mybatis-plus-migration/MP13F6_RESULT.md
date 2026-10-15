# MP13f6：数据质量巡检双源输入

DONE/PASS。8源指纹`657516695e99c7540f2c4b557ae0c9e73b926e5c0ad891252ee92bc21a6e48cd`，清单`/tmp/recsys-mp13f6-source-paths.json`。

DataQualityJob七次查询进入主源DataQualityInputMapper及派生DataQualityCoverageMapper/XML。主源目录/行为分母、pCTR校准输入和类目分布与派生向量计数明确隔离；两次类目聚合复用一个具名statement，以可空days表示全历史。来源沿局部CatalogTable/BehaviorSource/AdEventSource，删除可变表名字段、业务SQL拼接及count(String sql)入口；CalibrationBin具名记录替代平行数组临时输入。无新事务/池/依赖/基础设施，计算和CSV契约保持。

- 旧9真实PG专项、新9+四源装配1通过。主/派生schema互放矛盾计数，误绑即改变结果；子JVM只写临时目录CSV，TIMESTAMP窗口由原主源测试事务冻结。故障隔离用原自动提交模式，不让测试外层事务的aborted状态改变检查之间的失败隔离。退出前检查两路真实连接及服务端会话释放。
- 覆盖率仍为原表COUNT(*)与所有行为COUNT(DISTINCT user_id)，NULL用户排除、非正反馈也算用户；分母0视为1，分子可大于分母，不额外去重/clamp/JOIN。派生任一表缺失时整个coverage节跳过，不能输出半对统计。
- 校准保持PG width_bucket十桶、负/1/超1预测、不夹断、NULL预测排除，点击按(request_id,ad_id) DISTINCT；空样本跳过。PSI仅四类正反馈INNER JOIN非NULL类目，按基线类别全集对齐；微秒窗口端点、days=0/负值仍有真实下界，不能当作全历史。
- 缺ad_event只跳ECE，缺类目列只跳PSI；SQL缺失捕获、来源非法/数字参数非法在报表前失败、目录不可写晚失败均保持。所有检查因缺表跳过仍写breaches=0并记录“巡检通过”已实证为历史提示债务；后续统一错误码须区分未检查与通过，本片不宣称该问题已解决。
- 六份完整旧CSV SHA256：DEFAULT `b19f65e6f832da3f2d481a18df6fd0ff2726b317ceb7be9e7fa0ff4a45b789b1`；OPTIONS `d91f4ad293d1dc1c9681f57ff753f2a06a36a473b78b36ee461ffad02f86f030`；ECE_EDGES `f00006f049bab318f1ef5a989eac4d56958fce801e7581e67c8fe8c01d515521`；WINDOW_7 `e284ed57a0cb0270491a91ec88acae0cf3cc5784f56c4fc749f95e7d1e9c1e7e`；WINDOW_0 `d198a880ba4d458780357a2ec5355a3f0d295c47b2de4610917c14ddc27a43c2`；WINDOW_NEGATIVE `8b6234360b5d3598a5f90fca58626e39fbe0980d2f5cffb912448a5bc2e607b3`。新路径全部逐字节一致。
- 首轮旧测试PSI手算预期0.2881错误；按[2,3]对[2,1]独立复算得0.2929632769781625，四位为0.2930。修正测试后旧9通过，未改算法或放宽新旧比较。
- 完整离线280项/46类0失败/错误/跳过，全仓24 reactor测试编译通过，验证后未改源码；45历史SQL及8用户保护文件摘要不变。
- Hygiene IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS，无阻塞；formatter及独立Java静态分析未配置。

日志`/tmp/recsys-mp13f6-{before,before-final,after,offline,compile}.log`及`/tmp/recsys-mp13f6-hygiene.json`。业务SQL清单剩Flink；仍须清理过渡装配/表名入口、MP14/MP15，随后统一错误码/魔法值，不能把本片视作全部目标完成。

f2e4ccff的CI38779450526四专项SUCCESS、build仍在途。本片先独立本地提交，继续f7过渡装配清理，再相邻正常ff/push。
