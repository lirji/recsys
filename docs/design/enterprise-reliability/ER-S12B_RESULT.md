# ER-S12B：内部CPA业务事实与原子结算

2026-10-15；基线2f5c3dabbd9697a91d522c3c14df3371d10f8cbc，原目录feat/sandbox-conversion-settlement。**S12B DONE / PASS，父S12仍IN_PROGRESS**。

V013新增独立CPA资格/可见回执/广告主作用域业务事实/账本/捕获Outbox。资格等于原quoteId且用途独立，完整单次转化金额占用同一ad_budget_account；原准备决定/期限/预算日不变。转化actor与展示主体分离，(advertiserId,eventId)数据库唯一键及资格归属外键阻止跨广告主或业务事件重绑；原actor审计，同业务内容可由当前另一个有权主体恢复。公开入口仍未安装，外部授权不是由此内部方法替代，属于S12C2必需验收。

可见与转化任意先到等待；第二条件持有quote和原日账户锁后重读DB时间，统一签发起五分钟，发生时间在窗口内且不晚于接收决定。捕获账户、唯一账本、捕获Outbox和状态在同事务提交；原决定独立于当前状态。迟到/释放/归因窗口外/非canonical业务事件保留非计费事实；零额仍有账本。原命令可在暂停或key轮换后恢复，新条件必须验签。回收SKIP LOCKED、<=100笔/轮、固定延迟/退避，准入和回收独立；无共享环境启用。

## 验证

- PostgreSQL专项23项：并发准备、稳定拒绝、双到达顺序、同事件/可见并发、跨资格同业务键竞争、跨广告主同event UUID独立、当前不同actor恢复原内容且原审计不变、跨主体/错广告主/错用途/篡改、归因窗口及可见政策、释放后仅非计费、合法零额、账本/Outbox/业务决定/准备写失败整体回滚、key轮换、释放/捕获竞争、真实等待账户锁越期、到期工作器SKIP LOCKED与重启、原日跨午夜、18并发CPA/CPC/CPM共享0.5预算仅5笔0.1预占、注释/不可变/PENDING提交约束、default-off/owner/错资源/摘要漂移与真实Spring装配，全部PASS。
- 回收任务5项：批次有界、失败退避、重启和停机行为PASS。
- 最终完整money-chain **290项/57类，0失败/错误/跳过，17模块SUCCESS**。命令同ER-S12A，日志/tmp/recsys-s12b-money.log；专项/tmp/recsys-s12b-narrow2.log为28项PASS，随后补强同资格FK及超时事实断言已由最终290项覆盖。
- 首轮装配遗留已移除的点击FraudPolicy参数导致编译失败，已纠正；首轮PG提交期约束异常由事务管理器包装为TransactionSystemException，断言改为该准确类型并核对PG原错误和无残留，未放宽事务要求。
- Hygiene /tmp/recsys-er-s12b-hygiene.json：IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS。既有根级formatter/独立Java静态分析未配置；事务已审查无网络/MQ调用、同资源REQUIRES_NEW/READ_COMMITTED、quote→命令→账户锁序、失败全回滚。worker catch边界保留持久化待办/指标/类型日志及退避；60秒退避为已命名常量、批次范围沿已有契约。与本地忽略venv中Python Status相似枚举为无关扫描advisory，不改产品类型或venv。

验证限制：临时PG真实锁/约束/事务，不声称宿主kill或灾备；跨午夜时间由数据库参数查询模拟，资金仍真实PG；原actor由内部已授权命令输入，外部严格归属/可信caller将在C2验证。Outbox只原子落库，Kafka及投影属于C1，不能称已发送或S12完成。

8源文件指纹`07a894e209c52d3cbbb73684a7bf4112d671733e633169e3f79a99e96ec3777d`，路径清单/tmp/recsys-s12b-source-paths.json。同步C12设计/契约、架构、手册、切片和根进度；用户七项指纹不变，保留未跟踪/忽略文件，无新worktree。

SKILL_HANDOFF: backend-implementation → implementation-validation → project-documentation → update-progress-docs → task-git-delivery；ER-S12B DONE，validation=PASS，gate=PASS_WITH_LIMITATIONS。下一项ER-S12C1可靠事件，随后C2外部授权/稳定交付；按已有授权正常Git交付，不执行生产部署。

Git交付：2d107e28已正常提交、快进合并并推送origin/main，CI38701260200全部五项SUCCESS；原任务分支继续C2。
