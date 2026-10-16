# ER-S15 结果与恢复入口

## 当前状态与范围

父片 **ER-S15=IN_PROGRESS**。正式状态只维护 [IMPLEMENTATION_SLICES](IMPLEMENTATION_SLICES.md)：ER-D0/C15A 设计门禁已交付；本回合 `eb5feb61-26b8-4a9c-aedf-e4ec6fbbf858` 的 **S15A 自然事件可靠发送已实现，本地 A01～A10 PASS**，详见 [ER-S15A_RESULT](ER-S15A_RESULT.md)。旧 BehaviorService 原子事件 S15B 和真实行为消费者 S15C 尚未实现，不能把自然测试 Inbox/effect 当作父片完成证据。

用户持续授权 S15～S21 分轮正常提交/合并/push，每回合只完成一个最小纵向片或必要设计门禁。S22～S25 暂停。仅 Sandbox/独占组件验证；不部署、不启用共享资金、不重启共享基础设施。原目录任务分支，无额外 worktree；11 个保护文件及原 C15A 草案始终保留。

## 已交付设计门禁

上轮 `ff6491f6-0576-4839-b2cd-026ce95da63c` 在基线 `1f77eb4d037844199c922cd2bf92ea230fc0a56f` 上补正式 [C15_DESIGN](C15_DESIGN.md) 与 CONTRACTS C15，不修改受保护草案。提交 `d978e5686130610cc59a1f948970be40947a6169` 已正常 ff 合并并 push origin/main。交付记录 `.dev/codex-flow-reliability/round-ff6491f6/delivery.json`；设计证据同目录 `design-validation.json`。

D01 原父片范围、D02 原事件身份/JSON、D03 状态/恢复、D04 owner/资源/迁移、D05 文档依赖、D06 保护指纹、D07 8 个文档暂存边界、D08 设计与实现完成语义均 PASS。同一 Agent 独立只读验证 pass，未声称第二评审者；当时没有产品实现或运行测试。保留当前 key/政策不重验历史事实、V001 原子扩展迁移、原 payload 与 fixture/产品消费者边界等决定。

上轮 CI [38915759801](https://github.com/lirji/recsys/actions/runs/38915759801) 已核对为 completed/failure：6 个 job 成功，money-chain-gate 因 ConversionOnlinePostgresTest 第 158 行偶发错误时间输入失败。具体调查、确定性测试修正及本轮复验见 ER-S15A_RESULT；不能把旧设计 CI 登记为成功。

## S15A 当前实现与验收

V002 与 Mapper/XML/仓储、严格自然事件 codec、默认关闭的独立 relay/worker、有限重试/fence 与审计重放已经实现。真实 PG/Kafka、独立 JVM 退出、测试 PG Inbox/effect 与消费提交窗口证明自然事件可恢复，完整证据与 Git/CI 恢复入口见 ER-S15A_RESULT。本轮使用 `feat/er-s15a-natural-event-delivery`，不实现第二个业务子片。

运行记录 `.dev/codex-flow-reliability/round-eb5feb61/` 不提交。原部署入口 `.dev/deployments/latest-1f77eb4d0378/PROGRESS.md` 保留，不重复部署。CODEX_PROGRESS 仅保存当前指向及原历史交接。

## 下一操作

本轮完成 Git 交付后返回外层。下一回合先核对本片交付 SHA/CI 和保护指纹，再执行 **ER-D0/C15B**：读取旧 BehaviorService、事件对象、Mapper/DDL、开关与消费者，冻结本库业务/Outbox 原子写、稳定事件身份、历史待发覆盖和迁移共存契约。已有无键旧请求不能擅自发明去重政策。随后依次 S15B→C15C→S15C；全部原验收通过前父 S15 不得 DONE，S19 Flink 范围不转移。
