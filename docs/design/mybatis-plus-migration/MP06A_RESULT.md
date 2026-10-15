# MP06a：行为事实写入与曝光归因

本片DONE/PASS；源指纹 `7ec94faa345518a2071be6f7f270fa3ed673b6aa89ea971f3685ed973b56a4e1`。验证后仅同步文档。

BehaviorService两个业务SQL迁至primary BehaviorMapper/XML，归因校验、时间规范化、指标及Kafka发布顺序不变。CLICK部分唯一索引继续决定是否首次写入；重复不计指标、不重复发布。引入具名BehaviorWrite写入值，测试不再依赖Object[]的第5/6项。

曝光bucket查询使用列表区分“无记录”和“记录存在但bucket=NULL”；后者仍是有效服务端曝光验证，不能因null标量结果误清除ID。主源共享原DataSource；自然回执暂未迁移，其原事务/约束继续回归保护。

## 验证

- 旧实现7项全通过（4归因单测+3真实PG），`/tmp/recsys-mp06a-before.log`。
- 新实现28项/5类全部通过，0失败/错误/跳过：上述7、自然回执真实PG15、配置2、Controller4，`/tmp/recsys-mp06a-after.log`。
- 真实PG覆盖20并发CLICK单事实单指标、LIKE不折叠、归属不匹配清空、最新曝光NULL bucket仍有效、RATING保留上报时间/值/桶。已有自然回执事实/Outbox原子性和恢复测试通过。
- 全仓24 reactor编译成功，`/tmp/recsys-mp06a-compile.log`。Hygiene无阻塞，`/tmp/recsys-mp06a-hygiene.json`；统一formatter/独立Java分析工具缺失限制保持。
- 45个历史SQL及8个保护文件摘要不变；测试建表补齐注释，未写共享库。

## Git与后续

MP05b已正常ff合并/push main：4f928ebeabe7069bf9002269bf9a53a4f094dca9。CI38721956525最后观察运行中；MP05a和MP04c因后续并发策略取消。继续MP06b自然回执Mapper、UUID与事务绑定验证，再MP06c及后续全部SQL、最终统一错误码。
