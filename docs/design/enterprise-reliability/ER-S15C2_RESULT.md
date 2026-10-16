# ER-S15C2：LIVE/HISTORY真实Seen消费

状态：DONE / PASS（本片实现及本地必要验收）；实现提交a2ca663c保留独立；本轮按明确授权完成CI预算修正并正常交付，精确Git/新CI事实见delivery.json/ci-snapshot.json。唯一状态见[IMPLEMENTATION_SLICES](IMPLEMENTATION_SLICES.md)。基线C1/main `d05d23c0432243ae0d985edba963ffdc9aa85db6`，原分支`feat/er-s15c-seen-recovery`；证据`.dev/codex-flow-reliability/round-6ebaf7da/`。设计679274fd与C1实现提交保持独立。

## 实现和恢复核对

恢复先核对C1最终21项源码指纹、33项engine XML、编译、11保护文件、37项既有迁移/fixture、原Git状态。父审查初始化终态与锁序已有明确条款，未重做设计。未知recordId通过真实PG仓储接口测试固定NOT_FOUND、无cause，无NPE：原rawRecord.require已经处理，不作无必要产品整改。

新增SeenStream绑定三流配置，VersionedSeenItemsConsumer装配独立LIVE/HISTORY产品listener；旧入口与新版共用SeenRecordListener真实手动ACK路径。新版严格复用LegacyBehaviorCodec并检查UTF8、key与来源，原wire/time不变。各流独立控制、Admin、组和恢复服务，停止器按真实group映射；隔离查询和所有指标按stream区分，恢复操作先核对流再领取预算。四开关均false，实际实现后撤掉C1未实现开关拒绝；单开关只启动本流，全关无Seen连接。

继续复用C1 PG持久预算/租约/fence/命令/起点登记；事务外SADD→PG APPLIED→ACK，NO_EFFECT/QUARANTINED分别持久。C1 engine-seen V001及其他旧迁移不改，无新schema/第三方依赖/基础设施；唯一CI修正是明确批准的behavior门禁超时12→20分钟。behavior已有engine test-support依赖承担跨模块验收，engine没有反向依赖behavior。Replica说明仅覆盖实际投影，保留读失败空集语义并去除日志中的业务ID/原错误文本。

## 已执行验证与失败记录

- `not-found-check.log/xml`：未知recordId真实PG接口回归1项PASS；不表示重做C1。
- `compile-1.log`：产品编译PASS。
- `c2-first-real.log`、`first-reports/`：新版真实Kafka/Redis4项与配置4项PASS；跨模块测试Admin因default.api.timeout.ms小于默认request.timeout.ms在链路前失败，未宣称链路已运行。仅修正测试Admin请求超时为同一5秒，产品预算未放宽。
- `c2-additional-real.log`、`additional-reports/`：新增两流真实Redis暂停超时、单消费开关自动启动及修正后的原history全链路共3项PASS。
- `behavior-final.log`、`final-reports/`、`full-behavior-result.json`：最终完整门禁83项/14类PASS（engine38、behavior43、streaming2），零失败/错误/跳过；耗时13分05秒。全部22项源码/测试指纹与source-fingerprints.json一致，验证后无产品改动。

## C01～C18验收映射

| ID | 实际范围及证据 |
|---|---|
| C01 | C1旧真实listener兼容测试保留原7/8字段、长整数、16次正反馈与NO_EFFECT断言，完整门禁重新执行。新流同eventId/原字节不同offset各持久APPLIED，SADD重复仍确认。 |
| C02 | C1真实停止/暂停/WRONGTYPE共用路径回归；C2独占Redis真实暂停，两流各3次PAUSED且committed=0，逐流审计RETRY后committed=1及真实集合。另WRONGTYPE仅停本流，其他流仍前进。 |
| C03/C04 | C1真实SADD后响应未知与容器commit窗口回归；C2每流各两个独立JVM halt窗口：真实SADD后PG前、PG APPLIED后ACK前，退出时broker0/集合已有，重启原记录/字节不变，最终offset1、attempts2或1。 |
| C05 | C1处理中退出接管、租约/fence、多个PAUSED回归；C2各流真实两consumer/两分区重平衡，一方退出另一方接管两分区。 |
| C06 | C2每流10条：APPLIED2、NO_EFFECT1、QUARANTINED7，最终offset10；错key/错origin/version/UTF8/tombstone/超长明确隔离。C1非法旧字段与隔离PG失败不ACK回归。 |
| C07/C08 | C1稳定命令并发/冲突/终态/最后领取与恢复后进程退出回归。C2跨流record/advance拒绝且目标attempts不变；实际RETRY/RECHECK/RESTORE都沿绑定流处理。初始化FAILED保留owner边界不变。 |
| C09 | C1真实PG拒绝、可观察提交异常及RebalanceInProgress/Wakeup内部窗口回归；C2真实PG触发器只拒绝LIVE写，LIVE持久停止、HISTORY继续，LEGACY控制未被污染；修复依赖后显式恢复。 |
| C10/C11 | C1 ADOPT非零、ENROLL保护旧位点、跨command排他、Admin后退出/未知与真实缺口/topic重建回归；C2两流初始化前已发布记录全部可见，单开关启动已登记流。 |
| C12 | 新版完整非法原文在真实Kafka DeleteRecords后仍RECHECK=STILL_INVALID；超长不完整原文在原坐标过期后SOURCE_UNAVAILABLE，无相邻记录替代或误记APPLIED。 |
| C13 | 两独立新版产品listener、原codec和流校验；真实Redis超时恢复、独立组和作用域指标、同eventId不同offset、history ts=null及原local微秒保持。 |
| C14 | BehaviorHistorySeenIntegrationTest使用原B2捕获3/非法1；真实publisher ACK后未标记，等待原租约再由relay重发：PUBLISHED3、Kafka4。原源删除仍复用快照，另一源漂移仍SOURCE_CHANGED，非法仍INVALID_SOURCE。真实HISTORY产品listener→Redis→Replica得到{42}，APPLIED3/NO_EFFECT1、原事件原字节2份；消费前后behavior Outbox逐行未变。旧Flink topic guard回归，history不进入实时累计。 |
| C15 | 各流ACK后删除独占key，重启与offset不变时集合仍缺失；RESTORE已知原APPLIED记录真实SADD，只恢复该记录成员，不伪称完整历史。 |
| C16 | 全关无Seen beans/连接、误topic/group拒绝；各新版开关单独启动且只有一个控制器；恢复默认关闭拒绝命令。C1缺迁移/checksum/起点与真实装配回归。 |
| C17 | 保留C1带中文表列注释/约束/checksum与primary事务；37不可变及11保护指纹不变。跨模块测试使用engine独立schema，不允许consumer写behavior表。 |
| C18 | 沿每流单线程/单poll、3次持久预算、共享停止线程1/队列3、脱敏固定错误；完整行为门禁及固定维度指标/停止/线程生命周期验收。生产吞吐与容量未测。 |

