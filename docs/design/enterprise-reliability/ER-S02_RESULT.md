# ER-S02：sandbox 预算命令验收

日期 2026-10-15；基线 `9cad79fc`；实施分支 `feat/sandbox-budget-account`；契约 [C02](CONTRACTS.md#c02sandbox-预算命令er-s02)。本片 DONE / 验证 PASS。真实收费、预占和曝光闭环尚未实现，不能由此宣布整体企业就绪。

## 已实现行为

adDb 作为唯一账务权威，新增 NUMERIC(20,6) 预算账户及持久化命令。应用层校验、数据库唯一键、行锁、版本和金额约束共同保证同命令只执行一次，调额不得低于已消费加预占。APPLIED、VERSION_CONFLICT、BELOW_COMMITTED 全部保存原结果快照；后续账户变化不改变历史命令结果。

独立 billing.v1 gRPC 契约使用精确字符串金额，与旧 ad.v1 共存。advertiser 仅通过 owner RPC 调整账户，HTTP 明确 JSON、金额/广告主 ID 字符串，不接受金额数字或小数版本号。管理员身份来自服务认证，RPC 必须是可信 advertiser 调用方；旧 nosec 不能使匿名调用获得预算权限。

新能力缺省关闭，不改旧 Redis 预算及收费。版本化 SQL 独立存放，显式应用到隔离 adDb，记录 SHA-256；启动只校验，不自动迁移。装配校验 Repository 与事务连接池身份；REQUIRES_NEW / READ_COMMITTED 短事务不依赖 primary 数据源的默认事务。详细操作及恢复方式见 [SANDBOX_BUDGET_RUNBOOK](SANDBOX_BUDGET_RUNBOOK.md)。

## 验证证据

最终执行：

```sh
mvn -B -pl recsys-ad-serving,recsys-advertiser -am clean test -Dgroups=money-chain -DfailIfNoTests=false
```

43 tests，0 failure/error/skipped；其中新增 29 项（含真实 PG 11 项），旧钱链路回归 14 项。此前定向编译/测试额外执行 AdvertiserAuthzTest 与 AdvertiserBudgetViewTest 共 21 项通过；最后修改仅增加 PG 装配用例及测试标记，未改这两个回归覆盖的产品代码。日志 `/tmp/recsys-er-s02-money-chain.log`、`/tmp/recsys-er-s02-validation.log`；各模块 Surefire 报告可由命令重建。暂存检查移除迁移末尾多余空行后，针对最终脚本摘要重跑真实 PG 11 项全部通过（`/tmp/recsys-er-s02-migration-final.log`）。

| 验收 | 方法与结果 |
|---|---|
| 同键幂等 | 真实 PG：16 并发同命令均返回同一版本 1 快照，命令只有一条；PASS |
| 乐观并发 | 真实 PG：16 个不同 ID 使用 expectedVersion=0，只有一个 APPLIED，其余持久化 VERSION_CONFLICT；PASS |
| 原结果与作用域 | 真实 PG：金额规范化、改金额/actor/日期冲突、换广告主不可查询他人命令，重建 Service 后仍可查原结果；PASS |
| 预算硬约束 | 真实 PG：低于 held+charged 拒绝，之后释放也不改变旧拒绝；PG 拒绝超占、负消费、NaN；PASS |
| 失败原子性 | 真实 PG：账户更新后，命令完成时触发数据库约束失败；账户回到原快照、命令领取回滚，原 ID 可重试；PASS |
| 数据源/迁移 | 真实 Spring 装配+PG：同池成功、不同连接池失败、迁移摘要漂移失败；缺 schema 失败；重复迁移回滚不改变账户；PASS |
| SQL 注释 | 查询 pg_description，三个表及全部列均有注释；PASS |
| RPC 身份及错误 | 真实 gRPC 传输+项目令牌拦截器：匿名/错误服务/坏签名拒绝，可信方精确透传；非法/冲突/缺结果/系统故障区分，内部错误不泄漏；PASS |
| 超时恢复 | 真实 gRPC deadline：测试服务保留结果但不应答，HTTP adapter 返回 503 未知且只发一次，原 ID 查询取得结果；PASS |
| HTTP 契约 | MVC：禁用方法安全仍拒绝匿名/非 ADMIN；目标必须存在，actor 不可伪造；JSON 类型/金额/版本/日期校验；200/409/查询语义与精度；PASS |
| 开关 | ApplicationContextRunner：默认不创建新账务 Bean，不要求连接数据库；超时参数越界拒绝；PASS |
| 旧行为兼容 | BidType、BiddingService、VCG、ad.v1 protobuf 钱链路回归通过；未改旧线上调用路径；PASS |
| 格式/静态/范围 | git diff --check、文档链接/围栏/DAG、六个用户文件原哈希；PASS。根 Maven 无 formatter/static-analysis 配置，不冒称自动格式与静态分析通过 |
| Code Hygiene | IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS：根工程无 formatter；保留周边风格。无 blocking finding；复用 BOM 的两项 Testcontainers 测试依赖已在技术选择确认 |

测试层级边界：数据库事务由真实 PG 证明；gRPC 权限和丢响应窗口采用真实传输加测试服务/领域替身，MVC 为协议与授权单测。本片未启动整套业务进程，也未把各层测试合称全系统 E2E。PostgreSQL 为 Testcontainers `postgres:16-alpine` 临时实例，未修改共享 dev_infra 或生产数据。

## 审查和限制

独立验证 pass 核对了事务路径没有远程调用/MQ、锁顺序为命令键后账户、结果保存失败向外抛出、全部更新检查影响行数、金额不经过 double、查询按 SANDBOX+advertiser 过滤。RPC 边界统一捕获异常是为了脱敏并表达未知结果；不会返回成功 ACK。事务超时 1～30 秒为受限配置，实际还受现有 PG statement_timeout 约束。

首次测试曾因测试 JDBC URL 已有查询串而未切换到缺 schema，修正 URL 连接符后验证按预期失败；HTTP 首次测试发现默认内容协商可产生非 JSON，产品新入口已显式 produces/consumes JSON、错误解析响应指定 JSON。未删用例或放宽资金断言。

首版只开放可信管理员 sandbox，不开放广告主自助或真实资金。内部身份复用当前共享签名体系，未宣称有服务独立密钥隔离。命令审计保留且无自动删除；归档期限、生产容量、真实账户期初和灾备目标仍待后续政策/验收。关闭本片开关保留表与历史；不得通过清表回退。新增 PG 测试标记 money-chain，既有 CI 的全量 build 会执行所有新测试；当前窄 money-chain CI 模块范围会执行共享契约/PG 测试，RPC/HTTP 测试由全量 build 承担。

## 后续

ER-S02A 精确定价：明确报价从竞价输出跨入预算/结算的唯一精确边界、六位 HALF_UP 与不可变报价保存。ER-S03 预占将在本账户约束下增加 held 的合法流转；不得用 Redis 计数替代本表，也不得用旧 double 报价直接收费。
