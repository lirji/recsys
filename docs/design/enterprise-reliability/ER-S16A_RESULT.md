# ER-S16A 验收结果

本片必要本地验收与分离复审为 DONE / PASS。Git提交、正常ff/push及精确新CI状态由本轮`delivery.json`记录，不能把本地PASS当成远程CI终态。正式设计提交 `5c0f29012dbfa250aaa4f6614571b05b1c59910b` 保留；唯一实施状态见 [IMPLEMENTATION_SLICES](IMPLEMENTATION_SLICES.md)。本片交付目标是实际Controller到原事件Kafka的单广告可靠闭环，包含初始化、存量基线、开户、查询/恢复和seed准入。广告主status/fanout与跨owner批次留给S16B；父S16未完成。

## 验证版本与执行记录

当前round：`.dev/codex-flow-reliability/round-43880a89/`；最新纠偏请求`b48fcdef-3017-4e2d-a924-0d45ffe78e1d`。最终75个产品/测试/构建文件绑定 `final-source-fingerprints.json`；完整运行、原失败和修正见 [实现证据](ER-S16A_IMPLEMENTATION_EVIDENCE.md)、`checkpoint.json`。

- `catalog-full-gate-3.log`：114项/14类，0失败/错误/跳过，9分29秒，BUILD SUCCESS。客户端中断后原30312无法查询，已核对原Maven/Surefire进程退出、终态日志和全部XML；未重复启动或终止。源码75项在保存报告前与原指纹完全相同。
- 随后按父澄清修正seed旧模式兼容性、内部恢复缺记录分类；`parent-corrections-1.log`单独26项PASS（Recovery8、Seed18），0失败/错误/跳过。未受影响的完整门禁证据按指纹和差异复用。114与26是两个版本的两次运行，不能相加称一次140项。
- `lease-closure-1.log`独立5项PASS，覆盖末次command/step/send真实领取退出、开户身份预留退出、QUIESCED恢复专用原轮次审计，以及Kafka原记录不可读后按已知数据库原文重放；随后完整门禁已包含这些测试。
- 真实细粒度权限：原`real-authz-2`完整PASS保留；因ROOT_CREATE原actor竞争/准入/PREPARE回查顺序确实变化，最终仅重新执行 `--scenario root-recovery`。实际SDK/auth-platform/独占SpiceDB/产品HTTP通过原关系提交响应丢失、原ID/actor恢复、原actor查询200、其他管理员404和原owner关系验证。结果 `root-authz-final.json`；默认full原断言保留，未将局部重验冒充全场景重跑。首次尝试在沙箱Docker socket处失败、尚无产品启动，获准后同一隔离验收PASS。
- 前轮基础为`catalog-budget-retry-3.log`27项PASS，另33项源码未变证据复用；不是一次60项。更早57项另有原日志/XML，均不能替代本轮A。
- 原失败完整保留：full-gate-1的98项有2失败（ACK用例未进入新基线后的断点、PG池重连首次503）；定向HTTP2+Seed12通过后，full-gate-2的98项有1日志文件名错误。均由后续完整full-gate-3关闭，没有弱化原字节/唯一效果断言。前端生成枚举的`npm run typecheck`PASS，无可见UI变更。

## C16适用验收对应

下表A适用项均PASS，边界按每项限定；C16-13～15不属于A，父S16仍IN_PROGRESS。

