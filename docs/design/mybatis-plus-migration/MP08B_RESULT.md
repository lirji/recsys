# MP08b：CPM报价与预算预占

本片DONE/PASS。25个源文件指纹 `f63df6b52a2bda1dc9f20efaf0e25f3ba8c412a4be76088aa705addbe4893853`。

CpmQuoteRepository与ReservationRepository的业务SQL、schema探针和数据库时钟已进入各自addb Mapper/XML。Repository从实际SqlSessionTemplate工厂取得DataSource，维持报价、预占、账户和事务管理器的资源身份校验；不创建额外连接池。装配及各资金/浏览器测试宿主同步适配。

CPM报价沿用一次clock_timestamp生成签发/五分钟到期时间、首次签发截止条件和ON CONFLICT不覆盖原快照。threshold/billFactor仍按十进制原文保存，金额仍为BigDecimal；查询后重新核对冻结依据与金额。新增期限用例证明首次已过期不写入、同ID过期重试恢复原快照且不延长期限、变更价格拒绝。

预占保留唯一键领取、先预占后账户的既有锁序、预算日、版本/额度条件更新、HELD到捕获/释放/过期的条件，以及所有影响行数检查。扫描仍按expires_at/reservation_id稳定排序、LIMIT 1、FOR UPDATE SKIP LOCKED。Mapper显式映射可空中间状态，Repository先拒绝PENDING再构造领域对象；只新增私有持久化常量，不扩展公开状态枚举。既有SQL中的SANDBOX/CNY、关闭原因及时间取值保持不变。

## 验证

- 旧实现23项通过：报价PG6、预占PG12、回收5，`/tmp/recsys-mp08b-before.log`。
- 新实现同23项通过，相关ad-serving/behavior/engine宿主测试编译成功，`/tmp/recsys-mp08b-after-final3.log`。适配期间出现受检异常声明错误，已按具体调用修复；没有修改产品行为或跳过测试。
- 完整资金链369项/68类全部通过，0失败/错误/跳过（`/tmp/recsys-mp08b-money.log`），覆盖真实PG/Kafka、CPM/CPC/CPA、事件投影、对账与协议边界。
- Hygiene无阻塞（`/tmp/recsys-mp08b-hygiene.json`），统一formatter/独立Java分析工具的既有限制保持。默认关闭账务的推荐宿主3项通过（`/tmp/recsys-mp08b-host.log`），全仓24 reactor编译通过（`/tmp/recsys-mp08b-compile.log`）。
- Sandbox浏览器4项通过，真实PG账本、Outbox/Kafka投影和宿主资金对账通过（`/tmp/recsys-mp08b-sandbox-browser.log`）；混排浏览器5项通过，真实PG最终位置/预占/捕获/自然事实及Outbox核对通过，该脚本不含Kafka投递（`/tmp/recsys-mp08b-feed-browser.log`）。
- 45个历史SQL及8个保护文件摘要不变；只用独占测试容器。原暂停任务交接原文完整保留。

## 后续

MP08a=b7853b47，远程CI38726514355已观察全部SUCCESS。本片通过验证并交付后继续MP08c准备检查点与曝光回执；MP08c～MP15和统一错误码目标仍未完成。
