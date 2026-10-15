# MP13b8：广告uplift模拟双批次

DONE/PASS。5源文件指纹`9d8cdd7cde9e2628da1ba874ce8545333d182f4d14c45336fdeb8b953f309053`，清单`/tmp/recsys-mp13b8-source-paths.json`。

SimAdUpliftDataJob的分配/转化事实两个INSERT进入adDb UpliftSimulationMapper/XML。Assignment/Fact记录替换Object数组，所有17/6字段、原时间精度、数据库created_at默认值、随机算法和日志保持；ON CONFLICT DO NOTHING仍涵盖所有唯一约束，不收窄到主键。UpliftSimulationWriter沿用两批先后顺序并复用SpringMapperBatches，不引入新事务、主动commit或全量VALUES。默认5000×17绑定超过一条SQL参数上限，不能机械合成单语句。

## 验证

- 旧真实PG6项首次测量后冻结精确期望，再次旧6项通过；日志`/tmp/recsys-mp13b8-before-{probe,final}.log`。
- 新专项7项+实际四源装配1项通过，`/tmp/recsys-mp13b8-after.log`。默认5000分配、2539 treatment、602事实；完整分配/事实身份及去now偏移摘要`1d9739b75813cc9694ce663c8b2ab7f0`与旧一致。全部字段、高低quality/广告归属、3天期限/1天转化延迟检查通过。
- 引号run-id绑定、相同run重跑保留所有列/时间、请求+广告唯一冲突保留已有quality验证通过。零users无写、负users仍在生成阶段拒绝。
- 第4001分配CHECK失败时原/新均已提交3839条、事实0；该数是当前PG42.7.3/17字段语句的实测，不能套用其他语句14847或视为新业务批大小。第二批失败保留5000分配、事实0；外层事务可统一回滚，两批前后既有SIMPLE会话可继续读取并提交。真实连接关闭与服务端有界归零通过。
- 全离线114项/23类，0失败/错误/跳过；全仓24 reactor测试编译通过（`/tmp/recsys-mp13b8-{offline,compile}.log`）。hygiene无发现/阻塞；统一Java formatter/独立静态分析未配置限制保持。
- 45历史SQL和8保护文件摘要保持；公共helper/配置/依赖未改。

773cf8c3的CI38751096791因后续运行取消，不记通过；ac21c920的CI38751843655仍进行中。前序连接探针修复的远程结果继续由后续完整CI验证。下一片MP13c1 BackfillEmbeddingJob：derived向量存在检查、原PGvector upsert与总数，保留跳过、逐条失败继续、配额中断和来源ContentService边界。
