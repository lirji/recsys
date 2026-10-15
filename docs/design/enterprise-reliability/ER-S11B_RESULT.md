# ER-S11B：点击预占与原子双条件结算

2026-10-15；基线 `365595e261d6018a2a430ee16ed1c65372f37767`，复用 `feat/sandbox-click-settlement`。**S11B DONE / PASS**；父S11仍IN_PROGRESS，下一项S11C。边界为 [C11B正式契约](C11_DESIGN.md#c11b-正式结算契约2026-10-15-冻结)，并非真实收费或生产部署。

## 实现与复核

V009新增点击资格/回执/用户计数/账本/Outbox五表，与CPM共享唯一ad_budget_account；不修改V001～V008或旧CPM协议。资格=quoteId，准备和回执首次决定不可变；独立click-qualification用途的HMAC绑定不可变报价摘要及预算日。当前状态区分等待可见/点击和捕获/释放/到期，只有CAPTURED存在ledgerId和实收金额，零元捕获也有事实。

显式同源REQUIRES_NEW/READ_COMMITTED短事务；统一quote→receipt唯一键→rate→原日账户锁序。第二条件原子产生账户迁移、唯一账本、Outbox、结算终态和原回执决定；等待账户或用户锁后重读DB时钟。没有事务内网络调用。点击风控按原配置的默认true/20使用PG固定分钟有效点击计数，超限拒绝并释放；幂等重试不重复计数、DB故障不放行。

状态guard禁止修改首次准备/已接受条件/终态；回执guard及提交时的延迟约束禁止改历史或提交永久PENDING；账本/Outbox载荷不可改删。拒绝、失败、等待、已捕获与零元收费没有混用。到期工作器同序SKIP LOCKED，每笔短事务、单线程有界批次、失败退避和低基数指标，日志只输出错误类型。两个新增admission开关不关闭查询/原结果恢复/释放/回收。

## 验证证据

| 验收 | 实际方法和结果 |
|---|---|
| 准备幂等/期限/主体 | 12线程同报价只一次held；拒绝后补预算不改变原决定；历史已过期报价不能占用；原结果/原token/期限重启恢复 PASS |
| 双条件与并发 | 先点击/先可见、12线程同命令及不同命令、多资格重用receiptId竞争；每种类一个canonical、一个账本、一次风控计数 PASS |
| 身份、用途、观测 | 变种类冲突、跨主体读/准备/释放/接受拒绝、CPM purpose拒绝、改摘要拒绝；999ms/4999bps/hidden/早于签发观测拒绝，新的合法观测可继续 PASS |
| 反作弊 | 同用户跨8广告主并发阈值2只接受2；超限资格释放并永久非收费；拒绝保存失败回滚释放；固定分钟重置、显式关闭风控仍保持资格去重 PASS |
| 原子失败恢复 | 注入Outbox实际INSERT后失败，以及首次回执完成后失败；真实PG检查资金/条件/回执/计数/账本回滚，先前等待条件保留，原ID恢复捕获 PASS |
| 期限与生命周期 | 真实账户行锁等待越过到期后拒绝并释放；释放与第二条件竞争4轮只一个终态；两个expiry worker跳过已锁quote并恢复；停止新增仍回收 PASS |
| 原日及硬预算 | 控制决策时刻的真实PG跨上海午夜仍扣原日；16线程CPM+点击共享0.5额度、每笔0.1，恰5笔held；全部释放后held=0 PASS |
| 数据与版本 | 零元仍一账；AdClickCapturedV1两canonical回执、大ID字符串、精确金额/文本时间，不含token/主体；账本/回执/终态/Outbox不可变；所有新表及列注释完整 PASS |
| 开关和资源身份 | 默认关闭；grpc engine不装配；V009摘要漂移拒绝且旧V003/V008不受影响；错误DataSource拒绝；阈值/回收参数校验 PASS |
| Worker | 单轮有界、失败退避、默认关闭、越界配置、实际单线程调度启停5项 PASS |
| 完整回归 | `mvn -B -e -pl recsys-rec-engine,recsys-ad-serving,recsys-advertiser,recsys-gateway -am test -Dgroups=money-chain -Dsurefire.failIfNoSpecifiedTests=false`：**197项，0失败/错误/跳过**，43测试类，17 reactor模块全部SUCCESS；包含22项新增PG和5项worker、原CPM/预算/RPC/HTTP/PG/Kafka链路 |
| Hygiene | `IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS`；无根级canonical formatter，沿已有Java风格，FORMAT=SKIPPED，Java独立STATIC_ANALYSIS未配置。其余无阻断；数值advisory为已命名退避上限/既有配置范围，catch Exception为调度边界失败退避；枚举相似项来自忽略的Python虚拟环境，与Java领域无关 |

日志：首轮20项PG `/tmp/recsys-s11b-pg.log`；最终197项 `/tmp/recsys-s11b-money.log`；编译 `/tmp/recsys-s11b-compile.log`；hygiene `/tmp/recsys-er-s11b-hygiene.json`。首次编译/PG均通过，之后补充风控失败/分钟边界、回收任务及脱敏检查，再完成最终回归，没有降低断言或跳过测试。

测试时钟说明：不可变报价通过隔离测试INSERT触发器生成历史签发时刻，保持完整五分钟；跨午夜用测试适配固定两个数据库返回时刻，资金仍由真实PG约束/事务执行；风控并发固定同一分钟，避免测试偶然跨窗。实际到期锁等待测试使用真实DB时钟。重启恢复以重新构造repository/service验证，故障注入为事务异常，不声称已完成宿主kill、整站灾备或生产时钟同步演练。

本片无可见UI变化，浏览器视觉验收N/A；新RPC/HTTP与点击Kafka/Inbox为S11C范围，本片不写PASS。Outbox落库不等于投递完成。没有新增依赖、middleware、端口、共享环境迁移或真实收费；保留策略仍待治理，无自动删除。

## 文档与交付

同步C11_DESIGN、CONTRACTS、IMPLEMENTATION_SLICES、BACKEND_ARCHITECTURE及SANDBOX_BUDGET_RUNBOOK。运行手册增加V009、admission与回收/原结果语义、混合模式账本对账，明确旧CPM投影不能独自代表账户总消费。实际部署仍未执行。

原目录单worktree；六个用户文件及未跟踪C15A草案按SHA256核对保留，不纳入提交；console/test-results及忽略的本地配置/数据/模型/构建产物保留。Git按已授权的正常提交/合并/push main执行，交付后记录实际提交与CI；不强推、不清理用户文件。

SKILL_HANDOFF: backend-implementation → implementation-validation → project-documentation → update-progress-docs；slice=ER-S11B，status=COMPLETED，gate=PASS；仅formatter/未配置Java静态检查为已明示限制。下一项S11C冻结双模式API/恢复与可靠事件契约，完成后父S11才能DONE。

最终非文档验证指纹（按路径及SHA256排序汇总）：`513bfe3664e2bbd0f2bf4a4188da25a4c2a971bc99e5603dc98899ce1ad7f150`。文档本地链接、围栏、git diff --check和保护文件指纹通过。
