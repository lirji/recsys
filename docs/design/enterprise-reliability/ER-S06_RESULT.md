# ER-S06：账本可靠事件与幂等投影

2026-10-15；基线 `7834ad3b`；分支 `feat/sandbox-billing-events`；[C06](CONTRACTS.md)。DONE / PASS；S07 的浏览器闭环尚未完成。

## 实现与范围

V006 增量加入 Outbox 状态、数据库租约/fence、重试次数/退避、DEAD 重放审计；原始负载不可改删。领取提交后才发送，broker 确认后按有效 fence 标记成功。旧执行器不覆盖新执行器；ACK 与标记之间失败允许重发。专用单线程每轮最多十条，领取最多二十次，最后一次崩溃也会进入 DEAD；人工重放保留原 ID、原负载、操作者与原因。

消费端按每条账本 eventId 去重，Inbox/明细与日汇总同一 adDb 短事务；提交后才确认单条 Kafka 位点。不同合法事实不受账户最大版本影响，重复或乱序不会漏记/重记金额。已知业务字段变更或同资格换事件 ID 拒绝，v1 新增字段兼容。未知版本停止消费者保留位点；普通失败最多三次处理，不无限重试或跳过记录。

复用 Spring Kafka/BOM，未升级依赖，未新增中间件。relay/projection 分别默认关闭，独立于新回执接受，grpc engine 不装配 owner。低基数指标与受限日志关联事件/位点；暂停、重放、对账和回退见 [运行手册](SANDBOX_BUDGET_RUNBOOK.md)。CI 钱链路扩大到 PG/Kafka、RPC、engine HTTP 与 gateway 权限。原 V001～V005 未改，无共享迁移/部署/真实收费切换。

## 验证证据

```sh
mvn -pl recsys-rec-engine,recsys-ad-serving,recsys-advertiser,recsys-gateway -am test -Dgroups=money-chain -Dsurefire.failIfNoSpecifiedTests=false
```

最终 144 tests，0 failure/error/skipped；新增 18 项执行（PG 9，真实 Kafka+PG 4，worker 3，配置 2）。日志 `/tmp/recsys-er-s06-final.log`。Testcontainers 使用 PostgreSQL 16、与 dev_infra recsys profile 相同的 Apache Kafka 3.7.1；客户端 3.6.2，唯一 schema/topic/group。未对共享 broker 注入故障。

| 验收 | 实测 |
|---|---|
| 提交后待发 | 完整预算/准备/回执产生 READY，relay 续发并投影；PASS |
| ACK 后未标记 | 真实 broker 已确认后注入标记异常，SENDING 过期新实例重发两条，只投影一次；PASS |
| 投影后未确认位点 | 真消费记录、PG 提交后注入 commitSync 失败，新 consumer 从未提交位点重放，金额一次；PASS |
| 真实断连 | 仅暂停测试拥有的 broker，发送失败不标 PUBLISHED；恢复后原事件送达并投影；PASS |
| 乱序/重复 | 先较大账户版本后较小版本，各独立事实都累加；同事件八并发，仅一次效果，重复五轮；PASS |
| 原子投影 | Inbox 后与汇总后注入异常均整体回滚，重建 service 后原事件恢复；PASS |
| 租约/上限/重放 | 八执行器仅一领取，旧 fence 无权标记/失败回写；末次崩溃 DEAD，审计后原事件重新领取；PASS |
| 不变量 | 原负载/账本/投影不可改删；换 ID 重用资格、同 ID 改金额拒绝；字段注释与积压查询；PASS |
| 消费停止 | 真 broker 未知 schema 不确认该位点；三次 DB 处理失败后停止、关闭，shutdown 唤醒并有界退出；PASS |
| 配置/资源 | 默认关闭、嵌套配置扫描仍校验 owner/sandbox、旧参数无法覆盖 ACK/位点、复用安全属性，单轮十条/失败退避；PASS |
| 文档/CI | YAML 解析、链接/围栏/DAG、用户六文件哈希、diff --check；PASS |

回归发现并修复真实竞争问题：只指定 ON CONFLICT(event_id) 时，同事件并发可能先命中 qualification 唯一键而异常。改为处理所有唯一键冲突后，强制找到同 eventId 同规范化负载；不同 eventId 重用资格仍失败。没有放宽业务断言，增加五轮并发回归并重新执行全部 144 项。

Code Hygiene 为 IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS，仅无 formatter。spring-kafka 是 C06/TECH_SELECTION 中明确复用的宿主依赖，已显式登记而非引入新版本。通用捕获仅用于受限协议拒绝或 worker 停止/退避，不返回虚假成功；事务同源、无事务内网络、位点顺序已审查并有真实测试。常量提示为已命名的大小/次数边界及退出响应间隔。

## 限制和下一步

这是单节点故障窗口和对象/消费者重建证据，未做整个 JVM kill、副本故障、备份还原、生产负载或 RTO/RPO。毒消息会停止该消费者，不承诺其他分区继续；修复后重启，不自动跳过。topic/Outbox/Inbox 暂不清理，正式保留期与容量尚待确定；依赖漏洞扫描沿用报告模式，不代表安全认证。

继续 S07：真实 API 的 sandbox 搜索交付、可见测量、有界同主体队列与回执状态；浏览器/历史展示不得产生旧收费调用，票据必须从复制、历史和导出中脱敏。不得将本片服务集成测试当作浏览器端到端完成。
