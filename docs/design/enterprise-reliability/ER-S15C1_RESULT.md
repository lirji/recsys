# ER-S15C1：旧Seen可靠消费闭环

状态：DONE / PASS（本片实现与必要验收）；Git/CI实际状态见下方交付证据入口。分支 `feat/er-s15c-seen-recovery`；基线设计提交 `679274fda27ad4d5a5339023f88235cdd4e2e077`，独立保留。回合证据 `.dev/codex-flow-reliability/round-282d2b60/`。父S15C/S15未完成，C2未实现。

## 范围与实现

旧 `SeenItemsConsumer` 已接真实Spring单记录容器，移除吞错；专用factory硬设MANUAL_IMMEDIATE、禁自动提交、同步提交、零提交重试、max.poll.records=1、auto.offset.reset=none。PG持久登记/领取→事务外真实SADD→PG APPLIED→listener线程ACK。合法IMPRESSION持久NO_EFFECT；非法原字节持久QUARANTINED后才能确认。不同Kafka坐标同内容不合并，旧key仍不参与合法性判定。

engine自有V001与primary Mapper/XML保存流控制、分区绑定、技术原文和稳定审计命令。初始化在Kafka副作用前持久占有流；不同命令不互相覆盖。新增分区只登记冻结新增项，旧offset逐项保护。记录/命令均3次持久预算、30秒DB租约/fence；恢复占有阻止普通listener抢占；耗尽暂停跨进程保存。整流任一PAUSED/耗尽/活动恢复义务仍在时不能resume/start放行。

内部INITIALIZE/RETRY/RECHECK/RESTORE不新增HTTP、RPC或调度器；默认关闭。STOP标记可写时持久；PG不可达时不能保证保存标记，但容器停止，后续启动必须重新检查PG、身份、位点与未完成义务。全关不创建Seen Admin/factory或访问新表；C2开关在本片显式拒绝开启。无新增依赖、部署单元或CI配置修改。

## 当前验证证据

- `first-real-3.log`：PG4、旧兼容2、配置/解析3 PASS；Kafka新组协调器就绪前失败，保留证据。
- `kafka-ready.log`：真实旧listener 16正反馈+1无效果，broker committed=17、Redis单成员；16次旧调用断言保留。
- `c1-faults-1.log` / `first-fault-reports/`：23项中18 PASS、5失败。初始化竞争返回未预期分类，control插入改为双唯一键统一冲突处理；Redis停止重启改变随机宿主端口导致后续夹具失联，已改独立容器和显式端点核验。
- `targeted-faults-2.log`：7项全部PASS，含上述整改、多PAUSED、第三次领取/恢复命令退出、真实Spring装配、实际SADD响应未知及真实容器提交边界。
- `behavior-final.log`：补充指标测试误用了Micrometer当前版本不支持的AutoCloseable，产品测试未执行；改显式finally.close。
- `behavior-final-2.log`：完整行为门禁73项PASS：engine29+behavior42+streaming2，零失败/错误/跳过。engine审查后有增补，最终版由engine-final.log单独验证；未变behavior/streaming44复用，不能把组合证据冒称单轮75项。
- `same-id-red.log/xml`：新增真实PG反例1项如预期报COMMAND_CONFLICT；同ID并发不同观测应复用胜者，已修复，最终engine6项PG测试全部PASS，原红色反例保留。
- `engine-final.log`：最终受影响engine五类33项PASS：真实Kafka/Redis19、PG6、配置4、旧兼容2、DTO2；零失败/错误/跳过。对应XML见engine-final-reports，最终产品指纹见engine-final-fingerprints.json。运行后仅补Mapper中文注释，去除新增注释后与被测源码摘要完全相同，见comments-only-proof.json；补编译final-compile.log。

## 验收映射与限制

