# C13：sandbox账户预算日对账与故障续办

对应原PLAN ER-S13/F12。S12已交付b8cf737b；本文件在已批准总体架构内细化，不新增服务或账务权威。用户授权继续整个DAG；本片不改变价格、归因、预算日、收费/冲正政策，不运行真实账户修复。

## 事实、取舍和所有权

F13-01：三种模式共用ad_budget_account；CPM金额在ad_budget_reservation，CPC/CPA金额在不可变quote，当前状态和原预算日在settlement。三族ledger/outbox/projection均在ad owner的adDb；projection不是资金真值。

F13-02：三族ledger均有账户/预算日/accepted_at/ledger_id索引；CPC/CPA活跃settlement当前仅qualification_id partial索引，新增账户日查询需追加索引迁移，不修改已执行V001～V015。

F13-03：既有Outbox replay只允许DEAD并审计。三类projection_fact不可改删；已有expireOne全局扫描，不可冒用为单账户恢复命令。offline目前没有资金owner写接口；它不应直接写adDb。

D13-01：复用ad owner本地PG/JDBC实现核查，offline后续通过受控契约驱动。备选离线直接扫写资金库会破坏单写权威；新对账服务/调度框架无当前需求且增加故障边界，均不采用。每次只针对一个明确的SANDBOX+CNY+advertiserId+budgetDate。

D13-02：单次快照用同adDb的REQUIRES_NEW、REPEATABLE_READ、readOnly、5秒事务/语句截止。相比逐条READ_COMMITTED查询，避免捕获恰发生在账户/账本两次查询之间造成假差额；相比锁账户，避免只读对账阻塞正常资金写入。报告observedAt和accountVersion，不声称跨任务或全局提交水位。外层事务、错误DataSource/事务模式拒绝。

D13-03：资金核对、传输积压、投影落后三类状态分别表达。合法零金额仍核对事实笔数与身份。缺库/缺表/超时是未知异常，不用全零伪装健康。首次没有projection_daily行且没有事实是合法空集合，summaryPresent显式说明；不能与依赖不可用混同。

D13-04：A仅查询；后续有界任务持久化冻结输入、阶段/检查点、租约fence、原结果和审计。恢复只续办已有义务和原事件，不直接平账；无法解释的账本/账户或已存在坏投影标记需人工处理，不能删改历史。任务取消不撤销已提交业务效果。

## C13A正式契约：内部只读账户日快照

Scope={advertiserId正long,budgetDate严格ISO日期}，trafficClass固定SANDBOX/currency固定CNY。返回Snapshot={scope,observedAt(数据库观察时间),account(当前权威BudgetAccount或null),fundsStatus,heldDelta,chargedDelta,families[固定IMPRESSION,CLICK,CONVERSION]}。account=null须ACCOUNT_MISSING，两个账户差额为null，不能把账户不存在当作0余额。

每族FamilySummary包含：family；heldCount/heldAmount/expiredHeldCount；ledgerCount/ledgerAmount/missingLedgerCount/invalidLedgerCount；pendingOutboxCount/deadOutboxCount/missingOutboxCount/oldestPendingAt；factCount/factAmount/missingFactCount/invalidFactCount/unexpectedFactCount/lastAppliedAt；summaryPresent/summaryCount/summaryAmount；projectionStatus。各族统计与delta金额为精确六位十进制字符串；delta允许负数；空时间为null。account在本片内部复用既有BudgetAccount精确BigDecimal领域值；后续C13C的RPC/API映射须统一字符串，不直接序列化领域对象为外部金额。差额定义heldDelta=account.held-sum(各族HELD金额)，chargedDelta=account.charged-sum(各族账本金额)。资金BALANCED只在两个差额为0且没有缺失/不匹配账本、缺失Outbox时成立；否则MISMATCH。缺失账户单独ACCOUNT_MISSING。

账本核验：当前CAPTURED义务必须有同资格账本；账本必须指向CAPTURED义务，匹配原账户/预算日/金额，以及CPC/CPA保存的ledgerId（CPM由账本qualification唯一键关联）。CPM资格与reservation_id相同，CPC/CPA资格与quote_id相同。已释放/过期不计held；到期但尚未释放的HELD仍计入held且另外计expiredHeldCount，不提前从余额扣除。

投影核验：按eventId对账同资格/金额/广告主/预算日，额外事实与同ID不匹配分开；不以总额或最大版本代替逐事件匹配。daily按实际fact笔数/金额核对；无fact且无daily为正常空集合。有坏/额外fact或daily不一致为MISMATCH；否则缺少fact为LAGGING；完全匹配为CAUGHT_UP。传输READY/SENDING/DEAD和最老创建时间独立展示，PUBLISHED不等于消费完成。该口径验证金额与关键身份，不冒称验证所有事件扩展字段。

单次报告仅同owner事务，不跨库join、不调用Kafka/Redis/RPC。SQL集中ReconciliationRepository；Application Service验证同一事务资源。独立`recsys.ad.billing.reconciliation-enabled=false`与sandbox共同安装；不依赖新增收费admission，暂停收费仍可核对历史。启动验证必要既有迁移及追加V016索引摘要，不自动迁移。

## C13B正式契约：持久化有界只读任务

