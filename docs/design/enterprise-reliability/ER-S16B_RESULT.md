# ER-S16B：广告主状态与显式跨owner批次

状态：DONE / PASS（必要实现、本地验证与分离只读复审）。A/B均已满足必要验收，父ER-S16可关闭。设计5c0f2901与A实现6fe79594保留；本片逻辑提交、正常ff/push与精确新CI事实见`.dev/codex-flow-reliability/round-7fe6f115/delivery.json`，不以本地PASS或前置CI代替新CI结论。唯一状态见[IMPLEMENTATION_SLICES](IMPLEMENTATION_SLICES.md)。

## 结果与实现边界

真实Controller的广告主status写已接通ROOT_STATUS，原DTO、成功响应和manage政策保持。旧A命令按原kind/原字节查回，全部可靠开关仍默认false。root状态/version/事实与起点在owner库同事务；guard封闭集合，最多100个ad/页持久冻结原版本、摘要与游标。每ad使用独立OWNER/CHILDREN/FINAL步骤，本库事件与目标完成原子；全部目标事实完成后才最终化根命令并释放guard。暂停/恢复保留各ad原status/review和删除墓碑。

内部CatalogStatusBatchService冻结1～100个明确owner/expectedVersion/status，固定home、batch及child身份。逐目标先持久派发再访问另一库；未知次数、租约和观察轮转有界，一目标暂停仍可推进其他目标，父计数保持PARTIAL。新稳定审计只取消未派发READY；续办保留原目标/expectedVersion/childId，迟到旧批次保存CONFLICT。原child PAUSED仍需其自身审计恢复。新批次/审计分别受admission/recovery开关控制，没有新增公网运维接口。

追加V002任务列、调度索引与不可变清单保护；已发布V001摘要不变。显式物理安装器每版重建锁和预算、核验原SHA；V001成功而V002失败可续装缺失版本，B启动核验两版。保留无新表legacy seed例外、默认分片、无迁移default-off启动及可靠profile日志脱敏。运行入口、处置和回退限制见[CATALOG_EVENTS_RUNBOOK](CATALOG_EVENTS_RUNBOOK.md)。

## 最终验证

同一次最终完整目录门禁：**141项/17类，零失败、错误、跳过，exit 0，15:10**。其中advertiser123、offline18；包含A精确CI的全部14类及新增B三类20项，不是分版测试相加。日志、17份XML、403项源码SHA256和逐项矩阵分别见round-7fe6f115的`status-full-gate-2.log`、`full-gate-2-reports/`、`full-gate-2-sources.json`、`TEST_RESULT.json`。

```sh
mvn -B -e -pl recsys-advertiser,recsys-offline -am test '-Dtest=Catalog*Test,SeedAdsPostgresTest,AdvertiserRepositoryPostgresTest,CreativeReviewTest,AdvertiserAuthzTest' -Dsurefire.failIfNoSpecifiedTests=false
```

| 必要验收 | 实际证据与结果 |
|---|---|
| C16-13 多页暂停/恢复 | 105广告暂停/恢复；104目标清单中首个原事件已提交后强退独立JVM，原清单、版本及事件字节续办；另一用例真实children PG停机，3次耗尽PAUSED保留guard，新RETRY_STEP审计轮经产品worker完成原片段/版本和Kafka。PASS |
| C16-14 跨owner部分结果 | 奇偶owner和相反children分片；真实单库stop/start期间健康目标推进，父PARTIAL；原批次/子命令续办；取消仅未派发目标、末次领取JVM退出后有界清算。PASS |
| C16-15 并发与版本 | 同owner并发、低ID创建封闭集合、相反暂停/恢复竞争、重复batch、旧expectedVersion冲突；原身份与计数不被重采样。PASS |
| 迁移和预算 | 真实两PG/ShardingSphere的V001升级、并发安装、逐版锁预算、原身份/摘要保留、V001-only启动拒绝/V002启动通过；查询真实锁等待有界。PASS |
| A回归及兼容 | 物理原子27、原HTTP/独立进程13、初始化8、恢复8等原类全部执行；真实无治理迁移default-off及18项seed兼容检查。PASS |
| 权限专项 | 最终源码的真实SDK、权限服务、独占SpiceDB与HTTP status manage：editor/无资源权限ADMIN拒绝，owner完成并查回原结果。`status-authz-3-result.json` PASS；不计入141。 |

首次完整门禁108项/16类因两次容器启动错误失败，offline未执行，日志原样保留；不是产品PASS。测试创建阶段增加最多3次启动并重新探测127.0.0.1端口，真实故障stop/start仍保持同容器/端口；未改变产品step3/send5/lease30预算或断言。更早定向夹具失败与修正见本轮原始result/log文件；最终以上述完整运行优先，不叠加旧结果。

三名分离只读审查者覆盖业务/持久化/运行与测试；receipt身份、逐版安装事务锁、查询超时与批次开关发现均已修正并实测。持久化边界、错误码、公开错误、diff和链接检查通过。hygiene无阻断，唯一工具限制是仓库未配置统一formatter；独立静态分析器未配置，按既有结构检查，不新增依赖。

前置A的精确CI38983151163、head6fe795945164f39901159f51019d7fd835436096已9/9成功。11保护、37旧迁移/fixture、59旧SQL、A V001、正式C16及CODEX_PROGRESS历史均不变，原用户改动排除交付。未重跑未受影响S15验证。

## 保留边界

多页JVM恢复通过独立真实产品进程的有界内部advance；批次/PAUSED恢复另由真实worker证明，不混同。物理锁阻塞、真实PG stop/start及JVM强退分别记录，未宣称网络分区、broker副本容灾或ACL故障已测。

S16只生产目录事件，不包括S17 Inbox/墓碑/Redis消费保障。无共享迁移/启用、部署或生产吞吐/RTO/RPO结论；B未收敛命令不能交给仅识别A协议的旧worker，混合版本切换未验收。所有.dev/target和用户材料保留。B完整交付后返回父；后续S17～21按原DAG继续，S22～25暂停。