进程断点证明真实副作用后的退出窗口；consumer代理在真实提交前后注入异常只证明对应客户端机制，不宣称真实TCP丢包、broker ACL拒绝或Kafka提交网络故障。Redis暂停为真实组件超时，单节点组件不证明副本容灾；未做生产吞吐/饱和负载、保留期或RTO/RPO验收。

## 分离审查、复用与边界

分离只读审查由同一Agent实施，不声称第二评审者。检查实际listener/ACK顺序、参数作用域、SQL/owner、默认装配、恢复/保留边界、测试替身范围与文档事实；最终结果见`independent-review.json`。持久化边界657产品Java/96 Mapper XML/45历史SQL及公开错误检查PASS；Code Hygiene无阻断，见code-hygiene.json；状态IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS。没有配置统一Java formatter或独立Java静态分析器，人工检查与边界脚本不冒称这些工具已运行。

C1公共实现因参数化重新验证；无变化旧HTTP/归因/错误/Flink相关22项与DTO2项按指纹复用C1有效证据。自然S15A、B1/B2及非法源整改有效旧证据保留；没有重复资金/午夜/自然fixture整改或验收。S15A原17项产品/测试/fixture指纹未变，旧workflow在B1阶段已有演进、本轮不把历史workflow摘要相同作为复用前提；A01～A10有效证据复用。B01～B12及B2整改由本轮行为门禁重验，C01～C18全适用项通过。S15原A/B/C实施及已批准必要验收已齐，父S15记DONE/PASS；新源码CI状态独立登记，尚未取得终态不得写远程全绿。共享启用、S19及全历史重建不在已完成范围。

所有开关默认关闭，无共享Seen/资金启用、业务数据访问、共享容器重启、部署或新worktree。初始化耗尽保持不可变FAILED及排他占有，owner另行处置，没有自动修复入口；已知record可RESTORE，但Kafka仅存原文且本库无record的另组全量重建没有入口。B2 ALREADY_CAPTURED/PUBLISHED不等于重新发送。非法/漂移/保留缺口单列，lag=0与PG APPLIED不是Redis历史完整证明。

## Git与CI

复用原任务分支，设计/C1提交不改历史；C2独立提交a2ca663c95a6c0e9a9278a53f28baee82d7114bb的32路径blob与验证指纹一致。前置CI38958960102精确d05d23c0已completed/success、8/8成功，恢复后再次在线核实，见preceding-ci-before-delivery.json；未取消或重跑。

完整本地13分05秒超过原CI12分钟，前置C1 behavior job已用11分48秒。用户请求2728fdeb明确批准仅behavior-reliability-gate超时12→20分钟，已单独落实，原产品租约/测试断言和全部其他job/验证步骤不变。Ruby YAML解析、结构逐项比较、精确单行diff检查PASS，见ci-static-check.json；无源码变化，不重跑83项。CI修正及必要说明另作独立逻辑提交，不改写a2ca663c。实际ff/push完整SHA和新CI绑定写delivery.json、ci-snapshot.json，不能拿前置CI替代新源码CI结论。

保护原用户改动和CODEX_PROGRESS历史正文；所有.dev/target保留，不提交运行材料、不清理。回退关闭对应消费/恢复，保留迁移/审计/位点，旧二进制不得带旧吞错consumer重入组。本回合完成后返回父协调者，下一回合C16正式设计，之后S16～21；S22～25暂停。
