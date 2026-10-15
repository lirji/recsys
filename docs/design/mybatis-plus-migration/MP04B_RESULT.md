# MP04b：广告主写库与报表迁移

本片DONE/PASS；源指纹 `c9ef9ce9dc8963c57acbf5fc2af254909d269f936520e320cc447a28a65e5d57`。最终测试后仅调整中文注释、同步文档，业务实现不再变动。

AdvertiserRepository及DbAdReportReader全部业务SQL进入primary Mapper/XML，item_embedding查询进入derived Mapper/XML。四类插入通过useGeneratedKeys/keyColumn回填驱动Snowflake ID，不使用MP默认ID生成。主库仍为原ShardingSphere，广告按advertiser_id分库、子资源按ad_id分库；顺带纠正旧类注释中的过期路由描述，规则文件不变。

派生SqlSessionFactory复用原derivedJdbc内部DataSource，不新增DataSource Bean导致Boot主源退避。主源工厂复用共享配置；保持部分更新COALESCE和audienceId显式清空、影响行数、NULL数值回填、SINGLE向量upsert/批量标志、子资源删除顺序、报告零填充及舍入行为。没有新增事务边界或跨分片原子性承诺。

## 验证

- 旧实现6项真实双分片测试通过，`/tmp/recsys-mp04b-before-final.log`。初稿测试用负数代表缺失ID，触发旧INLINE算法ds_-1；修正为合法正数缺失ID后通过，未借迁移修改分片规则。
- 新实现8项真实PG通过，`/tmp/recsys-mp04b-after.log`。覆盖四表主键回填、实际物理路由、部分更新/删除/缺失/NULL、跨库向量复制、报表口径；新增工厂DataSource身份、Boot自动配置及Spring事务回滚验证。
- advertiser模块49项全部通过；含依赖模块共143项/32类，142通过、1项既有CI环境门禁条件跳过，0失败/错误。`/tmp/recsys-mp04b-module.log`。
- 全仓24 reactor编译成功，`/tmp/recsys-mp04b-compile.log`。Hygiene无阻塞，`/tmp/recsys-mp04b-hygiene.json`；原formatter/独立Java分析工具缺失限制不变。
- 45个历史SQL资源摘要均一致，8项保护文件摘要一致。运行只使用独占测试库；无共享环境数据写入。

## Git与下一步

MP04a已正常ff合并/push main：1f000266f042f9c3e26ee40ef943606662d62481，CI38718721292最后观察运行中。当前片验证通过后按授权交付，继续MP04c广告副本及报表；之后按PLAN完成全部SQL与统一错误码目标。
