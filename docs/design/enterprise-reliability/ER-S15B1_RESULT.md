# ER-S15B1 实施与独立验证结果

本地验收 **DONE / PASS**。本片只包含在线原子接受、重复 CLICK 修复、两流可靠发送及审计重放；父 ER-S15B / ER-S15 保持 IN_PROGRESS。B2 清单恢复和旧消费 topic guard、C15C 实际旧/新 Seen 确认恢复、S19 Flink 语义未实现，不以测试 Sink 冒充产品效果。

回合 `347abae9-7d1b-43c3-ac8f-3281e8272f6e`，原目录分支 `feat/er-s15b1-behavior-event-delivery`，基线 `8b695c76bc343a082d64add4775f1f0729957bc4`。实际提交、合并、push 与当前 CI 的不可变 SHA 由 `.dev/codex-flow-reliability/round-347abae9/delivery.json`、`ci-snapshot.json` 记录；本结果不冒充尚未获得的远程成功。

## 实现与文档地图

| 代码区域 | 已交付行为 / 文档入口 |
|---|---|
| behavior `events/BehaviorEventRepository`、primary `BehaviorMapper` / `BehaviorEventMapper` XML | 同 primary DataSource 的 REQUIRES_NEW/READ_COMMITTED；源行与 Outbox 原子提交；CLICK 用下一条语句读取赢家；正式事务边界见 C15_DESIGN |
| behavior `db/behavior-events/V001__behavior_event_delivery.sql` | 实际 schema/索引预检，持久源 UUID、独立 checksum、不可变源快照/wire、投递租约与不可变 replay 审计；不改自然/历史 SQL |
| common `LegacyBehaviorAccepted` / platform `LegacyBehaviorCodec` / behavior `BehaviorEventCodec` | 大 ID 字符串、严格版本/字段/重复 JSON、来源与时间隔离、有限数值、32KiB wire 和原文摘要校验；公共领域模块不增加 Jackson 依赖 |
| `BehaviorService` 可靠分支 | 保留 HTTP/batch/归因和无业务键语义；原事件不因重复请求重开；旧 CLICK 缺事件时捕获原行 HISTORY 义务；非法源保留 DEAD 义务 |
| behavior `BehaviorEventConfiguration/Worker/Job/Relay/KafkaPublisher` | capture/live/history 独立默认关闭、旧发布冲突拒绝、每流一线程一producer、有界发送/停机、固定指标与异常脱敏 |
| `.github/workflows/ci.yml` | 新增 behavior-reliability-gate，沿既有 JDK/Maven/Docker；保存进程崩溃与Surefire证据，无新依赖或旧门禁放宽 |
| [BEHAVIOR_EVENTS_RUNBOOK](BEHAVIOR_EVENTS_RUNBOOK.md) | 显式迁移、默认关闭、诊断、审计重放、保留与回退；无本机/生产部署或共享连接操作 |

父审查补充已同步 C15_DESIGN / CONTRACTS / VALIDATION_PLAN：B2 前10个 WAITING_SOURCE 不得永久饿死后续可见项；C15C 必须处理旧格式 Seen Redis 异常吞错确认。它们是未来必需验收，本片不实现或宣称通过。

## 验收矩阵

| 验收 | 实际证据 | 结果 |
|---|---|---|
| B01 | 真 PG schema/部分唯一索引语义预检，迁移失败回滚、checksum漂移拒绝；新仓储重启同sourceId，独立schema相同行号不同ID；旧writer可写删；新表与所有字段注释齐全 | PASS |
| B02 | 序列化故障、Outbox触发器失败源行均回滚；大ID/null/原毫秒保留；PG16完整jsonb文本golden，NaN/±Infinity/超长/null action建立DEAD；HTTP脱敏500，batch保留前项提交 | PASS |
| B03 | 20线程同已验证CLICK仅一业务行/事件/指标；真实未提交赢家让另一INSERT等待，提交后下一语句捕获原行；两LIKE两源行/事件，错归属不修复 | PASS |
| B04 | 暂停独占Kafka后业务行仍提交，重复CLICK不新建/丢待发；旧CLICK修复history，以原ts/bucket/value发布；PUBLISHED/DEAD重复不重开 | PASS |
| B05 | 独立JVM在提交后、领取后、broker ACK标记前退出，退出码81/82/83；新仓储/producer等真实DB租约恢复，原payload摘要一致；ACK窗口两个offset、测试Sink effect=1 | PASS |
| B06 | 两执行器只领取一次，旧fence不能标记；20次耗尽与第20次真实halt后清算；合法DEAD审计重开、精确命令重试不再次重开、改内容冲突、失败回滚；INVALID_SOURCE拒绝重开 | PASS |
| B07 | 全关闭不需新schema；旧00/01/10/11实际Spring服务至多一次旧发布；新旧冲突拒绝；真实Spring capture、双relay-only排空；事务外网络、两线程、停机中断留SENDING、DB故障退避和NaN | PASS |
| B10（B1部分） | 严格wire/来源/大ID/local时间/未知版本/错误key；两独立topic不串流；旧HTTP/归因回归 | PASS；Seen/Flink入口guard及兼容仍属B2 |
| B11（B1部分） | PG behaviorId→Outbox→真实Kafka topic/key/partition/offset→独立PG Sink单一效果，证据打印事件与摘要而非负载 | PASS；实际Redis/Flink未验证 |
| B08/B09/B12剩余 | manifest、低ID晚提交、公平续办、取消/报告及旧消费者兼容 | UNVERIFIED，B2后续 |

