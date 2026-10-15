# MP08c：准备检查点与曝光回执

本片DONE/PASS，MP08整体完成。23个源文件指纹 `8b86c433e636bb540a70472e6c2c2a1b3a179787aef4a033c2a682e292480d21`。

PreparationRepository和ImpressionReceiptRepository业务SQL、schema探针、数据库时钟已迁移到addb Mapper/XML。两者从实际SqlSessionTemplate工厂取得Mapper/DataSource，外层用例原有同资源校验、事务范围和锁序保持。现有Repository保留JSON协议转换、主体/摘要校验、原结果恢复及影响行数检查；不增加透传层或新的连接池。

准备检查点覆盖CPM/CPC/CPA三种既有模式，以封闭枚举选择XML固定表分支，没有动态SQL标识符拼接。每条准备语句保留5秒查询超时；独立语句提交、原ID请求一致、首次结果获胜和签名轮换恢复不变。CPC/CPA准备装配同步构造变更，点击/转化报价与结算SQL仍待后续片迁移。

曝光回执使用显式原始行映射，先核对主体、拒绝PENDING，再处理可空ID/接受时间及最终结果；金额继续精确BigDecimal/十进制字符串。账本和Outbox写入、回执终态与资金捕获沿用同一adDb事务和行数校验，事件仍按原TEXT JSON与日期字符串协议保存。

## 验证

- MP08b完整资金链369项作为旧路径基线。
- 旧路径新增未完成检查点主体/原请求保护、PENDING回执在空结果映射前拒绝，四套PG专项38项通过（回执14、三类准备各8），`/tmp/recsys-mp08c-before.log`。
- 迁移后同38项全部通过，相关ad-serving/behavior/engine宿主测试编译成功，`/tmp/recsys-mp08c-after.log`。
- 新增真实行锁超时测试最初假定QueryTimeoutException失败；现直接对照原JdbcTemplate与Mapper，两者同为DataAccessResourceFailureException且底层SQLSTATE=57014（5秒取消）。保留精确异常类型与SQLSTATE断言，并验证取消无伪结果、释放锁后恢复；准备PG9项通过（`/tmp/recsys-mp08c-timeout.log`）。产品超时逻辑未改。最终完整资金链372项/68类全部通过，0失败/错误/跳过，全仓24 reactor测试编译成功（`/tmp/recsys-mp08c-money-final.log`）。
- 最终Hygiene无阻塞（`/tmp/recsys-mp08c-hygiene-final.json`），统一formatter/独立Java分析工具的既有限制保持。默认关闭账务的完整推荐宿主3项通过（`/tmp/recsys-mp08c-host.log`）。
- Sandbox浏览器4项通过，真实PG账本、Outbox/Kafka投影及宿主最终资金对账通过（`/tmp/recsys-mp08c-sandbox-browser.log`）。混排浏览器5项通过，真实PG最终位置/预占/捕获/自然事实及Outbox核对通过，该脚本不含Kafka投递（`/tmp/recsys-mp08c-feed-browser.log`）。
- 45个历史SQL与8个保护文件摘要不变，仅独占测试容器。

## 后续

MP08b已正常提交、ff合并、push main：ddcd60ba34d89c866db04a2ba69a31bba82f2f45。远程CI38728391213已观察全部SUCCESS。本片验证/交付后继续MP09点击报价和结算，MP09～MP15及统一错误码目标仍未完成。