| 验收 | 具体证据 / 仍需完成 |
|---|---|
| C01 | 7/8字段、long最大ID、4正反馈、不同坐标同内容、SADD返回0、IMPRESSION无Redis效果；真实listener与位点。 |
| C02 | 真实Redis暂停超时、WRONGTYPE、独占Redis停止，3次PAUSED且位点不越过；RETRY后真实集合与offset。停止后Docker随机端点变化单列，不冒充稳定端点自动重连。 |
| C03 | 真实SADD后进程halt、实际SADD后注入响应未知；再次执行集合单成员，预算不重置。断点不证明TCP层确实丢包。 |
| C04/C09 | PG APPLIED后ACK前独立JVM退出；真实容器/consumer代理在真实提交后抛超时，以及注入RebalanceInProgress/Wakeup内部窗口，Admin查实际offset。真实PG触发器拒绝隔离写时停止、不ACK。未配置真实broker ACL拒绝或提交网络断连。 |
| C05 | 真实两consumer重平衡与退出接管、两个分区PAUSED、持久fence防旧回写；不能因只恢复一条解除整流暂停。 |
| C06/C12 | 非JSON/UTF8/action/version/tombstone/超长数据隔离；完整PG原文在Kafka删除后仍RECHECK，缺完整原文按原坐标过期为SOURCE_UNAVAILABLE；相邻消息不能顶替。隔离持久后ACK前独立退出PASS：重启唯一原记录、attempts=0、committed=1。 |
| C07/C08 | 稳定命令同ID复用、改内容冲突、并发占有、终态不可变、最后一次退出清算释放；同ID并发不同观测的PG交错反例红绿均保留；最终复用先提交request/digest，改变mode拒绝。 |
| C10/C11 | ADOPT非零offset、新组先发后登记、Admin写后PG前退出、并发命令、ENROLL旧位点不变；真实DeleteRecords和topic重建。实际Admin删除group offset/写超过end后均拒绝，未静默reset；本测试恢复其独占位点后再验DeleteRecords与topic重建。 |
| C15 | ACK后删独占key，普通重启和lag=0不补全；RESTORE实际SADD恢复已知记录。只代表局部恢复。 |
| C16/C17 | 默认关闭、C2未实现开关拒绝、专用配置、真实Spring装配、表/列中文注释、不可变约束；缺迁移/checksum漂移/primary错绑最终真实PG拒绝测试PASS。 |
| C18 | 固定维度指标、单线程/队列3/单poll/3次预算/停止与脱敏；最终日志无测试原文标记；真实线程生命周期与固定队列预算通过，未做饱和负载压测。生产吞吐、容量、保留与RTO/RPO未验证。 |
| C13/C14 | C2所有：新LIVE/HISTORY listener及B2→产品HISTORY→Redis→Replica读模型，C1不能代证。 |

## 分离审查、质量与证据复用

同一Agent在实现后执行分离只读pass，不声称第二评审者。检查实际listener与ACK顺序、事务/SQL条件、同command和跨command边界、保留/重放、默认开关、原断言和日志；R1～R6已关闭，详细见回合independent-review.json。C13/C14归C2，不能把旧流通过扩展为新版全部通过。

当前tag门禁由最终engine31项+本轮未变behavior/streaming44项组成；另DTO2项本轮重验，旧HTTP/归因/错误/Flink相关22项按源码指纹复用原有效结果。第一次完整运行73项，审查后最终engine33项，**不是同一次75项执行**。全部复用与重新验证映射见full-behavior-result.json、engine-final-result.json、regression-reuse.json。旧资金/午夜fixture没有修改或重跑。

Code Hygiene无阻断；闭合状态用枚举name，协议大小/超时是已冻结技术常量，外部异常不透传原文，SQL参数绑定且网络在PG事务外。统一formatter未配置（TOOL_NOT_AVAILABLE），独立Java静态分析未配置；人工规范/事务审查与diff检查不能冒充这两项自动化。生产吞吐、容量、保留、RTO/RPO、共享切换及全历史Redis重建未验证。

## 交付边界

11保护文件、36项已发布迁移/选定fixture与CODEX_PROGRESS历史正文保持。旧设计门禁与前置CI38947474433（精确6c868e73完整SHA、completed/success）复用。无共享Seen/资金启用、部署、共享数据查询、基础设施重启、worktree、stash/reset/clean或强推。`.dev/target`保留。

设计提交679274fd保持独立；C1实现为另一完整逻辑提交。最终Git动作/完整SHA/新CI精确绑定以回合delivery.json、ci-snapshot.json为权威，不用旧CI充当新源码结论。推送前再次查询CI38947474433仍8/8成功，远程main仍6c868e7349ba72682a8b7707fe7acdfcc98c3ed3；未取消/重跑。交付后返回父协调者，下一片C2，父S15C/S15仍IN_PROGRESS。

回退保持旧Seen关闭，保留原文/审计/位点；不删迁移或改历史。所有.dev/target和唯一原工作目录保留，没有可自动清理的工作树；用户原改动不进入本片提交。
