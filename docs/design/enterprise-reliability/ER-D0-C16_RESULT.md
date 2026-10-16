# ER-D0/C16 设计结果

2026-10-17，本回合301afed4：**设计门禁DONE/PASS**。正式[C16_DESIGN](C16_DESIGN.md)与原F15/ER-S16一致；下一片ER-S16A READY，ER-S16B TODO，父S16 IN_PROGRESS。C16-01～20产品验收全部UNVERIFIED，没有实现或运行新迁移、listener、worker、测试。整体S16～21仍未完成，S22～25暂停。

## 恢复核对

- S15实现`a2ca663c95a6c0e9a9278a53f28baee82d7114bb`、单项CI时限修正`686454dc132da7831dfdc197cb6fdfa9e6348db0`已正常ff/push；本轮读取实际Git并只读核实远程main仍为后者，没有重复交付。原目录仅一个worktree，原用户改动保持。
- S15C2最终14份XML共83项，失败/错误/跳过均0；22源码指纹和32路径实现提交blob、CI修正提交blob均一致，复用有效验收。S15A/B/C必要验收已闭合；不重复资金、午夜fixture、自然或Seen测试。
- 前置C1 CI38958960102精确d05d23c0已8/8成功；当前S15 CI[38964531069](https://github.com/lirji/recsys/actions/runs/38964531069)精确686454dc仍in_progress，7个门禁已成功、build运行中。本轮按用户明确推进规则完成本地设计，不宣称远程全绿、不取消/重跑CI；下一main推送前核对终态，若真实失败先处理。
- 初始与提交前核对11保护、37旧迁移/fixture、1373产品/测试/构建/脚本文件、补充59份全部已跟踪SQL以及CODEX_PROGRESS历史后缀；无产品变化。证据见`.dev/codex-flow-reliability/round-301afed4/start-evidence.json`及最终文件检查。

## 设计结论

选择显式单物理分片事务、本库Outbox和advertiser持久串行命令。保留原广告/子资源分片，不使用公共SINGLE Outbox，不搬数据、不加XA/CDC/基础设施。跨库中间态可见且持久可查，最终事件来自冻结的各库结果；Kafka发布、S17投影与业务完成分开。

正式稿覆盖所有真实广告、词、创意、审核、广告主状态、删除入口，以及offline seed直写的准入；冻结aggregateVersion、不可变字节、新topic、S17版本/Inbox/墓碑/Redis接口、UNKNOWN enrichment、暂停fanout/多owner批次、lease/fence/审计/有限恢复、起点与回退边界。A闭合真实单广告写口到Kafka并可恢复，B闭合广告主与多分片暂停续办，两片均必需。

实现仍需证明真实ShardingSphere 5.5.0对所需路由SQL/PG guard的运行支持；文档与本机JAR API核对不是运行成功。两个PG实例、真实Kafka、独立JVM退出的C16-01～20明确拒绝Mock/sink替代；生产吞吐、网络丢包、broker副本容灾、完整历史重建没有证据。

## 分离只读复审

同Agent在主稿形成后进行分离只读pass；没有子Agent或独立第二人。结构漏项脚本仅辅助编号/条款检查，不能代替以下语义审查。

| 门禁 | 结论与依据 |
|---|---|
| D16-01 | PASS：实际Controller/Service/Mapper/主DataSource、原分片DDL、seed及creative真实审核过滤核对；旧注释与实际不符处明确列出。 |
| D16-02 | PASS：OWNER/CHILDREN各自物理事务/Outbox，显式路由/同txid guard、禁止广播写/公共库假原子。 |
| D16-03 | PASS：跨command副作用前持久占有、owner与本地step fence分开、原receipt/未知结果查询、迟到执行器限制明确。 |
| D16-04 | PASS：冻结切面/版本/明确删除/UNKNOWN向量及S17乱序边界完整，没有以“完整快照”忽略乱序。 |
| D16-05 | PASS：暂停封闭集合、低ID并发、分页清单、多owner部分结果/旧expectedVersion/取消未派发目标明确。 |
| D16-06 | PASS：初始化跨command排他、恢复专用装配、默认profile不引用新表、ACTIVE禁止开关回退旧写，切换前提诚实保留。 |
| D16-07 | PASS：持久有限预算/过期清算/多阻断停止、统一错误/权限/审计/脱敏、来源缺失与保留边界明确。 |
| D16-08 | PASS：两条串行纵向片闭合业务结果；文件步骤和C16-01～20映射齐全，S17未实现的效果不冒称验收。 |
| D16-09 | PASS：稳定S-ID与S06依赖保持；本轮只文档/本地设计提交，保护/历史/源码指纹与Git范围检查独立落盘。 |

审查中关闭的局部空白：开户生成ID前的路由/未知结果（ROOT_CREATE固定home+原Snowflake持久预留）；删除后的同ID重试先查原命令；默认资源加入未迁移表的启动风险（显式可靠profile+规则对比）；creative review_status遗漏；多暂停目标与单执行槽的占有表述；seed不能经旧SINGLE探测两份control；READY不存在时初始化装配死路；owner lease不等于远程步骤瞬间失效。详见`independent-design-review.json`与正式条款，没有遗留设计阻断。

## 交付与恢复

按`project-documentation`同步契约/架构/选型/验证与[目录手册](CATALOG_EVENTS_RUNBOOK.md)，按`update-progress-docs`维护唯一[实施状态](IMPLEMENTATION_SLICES.md)和CODEX_PROGRESS入口。采用backend-architecture-design、implementation-slicing及相关architecture-standards章节；设计交付按task-git-delivery显式路径检查执行。

分支`feat/er-s16-catalog-outbox`，基线/main/origin-main=686454dc；仅9个设计/进度文档作一个独立本地设计提交。实际提交SHA及提交blob/指纹、Git状态、当前CI与无运行中操作记录于`.dev/codex-flow-reliability/round-301afed4/{delivery.json,handoff.json}`。不merge、不push，不触发新CI，不创建worktree，不部署/访问共享业务数据/启用共享开关/重启共享容器，不清理.dev/target或用户内容。

下回合读取C16正式稿与交接、核对实际文件和CI后直接实施ER-S16A；复用本分支，保留设计与实现独立逻辑提交。源码未变的旧验收不重跑；新实现必要真实验证通过后按持续授权正常ff/push，推送前必须复核前置源码CI终态。未决step耗尽保留占有、旧writer退出、源与全部历史丢失及S17读模型等边界不得在实施中静默削弱。
