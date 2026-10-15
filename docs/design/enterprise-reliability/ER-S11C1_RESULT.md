# ER-S11C1：可靠点击事件与独立资金投影

2026-10-15；基线`b2c2911ae5233b5a2b90c5aba5624141cf5d75a7`，复用原任务分支。**C1 DONE / PASS**；父S11保持IN_PROGRESS，下一项C2稳定交付/双模式API。没有真实收费或生产部署。

## 实现及复核

V010新增点击重放审计、不可变Inbox事实、跨VISIBLE/CLICK共用回执身份索引及click_count/charged_amount日投影。固定IMPRESSION/CLICK事件族复用S06领取、fence、ACK后标记、退避、重放审计和提交DB后确认offset的传输机制；独立codec/Inbox/金额汇总，旧构造/CPM默认保持兼容。SQL命名空间仅来自有限服务端枚举，值仍绑定。事务同DataSource校验，无事务内Kafka调用。

点击事件严格已知字段、大小/重复字段/尾随内容、枚举与单位版本、key、规范大ID字符串/金额；未知扩展字段可兼容，已知负载规范化摘要用于幂等。不同合法事实即使账户版本倒序也累加；同ID变负载或复用资格/任一种类回执失败。共同回执索引以UUID排序写入，避免交叉领取死锁，失败回滚整个Inbox及投影。

ClickEventConfiguration独立默认关闭、owner限制、迁移校验、topic/group和低基数指标；与旧CPM用显式Qualifier共存。停止新报价/预占/接收不会停发既有资金事实，不要求签名密钥才能排空事件。

## 验证

| 范围 | 证据 |
|---|---|
| 新PG 12项 | 领取租约与旧fence、并发claim、耗尽/审计重放、同ID冲突与独立倒序事实、失败回滚、跨种类回执复用拒绝及回滚、严格解码、CPM+点击共享账户且独立投影对账 PASS |
| 新真实Kafka+PG 5项 | 发送ACK后DB标记前故障重发；Inbox提交后位点失败重放；broker暂停超时恢复；实际消费者重复/乱序和毒消息停机不确认；两套真实Spring装配收费开关均关闭仍发送并排空各自投影 PASS |
| 新配置2项 | 默认关闭/owner范围、独立group/topic与共享有界Kafka参数 PASS |
| 完整回归 | 最终216项、46类、0失败/错误/跳过，17模块全部SUCCESS；覆盖原CPM/预算/PG/Kafka/HTTP/RPC以及S11A/B回归 |
| Hygiene | 修复首轮字符串类型比较为既有BidType枚举的严格valueOf，未知值不默认CPC；最终无阻断，IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS。既有根级无formatter、未配置独立Java静态分析；异常捕获为事件边界脱敏并统一拒绝，不吞ACK，事务警示已按上述实际失败回滚复核 |

最终命令：`mvn -B -e -pl recsys-rec-engine,recsys-ad-serving,recsys-advertiser,recsys-gateway -am test -Dgroups=money-chain -Dsurefire.failIfNoSpecifiedTests=false`，日志`/tmp/recsys-s11c1-money-final.log`；此前首轮`/tmp/recsys-s11c1-money.log`亦216 PASS，枚举等价修正后完整重跑。窄集初轮`/tmp/recsys-s11c1-events.log`不作为最终revision证据。Hygiene `/tmp/recsys-er-s11c1-hygiene.json`。

故障注入为真实broker暂停、实际DB事务失败或提交后抛错，非宿主kill/全站灾备；测试在隔离Docker/schema/topic执行，不迁移共享环境。无前端变化，浏览器视觉验收N/A。没有新增依赖或中间件。

## 文档、范围与交付

同步C11_DESIGN、CONTRACTS、IMPLEMENTATION_SLICES、BACKEND_ARCHITECTURE和SANDBOX_BUDGET_RUNBOOK、CODEX_PROGRESS；记录Q-04本轮用户批准的CPA五分钟政策，但尚未实施S12。S11B实际commit/CI也已同步。

源码改动为BillingOutboxRepository/Relay/Job、BillingProjectionConsumer、BillingEventConfiguration的有限复用；新增ClickEventCodec/Configuration、ClickProjectionRepository/Service、V010和4个测试/fixture文件。当前非文档源码指纹：`1604da78c4297442c3ff3b1e0c5be6ebc628ac3c6c331fd99a29f4288c3af858`（路径+逐文件SHA256排序汇总）。保护文件七项及原目录单worktree未改；console/test-results、忽略配置/数据/模型/产物全部保留，未授权清理。只stage本片明确路径，正常合并推送main。

SKILL_HANDOFF: backend-implementation → implementation-validation → project-documentation → update-progress-docs → task-git-delivery；slice=ER-S11C1，validation=PASS，status=COMPLETED；下一项ER-S11C2，父S11不能因事件链完成提前关闭。

Git交付：`e599b3e5`已正常提交、快进合并并推送origin/main；继续C2。远程[CI 38695851841](https://github.com/lirji/recsys/actions/runs/38695851841) completed/success，5个job全部通过。
