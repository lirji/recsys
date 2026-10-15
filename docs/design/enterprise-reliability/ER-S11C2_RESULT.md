# ER-S11C2：稳定点击交付与双模式API

2026-10-15；基线`e599b3e524c19b7f277d3253293a20a4f13a5e7a`，原任务分支`feat/sandbox-click-settlement`。**C2 DONE / PASS，父S11 DONE / PASS**。资金链与全仓clean verify均通过，进入正常Git交付，随后继续S12。

## 实现和审查

ad V011独立点击准备检查点；engine V004独立整次点击交付。共用Query/Candidate/Prepare/Prepared形状及已有租约/fence/冻结/逐项恢复机制，以有限服务端ChargeMode选择固定SQL命名空间，不接受外部标识符。旧构造默认IMPRESSION；新旧同类型bean显式Qualifier，避免多bean装配歧义。旧表及已执行迁移不变。

新ClickDelivery.Offer使用ClickPrice和独立click-offer用途；只有纯候选签名，准备阶段冻结真实价格、调用同一C11B预算owner，再记录原资格。一个adDb事务资源，编排禁止持外层事务。报价、预占或准备结果提交后响应丢失按原childId续办，不续期/重占；已完成原请求在密钥轮换或停止新增后可恢复，未完成待验签仍须保留key。候选/原请求/已完成结果受数据库guard保护，预算拒绝不补位。engine完整计划先落库才调用owner，primary库与adDb分开；不重跑已冻结候选/实验。

新click.v1 SandboxClickService与ClickProtoMapper，可信独立engine身份、金额字符串、纳秒时间、严格枚举和可选金额/ID、结果身份校验。in-process/gRPC gateway共同指向同一真实业务实现；未知/超时/旧服务未实现保留失败，无旧Redis fallback。ADMIN HTTP增加click-ad-deliveries、click-receipts、click-settlements；首次receipt决定与当前资金状态分开，WAITING实收为空，状态快照无token/主体，错误脱敏。gateway现有sandbox通配路由及ADMIN防线继续适用。

`click-delivery-enabled`与原三个安装开关同时显式开启，默认false。停止新增用原admission，保留查询、原结果、到期回收和C1可靠事件。无新UI、真实收费、生产部署或共享环境迁移。只在ad-serving测试范围复用现有engine test-support及已选Testcontainers，不新增组件/版本或生产依赖边。

## 验证证据

| 验证 | 实际结果 |
|---|---|
| ad真实PG 8项 | 纯候选无资金；8线程同准备；报价后失败/预占后丢响应；原期限/token和原拒绝恢复；key轮换、暂停新增及既有结果续办；错用途/跨主体/过期；不可改原结果、注释、摘要与外层事务拒绝 PASS |
| engine真实PG 8项 | 冻结失败不得准备、部分owner响应丢失原页恢复、租约竞争与旧fence拒绝、不可替换条目/结果、拒绝不补位、空候选与依赖失败区分、同UUID两模式隔离及数据库guard PASS |
| HTTP 6项 | 准备入口与回执/状态入口认证、严格字段类型、400/401/403/404/409/422/503区分、等待无实收、主体注入/脱敏 PASS |
| 真实PG+TCP/HTTP 8项 | 本地及实际gRPC分别完整交付→先点击等待→可见捕获→原回执查询/当前状态；相反顺序；准备提交后丢响应、回执提交后丢响应；大ID/精确金额和双owner隔离；实际Spring新旧装配共存；nosec仍拒绝匿名/通用caller PASS |
| 边界3项 | 默认关闭与grpc engine无本地资金owner；proto null/零额/纳秒/坏枚举/错资格一致性；deadline/未实现/冲突与查询404/写404区分 PASS |
| 资金链完整回归 | **249项、52类、0失败/错误/跳过**，17模块SUCCESS；覆盖原CPM与S11A/B/C1 PostgreSQL/Kafka/HTTP/RPC |
| 全仓构建 | `mvn -B -e clean verify`：23模块全部SUCCESS；577项/133类、0失败/错误，4项既有按需门禁跳过，573项实际执行通过；编译、打包、已有coverage门禁通过 |
| Code Hygiene | IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS，无阻断。根级无canonical formatter、独立Java静态分析未配置；有限模式枚举与资金事件族同名值的advisory经审查为不同职责，不耦合两层。测试依赖沿TECH_SELECTION的显式ChangeSet元数据复用，非工具绕过 |

