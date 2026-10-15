# MP10a：CPA不可变报价

本片DONE/PASS。9个源文件指纹 `0615b6d01b1118d7c1cb21c9b196bf04550a4914f90418bbc51d0672cf7236ae`。

ConversionQuoteRepository六条SQL进入独立addb ConversionQuoteMapper/XML；配置与事件探针及全部构造调用同步改用实际SqlSessionTemplate。每条语句保留5秒超时，owner资源身份来自真实工厂DataSource，不新增连接池。

保留CPA bid_type强校验与ConversionPrice构造，原hash/fingerprint函数字节未改；金额/数学依据文本精度、价格单位/版本检查、单次数据库时钟签发、五分钟TTL、原报价恢复和位置冲突优先于过期均保持。Mapper列结构与点击报价相同，仍保留各自固定表、迁移版本、业务校验和可独立审查的XML，不引入动态表名。

## 验证

- 旧路径基线为MP09b完整资金链373项，包含转化报价/结算/准备/事件及在线路径。
- 迁移后49项/5类全部通过，0失败/错误/跳过（`/tmp/recsys-mp10a-after.log`）：转化报价6、事件配置2、结算23、准备8、在线10。真实PG覆盖精度、不可变、并发/期限、共享账户、三模式兼容与HTTP/gRPC回调。
- 默认关闭账务的推荐宿主3项通过（`/tmp/recsys-mp10a-host.log`），全仓24 reactor编译通过（`/tmp/recsys-mp10a-compile.log`），Hygiene无阻塞（`/tmp/recsys-mp10a-hygiene.json`）；统一formatter/独立Java分析工具的既有限制保持。
- 45历史SQL及8保护文件摘要不变，仅使用独占测试容器。
- 未重复CPM/混排浏览器，相关路径无变化，MP08c刚验证sandbox4/feed5；最终全量门禁仍由MP15执行。

## 后续

MP09b/c已正常提交/ff合并/push main：345dcb23，MP09整体完成；MP09a CI38730596602全部SUCCESS。本片通过验证并交付后继续MP10b转化结算，之后MP11～MP15及统一错误码目标。
