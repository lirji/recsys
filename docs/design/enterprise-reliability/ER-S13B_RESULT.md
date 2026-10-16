# ER-S13B：持久化只读对账任务验收

2026-10-16，本地实现与独立验收 DONE/PASS。基线0667fedc；最终9个源码/测试/迁移文件指纹 `2c5e30f9f81e6960325616bbcd84064ced842303ea31d2ac8650f6d262e8b5ae`。Git及精确提交CI另在实施状态记录，不能以本地通过冒充远程通过。

## 交付行为

同广告主最多一个活跃任务，冻结最多31原预算日。一次领取一天，30秒数据库租约、单调version fence、每日期20次尝试与持久退避；逐日只读RR快照、不可变报告与原子检查点。重启/重复命令可恢复；取消保留部分结果，旧执行器不能回写。COMPLETED只表示核查完成，账户不存在或差额保持原报告状态。仅内部服务、默认关闭，无自动全账户调度和收费副作用。

## 验收映射

| 验收 | 实际证据 |
| --- | --- |
| 持久恢复、逐日范围、缺账户不转零、完成与账平区分 | ReconciliationTaskPostgresTest真实PG，重新创建服务后续跑，原两日报告可读且观察时间分别保存 |
| 幂等/冲突、同账户唯一活跃、不同账户隔离 | 6并发创建/领取只有一个成功；跨广告主查询/取消拒绝 |
| 崩溃、旧lease/fence、失败上限与退避 | 过期回写拒绝、新执行器接管；20次失败/20次崩溃后持久FAILED；不保存异常原文 |
| 报告和检查点原子性、提交后查询恢复 | 报告INSERT后故障导致结果/进度/审计一起回滚；过期接管恢复，重复查询不增加结果 |
| 取消幂等/终态/部分结果 | 相同命令和原因返回原结果；内容变化/完成后取消冲突；取消后旧执行器无效 |
| 不可变/结构/运行边界 | 真实PG拒绝更新删除日报/审计；新增列中文注释完整；默认关闭、迁移摘要漂移拒绝、外层事务拒绝 |
| 原资金业务回归 | 资金链436项/75类，0失败/错误/跳过，BUILD SUCCESS |

专项命令：`mvn -o -B -pl recsys-ad -am test -Dtest=ReconciliationTaskPostgresTest -Dsurefire.failIfNoSpecifiedTests=false`，10项全部通过。日志 `/tmp/recsys-s13b-test.log`。

回归命令：`mvn -o -B -pl recsys-rec-engine,recsys-ad-serving,recsys-advertiser,recsys-gateway -am test -Dgroups=money-chain -Dsurefire.failIfNoSpecifiedTests=false`。日志 `/tmp/recsys-s13b-money.log`。

持久边界检查PASS（612生产Java/91 XML/45历史SQL），公开错误检查PASS，git diff --check PASS。Hygiene结果 `IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS`，无阻断；统一Java formatter与独立Java静态分析未配置，不宣称其执行。3项magic advisory已为具名技术边界常量；事务advisory已通过源码与真实PG核对：无外部IO持锁、快照独立、版本/lease保护、报告及检查点/审计同事务。报告 `/tmp/recsys-s13b-hygiene.json`。

## 文件与发布边界

新增common-ad ReconciliationTask；ad ReconciliationTaskRepository/Service/Configuration、Mapper/XML、V017、真实PG专项；ReconciliationService只追加同owner资源校验入口。没有新依赖、RPC、HTTP、前端或共享环境操作。V017为新迁移，旧迁移未改。关闭reconciliation-tasks-enabled仅停新任务入口，保留已有任务/报告/审计，不自动删除；保留期尚未指定。

用户七项文件指纹保持；console/test-results、env/模型/数据/构建产物保留，不stage。源清单 `/tmp/recsys-s13b-sources.json`。下一片为S13C，父S13仍IN_PROGRESS；S14方案已五维分析与独立审查通过，查询契约依赖S13C。
