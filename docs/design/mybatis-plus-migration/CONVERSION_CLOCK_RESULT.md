# CPA在线验收的跨日预算夹具修正

2026-10-16，DONE/PASS（本地）。仅修改ConversionOnlineFixture、ConversionOnlinePostgresTest；产品预算日期、授权、资格、金额和HTTP状态逻辑不变。最终2源指纹`09785e15bae9b396fee36b9521d8de2e19f95f60b69504d522e991f754f3b713`，清单`/tmp/recsys-conversion-clock-source-paths.json`。

## 失败链路与可重复验证

CI38764359355 attempt1在北京时间零点附近，跨广告主请求预期403实际400。夹具只初始化建账当天账户，而ConversionSettlementService.prepare按执行时数据库北京时间选预算日；若跨零点则ACCOUNT_MISSING，ConversionPreparationService输出空资格字符串。原测试没有检查资格前置条件，HTTP请求先因空证明返回400，尚未进入跨广告主归属校验。

新增setupDaysAgo=1的独占PG双展示模式场景，模拟建账在前一天、准备在当前日：旧单日初始化稳定复现两项ACCOUNT_MISSING/空证明失败（`/tmp/recsys-conversion-midnight-before.log`）。修正后12项全通过（原10+双模式跨日2），并确认HELD、冻结预算日、跨广告主403、业务事实与账本仍为0。

## 修正与验证范围

夹具保留模拟建账日，并初始化实际当天和次日的独立预算；默认只有当天/次日两份，模拟跨日时另含前一天。不会修改产品时钟，也不操作共享数据库。日期断言取已冻结preparedAt的北京时间，不再次读取观察时钟；原权限场景新增非空资格前置断言，避免类似问题误报为权限状态码变化。

- 旧完整本地资金门禁382项/68类通过，说明原失败在普通时段不稳定复现；不据此删除失败断言。
- 初版跨日修正2源指纹`7c5d29a450e7d60aeac436a1baa034b3e7574b009fea79e5a7917e26f28b98d6`：专项12及完整资金门禁384项/68类0失败/错误/跳过（`/tmp/recsys-conversion-clock-money.log`）。
- 最终再防止跨日测试本身碰到真实零点，使用当前/次日预算及preparedAt断言；所有受影响12项再次通过（`/tmp/recsys-conversion-clock-final.log`），最终全仓24 reactor测试编译通过（`/tmp/recsys-ci-fixtures-compile.log`）。生产代码无变化，剩余资金测试未因该夹具调整受影响；新提交远程完整门禁仍待核验。
- tests-only hygiene为NOT_APPLICABLE，显式diff检查通过；45历史SQL/8保护摘要不变。FTRL测试修正另见FTRL_FLOAT_RESULT。

CI38764359355 attempt2定向money重跑在recsys-advertiser读取SDK依赖描述时遇Maven Central HTTP429（Spring Boot parent3.3.5），未执行到原CPA用例，不能记为通过；保留两次失败证据。修正与MP13e2正常ff/push后以新CI实际结果闭环，不修改依赖来源、不放宽HTTP断言。
