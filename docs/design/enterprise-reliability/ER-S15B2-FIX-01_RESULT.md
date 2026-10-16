# ER-S15B2-FIX-01：源删除不消除已知非法快照

本地整改验收 **PASS**。本回合只关闭B2父复核边界，不同时进入C15C设计或消费者实现；父S15及S15～S21整体仍未完成。权威状态见 [IMPLEMENTATION_SLICES](IMPLEMENTATION_SLICES.md)。

## 发现与确定性复现

基线 `86a094ff370e9def9300fce68a7a830e49a99f19`，B2已正常交付；16个B2源文件、11保护及午夜fixture指纹与上一回合一致，复用原43+24项有效证据。父审查来自 `.dev/codex-flow-reliability/round-8395c172/PARENT_REVIEW.md`，不是直接判FAIL的依据。

新增真实PostgreSQL三态用例：先捕获一个非法源和一个合法源，保存原Outbox所有列与原命令报告，再分别保持非法源不变、删除非法源、修改非法源；合法源删除作为共同对照。未改产品代码时运行3例，只有deleted组合失败（1失败、0错误、0跳过）。实际非法项为ALREADY_CAPTURED、error=null、命令COMPLETE、covered=2；期望INVALID_SOURCE、COMPLETED_WITH_ISSUES、covered=1（仅合法对照项）。错误分类同时掩盖了已知问题和覆盖缺口，父发现由实际反例确认。

## 最小修正及历史边界

只调整 `BehaviorHistoryRepository.advanceOne` 的既有事件分类：当前源存在且摘要漂移仍为SOURCE_CHANGED；否则先保留已持久的INVALID_SOURCE，再允许ALREADY_CAPTURED。合法快照的源删除继续复用原事件，缺失且无原事件仍WAITING_SOURCE。报告沿原统计自然排除非法项，并以COMPLETED_WITH_ISSUES结束无等待的清单。

没有改Mapper SQL、已发布V001/V002、eventId、wire/source快照、发送状态或任何历史记录，也没有重新捕获/重发非法事件。新增三态回归确认原Outbox逐列相等、原命令报告不变、新命令重试稳定；有当前源漂移仍保持原SOURCE_CHANGED语义。过去已终态的误分类不会自动回写：保留原审计，以新稳定commandId和受控显式清单重新核验。该修正不构成数据删除、自动回填或共享环境执行。

## 验证与证据

证据目录 `.dev/codex-flow-reliability/round-9fb853b6/` 全部不提交：

| 验证 | 实际结论 |
|---|---|
| 未改产品代码的真实PG反例 | 3例，deleted一例失败，其余两例PASS；`invalid-source-red.log/xml`，不是环境错误 |
| 修复后完整历史清单PG | 15项PASS，含新增3态、合法删除、原快照/历史不变、前10缺失公平续办、低ID晚提交、并发advance/cancel、原子回滚和独立JVM退出恢复 |
| 原history真实Kafka恢复 | 2项PASS：独占broker故障后原快照恢复、ACK未标记后真实租约恢复/同字节不同offset及测试Sink单效果；不代表实际Seen恢复 |
| 最终汇总 | 17项/2类，零失败/错误/跳过；`history-final.log`、`final-reports/`、`test-summary.json`、`recovery-markers.log` |
| 静态/卫生 | persistence边界、公开错误、diff检查PASS；hygiene=IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS，formatter及独立Java静态分析未配置 |
| 独立验证 | 同一Agent分离只读pass，核对分类穷举、测试红绿、不可变事实及变更边界；不声称第二评审者，见`independent-review.json` |

反例命令：`mvn -B -pl recsys-behavior -am test '-Dtest=BehaviorHistoryPostgresTest#knownInvalidSnapshotRemainsIssueAcrossSourceLifecycle' -Dsurefire.failIfNoSpecifiedTests=false`。

绿色命令：`mvn -B -pl recsys-behavior -am test -Dtest=BehaviorHistoryPostgresTest,BehaviorHistoryKafkaTest -Dsurefire.failIfNoSpecifiedTests=false`。绿色运行使用原反例断言，未放宽或跳过。未重跑无关B1、午夜浏览器、资金或旧HTTP完整任务；它们的实现/依赖未变、有效证据仍适用。

## 交付与后续

原目录独立分支 `fix/er-s15b2-invalid-source-coverage`。前置B2源码CI38943483849须终态核实后才推送；其真实结论见`b2-ci.json`。本整改完整SHA、正常ff合并/push与新源码CI分别记录于`delivery.json` / `ci-snapshot.json`，本地PASS不冒充远程全绿。11保护、旧CPA、午夜fixture、已发布迁移与历史进度保留；本次容器/会话核对见`runtime-final.json`，不清理用户内容或新增worktree。

下一必要业务步骤ER-D0/C15C，必须同时冻结旧格式Seen及新版live/history的Redis失败不确认、成功幂等SADD后确认、有界重试/暂停、位点窗口、毒消息持久隔离/受控重放与重平衡恢复，并以真实Kafka/Redis验收。旧消息无业务ID不按内容hash去重，history不进入旧Flink累计；S19继续独立。随后S15C与S16～21按原序推进，S22～25暂停。
