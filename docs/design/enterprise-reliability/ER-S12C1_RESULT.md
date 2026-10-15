# ER-S12C1：CPA可靠捕获事件与独立投影

2026-10-15；基线2d107e2828fe632efca15346f20d07ffc791f77c；原目录feat/sandbox-conversion-settlement。**C1 DONE / PASS，父S12仍IN_PROGRESS**，C2公开授权与稳定交付仍必需。

AdConversionCapturedV1 / ad-conversion-captured-v1独立契约，包含内部outcomeId、广告主业务conversionEventId、原金额/预算日/发生和捕获时间；无actor/token。严格类型、规范ID、CNY6、时序、16KiB、重复JSON键及尾随内容校验，新增未知字段兼容。V014增加不可变重放审计、Inbox明细和conversion_count日投影。

固定EventFamily新增CONVERSION，复用原投递/调度/消费机制。无外部表名替换或新基础设施。ACK后以fence标记、20次耗尽保留DEAD、重放审计；Inbox及汇总同本地事务，提交后offset+1；低账户版本独立事实仍累加。唯一索引约束event/qualification/visible/outcome/(advertiser,conversionEvent)，跨广告主同业务UUID合法，可见与内部outcome命名空间分别约束。独立conversion-events开关默认false，停止新收费仍可续发，不需要资格key。

专项**20项PASS**：13真实PG（含5轮并发/逆序回放）、5真实Kafka、2配置。最终完整money-chain **310项/60类，0失败/错误/跳过，17模块SUCCESS**；命令沿ER-S12A，日志/tmp/recsys-s12c1-narrow.log与/tmp/recsys-s12c1-money.log。实际覆盖三类资金/Outbox/投影共存、广告主业务身份冲突及跨广告主合法、不同命名空间合法/各自唯一、改负载/追加字段、半途投影失败回滚、领取fence/耗尽/审计重放、同库提交后未发、broker ACK后未标记、消费提交后未ACK、broker暂停及恢复、非法消息停止且不跳过位点、关闭收费后真实Spring装配并排空三种事件。

审查及限制：不访问权威账户来更新投影，不以进程健康代替效果；测试在独占PG schema和临时Kafka执行，暂停仅测试broker。故障注入覆盖提交窗口，不声称主机kill/整站灾备。历史和投影都未在共享环境开启；非计费转化仍在B权威业务事实表，不能将其计入收费日汇总。没有新UI、依赖或公开API，本片不承担广告主认证。

Hygiene=/tmp/recsys-er-s12c1-hygiene.json，IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS。根级formatter/独立Java静态分析仍未配置；16KiB已有命名常量，codec边界捕获异常只返回脱敏InvalidEvent且不会ACK，投影同资源短事务和外部位点窗口已由真实故障验证。无阻断finding，git diff --check通过。

10源路径清单/tmp/recsys-s12c1-source-paths.json，最终指纹38ae50e62b320aa8bd150b7569afb04fe9785eeb77e1b98e07c3a27db8044c10。已同步设计/契约、手册、架构、切片/进度；七项用户文件指纹未变，未跟踪/忽略文件保留，无额外worktree。

SKILL_HANDOFF: backend-implementation → implementation-validation → project-documentation → update-progress-docs → task-git-delivery；ER-S12C1 DONE，validation=PASS，gate=PASS_WITH_LIMITATIONS。正常Git交付后同分支继续C2，原整体DAG不缩减，不执行真实收费或生产部署。