资金链命令：`mvn -B -e -pl recsys-rec-engine,recsys-ad-serving,recsys-advertiser,recsys-gateway -am test -Dgroups=money-chain -Dsurefire.failIfNoSpecifiedTests=false`，日志`/tmp/recsys-s11c2-money.log`。专项`/tmp/recsys-s11c2-narrow2.log`的20项已通过，随后独立在线`/tmp/recsys-s11c2-online.log`11项通过，最后补2项ad恢复并运行完整249项；最终以完整回归为准。hygiene `/tmp/recsys-er-s11c2-hygiene.json`。全仓日志`/tmp/recsys-s11c2-verify.log`。

首轮PG检查发现新JSON guard中减字段运算与数组下标的优先级错误，修正括号后实际数据库恢复测试通过；在线测试首次编译调用了包内测试辅助方法，改为测试自建相同事务配置及SHA256计算，没有扩大产品方法可见性。所有最终断言保留，没有跳过必需测试。

测试限制：真实事务/HTTP dispatch/TCP RPC、Spring配置实际执行；候选排序、实验外部依赖使用替身，精确定价本身由S11A真实竞价测试持续回归。报价时钟仅在独占测试schema INSERT时回拨两秒以满足可见时长，不修改产品不可变历史。故障为提交后主动抛错，不声称宿主kill或全站灾备。新UI未实现且本片无UI变更，浏览器视觉验收N/A；旧浏览器由正常CI继续验证。

## 文档及交付边界

已同步C11_DESIGN、CONTRACTS、TECH_SELECTION、运行手册、架构边界、实施状态及CODEX_PROGRESS。Q-04已确认，S12不再等待窗口政策。只stage本片路径，不夹入7项保护文件、console/test-results、未跟踪C15A草案或忽略配置/数据/模型/产物；无新worktree、未清理目录。

非文档验证指纹：`b4e5eec607c48b970bf9da2c8aba7380f87239d17f4e6fe9b185e9ff41bb51b9`。源路径清单保存在本机`/tmp/recsys-s11c2-source-paths.json`。最终验证之后不再修改源码，按已授权正常提交/快进合并/push main，不强推、不执行部署。

全仓4项跳过为原PostgresIntegrationGateTest与3个专用浏览器fixture/acceptance（SandboxExposureBrowserFixtureTest、NaturalExposureBrowserAcceptanceTest、FeedExposureBrowserAcceptanceTest），需其专用启动命令，非本片必需钱链路跳过。当前249项money-chain全部实际执行；本片没有新增skip。

SKILL_HANDOFF: backend-implementation → implementation-validation → project-documentation → update-progress-docs → task-git-delivery；ER-S11C2与父ER-S11 DONE，validation=PASS，gate=PASS_WITH_LIMITATIONS（既有formatter/独立Java静态分析未配置）；下一项ER-S12 CPA，Q-04已批准，无需再次确认。

Git交付：`f914f57184f36b17ca11302ed53686f3a3e48516`已正常提交、快进合并并推送origin/main；切到原目录新任务分支feat/sandbox-conversion-settlement继续S12。远程CI38698175497已完成：build、money-chain、natural-exposure、security四项成功；sandbox-exposure-browser失败。原因是旧按需浏览器宿主@Bean名称operations未匹配新显式Qualifier("deliveryOperationService")，未启动即退出。修复及专用浏览器证据见ER-S11C2_FIX_RESULT.md，不能把原clean verify的按需跳过当作已执行浏览器验证。