2026-10-16 在用户已批准的 S13B 范围内冻结以下内部契约；不增加收费规则、对外接口或自动全账户调度器。

- 创建输入为 advertiserId、taskId(UUID)、startDate/endDate（原预算日，含两端，1～31天）、reason（1～256字符）、可信 actor（1～128字符）。流量 SANDBOX、币种 CNY 固定。taskId 全局唯一且绑定广告主；同ID同范围/原因返回原任务，actor仅作首次审计，不参与幂等比较；跨广告主或内容变更拒绝。每广告主最多一个 READY/RUNNING 任务，由数据库部分唯一索引保障，不同广告主互不串行。
- 状态 READY→RUNNING→READY/COMPLETED；失败可从 RUNNING→READY（退避）或 FAILED；READY/RUNNING 可取消为 CANCELLED。COMPLETED 仅表示全部日期核查完毕，不能当作资金平账。FAILED/CANCELLED 保留已完成日期，终态不自动重开，需要新taskId重新核查。
- 每次 advance 只领取一个 nextDate；短 READ_COMMITTED 独立事务，数据库时钟，30秒租约，单调 version 作为 fence。每日期最多20次领取，领取即计次，崩溃/超时也计数；租约过期可重新领取，次数耗尽转 FAILED。失败指数退避从1秒至300秒上限，并带最多20%抖动且总延迟不超过300秒；成功推进一天并归零次数。全部写事务5秒截止。不得在外层事务中调用任务用例。
- 领取提交后调用C13A独立5秒只读RR快照；不同日期observedAt不同。仅当taskId/advertiserId/date/version/state/有效lease全部匹配时，原子插入不可变每日报告并推进检查点。完成事务提交前失败则报告和进度均回滚；提交后丢响应以taskId及日结果查询恢复，重复advance处理下一未完成日期。旧fence、过期lease、取消后的回写被拒绝。
- 日结果主键(taskId,budgetDate)，包含序列化版本1、完整Snapshot、observedAt、领取fence；DB拒绝修改/删除报告与审计。报告读取受task所属广告主约束，最多31条，顺序按budgetDate；坏JSON/缺依赖明确失败，不转成空报告。
- cancel输入为advertiserId/taskId/commandId(UUID)/reason/可信actor。相同取消commandId和reason返回原CANCELLED结果，原actor不被改写；不同取消命令、不同原因或对COMPLETED/FAILED取消均冲突。取消与完成按任务行锁串行化；取消不删除已有日结果、不改变账本、不撤销已提交业务效果。
- 创建、领取、失败/租约耗尽、日完成、取消均写追加审计(taskId,version,event,actor,budgetDate,attempts,DB时间)，与状态更新同事务。错误类型只存固定 SNAPSHOT_FAILED/LEASE_EXHAUSTED，不落异常消息或凭据。
- 新表迁移V017；保留期限未知，暂不自动清理。复用adDb/MyBatis XML、现有Jackson；无新依赖。独立reconciliation-tasks-enabled=false，需sandbox-enabled与reconciliation-enabled共同开启；不依赖新增收费admission。应用启动只校验迁移，不自动DDL。

允许文件：common-ad新增ReconciliationTask内部值；ad新增任务Repository/Service/Configuration、Mapper/XML、V017及真实PG测试；更新本设计/CONTRACTS/TECH_SELECTION/实施状态/运行手册/验收证据。所有SQL放XML，业务层不拼SQL。

验收：真实PG持久化重建服务、同ID重复/冲突、并发创建/领取、广告主隔离、逐日部分结果、故障退避/耗尽、崩溃后租约接管、旧fence/过期回写拒绝、取消幂等和终态、日结果不可变、报告/检查点原子提交及丢ACK查询；开关默认关闭、迁移缺失/漂移拒绝。回滚停新任务入口并保留表与结果，不删任务；无资金业务回滚。

## C13C待细化

C13C：受控原事件重发/到期义务续办、独立可信RPC与offline BillingReconciliationJob。需要明确重发条件/幂等审计、单账户范围与授权，复用原payload/eventId；错误资金或已有坏fact不自动修复。真实PG/Kafka验证恢复后对账追平，不能把broker ACK或进程健康当业务通过。该部分仍是父S13必需范围，不由A替代。

## 文件、验收与回滚

A允许common-ad新增BillingReconciliation契约，ad新增ReconciliationRepository/Service/Configuration、V016索引、真实PG测试，相关文档。无新RPC/HTTP/UI、运行依赖、定时器、额外组件或共享环境写入。S14运营API/UI在父S13后实施。

A验收：真实三族等待/捕获/释放、原预算日/广告主隔离、零金额笔数；账户平账而投影落后；同ID错误fact/额外fact/daily偏差；账户缺失与空日；并发capture跨读取间隙的快照一致；事务配置/资源错配拒绝、只读不可写、schema与默认关闭；查询有5秒截止。采用独占PG schema，既有fixture产生实际资金事实。不为大规模容量造虚假结果；聚合查询超时保持未知，Q-07目标仍待业务数据。

新增索引不改旧数据/历史，能力默认关闭，回滚可停止新核查并保留索引。B/C任务与义务恢复另有排空约束。审查确认没有新服务、跨库写、隐藏双写或自动金额补偿。
