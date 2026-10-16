# ER-S13C2 验收结果

2026-10-16，PASS。基线1482947c；S13C2可信RPC及offline入口完成，父S13/C的A/B/C验收闭合。未启用共享环境、未部署。

## 行为及范围

reconciliation.v1提供快照、持久任务操作及单目标恢复/原结果查询；Inspect仅独立advertiser/offline，其余只offline。匿名、错误caller/签名、通用/同名身份拒绝；关闭管理功能保留只读查询。显式typed消息保存六位金额、可缺失账户/差额、三族、Timestamp、状态与检查点，双向拒绝未知版本和不一致结果。

offline新增billing-reconciliation八个动作，稳定ID显式输入、actor来自受控配置、1～30秒deadline且无自动重试。原main在唯一此作业时使用限定RPC上下文，完成即关闭，无资金库连接；其他作业保持原路径。公开失败及其cause均不包含任意远端描述。

## 验证

- `ReconciliationProtoMapperTest` 5项：大ID、微金额、缺失账户、精确差额/投影、坏版本/时间/计数、任务检查点/租约/取消、日结果范围/顺序、恢复状态。
- `ReconciliationOnlinePostgresTest` 5项：真实PostgreSQL与TCP、实际内部令牌拦截器、完整caller矩阵（含全部7个管理RPC对advertiser拒绝）；两日任务检查点/不可变日快照；取消/恢复重复与原ID查询；跨账户与内容冲突；功能关闭、坏参数和真实表不可用固定未知失败。
- `BillingReconciliationJobTest` 6项：八动作各一次真实TCP、可信配置actor、缺失/坏参数零调用、远端不可用不重试不泄漏描述或cause、跨账户响应拒绝、默认关闭和错误配置、真实main在PG/Redis不可达时仍一次RPC后关闭。这里协议endpoint是测试替身，资金owner由上项真实PG验证，不用CLI替身证明资金事务。
- 三类共16项最终通过；`/tmp/recsys-s13c2-final.log`。随后CLI错误cause收口后6项再次通过，`/tmp/recsys-s13c2-offline-final.log`。
- 已有错误目录/内部令牌/Grpc错误/作业参数及分发回归连同CLI共32项/7类通过，`/tmp/recsys-s13c2-regression.log`。
- C1恢复专项补验三族PUBLISHED缺fact超过等待窗口后的真实Kafka续发/重复投影，与DEAD分支分别完整重新inspect；现在8项全通过，`/tmp/recsys-s13c2-recovery.log`。初始PUBLISHED状态仅在独占fixture模拟，续发与消费真实执行。原C1资金生产源码未变，443项资金链回归证据继续有效，不重跑无关全仓测试。
- `mvn -o -B -pl recsys-ad-serving,recsys-offline -am compile -DskipTests`、console typecheck/生成文件Prettier、error generator --check、public errors、持久化边界（622 Java/92 XML/45历史SQL）和diff检查通过。
- 当前16项源码/契约/测试文件指纹`06ba693c778cbaaf47aa5f20841eaf53ccb82e43dc48ea7d057adba9885d2b65`，清单`/tmp/recsys-s13c2-sources.json`；最终验证后未改源码。

## 审查与限制

hygiene=IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS，无阻断。初检指出CLI System.out与两项依赖待声明；CLI改用仓库SLF4J，recsys-proto与grpc-client-spring-boot-starter使用C13C已登记依赖及根BOM，以approved-dependency输入重新检查通过。唯一generic catch位于RPC边界，将内部异常转换为固定未知结果；observer发送移出业务catch，避免半响应后再次发错误。无统一Java formatter/独立静态分析，未伪造执行。

新增TS仅自动生成错误字典，无可见UI变化，视觉验收N/A。未测生产容量，未运行共享恢复；既有内部凭据模型的强隔离限制保持。S13B CI38851321776六门禁成功后被新提交取消，不能标整轮成功；C1 CI38852706244仍待最终结果，后续精确提交CI须继续核查。用户七项已有文件指纹不变，测试产物及数据/env保持，唯一原目录工作树，无自动清理。
