# MP13f1：广告校准、探索统计与延迟拟合

DONE/PASS。11源文件指纹`0bbf6cb6784881191931f93b218d30a11a8ed0da3954c47687837d1a4ab72143`，清单`/tmp/recsys-mp13f1-source-paths.json`。

AdCalibrateJob、AdExploreStatsJob、DelayModelJob的五条SQL分别进入主源AdCalibrationMapper、AdExplorationMapper、AdDelayMapper及XML。统一使用已冻结的AdEventSource局部参数，移除可变aet字段。校准输入由double[]改具名Sample；探索/延迟继续回调聚合，算法内部数组及发布顺序不改，不新增Repository、事务或依赖。

- 旧11真实PG/Redis专项、新11+四源装配1通过；完整离线235项/41类0失败/错误/跳过，全仓24 reactor测试编译通过。日志`/tmp/recsys-mp13f1-{before,after,offline,compile}.log`。
- 校准保留预测升序、点击按请求/广告去重、NULL预测过滤、默认100条/20分箱阈值、PAVA下降段合并、自定义模型及完整双箱JSON。探索广告计数不去重，创意点击普通关联保留重复曝光×重复点击乘积；NULL广告创意仍写0身份，其他事件形成0计数行，Long.MAX_VALUE精度保留。
- 延迟只对点击施加days窗口，转化限制已到达；Java仍过滤负数和超过max-delay，0及上界包含，非正days保留NULL时间读0，重复关联不去重。原数据源事务冻结PG now验证10天端点/微秒外界；默认最少50、样本不足、零均值保留旧模型，主/读仓来源一致。
- 空探索总数更新0，但旧广告/创意键保留；创意列缺失的晚SQL失败保持已发布广告/总数与旧创意。真实Redis ACL拒绝回调写入时抛DataAccessException并保留NOPERM根因、关闭数据库；延迟第二SET拒绝时第一个lambda已发布而均值仍旧，不宣称两键原子。
- 已证实历史参数问题：bins=0先写空曲线后ArrayIndexOutOfBoundsException，SQL迁移按原行为保护，留后续统一参数/错误提示阶段修正。QualityScoreJob也存在捕获部分发布错误后继续成功日志，后续对应片记录。
- 45历史SQL/8用户保护摘要不变。Java formatter及独立静态分析未配置限制保持，不替代MP15完整验收。

前批da335f70的CI38770885163五门禁全部SUCCESS。e5=f7f63963与本片正常相邻ff合并/push后继续MP13f2报表发布：保留名称幂等、原自举与单文件失败继续，补齐自举表字段注释，不修改历史迁移。
