# MP10b：CPA结算事务

本片DONE/PASS；MP10c引用核对完成，MP10整体完成。10个源文件指纹 `3f34f9b7aff5a75e278ae4a05457852990fba911062c5bd9d4680968ba68ed1b`。

ConversionSettlementRepository的24条SQL进入addb ConversionSettlementMapper/XML；配置及全部手工构造入口使用实际SqlSessionTemplate。Repository仍负责主体/摘要检查、领域对象构造、影响行数检查和事件编码，事务和数据源身份保持。

可见事实和业务Outcome分别保留独立原决定；原决定与当前结算状态继续由一次JOIN读取。嵌套constructor resultMap没有nested select；`settled_outcome_id`与`event_outcome_id`分别映射，公共列保留advertiser_id。业务查询的广告主过滤和`ON CONFLICT(advertiser_id,event_id)`冲突目标不变；不会吞掉其他唯一键冲突。原actor仅作审计，当前有权主体仍可恢复同一业务结果。

保留quote锁、单条到期SKIP LOCKED、CPA双条件、原预算日、版本/状态/held条件更新、账户/账本/Outbox同事务与每条5秒超时；没有引入CPC频率计数。

## 验证

- 旧路径基线为MP09b完整资金链373项及MP10a转化相关49项。
- 新增未完成可见/业务事实保护：数据库拒绝两类PENDING独立提交；同源事务持有quote锁后分别claim，canonical/同JOIN路径先拒绝半成品，回滚后无记录且账户/账本不变。旧实现24项全部通过（`/tmp/recsys-mp10b-before.log`）。
- 迁移后42项/3类全部通过，0失败/错误/跳过：CPA结算24、准备8、真实HTTP/gRPC在线10（`/tmp/recsys-mp10b-after.log`）。
- Hygiene无阻塞（`/tmp/recsys-mp10b-hygiene.json`），统一formatter/独立Java分析工具的既有限制保持。
- 全仓24 reactor测试编译及完整资金链374项/68类全部通过，0失败/错误/跳过（`/tmp/recsys-mp10b-money.log`）；包含真实PG/Kafka、三族资金/投影/对账。默认关闭账务的推荐宿主3项通过（`/tmp/recsys-mp10b-host.log`）。
- 45历史SQL及8个保护文件摘要不变，只使用独占测试容器。本片没有改变CPM/混排浏览器路径；MP08c本地sandbox4/feed5以及MP10a远程全部浏览器刚通过，最终全量门禁仍在MP15执行。

## MP10c引用核对

ConversionPreparationRepository对应的三模式准备持久化已在MP08c迁移；ConversionPreparationConfiguration使用adDbSqlSessionTemplate。准备/结算Service、ExpiryJob与HTTP/gRPC消费者没有SQL残留，原业务与装配由专项验证覆盖。ConversionEventConfiguration中的报价/结算探针已迁移；Outbox/投影属于MP11，engine交付属于MP12，不能提前核销。无需增加空接口或透传层。

## 后续

MP10a已交付30eb28be；其远程CI38732423173五项门禁全部SUCCESS。MP10b完成验证和交付后继续MP11 Outbox、投影及对账，再推进MP12～MP15和统一错误码目标。
