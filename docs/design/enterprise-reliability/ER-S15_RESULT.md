# ER-S15 结果与恢复入口

## 当前状态与范围

父片 **ER-S15=IN_PROGRESS**。正式状态只维护 [IMPLEMENTATION_SLICES](IMPLEMENTATION_SLICES.md)：C15A设计与S15A实现已交付；S15A本地108/16/462项及远程CI38923751782全部通过，详见[ER-S15A_RESULT](ER-S15A_RESULT.md)。回合347abae9完成S15B1原子接受/重复CLICK修复/两流发送与审计重放，本地25项新验收+16项回归PASS，见[ER-S15B1_RESULT](ER-S15B1_RESULT.md)。S15B2显式历史清单/审计取消/真实报告和旧入口兼容已本地验收，完整门禁43项+补充24项PASS，见[ER-S15B2_RESULT](ER-S15B2_RESULT.md)；父B完成，实际行为消费者S15C尚未实现，父S15保持IN_PROGRESS。

用户持续授权 S15～S21 分轮正常提交/合并/push，每回合只完成一个最小纵向片或必要设计门禁。S22～S25 暂停。仅 Sandbox/独占组件验证；不部署、不启用共享资金、不重启共享基础设施。原目录任务分支，无额外 worktree；11 个保护文件及原 C15A 草案始终保留。

## 已交付设计门禁

上轮 `ff6491f6-0576-4839-b2cd-026ce95da63c` 在基线 `1f77eb4d037844199c922cd2bf92ea230fc0a56f` 上补正式 [C15_DESIGN](C15_DESIGN.md) 与 CONTRACTS C15，不修改受保护草案。提交 `d978e5686130610cc59a1f948970be40947a6169` 已正常 ff 合并并 push origin/main。交付记录 `.dev/codex-flow-reliability/round-ff6491f6/delivery.json`；设计证据同目录 `design-validation.json`。

D01 原父片范围、D02 原事件身份/JSON、D03 状态/恢复、D04 owner/资源/迁移、D05 文档依赖、D06 保护指纹、D07 8 个文档暂存边界、D08 设计与实现完成语义均 PASS。同一 Agent 独立只读验证 pass，未声称第二评审者；当时没有产品实现或运行测试。保留当前 key/政策不重验历史事实、V001 原子扩展迁移、原 payload 与 fixture/产品消费者边界等决定。

上轮 CI [38915759801](https://github.com/lirji/recsys/actions/runs/38915759801) 已核对为 completed/failure：6 个 job 成功，money-chain-gate 因 ConversionOnlinePostgresTest 第 158 行偶发错误时间输入失败。具体调查、确定性测试修正及本轮复验见 ER-S15A_RESULT；不能把旧设计 CI 登记为成功。

## S15A 当前实现与验收

V002 与 Mapper/XML/仓储、严格自然事件 codec、默认关闭的独立 relay/worker、有限重试/fence 与审计重放已经实现。真实 PG/Kafka、独立 JVM 退出、测试 PG Inbox/effect 与消费提交窗口证明自然事件可恢复，完整证据与 Git/CI 恢复入口见 ER-S15A_RESULT。本轮使用 `feat/er-s15a-natural-event-delivery`，不实现第二个业务子片。

运行记录 `.dev/codex-flow-reliability/round-eb5feb61/` 不提交。原部署入口 `.dev/deployments/latest-1f77eb4d0378/PROGRESS.md` 保留，不重复部署。CODEX_PROGRESS 仅保存当前指向及原历史交接。

## C15B门禁与下一操作

C15B已基于旧BehaviorService、Mapper/DDL、混合writer、Seen/Flink源码补齐正式契约：源行稳定身份、同库原子写、CLICK冲突后原行修复、无键POST非幂等边界、独立历史恢复流和显式有界清单、开关迁移及消费兼容。设计门禁与Git记录见ER-D0-C15B_RESULT；未运行或冒充B01～B12产品验收。7fccd6fe远程CI成功终态已记录后才允许本轮推送，没有取消其源码CI；旧CPA时间问题不重复修改。

B2回合8395c172完成显式有界历史清单、公平逐项续办、审计取消与旧Seen/Flink topic guard及兼容验收后返回外层。下一业务步骤 **ER-D0/C15C→S15C**；旧格式Seen吞Redis异常必须一并解决。B1 Git及新CI结果读取对应结果和证据，不能沿用前置设计CI失败或S15A成功充当新源码结论。


## C15C 设计门禁（89a217e3）

正式增量已写入C15_DESIGN末节，设计验证见[ER-D0-C15C_RESULT](ER-D0-C15C_RESULT.md)。旧Seen与新版live/history确认、PG技术隔离/稳定恢复命令、受控消费起点、暂停/重平衡与Redis丢集合边界均在本片范围内。计划细化为ER-S15C1→C2，父S15仍IN_PROGRESS；本回合只创建本地设计提交、不合并/push，不实现消费者。下一片复用同一任务分支，独立实现提交，必要验证后再按正常ff交付。
