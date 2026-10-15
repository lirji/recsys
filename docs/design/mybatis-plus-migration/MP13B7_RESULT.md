# MP13b7：推荐模拟批处理

DONE/PASS（本地）。5源文件指纹`5c0d35786470ea840d84729d5dcad6c5df6f377e7d90f310f9c021078875349a`；清单`/tmp/recsys-mp13b7-source-paths.json`。

SimRecEventsJob的4处SQL迁入primary RecSimulationMapper/XML：sim-rec清理、封闭item/item_local来源、RATING热度扫描与单行事件插入。RecSimulationWriter复用SpringMapperBatches，同一个原逻辑批次一次flush、保持混合事件顺序及实际Spring连接归属，不新增事务或主动commit。默认5000×10曝光再加点击/点赞，不能改成单条超参数上限的foreach VALUES。

原参数、随机调用顺序、时间、热度归一化、清理先于目录校验/空判断、NULL列与日志均保持。评分聚合item_id采用Long并由算法显式将NULL读为0，保留历史getLong语义；COUNT非空使该行不会被MyBatis当作空投影丢弃。具名Event替代Object数组；不对整个作业加新分层。

## 验证

- 旧真实PG6项通过（before-probe），得到实际数量/摘要/部分失败边界；将精确断言冻结后旧6项再次通过（before-final）。测试数据含NULL物品评分分组、item 0、不同目录及其他来源行为。
- 新专项7项和实际四源装配1项全部通过（after）：默认50000曝光、11285点击、3342点赞；去除统一now偏移后完整有序事件摘要`fe01f2a1fba32dd8bb9b8e643d5b61e2`与旧完全一致；位置/值、点击30～90秒、点赞延后5秒、时间范围与NULL列保持。
- CHECK在第15000新行失败时保留14847条（冻结PG42.7.3/本语句夹具实测，非通用承诺）；其他来源38条保持。空目录/零session、非法来源前清理顺序、复制目录、外层回滚恢复被清理旧行均通过。新增外层已有SIMPLE会话后批次关闭仍可继续读取并提交的验证。
- 实际连接isClosed立即检查与服务端有界归零通过。全离线107项/22类0失败/错误/跳过（offline）；全仓24 reactor测试编译（compile）。日志均`/tmp/recsys-mp13b7-<阶段>.log`。
- 共用批处理helper未改，45历史SQL及8保护文件摘要保持。原全量物化与无外层事务下部分提交是本片兼容边界，没有顺手治理。

Code Hygiene另见`/tmp/recsys-mp13b7-hygiene.json`；统一Java formatter/独立静态分析未配置的既有限制保持。前序773cf8c3的CI38751096791仍需核验实际终态；不把已修正的旧CI38749329611记录为SUCCESS。

下一片SimAdUpliftDataJob：adDb的assignment/fact两个原批次、全约束ON CONFLICT DO NOTHING、默认5000×17绑定、两批之间失败边界；先旧PG保护后复用原批处理。
