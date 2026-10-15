# ER-S04：可恢复的 sandbox 搜索广告交付

2026-10-15；基线 `7b6636df`；分支 `feat/sandbox-delivery-operation`；[契约 C04](CONTRACTS.md#c04sandbox-整次交付与准备er-s04)。本片 DONE / PASS，首个曝光资金闭环仍待 S05～S07。

## 实现与范围

新 ADMIN HTTP PUT/GET 入口 `/api/sandbox/search-ad-deliveries/{operationId}`，source 固定 SANDBOX，subject 来自认证上下文，userId 仅为模拟用户。只支持首版 CPM/GSP，1～10 位。旧接口兼容保留，feed 的自然/广告混排与频控接入留给 S10。

AdPipeline 纯候选分支不调用旧预算过滤/扣费、曝光日志、GD 交付计数或 uplift assignment；复用召回、相关性、定向、排序和 DCO，保存精确价格依据。依赖失败不会被新分支转成空广告成功。ad owner 用专用、无默认值的密钥环签候选证明，终端无价格/候选输入接口。

engine primary 持久化请求、首次实验参数，30 秒数据库租约和递增 fence 控制执行器。最终去重/截断后先保存整页条目/位置/稳定子 ID，随后才准备；逐项保存结果，失败后原 ID 续办，已冻结计划不重新竞价。拒绝项保留原位，不补位。网络调用在 DB 事务外，并拒绝带外层事务执行编排。

ad owner V004 记录不可变准备输入和首次结果，按报价→预占→资格结果分步恢复。报价首次签发须在候选期限内，已有报价不续期；未使用 held 由到期 worker 释放。资格签名绑定主体、父/子 ID、模拟用户、广告/创意、最终 slot、精确金额/来源/币种、可见政策和原五分钟期限。重试返回原资格；原 QUALIFIED 不代表当前预占仍 HELD，S05 将验证当前状态再收费。

delivery.v1 RPC 与两种 gateway 共用 DTO，要求已验证的 engine 内部身份；新方法不使用旧吞错/空广告 fallback。grpc engine 不启动本地新账务/回收 worker；in-process 和 ad-serving 为 owner。没有新增依赖/基础设施、自动数据库迁移、真实账户切换或部署。

## 验证证据

```sh
mvn -pl recsys-rec-engine,recsys-ad-serving,recsys-advertiser -am test -Dgroups=money-chain -Dsurefire.failIfNoSpecifiedTests=false
```

103 tests，0 failure/error/skipped；本片新增 29 项，其中真实 PostgreSQL 14 项。修正边界与代码审查发现的问题后，另跑 25 项针对性回归通过。证据 `/tmp/recsys-er-s04-final.log`、`/tmp/recsys-er-s04-review-final.log`；编译涵盖全部受影响模块。RPC 测试走真实本地 gRPC socket/身份拦截器，owner 用替身，资金/恢复语义另由 PG 验证；不冒充整套服务已部署端到端验收。

| 验收 | 实测结果 |
|---|---|
| 纯候选无收费副作用 | pipeline 测试校验旧 pacing/log/GD/uplift writer 均未调用，精确报价保留；查询异常传播；PASS |
| 证明与身份 | 篡改/错用途/未知 key 拒绝；新旧 key 显式轮换；匿名、通用身份和其他服务不能调用准备；PASS |
| 原资格恢复 | PG：8 个同 child 并发只一份 held/同 token；重建 service 返回原结果；拒绝/释放后重试不重占；PASS |
| 中断恢复 | PG：quote 提交后失败、held 提交后资格结果失败，原 ID 可恢复；已过期 quote 不续期；过期 offer 不创建 quote/held；PASS |
| 整次交付 | PG：第一项成功、第二项 owner 已提交但响应丢失，恢复仍为原条目/位置/资格，不重新查询候选；PASS |
| 持久化先于副作用 | PG：计划写入失败没有任何 prepare；正常空候选完成，依赖异常仍 PREPARING；PASS |
| 租约 fencing | PG：8 个并发领取仅一个成功；旧租约不能写计划/结果，也不能释放新租约；PASS |
| 访问与兼容 | MVC 显式 ADMIN/原主体，严格 ID/slots，JSON 响应；冲突/无权/未知区分；两模式结果相同，远程超时不变成空结果；PASS |
| 迁移与边界 | engine 独立 V001 与 ad V004 全字段注释；原 V001～V003 无差异；owner 默认关闭、grpc engine 不装配，缺资源拒绝启用；PASS |
| 文档与保护 | 运行手册含配置/密钥轮换/查询/恢复/回退；链接、围栏、DAG、用户六文件哈希和 diff --check；PASS |
| Code Hygiene | IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS：仓库无 formatter；无 blocking finding，MAX_SLOTS 已是具名技术边界常量，检查器提示已核对 |

边界测试曾发现 HTTP 未显式 JSON 输出，已在入口声明 produces 后通过；测试桩异常重复设定问题已修正，没有降低行为断言。候选、已接受准备、当前 held 与已收费四个概念没有混用。

## 未验证与后续

没有完整进程 kill/restart、生产负载、RTO/RPO、浏览器采集或 Kafka 事件投递证据；不据此声称全系统生产就绪。操作与幂等历史目前不自动清理，长期保留策略待业务确定；密钥保留需覆盖未完成操作。既有内部服务共用认证密钥、召回/定向组件内部降级及旧收费风险没有被本片消除。

ER-S05 下一步：以签名资格和持久化准备/预占为准，接受可见回执，并在同一 adDb 事务内完成幂等结果、held→charged、唯一账本事实和 Outbox；与到期释放竞争只允许一个终态。不能先发 Kafka 再写账，也不能用过期的首次 QUALIFIED 直接收费。
