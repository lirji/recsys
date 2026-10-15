# 混排 sandbox 交付与恢复

依据 [C10 设计](C10_DESIGN.md) 和 [正式契约](CONTRACTS.md)。默认关闭，仅新测试账户；不包含真实收费切换或生产部署。

## 所有权、开关和迁移

engine primary 保存整次最终计划；ad owner 在自己的 adDb 读可信接受账本做频控，并持有报价、预占、回执、账本和资金 Outbox；behavior 保存自然事实和自然 Outbox。禁止跨库修改这些数据。没有新增中间件或依赖版本。

按先接收后生产的顺序准备获授权的隔离环境：

1. common/behavior 升级到支持自然 kind=FEED；自然回执沿用 [自然运行说明](NATURAL_EXPOSURE_RUNBOOK.md) 的显式政策/key 和 V001。自然旧 RECOMMEND/SEARCH 字节及 HTTP 不变。
2. ad owner 已有账务 V001～V006 后显式执行 `recsys-ad/src/main/resources/db/ad-billing/V007__feed_frequency.sql`。新增两条接受日频控索引，不改历史表或资金事实。
3. engine 已有 V001/V002 后显式执行 `recsys-rec-engine/src/main/resources/db/engine-delivery/V003__feed_delivery.sql`。每个脚本先设置会话 `recsys.migration_sha256` 为实际 SHA256，使用明确目标连接及 ON_ERROR_STOP；脚本含事务。启动仅核验摘要/结构，不自动建表。不要修改已执行迁移。
4. ad 的 `recsys.ad.billing.feed-candidates-enabled=true` 还需要原 sandbox owner；沿用 `AdProperties.freq` 的正整数每广告/每广告主日上限。engine 的 `recsys.feed.delivery-enabled=true`、自然完整政策和密钥，以及原广告准备/回执、behavior 自然回执均须就绪。最后以 `VITE_FEED_EXPOSURE_SANDBOX_ENABLED=true` 构建 console。

以上新能力开关默认 false。配置是启动快照；每次操作另冻结 AdLoad、广告实验 bucket/reserve、自然政策和最终内容。广告配置位仅 1～10，混排总量 1～50。两种 owner 访问模式共用同一规则；gRPC 新增 FeedCandidates，旧服务不支持时按候选不可用冻结自然结果，不能退回旧收费 API。

频控采用接受时间所在的 Asia/Shanghai 自然日，与资金所属的原预占日分开。仅已接受账本计数，HELD 不计数。它是候选时的事实过滤，不是并发严格频次预占；硬预算上限仍由 reservation 保障。查询最多十个候选，各计数在配置阈值处停止，并有数据库语句超时。

## 操作与结果未知

ADMIN `PUT/GET /api/sandbox/feed-deliveries/{operationId}`。进入页面不自动创建；新交付先保存 ID 和原请求，再 PUT。完整响应 200；准备中 202 可能已含可浏览自然内容及已确认广告。刷新只 GET，恢复原操作始终原 ID/body；编辑输入不会改已交付结果。GET 不推进准备。

完整混排提交前没有 prepare 副作用；之后位置、自然资格、广告 childId/候选证明均不变。纯候选故障可以完成纯自然响应，原操作恢复不追加广告。冻结后 prepare 超时保留 PREPARING；未知广告显示占位，不展示可测创意。恢复先查原 child，再用原 offer/slot 续办；预算不足或过期同样占位，不补位、不移动后续自然内容。

30 秒数据库租约允许接管，fence 和旧计划同时防覆盖。短事务不持锁调用远程 owner。暂时未交付的预占仍按 S03 到期释放；不得人工删除操作或重置 childId。503 表示依赖或存储失败，不能当成功空列表。

自然、广告仍各用原回执协议和队列。自然接受不计费；广告金额仅显示权威回执，预占报价不是实扣。hidden/离屏/历史/对比遮盖重置测量；两队列总量上界 200 条/1MiB/4 并发。退出清原意图和两队列；关页未提交观测仍可能丢失。可靠事件保障从服务端接受开始。

## 查询与回退

仅在对应 owner 的受限连接查询关联 ID/计数，不导出含票据的 plan_json：

```sql
-- engine：未决规模与最早时间；可能只有广告项未决。
SELECT state,count(*),min(created_at) FROM engine_feed_delivery GROUP BY state;
SELECT operation_id,fence,lease_until FROM engine_feed_delivery
WHERE state='PREPARING' ORDER BY updated_at,operation_id LIMIT 100;
-- ad owner：接受日和原预算日用于不同问题，不能混为一个口径。
SELECT (accepted_at AT TIME ZONE 'Asia/Shanghai')::date AS accepted_day,
       budget_date,count(*),sum(amount)
FROM ad_billing_ledger WHERE traffic_class='SANDBOX'
GROUP BY accepted_day,budget_date;
```

暂停新建用 engine `recsys.feed.admission-enabled=false`，已有操作继续恢复；必要时同时关闭页面新入口并重建。保留原查询、回执、预占回收及已接受事件投递。不能直接降级到不识别 FEED 的 behavior 或删除 V003/V007。关闭开关不撤销账本/自然事实。

操作/票据/事实没有已批准的保留期限，本片不清理。扩量前明确容量、归档和告警阈值；本片不承诺生产 RTO/RPO 或并发硬频控。

## 隔离验收

`python3 scripts/test-feed-exposure.py` 使用现有 Docker、JDK21、Node/Playwright，启动临时 PG16、同 JVM 三个独立 owner schema/DataSource、数据库种子和 loopback 测试认证。真实执行最终冻结、预占、捕获、自然接受及 Outbox 写入。候选故障和提交后丢响应由专用测试适配器注入，不代表生产 gateway/OIDC、多进程或 Kafka feed 联调。

截图/日志在 `recsys-rec-engine/target/feed-browser-evidence/`、`feed-browser-backend.log`，CI 同时归档。自然 Outbox 仍仅落库，可靠发送归 S15；资金投递共用 S06，广告原浏览器另有真实 PG/Kafka 回归。详见 [S10 验收](ER-S10_RESULT.md)。