## 可复现验证

1. `mvn -B -pl recsys-behavior -am test -Dgroups=behavior-reliability -Dsurefire.failIfNoSpecifiedTests=false`：最终本轮主体 **23项 / 4类**，0失败/错误/跳过；日志 `behavior-final.log`。真实PG/Kafka、四种进程退出均已命中。
2. `mvn -B -pl recsys-behavior -am test -Dtest=BehaviorEventWiringPostgresTest,BehaviorServiceTest,BehaviorServicePostgresTest,BusinessErrorContractTest -Dsurefire.failIfNoSpecifiedTests=false`：**18项 / 5类**全通过，其中B1装配2项、既有回归16项。B1合计 **25项 / 5类**，并非同一命令的25项；没有重跑已验证的完整资金/自然本地门禁。
3. `test_persistence_boundaries.py` 6项、`check_persistence_boundaries.py`、`test_error_codes.py` 10项、`generate_error_codes.py --check`、`check_public_errors.py`、`git diff --check`：PASS。
4. Code Hygiene：`IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS`，无阻断。统一formatter不可用，独立Java静态分析未配置；没有为满足检查新增工具。异常捕获/事务的人工审查见 `independent-review.json`；这是同一Agent切换至只读验证pass，不声称另一评审者。

证据目录 `.dev/codex-flow-reliability/round-347abae9/`：最终日志、final-reports/regression-reports、test-summary、source-fingerprints、hygiene-final、independent-review、protected-and-prior；真实退出日志在 `recsys-behavior/target/behavior-event-evidence/` 并由CI上传。所有.dev/target运行材料不提交。

故障记录保留：首次Maven被缓存目录沙箱权限阻止，按平台审批重试；common缺Jackson编译依赖改为platform codec边界，无新增依赖；发送类包引用笔误修正。首次21项仅golden键顺序错误，保留精确PG16字节断言修正；定向复验进一步暴露fixture以最大ID定位最新事件、MockMvc converter选择，两项均修正夹具后通过，没有放宽业务断言。最终测试包含此前失败分支。

## CI、保护与未完成项

C15B设计提交 CI38927688429 已确认 completed/failure：6门禁成功，浏览器因 ACCOUNT_MISSING 未产生真实计费；初始化只建上海当天账户且请求跨午夜，与跨日夹具缺账户相符，详见 ER-D0-C15B_RESULT 与 c15b-ci-finding.json。未改受保护的旧资金fixture、未重跑这轮CI。前片 `7fccd6fe` 的CI38923751782成功记录继续有效；不将任一旧CI结果套给新B1源码。

11项 protected_sha256 全部一致；前片17项实现/测试/资金fixture指纹未变，唯一允许扩展的旧指纹文件为CI工作流，新加门禁未改原自然门禁。CODEX_PROGRESS 原历史和部署段落保留。没有额外worktree、部署、共享开关、共享数据访问或递归codex-flow。

本地组件均由本次Testcontainers独占创建并随测试退出，未清理任何用户内容。Git交付后保存完整SHA和新CI状态；远程CI未终态时不得写“全绿”，后续推送应先核实避免取消证据。下一业务片为 ER-S15B2；B2/C和S16～21由外层推进，本回合返回。生产容量、复制容灾、实际历史覆盖和产品Seen/Flink效果均没有本片证据。


## 2026-10-17 恢复核实

ER-CI-FIX-01回合已核对B1提交 `13f79e89b7cb02a6e498ccd6efac32c7ed71f6f8` 在main/origin-main，23项源码指纹与原25新验收+16回归报告一致。[CI38933333366](https://github.com/lirji/recsys/actions/runs/38933333366) 已 **completed/success，8个job全部success**（含behavior-reliability、build、money、natural、安全及3个浏览器job），在后续main推送前保存终态，未取消/重跑。最终证据 `round-99fbfaf6/b1-ci.json`；不再沿用旧“运行中”快照。

B1成功的非午夜浏览器运行不关闭旧跨日fixture缺陷；该缺陷另以 [ER-CI-FIX-01](ER-CI-FIX-01_RESULT.md) 的确定性PG负对照、修复和完整真实门禁完成本地验收。旧CPA纳秒fixture未修改；ER-S15B2仍为下一业务片。
