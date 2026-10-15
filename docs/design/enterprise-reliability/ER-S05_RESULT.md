# ER-S05：可见回执原子捕获

2026-10-15；基线 `9c95f2dc`；分支 `feat/sandbox-visible-receipt`；[契约 C05](CONTRACTS.md)。DONE / PASS，端到端闭环仍待 S06～S07。

## 实现与范围

ADMIN PUT/GET `/api/sandbox/ad-impressions/{receiptId}` 接受签名资格及可见性声明，校验主体、持久化准备原 token、报价/预占绑定、当前状态、50% 连续一秒政策和数据库接受期限。receiptId 同负载返回原结果，不同负载冲突；同资格不同回执返回唯一原账本，拒绝结果也持久化。客户端声明不是防作弊或行业认证。

adDb 单事务保存回执结果、held→charged、CAPTURED、不可变账本与待发 Outbox。沿 reservation→account 锁序；等待账户锁后重新读数据库时刻，跨日计原预占日。SQL 关键写入检查行数，同源数据源装配验证，无事务内 RPC/MQ。V005 增量扩展状态与约束、全表字段注释，原 V001～V004 不变。

impression.v1、两种 gateway、HTTP 与网关 ADMIN 路由接通；内部 RPC 仍要求可信 engine 身份，错误不变为空成功。receipts-enabled 默认关闭；receipt-admission-enabled=false 仅暂停新回执，保留原结果恢复和查询。未执行共享库迁移、真实收费切换、部署或新增依赖。

## 验证证据

```sh
mvn -pl recsys-rec-engine,recsys-ad-serving,recsys-advertiser,recsys-gateway -am test -Dgroups=money-chain -Dsurefire.failIfNoSpecifiedTests=false
```

126 tests，0 failures/errors/skipped；本片七类新增 23 项，其中真实 PostgreSQL 13 项。最终日志 `/tmp/recsys-er-s05-final.log`。构建涵盖所有受影响模块；RPC 走本地真实 socket/身份拦截器，owner 为替身，资金语义单独由 PG 验证。

| 验收 | 结果 |
|---|---|
| 并发及恢复 | 同 ID 10 并发仅一账/一事件/一次扣费；不同 ID 同资格 10 并发返回同账本；重建 service 查询原结果；PASS |
| 可信边界 | 篡改、跨主体、未持久化资格、冲突输入拒绝；旧已提交 ACK 不依赖已退出签名 key；PASS |
| 可见政策 | 999ms、低于 50%、hidden、声明时长超过资格存续均拒绝且不扣费，新合法回执可捕获；PASS |
| 原子性 | 捕获后、账本后、Outbox 后、结果保存后注入失败全部回滚，原 ID 重试成功；PASS |
| 竞争及时间 | 捕获与释放竞争只一终态；锁等待越过期限拒绝；跨日历史 fixture 计原账户，当日账户不变；PASS |
| 完整性 | 账本 UPDATE/DELETE 被数据库拒绝；已捕获状态与账本不一致时 fail closed；字段注释/终态约束校验；PASS |
| 事件 | 原始精确金额、业务字符串 ID、ISO 时间；无 token/subject；published_at 保持空；PASS |
| 暂停与访问 | 暂停新增后原 ACK/GET 有效；RPC/HTTP 区分冲突/无权/拒绝/未知；网关匿名 401、USER 403、ADMIN 可达；PASS |
| 配置兼容 | 默认关闭、grpc engine 无本地 owner、缺资源拒绝启用；两份 gateway YAML 路由解析通过；PASS |
| 文档/代码审查 | 链接、围栏、DAG、六个用户文件哈希、diff --check；事务同源/锁序/回滚审查；PASS |

Code Hygiene：IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS，无 blocking finding；仓库未配置 formatter。可见阈值已使用具名政策常量；事务提示已用真实 PG、显式事务和无外部调用逐项核对。没有用纯 Mock 证明数据库原子性。

## 后续及限制

Outbox 目前仅待发，S06 提供确认后标记、失败重试、按账本 ID 幂等投影；不得按账户最大版本丢弃乱序独立资金事实。S07 补浏览器采集。未验证整进程崩溃、真实 broker 断连、生产容量或 RTO/RPO，未声称全系统已生产就绪。

V005 启用后旧 S03/S04 owner 不认识 CAPTURED，不可直接降级；暂停新增并保留兼容查询/回收版本，已发生账务需业务补偿，不能删账回滚。历史保留、正式来源及期初账务仍待业务确定，旧收费链路风险和共用内部认证密钥未由本片消除。
