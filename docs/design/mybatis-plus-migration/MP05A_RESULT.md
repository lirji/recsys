# MP05a：广告事件、归因与采样

本片DONE/PASS；源指纹 `20e96b6c261fadb36c9fa0c5380fe139d5fd31fa66c4ff2e6dba553cbebefa67`。验证后仅同步文档。

AdEventLogger、AdOutcomeService、UpliftAssignmentService的业务SQL迁入广告自有Mapper/XML，归因回退的广告归属查询进入分片AdCatalogMapper。当前页曝光改为多VALUES INSERT；真实PG证明与旧JDBC批次相同，任一行约束失败时整批不落库。异步队列/归因缓存/回退/反馈异常策略不变，creativeId<=0仍存NULL。

转化事实保留eventId主键ON CONFLICT DO NOTHING及首次负载；uplift保留原advisory lock CTE、窗口条件、请求/广告唯一键及成功落库后才隐藏控制组广告的规则。TIMESTAMP仍以Timestamp.from绑定，未修改观测窗口或采样算法。防御性DDL提取至db/bootstrap/ad-uplift-assignment.sql，补全表和17列注释，关闭采样时不触碰DataSource。

## 验证

- 旧实现12项全通过（真实PG6+原单测6），`/tmp/recsys-mp05a-before.log`。
- 迁移后19项全通过（真实事件7、真实分片归因/目录6、原单测6），`/tmp/recsys-mp05a-after.log`。
- 最终宿主联合34项全通过，0失败/错误/跳过，含推荐Boot、广告目录副本及sandbox候选不走旧曝光路径；`/tmp/recsys-mp05a-hosts.log`。
- 真实验证覆盖异步队列结束、曝光值/创意NULL、整批失败0行、归因缺失/NULL/双片来源、8个并发同eventId只有1行、负载不覆盖、控制/实验组、同请求及待观测窗口抑制、到期后新分配、缺表fail-open、DDL重复执行与全部注释。
- 全仓24 reactor编译通过，`/tmp/recsys-mp05a-compile.log`；Hygiene无阻塞，`/tmp/recsys-mp05a-hygiene.json`。统一formatter/独立Java分析工具缺失限制保持。
- 45个历史SQL摘要一致。根CODEX_PROGRESS入口更新至MP05a检查点，原暂停交接字节完整保留且不stage；其他7个保护文件未变。

## Git与后续

MP04c已正常ff合并/push main：2cad7dbc2a32473b987f02edd28dd64d04edaa27。最近CI：MP04c 38720320429运行中，MP04b 38719629042被后续并发策略取消；MP04a 38718721292 SUCCESS。

继续MP05b的AdEmbeddingSimilarity、CreativeSelector、GuaranteedDeliveryService、PacingService；之后全部原计划SQL及统一错误码目标。
