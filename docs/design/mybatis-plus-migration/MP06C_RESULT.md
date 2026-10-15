# MP06c：控制台画像和报表查询

本片DONE/PASS；9个源文件指纹 `c1e1d46d29de673c98a0730688f1b0b49ff0182fe78ac82da82dfa56189cf40b`。MP06整体完成。

UserProfileService的6组查询迁至UserProfileMapper/XML，ReportService的2组查询迁至ReportMapper/XML。直接声明共享持久化与MP依赖，精确扫描console的primary包；与content复用同一原主源工厂，不新建连接池。可选Mapper保留服务无数据库依赖时的空画像/CSV能力；不扩大为“完整console应用不需要任何DataSource”的承诺。

类型化记录替代Object[]，画像额外读取非空主键以保留“行存在但画像/更新时间都NULL”。行为item_id及报表大小原getLong(NULL)=0通过COALESCE显式保留，其他nullable字段不改0；时间仍通过Timestamp.getTime转毫秒，避免PG数值舍入。聚合ResultHandler保留旧读取中断时已取得的部分结果。Redis、逐项失败降级、报表JSON解析及CSV回退、路径允许列表和接口结构均保持。

## 验证

- 新增8项真实PG保护测试先在旧JDBC实现通过，`/tmp/recsys-mp06c-before.log`。
- 迁移后10项（上述8+Spring可选依赖+Boot/content同源装配）及12项原控制台测试，总计22项/4类全部通过，0失败/错误/跳过，`/tmp/recsys-mp06c-after-final.log`。
- 覆盖用户范围、聚合/分桶排序、画像全NULL、未知用户、近期50条、NULL行为字段、毫秒精度、单查询故障保留其他部分、JSONB空单元格/非法结构回退、缺表/缺行/无DB回退CSV及路径拒绝。
- 初次新测试编译因Spring registerBean重载与ObjectMapper构造器引用歧义失败，改为显式零参数lambda后通过，无产品行为变化。
- 全仓24 reactor编译成功，`/tmp/recsys-mp06c-compile.log`；Hygiene见`/tmp/recsys-mp06c-hygiene.json`，无阻塞，formatter/独立Java分析的既有工具限制保持。
- 45个历史SQL与8个保护文件摘要不变；console生产Java不再执行SQL。未操作共享数据库。

## Git与后续

MP06b已正常提交/ff合并/push main：0249ae19。继续MP07a推荐过滤、冷启动、个性化与曝光日志，先真实PG保护正反馈口径、主/派生源绑定、NULL向量及异步批次失败语义；MP07b重排和MP08～MP15仍未完成，之后继续统一错误码目标。
