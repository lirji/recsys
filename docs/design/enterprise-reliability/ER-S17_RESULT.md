# ER-S17 验收结果

2026-10-18 本地实现与分离验收 **DONE / PASS**。32项测试、零失败/错误/跳过，测试与本文件绑定同一最终源码；产品开关保持默认false，未安装/启用共享环境。代码、运行说明和CI门禁将按本轮授权正常交付，实际提交/远程CI状态记录在 `.dev/er-s17-s18/delivery.json` 和检查点。

## 验证范围

| 验收 | 实际证据 |
| --- | --- |
| C17-01～04 | 13项真实PG/Redis测试：高低版本、重复、永久墓碑、暂停恢复、原文隔离及审计重检、事务回滚、同item UNKNOWN/换item、暂停后向量恢复和创意过滤 |
| C17-05～06 | 7项真实Kafka/PG/Redis测试：已发基线、毒消息后继、PG提交未ACK、独立JVM halt(17)后接管、活租约拒绝、保留缺口/陌生分区/topic重建拒绝 |
| C17-07～09 | 真实Redis中断（暂停/恢复独占容器）、Lua成功未确认、错误key类型、三次耗尽/到期暂停、审计重试、同广告串行/不同广告推进、完整long版本与迟到旧脚本不复活 |
| C17-10 | 2项配置测试：默认关闭无需新表和连接、子开关和旧消费冲突拒绝；真实数据库写保护和非空目标初始化拒绝 |
| C17-11 | 幂等迁移/摘要/全部表列注释、旧目录10项回归；Mapper XML、统一错误提示、稳定枚举code及diff检查 |

执行命令：`mvn -B -pl recsys-ad-serving -am test '-Dtest=CatalogProjection*Test,AdServablePostgresTest,AdCatalogEventRoundTripTest' -Dsurefire.failIfNoSpecifiedTests=false`。日志 `.dev/er-s17-s18/s17-gate-final.log`；结构化验收 `S17_TEST_RESULT.json`；源码清单 `s17-sources.json`。最终源码指纹 `ec52753df4d36864c5cf7d357932a44d2f4f52de7ccd160ba22a12e9086433c6`（包含本片CI配置）。CI目录门禁已纳入ad-serving投影和旧目录回归。

## 复审与限制

同一Agent将实现、验收与只读复审分开，逐项检查C17-01～11；没有把设计审查或旧测试当作最终版本证据。复审发现并修复：原隔离记录缺少重检入口、模型名丢失、暂停期间同item向量被清除、Redis空库检查游标可能无界、旧写入检查与注册间竞争。版本状态保留已知向量原事件；注册时表锁与触发器关闭旧写窗口；SCAN有页数和时限。

失败记录保留：测试导入冲突；PG函数美元引号被DDL分隔器截断（改为整份SQL执行）；初轮扩展测试的Redis重启动态端口与Kafka Admin超时配置。后续使用真实容器暂停恢复，保留真实中断断言；最终32项全部通过。

Hygiene报告 `s17-hygiene-final.json`：仓库未配置统一formatter/static-analysis，按周边风格核对并明确限制；通用异常捕获用于把原文/连接错误转换为固定原因，事务与日志边界人工复核。未新增依赖。原有12项用户文件内容保留（CODEX_PROGRESS仅新增本任务前缀，原文后缀校验保持）。

边界：首次启用要求空目标及完整源基线核对；Redis最终一致且仅单机/独占逻辑库；无源历史全丢失、存量切换、线上容量/RTO或生产部署声明。恢复入口和回退见 [C17_RUNBOOK](C17_RUNBOOK.md)。S18仍需独立完成，本结果不覆盖S19～S25。
