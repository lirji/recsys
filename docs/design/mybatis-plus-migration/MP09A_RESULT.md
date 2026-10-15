# MP09a：点击不可变报价

本片DONE/PASS。12个源文件指纹 `1bb4d22620fd45bad3db3158826fa3c7c4c3311b054c568da82b1359cb58d307`。

ClickQuoteRepository的六条SQL进入addb ClickQuoteMapper/XML；工厂实际DataSource供既有资金用例核验，配置和事件迁移探针改用adDbSqlSessionTemplate。Mapper只接收具名参数/类型化写入记录，所有语句保留5秒查询超时，没有新增连接池或运行依赖。

CPC/OCPC价格构造、BigDecimal金额、threshold/billFactor十进制原文、冻结摘要编码和版本/价格单位复核保持。首次截止时间仍使用单次clock_timestamp CTE；五分钟TTL、ON CONFLICT不覆盖、原报价恢复，以及最终位置冲突优先于过期返回保持。显式列映射后先核对主体，再构造价格并核验不可变依据。

## 验证

- 迁移前基线为MP08c完整资金链372项，含点击报价6项及点击/转化共享账户路径。
- 迁移后联合85项/8类全部通过，0失败/错误/跳过，`/tmp/recsys-mp09a-after.log`：点击报价6、点击准备8、点击结算22、转化报价6、转化结算23、点击事件配置2、点击在线8、转化在线10。真实PG覆盖并发/不可变/期限/同位置冲突、金额精度、大ID、主体、同源资源、故障回滚与HTTP/gRPC装配。
- 默认关闭账务的推荐宿主3项通过（`/tmp/recsys-mp09a-host.log`），全仓24 reactor编译成功（`/tmp/recsys-mp09a-compile.log`），Hygiene无阻塞（`/tmp/recsys-mp09a-hygiene.json`）；统一formatter/独立Java分析工具的既有限制保持。
- 45个历史SQL和8个保护文件摘要不变，只使用独占测试容器。
- 本片未重复CPM/混排浏览器：相关持久化未变，前一片刚通过sandbox4/feed5；本片通过真实点击/转化在线协议宿主验证变更路径。全量最终浏览器门禁仍在MP15。

## 后续

MP08c已正常提交/ff合并/push main：146796243957ffc19e41a41e01afbab1113de43f，MP08整体完成。本片验证及Git后继续MP09b ClickSettlementRepository/Configuration；MP09～MP15和统一错误码目标仍未完成。
