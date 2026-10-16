# ER-S15B2：显式历史清单恢复与旧消费入口兼容

本片本地验收 **DONE / PASS**：B2新增18项、完整行为门禁43项/9类及补充回归24项/6类全部零失败/错误/跳过。Git及本提交远程CI以本回合delivery.json/ci-snapshot.json和实际Git为准，不能套用前置成功。权威状态只维护 [IMPLEMENTATION_SLICES](IMPLEMENTATION_SLICES.md)。B1/B2全部验收后可关闭父B，父S15仍须ER-D0/C15C及S15C真实旧/新Seen确认恢复；S19的Flink窗口/checkpoint也未由本片替代。

## 恢复与范围

基线 `031a0443b73ca1fb260339301b850713c4a17ee2`，原目录分支 `feat/er-s15b2-behavior-history`。开始时核对B1 `13f79e89b7cb02a6e498ccd6efac32c7ed71f6f8` 已交付及有效验证；跨日fixture修正的3个源指纹仍一致，复用其确定性午夜负对照/PG及真实浏览器验收。前置 [CI38937727953](https://github.com/lirji/recsys/actions/runs/38937727953) 已 completed/success，8门禁全成功，精确绑定031a0443，不取消前置源码验证或重跑旧失败。

按正式C15B实施：不改已发布behavior V001、自然V001/V002、旧历史SQL或旧CPA纳秒fixture；仅追加独立owner V002。所有新表/字段中文注释。没有新依赖、通用框架、后台扫描或共享环境操作，三个可靠功能开关仍默认false。

## 实现与数据边界

| 范围 | 实际变更 |
|---|---|
| 迁移/Mapper | `V002__behavior_history_capture.sql` 新增command、item、cancel和公平访问序号；`BehaviorHistoryMapper` / XML负责所有SQL，输入/终态/审计不可变 |
| 内部入口 | `BehaviorHistoryRepository` 显式构造时验证同primary DataSource、V001/V002及旧发布关闭；最多100正ID、稳定命令、最多10项逐项短事务 |
| 捕获复用 | B1 Repository只增加包内“加入现有同源事务”入口，复用原snapshot/codec/身份/DEAD隔离；item和Outbox同提交，不另开捕获事务 |
| 公平恢复 | command→item→源行→Outbox锁序；持久last_visit排序及本次已访问ID排除，前10缺失不饿死后项；缺失不删、不按MAX(id)推进水位 |
| 审计/报告 | 独立cancelId幂等审计；取消仅阻止后续捕获。固定结果和当前Outbox状态分开，CAPTURED不代表PUBLISHED；取消等待项不计覆盖 |
| 旧消费者 | common最小逻辑topic guard，Seen构造和Flink连接前拒绝live/history/natural及点号后缀；原7/8字段和无eventId重复处理保留，无hash去重 |
| 文档/CI | BehaviorService类说明区分旧best-effort与capture；CI行为门禁包含三个模块的兼容/恢复测试及报告归档，详见运行手册 |

历史仍为LEGACY_ROW/UNKNOWN，原无时区TIMESTAMP保存ISO local微秒字符串，behavior.ts=null，不推断writer、时区、trafficClass或自然资格。源删除但原事件在时复用原事件；源漂移单列SOURCE_CHANGED，不覆盖快照。原LIVE事件不会因历史清单改路由。INVALID_SOURCE继续DEAD/attempts=0/wire=NULL，不允许普通replay重开。

## 可观察验收

| 契约 | 证据与结论 |
|---|---|
| B08 命令/事务/恢复 | 真实PG：规范清单和重试冲突、并发登记、重叠清单/8并发advance只建每源一个事件；item写入失败回滚同项Outbox/序号但保留之前提交；取消失败回滚审计，advance/cancel串行，精确取消重试及跨命令冲突 |
| B09 公平/历史边界 | 12项前10缺失、11/12可见：第二次续办先捕获后项，前10后来提交继续完成；真实sequence低ID未提交而高ID已提交先WAITING后捕获；原行删除、漂移、未知来源/local时间/null、非法和大源快照逐项报告 |
| B10 格式/路由 | Seen实际Spring构造及Flink main入口拒绝3类保留流和后缀；真实旧解析保留正反馈权重、缺exposureId、long最大ID和重复调用；新codec原身份/来源/时间沿B1回归 |
| B11 history真实发送 | 暂停本测试broker时清单捕获可完成但PUBLISHED=0；恢复后新仓储/producer发送已删除源行的原history快照；ACK未标记时等待真实30秒租约，新fence重发原字节，两个offset同摘要、测试Sink effect=1 |
| B12 报告与隔离 | 准备清单、CAPTURED/ALREADY_CAPTURED、INVALID_SOURCE/SOURCE_CHANGED、WAITING、取消未处理、READY/SENDING/PUBLISHED/DEAD分别可见；有问题或取消不冒充全量覆盖；11保护及旧迁移/CPA/跨日fixture保持 |
| 独立JVM退出 | before-item在Outbox写后item写前halt91，事务和序号均0；after-batch十项提交后halt92，保持10；新仓储续办均15项COMPLETE，不用普通异常冒充退出 |

测试Sink仅证明原事件重复应用机制。实际Seen Redis故障、offset提交/重平衡/毒消息与旧入口吞错仍属于C15C/S15C；Flink窗口正确性归S19。

## 命令与证据

运行材料全部保留在 `.dev/codex-flow-reliability/round-8395c172/`，不提交：

- `history-pg-first.log`：首轮PG12项全PASS。
- `b2-targeted.log` / `targeted-reports/`：B2 PG12 + Kafka2 + Seen2 + Flink2，共18项全PASS；没有失败、错误或跳过。
- `behavior-final.log`：CI同命令 `mvn -B -pl recsys-behavior,recsys-rec-engine,recsys-streaming -am test -Dgroups=behavior-reliability -Dsurefire.failIfNoSpecifiedTests=false`，43项/9类全PASS（B2新18项+B1受影响25项），零失败/错误/跳过，XML归档final-reports。
- `legacy-regression.log` / `regression-reports/`：`mvn -B -pl recsys-behavior,recsys-rec-engine,recsys-streaming -am test -Dtest=BehaviorServiceTest,BehaviorServicePostgresTest,BusinessErrorContractTest,BehaviorEventRoundTripTest,CategoryEnricherPostgresTest -Dsurefire.failIfNoSpecifiedTests=false`；24项/6类全PASS，零失败/错误/跳过。
- `crash-evidence/`：JVM退出marker；Kafka日志含eventId、payload SHA、topic/partition/offset及fixture效果，不打印原负载。
- `static-*.log`：持久化边界6项、错误码10项、Mapper边界、生成物一致性和公开错误检查PASS；`git diff --check` PASS。
- `hygiene.json`：IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS；100/10是已命名契约常量，history与自然outbox状态属于不同领域，事务审查见`review-notes.json`。统一formatter不可用，独立Java静态分析未配置，不宣称完成这些检查。
- `protection-check.json`、`source-fingerprints.json`、`prior-ci.json`：保护/验证源码/前置CI证据；最终交付与CI见`delivery.json` / `ci-snapshot.json`。

独立验证为同一Agent的分离只读pass，不声称第二评审者。未修改共享时钟/资金规则、未部署、未启用共享CPC/CPA/对账、未访问共享测试数据；不新建worktree、不清理用户内容。

本次18个独占测试容器均已退出，所有本地测试会话结束（runtime-final.json）；11保护文件、原迁移/CPA、跨日修复3源文件及CODEX_PROGRESS历史段落指纹一致。验证后16个源码/测试/workflow路径指纹不变。独立只读验证PASS见independent-review.json；验证未发现失败，无重跑旧CI或放宽断言。新增commit的完整SHA、正常ff合并/push和新CI状态写入delivery.json/ci-snapshot.json，后续main推送前须先核实该源码CI终态。下一业务步骤ER-D0/C15C，随后真实旧/新Seen恢复，再按原序S16～S21；本回合不自行启动下一片，S22～S25继续暂停。