| 验收 | 实际证据入口/检查内容 |
|---|---|
| C16-01 | `CatalogPhysicalTransactionTest`：两个独占PG、真实ShardingSphere、相反奇偶路由、原生UUID/JSONB、业务/receipt/fact同txid；缺receipt、错库/旧tx事实、错fence均真实回滚。`CatalogBusinessFlowTest`实际Controller链路。 |
| C16-02 | Physical的sourceFailure/missingLocalFact；Business的initialWordsRollback与APPLIED后未知检查点；Application的跨库步骤独立JVM强退、HTTP响应丢失，同ID原结果恢复。 |
| C16-03 | Business与Application四Controller的属性/初始词/关键词/创意/审核/送审/单ad状态/删除、ROOT_CREATE/name/budget；原Repository/CreativeReview/Authz回归。LeaseExit开户预留后退出；真实authz main验证原actor关系提交响应丢失。 |
| C16-04 | Application `sourceCommittedBeforeRelayProcessExitStillPublishesExactOriginalOnRestart`：源提交后杀进程，新JVM真实relay，consumer逐字节核对。 |
| C16-05 | Application `brokerAcknowledgedThenJvmKilledBeforePgMarkerRepeatsOnlyOriginalBytes`：实际Kafka ACK、PG advisory断点、kill、新writer等真实lease，两个offset上的原字节和attempts=2。 |
| C16-06 | Application独占Kafka与一个PG真实stop/start、同一固定本轮loopback端口；503/有限PAUSED、原身份/原字节审计恢复。连接池重连窗口保留UNKNOWN。 |
| C16-07 | Application双JVM同/不同command与开户并发，真实PG锁屏障强制不同actor竞争；Physical同UUID不同scope、owner/local step fence、APPLIED重用不重复效果。 |
| C16-08 | Physical最后领取清算及有限预算基础；LeaseExit真实第3次command/step、第5次send领取后kill与真实30秒lease；Recovery多个阻断/审计新轮/原ACK未知后broker停止仍完成，绝不全局解占有。 |
| C16-09 | Application稳定头/查询/原响应/删除后重试，真实authz逐次当前权限；Business同ID异payload；Physical不可变原文；Recovery REPLAY_EVENT与scoped/full RECHECK。 |
| C16-10 | Application跨库步骤锁与不同命令争用；Business APPLIED后当前item变化仍用原片段；Physical旧writer、初始化互斥；Seed真实两库探针在adDb/目录/Redis首次效果前拒绝非法/未知/同库，明确无治理表的旧模式完整清理/向量/Redis行为通过。 |
| C16-11 | Application删除owner墓碑后children真实SQL失败，保留可查cleanup，晚到update404，同ID完成清理且原墓碑不变。S17消费者防复活UNVERIFIED。 |
| C16-12 | Business实际派生行非法/空/维度/模型检查；Application真实derived连接拒绝、换item UNKNOWN、删除无派生依赖；codec防错绑，未声称S17向量缓存效果。 |
| C16-13～15 | N/A（A范围）；S16B真实广告主状态/fanout/批次/低ID封闭清单竞争仍UNVERIFIED。可靠status入口明确拒绝。 |
| C16-16 | Initialization不同INITIALIZE并发先占home、部分QUIESCED同ID续办、旧事务互斥、错物理模数拒绝；Application初始化仅一库QUIESCED后kill，新JVM仅原ID续办。 |
| C16-17 | Initialization跨库基线、100行分页/持久cursor、非法和1025子项超限阻断、真实BASE_SEND后才READY；Recovery真实推进隔离broker日志起点使原offset不可读，再按已知DB原字节重发；显式缺原文NOT_FOUND，不伪造。此为保留过期效果模拟，不称自然时间过期。 |
| C16-18 | Application无新迁移的真实旧profile Boot/HTTP；同库可靠启动拒绝缺迁移，恢复专用未登记装配；Profile结构规则对比、Admission真实schema/source/writer负对照，旧topic不双写。 |
| C16-19 | Recovery12命令/每轮10候选/失败目标公平轮转；Codec512KiB/1024词/256创意；HTTP SQL文本脱敏与权限、审计；Physical逐表逐列中文注释；11/37/59不变。无生产负载承诺。 |
| C16-20 | WireKafka保存真实高/低版本、重复和同版本异digest的原始key/value/base64/offset证据；codec严格验证；最终相关回归。S17 Inbox/高水位/墓碑/Redis仍UNVERIFIED。 |

## 复审、规范与文档

3名只读子Agent分别审查业务/权限、持久化/并发、配置/运行。7项确认发现已修正并源码复核：ROOT_CREATE actor竞争、已知超限前拒绝、REPLAY_ACK优先查询、存活publisher外发前检查Kafka身份、末次step退出过期清算。普通worker按库每轮至多10条SKIP LOCKED清算；QUIESCED恢复专用模式由审计RETRY_STEP按原round/fence条件清算，均保留占有与预算，有效租约拒绝。另修正seed无依据收紧legacy例外及内部恢复缺记录分类，26项定向回归通过。APPLIED PREPARE原receipt顺序、两项测试修正及局部规范整理另经只读复核。运行反例由实际测试承担，不把源码审查标成运行PASS。详细 `separate-review.json`。

适用规范为 `~/.codex/architecture-standards.md` 的业务建模、配置、持久化事务、分布式契约、并发/安全/验证、缓存、Kafka/中间件故障、容量演进、发布数据治理。Mapper/XML检查、统一错误码生成/边界、测试脚本、diff检查和保护指纹单独记录。仓库未配置统一Java formatter/静态分析器，不临时新增工具或豁免；`hygiene-final.json`编译/测试/差异与规范PASS，无阻断，唯一工具限制FORMAT_TOOL_NOT_AVAILABLE；事务/权限的人工复审由分离审查承载。

增量文档映射：Controller/事件/命令→[CONTRACTS](CONTRACTS.md)；物理事务/装配/所有权→[BACKEND_ARCHITECTURE](BACKEND_ARCHITECTURE.md)；路由兼容/原生PG函数/版本→[TECH_SELECTION](TECH_SELECTION.md)；验收/CI→[VALIDATION_PLAN](VALIDATION_PLAN.md)；实际配置、内部初始化与恢复、seed保守准入、保留/回退→[运行手册](CATALOG_EVENTS_RUNBOOK.md)。未修改正式C16历史设计；父23:07UTC明确seed缺表legacy例外始终有效，此前所谓用户覆盖无依据。两项均在原门禁终态与指纹保存后修正并通过定向26项。

## 保留与交付边界

前置S15 CI38964531069精确686454dc已completed/success，8/8成功；证据 `preceding-ci-before-delivery.json`。新CI只追加20分钟catalog门禁，旧job/步骤/触发器字节不变。真实authz main是额外本地验收，不计入该JUnit门禁。

保护11、旧迁移/fixture37、旧SQL59、CODEX_PROGRESS历史和用户原改动保留。无共享迁移/数据访问/开关/部署，无stash/reset/clean/worktree/强推；所有.dev/target不清理。全部A适用必要验收已通过，在原分支保留独立设计提交并创建完整逻辑实现提交，按授权正常ff/push及登记新CI；实际交付SHA/状态见`delivery.json`。新CI未有终态时仍记待定，不虚称成功。

单节点隔离验收不证明生产吞吐、RTO/RPO、多副本容灾、网络丢包或ACL恢复。Admin身份检查与Produce非跨系统原子，主题管理须由owner协调。原authz管理一致性政策保持，不宣称即时撤权。source与原文同时丢失不能恢复；S17读模型/Redis、S16B和S18～21均未由本片替代。
