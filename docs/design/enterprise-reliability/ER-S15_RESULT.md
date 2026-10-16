# ER-S15 结果与恢复入口

## 当前范围与状态

2026-10-16，编排请求 `ff6491f6-0576-4839-b2cd-026ce95da63c`。父片 **ER-S15=IN_PROGRESS**；本轮只交付首个必要设计门禁 **ER-D0/C15A=DONE/PASS**。正式 C15A 见 [C15_DESIGN](C15_DESIGN.md) 和 [CONTRACTS](CONTRACTS.md)，唯一实施状态见 [IMPLEMENTATION_SLICES](IMPLEMENTATION_SLICES.md)。S15A/B/C 产品均未实现，A01～A10 运行验收均 UNVERIFIED；没有自然发布/旧行为/产品消费闭环完成声明。

用户授权本轮一个子片或必要设计门禁、正常本地提交/合并/push origin/main；仅 Sandbox/隔离验证，不部署、不启用共享资金、不重启共享组件，S22～S25 暂停。基线为 `1f77eb4d037844199c922cd2bf92ea230fc0a56f`；使用原目录任务分支 `docs/er-s15-natural-event-contract`，未创建 worktree。

## 为什么先补设计

当前正式 CONTRACTS 止于 C14；受保护的 C15A_DESIGN 是未冻结草案，不能当作已批准实施输入。源码确认自然表仅有不可变 Outbox，旧 BehaviorService 有 DB 后 best-effort 发送及重复提前返回，SeenItemsConsumer 吞 Redis 失败。因此本轮按用户允许的必要门禁补足可执行契约，不重开整体计划，不以自然 A 替代旧行为与消费者。

交付包括：C15A 的原事件校验/默认关闭独立 relay、V002 兼容、短事务+事务外发送+fence、20 次领取上限和审计重放、资源与停机、10 组运行验收；S15B/C 必需范围、详细契约门禁和具体源码入口。B/C wire、历史补发/开关迁移及失败确认细节尚待对应门禁冻结。

## 设计验证（不等于功能测试）

核验方式：完成设计后进行独立只读验证 pass，从原 PLAN、现行源码与保护清单反向检查文档；同一 Agent 执行，未声称有第二位评审者或子 Agent。机器检查保存于 `.dev/codex-flow-reliability/round-ff6491f6/design-validation.json`，不提交本地运行材料。D01～D08 均 PASS；11/11 保护指纹一致，19 个直接证据源文件与基线一致，部署入口和历史交接正文与外层备份逐字一致。D07 暂存范围在提交前再按同一允许清单核对。

| 检查 | 可追溯证据 | 结论 |
|---|---|---|
| D01 原父片范围与稳定 ID | PLAN ER-S15/F14；自然 A、旧写入 B、实际消费者 C 均在实施清单，S19 边界保留 | PASS |
| D02 源事实与签名/JSON 兼容 | NaturalReceiptService/Repository、NaturalReceipt.Event、NaturalQualification、V001/Mapper | PASS |
| D03 失败/恢复可实施性 | C15_DESIGN 状态表、原始 payload、fence、耗尽清算、原命令重放与 A01～A10 | PASS |
| D04 Owner/资源/迁移 | behavior primary Mapper/DataSource；S06 配置与隔离测试；只追加 V002，不新增依赖/服务 | PASS |
| D05 文档交叉引用和依赖 | C15_DESIGN、CONTRACTS、架构、技术、验证计划、实施清单 | PASS |
| D06 保护文件与交付边界 | START_STATE 的 11 个 SHA-256；产品源码/配置无本轮变化；历史交接保留 | PASS |
| D07 格式与暂存范围 | git diff --check；显式 8 个文档路径，无 .dev/保护草案/测试产物 | PASS |
| D08 真实完成语义 | 仅设计；父 S15 IN_PROGRESS；S15A 运行测试 UNVERIFIED；S22～S25 不执行 | PASS |

设计审查已处理的风险：旧草案建议新增 Kafka 测试依赖，但现有 GenericContainer 能力已足够；relay 不重新用当前 key/policy/当前到期时间裁决已接受事实；V001 全不可变触发器需原子升级而非直接 UPDATE；新字段允许列表的兼容取舍已显式记录；fixture Inbox/effect 不记为产品消费者验收。

验证范围：不改 Java/SQL/POM/workflow/runtime，因此本轮不启动 Maven、PG/Kafka/Redis 或浏览器，不把 S14 的 13/246/8 项结果记成本片测试。产品 Code Hygiene Gate 为 N/A（没有产品源码修改）。本轮远程 CI 必须按实际设计提交 SHA 查询，不能沿用基线成功当新提交成功。

## Git 交付与运行现场

本门禁提交包含且仅包含 8 个文档：本文件、C15_DESIGN、CONTRACTS、BACKEND_ARCHITECTURE、TECH_SELECTION、VALIDATION_PLAN、IMPLEMENTATION_SLICES 和 CODEX_PROGRESS。允许清单和验证指纹见本地 `delivery-paths.json`、`design-validation.json`。

设计提交的 SHA 用 `git log -1 --format=%H -- docs/design/enterprise-reliability/C15_DESIGN.md` 定位；实际提交/合并/push 结果、origin/main 与 CI 的不可变 SHA 绑定记录在 `.dev/codex-flow-reliability/round-ff6491f6/delivery.json`，交接最终答复同步给出。该运行记录不提交，避免在提交中自引用其 SHA；检查 CI 时使用该 SHA，不能只看分支最新绿灯。

已实际查询基线的 CI 38866199212 / console-ci 38866199219，均 completed/success 且 headSha=1f77eb4d。本次只有文档改变，console-ci 按路径过滤不适用；CI 工作流仍按 main push 触发。CI 运行中或无法读取时记 UNVERIFIED，不能把设计核验 PASS 当远程 CI PASS。

本轮没有测试/部署/应用后台进程；外层 codex-flow 由协调者管理，本 Agent 未启动/重启其循环。源码保护清单来自 `.dev/codex-flow-reliability/START_STATE.json`，不修改、stage、stash、清理或强制还原其中任何路径。保留所有原未跟踪/忽略文件及既有部署入口 `.dev/deployments/latest-1f77eb4d0378/PROGRESS.md`。

## 下一步与真正阻塞

已冻结 C15A 无业务决策阻塞，ER-S15A=READY；仅设计门禁 DONE。下一回合：先核对本结果/Git/CI与保护指纹，然后按 backend-implementation 执行 **ER-S15A**，从 V002、NaturalOutboxMapper/Repository 开始，完成实际 relay/worker/装配和 A01～A10 真实隔离验证。后续必须完成 C15B→S15B→C15C→S15C；全部通过前父 S15 不得 DONE。

本轮设计交付结束即返回外层，不自行启动下一 codex-flow 或推进第二片；这不表示 S15～S21 总目标完成。
