# 企业级搜推广：下一阶段计划

版本：2026-10-15 / IMPLEMENTATION_AUTHORIZED。分析基线：`2dca4b86b8f082d0b07d999943a5c05e7507ed86`。

原计划文档交付已完成。用户随后明确“开始实施吧”，实施已获授权；首版 sandbox、金额/时区与图文曝光政策也已确认，见 [正式契约](CONTRACTS.md)。按 [实施状态](IMPLEMENTATION_SLICES.md) 逐片推进；下文保留规划顺序，待决策表中的已确认部分由正式契约覆盖。CPA、正式账户和运行目标不因开始实施自动定稿。

## 文档入口与决策状态

| 文档 | 职责 |
|---|---|
| [企业级能力评估](../../12-企业级搜推广评估.md) | 优势、不足、源码风险 ER-R01～ER-R15、五目标以外的后续建设项 |
| [后端架构草案](BACKEND_ARCHITECTURE.md) | 所有权、事务、生命周期、失败与迁移边界 |
| [契约提案](CONTRACTS_DRAFT.md) | API/事件/钱/身份/幂等语义，正式契约待 D0 冻结 |
| [技术取舍](TECH_SELECTION.md) | 比较替代方案、复用现有技术与成本 |
| [验证计划](VALIDATION_PLAN.md) | 真实组件测试、故障矩阵、指标与发布门禁 |
| [独立评审与文档验证](REVIEW.md) | 五类只读分析、独立审查意见、修订与本轮检查结果 |
| [恢复进度](../../../CODEX_PROGRESS.md) | 本轮产物状态、未完成和下一步 |

### 已确认决定

- **D-01 曝光口径**：客户端可见曝光回执，服务端校验资格、签名、主体与幂等。曝光收费模式在接受合法回执后收费；CPC/CPA 仍以各自合法后续事件触发，不能都改成曝光收费。
- **D-02 预算约束**：硬上限，先预占后计费，预算不足停止新增广告投放。预占应覆盖窗口内最大可收费义务，不用概率折算冒充硬上限。

### 待决策项

下表为建议和影响，不是隐藏默认值。可先批准计划顺序，再按 D0 冻结首个 CPM 闭环需要的参数；CPA 专属问题只阻断 CPA 和包含它的正式切换，不必阻断已具备契约的 CPM 验证。

| ID | 必须确定的业务问题 | 草案建议 / 取舍 | 阻断范围 |
|---|---|---|---|
| Q-01 | 可见比例、持续时间、连续/累计、页面 hidden 规则 | 按创意形态制定带版本政策；建议连续时长、hidden 停止/重置；不假定统一数值 | 可见回执及浏览器验收 |
| Q-02 | 真实发布终端/来源；Console 是否参与正式收费 | 建议 Console 默认服务端 sandbox，排除正式预算和实验；真实发布方单独准入 | 正式来源放量；sandbox 联调可先行 |
| Q-03 | 资格 TTL、离线最大延迟、预占有效期、点击先于回执 | 建议点击可持久化等待曝光依赖，有界到期后非计费；窗口需一致且可解释 | CPM/CPC 对应状态迁移 |
| Q-04 | CPA 归因/迟到/预占窗口；一资格是否可多次收费 | 首版每资格限定一次合法收费；长预占占额度，迟到已释放不自动补扣 | CPA；未决定时不开该模式新计费 |
| Q-05 | 预算日时区、跨日归属、额度调减、冲正是否恢复预算 | 建议义务归预占日；低于占用的调减拒绝；冲正不自动恢复预算 | 所有真实账务 |
| Q-06 | 支持币种、精度、金额上限、报价单位、舍入时点 | 推荐精确小数+字符串传输；必须通过小额 CPM 累计与最大值样例 | schema/Money/账本 |
| Q-07 | 峰值 QPS/热点/增长、可用性、RTO/RPO、数据保留 | 先建立基线；区分业务目标、估算和实测，不编造数字 | 正式容量、灾备和生产准入 |
| Q-08 | 历史账户期初余额、未决义务、切换账户与责任人 | 无可靠期初时仅测试新账户；旧账不从日志估算后直接切换 | 真实账户切换 |
| Q-09 | GD/其他未定义计费模式、外部结算和多转化收费 | 本轮先覆盖明确的 CPM/OCPM、CPC/OCPC、CPA；其他模式独立合同 | 相关模式，不阻断先行闭环 |

## 目标与非目标

| 目标 | 验收不变量 | 对应风险 |
|---|---|---|
| 曝光可信 | 候选、被混排剔除、离屏、未达阈值不因响应返回而收费；签名/身份/重放校验可解释 | R01/R05/R07/R13 |
| 钱算对 | 同币种预算日内 `charged + held <= limit`；重复、并发和结果未知不重复记账；报价/预占/实扣分开 | R02/R03/R04/R06/R14 |
| 事件不漏 | 从服务端持久化接受起，业务事件及待发送事实原子存在，可查询积压、补发、去重、对账 | R08/R09/R10/R15 |
| 故障可恢复 | 预占回收、Outbox/Inbox、投影、流式 checkpoint 与后台任务在故障后可续办；有隔离恢复证据 | R04/R06/R09/R10/R11/R15 |
| 效果可验证 | 新旧曝光口径隔离，训练数据时间正确；发布评估拒绝坏数据，实验结果可重现且区分真实与合成证据 | R12/R13/R14 |

本轮及此计划不承诺“真人可见已证明”、终端观测绝对不丢或任意灾难 RPO=0。不以技术选型代替恢复证据。搜索相关性、campaign/adgroup 深化、多租户运营、新模型和大规模拆分记录在评估 backlog，不混入最小资金闭环。

## 路线与依赖有序候选步骤

五阶段：A 契约与曝光/资金最小闭环；B 点击/转化及运营账务；C 可靠事件和投影；D 故障恢复与资源边界；E 数据质量、效果与受控切换。

以下 ER-Sxx 是**候选工作编号，全部为 PLANNED**。D0 完成批准的架构/契约后才由实施切片流程生成正式 `IMPLEMENTATION_SLICES.md`，保留编号；禁止直接把整张表交给实现工具一次性修改。每片实现+相应窄验证+文档同步为一次有界 pass。共享契约/迁移按单一 owner 串行维护，当前全部 `parallel=no`。

| ID / 阶段 | 可观察结果与本片范围 | Needs | Owner / 文件组 | 验收与 Runtime |
|---|---|---|---|---|
| ER-D0 / A | 冻结首片适用参数、契约版本、真实来源、事务数据源、迁移方式及结算价规范化规则；CPA 未定可分后续契约 | — | 设计 owner；本目录 | 正式契约与切片已批准；无业务运行环境要求 |
| ER-S01 / A | 修正预算分/元展示，报价不再显示为已实扣 | ER-D0 | backend+frontend；F01 | 单位样例贯穿接口/UI，缺失值不冒充零；既有 PG/Redis 测试或确定性 fixture |
| ER-S02 / A | 广告主提交并查询生效预算命令；账户数据与幂等命令原子持久化 | ER-D0 | backend；F02 | 重复/乱序命令和低于占用调减正确；真实 PG，多数据源回滚 |
| ER-S02A / A | 竞价输出形成可查询的精确结算报价与最大义务快照 | ER-D0 | backend；F02A | 货币输入/单位/转换/舍入版本可追踪；小额 CPM、极值/NaN 拒绝；money-chain + PG |
| ER-S03 / A | 单广告资格的预占、查询、释放和到期回收可运行 | ER-S02, ER-S02A | backend；F03 | 余额只够一份时并发只成功一次；回收重试、双执行器和未知响应不重复；真实 PG |
| ER-S04 / A | 拆开纯候选与最终准备；engine 持久化整次交付/位置及子操作，稳定 ID 贯穿双模式 | ER-S03 | backend；F04 | 最终混排/频控后预占；丢响应后即使候选变更也恢复原条目/位置/资格；部分失败不重排已签项；PG+gRPC |
| ER-S05 / A | 单条广告可见回执原子确认，CPM/OCPM 账本、幂等结果、Outbox 同事务 | ER-S04 | backend；F05 | 非法/重放拒绝；收费与释放竞争只有一个终态；已提交回执可查询；真实 PG |
| ER-S06 / A | 一条不可变账本事实从 Outbox 到 Kafka 消费投影并可重放 | ER-S05 | backend/runtime；F06 | 提交/ACK 后崩溃可恢复；乱序不丢合法金额；真实 PG/Kafka，隔离 topic |
| ER-S07 / A | `/search-ads` 接入公共测量/队列，完成 sandbox CPM 浏览器闭环 | ER-S06 | frontend；F07 | 阈值、隐藏页、超时重试、主体切换、历史脱敏；真实后端+Playwright |
| ER-S08 / A | 自然推荐/搜索最终资格与 behavior 可见回执落库及 Outbox | ER-D0 | backend；F08 | engine 不直接冒充已曝光行为；重复不增加分母；真实 PG，签名兼容 |
| ER-S09 / A | `/recommend`、`/search` 复用采集，替换手动曝光口径 | ER-S07, ER-S08 | frontend；F09 | 新交付与缓存重显可区分；历史/对比不发回执；浏览器+真实 API |
| ER-S10 / A | `/feed` 保留自然/广告最终身份并接入回执 | ER-S04, ER-S07, ER-S08 | backend+frontend；F10 | 混排剔除无回执计费；最终位置、重试与自然降级正确；双模式+浏览器 |
| ER-S11 / B | CPC/OCPC 点击接收、等待曝光依赖、捕获或到期非计费 | ER-S05, ER-S07 | backend；F11 | 点击重放、先点击后回执、反作弊拒绝、跨日均遵守预占；真实 PG |
| ER-S12 / B | 经广告主认证的 CPA 转化业务键及捕获/迟到处理 | ER-S11，Q-04 已定 | backend；F11 | 幂等 outcome 同时约束资金副作用；跨广告主/篡改/重复/已释放迟到不越额；真实 PG |
| ER-S13 / B | 账户/预算日可对账，故障未决可查询并续办 | ER-S06, ER-S12 | backend/offline；F12 | 有界任务检查点、领取版本、重复修复与差额说明；禁止通过修改历史账本“平账”；PG/Kafka |
| ER-S14 / B | 运营页展示权威预算、预占、账本消费、来源与水位 | ER-S13 | backend+frontend；F13 | API 与 UI 对账；未知/待确认显示真实状态；查询授权；浏览器+真实 API |
| ER-S15 / C | behavior 的已接受事件可靠发送，不因重复入库漏发 | ER-S06, ER-S08 | backend；F14 | DB 成功 broker 失败、重复请求和恢复后下游效果正确；PG/Kafka |
| ER-S16 / C | 广告目录分片内业务写入和 Outbox 原子提交 | ER-S06 | backend；F15 | 分片路由及多分片暂停任务失败可续办；不把公共库 Outbox 当同库事务；真实目录分片/Kafka |
| ER-S17 / C | 广告投影版本、Inbox、墓碑、Redis 可靠刷新 | ER-S16 | backend；F16 | 旧事件不能复活删除/暂停广告，消费提交窗口可恢复；PG/Kafka/Redis |
| ER-S18 / D | 日志增量同步具备补洞及持久化恢复检查点 | ER-S06 | offline；F17 | 低 ID 后提交不遗漏，崩溃后有界续跑；真实 PG、重复事件 |
| ER-S19 / D | Flink checkpoint、事件时间/迟到政策及 Redis 幂等窗口投影 | ER-S15 | streaming/runtime；F18 | 重启、重放、旧窗口清理与特征对账；Kafka/Redis/持久 checkpoint 隔离目录 |
| ER-S20 / D | 主召回总 deadline、有界执行与缓存故障回源预算 | ER-D0 | backend；F19 | 热点/断 Redis/慢通道下资源有上限，正常流量不被吞；负载及真实 Redis |
| ER-S21 / D | shadow 等非关键后台计算有界、过载可丢弃且不污染主链路 | ER-S20 | backend；F19 | 队列满和超时取消可观测，不放弃关键业务事件；负载测试 |
| ER-S22 / E | CVR 样本时序、as-of 特征、成熟度和时间切分正确 | ER-S12, ER-S15, ER-S18 | offline；F20 | 点击前转化、未来特征、泄漏和未成熟样本有拒绝/隔离证据；PG+固定数据集 |
| ER-S23 / E | 训练/模型评估门禁与可追溯制品清单 | ER-S22 | offline/CI；F20 | 空 valid、NaN、缺 best model 拒绝导出；数据/模型/特征/代码可定位；Python+Java 契约 |
| ER-S24 / E | 实验报告按可信分母、来源、窗口、水位重算 | ER-S09, ER-S10, ER-S14, ER-S23 | offline+frontend；F21 | 成熟度/污染/样本不足拒绝收益结论；raw/CUPED 可复算；固定与真实隔离数据 |
| ER-S25 / E | 全链路故障验收和切换/恢复 runbook 可审查 | ER-S17, ER-S19, ER-S21, ER-S24 | QA/runtime；F22 | [验证矩阵](VALIDATION_PLAN.md)有证据；RTO/RPO 实测；只产出切换准入结论，不自动部署 |

**下一步是 ER-D0，不是立即开启全部实现。** ER-S01 是最小风险修正；ER-S02～S07 是首个资金闭环。ER-S03 已包含基本过期回收，不能等最后才释放预算。ER-S06 提前验证账务 Outbox，避免前端第一条 accepted 路径仍靠尽力发送。其他 owner 的 Outbox 各自再做真实事务验证。

ER-S02A 为独立审查后补入的精确定价步骤，保留其他候选编号不重排；ER-S03 显式依赖它，不能先用旧 double 收费再补精度。以上共有 26 个候选实施步骤，另有 ER-D0 设计冻结。

正式实施时每片仅开启其已验证的 sandbox/模式。CPM 窄范围生产准入可以单独评审其所有必要依赖与运维能力；本计划 ER-S25 是包含 CPC/CPA 的整体准入，未完成不能宣称所有模式企业就绪。

## 文件级改动边界

以下路径为计划；“新增”文件尚不存在，命名在 D0 对齐仓库风格。路径组供上表定位，不表示本轮已修改。SQL 迁移由每个数据库 owner 顺序管理，新表/字段均须注释。

| 组 | 预计文件/目录 | 约束 |
|---|---|---|
| F01 | `recsys-advertiser/src/main/java/com/recsys/advertiser/AdvertiserService.java`；`console/src/pages/online/SearchAdsConsole.tsx`；`console/src/components/funnel/derive.tsx`；`console/src/components/charts/AdBiddingChart.tsx` | 明确单位及报价含义，不伪造账本数 |
| F02 | `recsys-ad/.../AdShardingConfig.java`；新增同包 `billing/BudgetAccountService.java`；`recsys-advertiser/.../AdvertiserService.java`；新增版本迁移（目录在 D0 定） | adDb 事务管理器、账户约束、命令查询和对象授权一起交付 |
| F02A | `recsys-ad/src/main/java/com/recsys/ad/BiddingService.java`；新增定价规范化用例及报价持久化；金额 DTO/proto/Mapper 与对应 money-chain 测试、精确金额迁移 | 排序浮点不直接成为收费真值；冻结报价供 S03/S05 使用，旧价格量化明确核验 |
| F03 | 新增 `recsys-ad/.../billing/ReservationService.java`、`ReservationExpiryJob.java` 及持久化 Mapper/SQL、迁移、PG 测试 | 不在业务层拼 SQL；到期与捕获同锁域 |
| F04 | `recsys-ad/.../AdPipeline.java`、`AdMixer.java`；`recsys-rec-engine/.../FeedOrchestrator.java`、新增交付操作持久化用例/迁移、`ad/AdServingGateway.java` 与两实现；`recsys-ad-serving/.../grpc/AdServingGrpcService.java`；`recsys-proto/src/main/proto/ad_serving.proto`、`AdProtoMapper.java`；`recsys-ad-common/.../SponsoredAd.java` | 整次操作及子操作可恢复；DTO/proto/mapper 双向一致；替换旧 fallback 契约测试 |
| F05 | 新增 `recsys-ad/.../billing/ExposureReceiptService.java` 及接入端点；`AdEventLogger.java`、`PacingService.java`；账务迁移和真实 PG 测试 | 旧入口按版本/来源封禁或隔离；receipt/账本/outbox 同事务 |
| F06 | 新增账务 owner 的 Outbox relay 与消费投影；`.github/workflows/ci.yml` 对应窄门禁；隔离测试配置 | 只复用 Kafka；发送/消费两种提交窗口都测 |
| F07 | 新增 `console/src/api/exposure.ts`、`hooks/useVisibleExposure.ts`、`exposure/receiptQueue.ts`、`components/exposure/ExposureScope.tsx`、`ReceiptStatus.tsx`；修改 `api/types.ts`、`api/ads.ts`、`pages/online/SearchAdsConsole.tsx`、`hooks/useAuth.tsx`、`hooks/useRequestHistory.ts`、`components/explain/TracePanel.tsx` | 页面 opt-in，票据脱敏，主体隔离；不新增 UI/状态框架 |
| F08 | `recsys-rec-engine/.../RecommendOrchestrator.java`、`experiment/ExposureLogger.java`；`recsys-common/.../RecommendItem.java`（按实际包定位）；`recsys-behavior/.../BehaviorService.java`、对应端点与迁移 | 自然行为权威归 behavior；签发不再记可信分母 |
| F09 | `console/src/pages/online/RecommendConsole.tsx`、`SearchConsole.tsx`、`api/behavior.ts`；`components/explain/ItemCard.tsx` 仅必要 ref | 卡片不自动打点，回执复用 F07 |
| F10 | `recsys-ad-common/src/main/java/com/recsys/common/ad/FeedEntry.java`；`AdMixer.java`；`FeedOrchestrator.java`；`console/src/pages/online/FeedConsole.tsx`、`api/types.ts` | 自然/广告资格及最终位置不丢失 |
| F11 | `recsys-ad/.../AdPipeline.java`、`AdOutcomeService.java`；新账务用例；`recsys-rec-engine/.../SearchAdsController.java`；`console/src/api/ads.ts` 及模拟按钮 | 来源与广告主授权；同业务事件去重约束收费 |
| F12 | 新增 `recsys-offline/.../BillingReconciliationJob.java` 与账务查询/修复命令；任务状态迁移 | 有界检查点、审计、授权；自动对账不等于自动冲正 |
| F13 | advertiser 报表 API；`console/src/pages/adv/AdvertiserReport.tsx`、`components/reports/viz/AdReportViz.tsx` | 钱账权威与报表水位分开，未知不转零 |
| F14 | `recsys-behavior/.../BehaviorService.java`；新增本库 Outbox、relay、迁移；行为消费者 | 与 F08/F06 共用契约不跨库共用事务 |
| F15 | `recsys-advertiser/.../AdCatalogEventPublisher.java`、业务写用例、分片迁移；`recsys-ad-common/.../AdCatalogEvent.java` | 新增 aggregateVersion；目录分片路由、停用 fanout 可恢复 |
| F16 | `recsys-ad-serving/.../catalog/AdCatalogEventConsumer.java`、`AdServableRepository.java`；新 Inbox/Redis 投影任务与迁移 | 不吞错；保留版本墓碑 |
| F17 | `recsys-offline/.../SyncAdEventLogJob.java`；任务 checkpoint 与补洞对账 | 不默认运行 `--full` / TRUNCATE |
| F18 | `recsys-streaming/.../RealtimeFeatureJob.java`；作业配置/启动脚本；隔离 checkpoint 存储配置 | 不升级 Flink；先明确事件时间、迟到和状态恢复兼容 |
| F19 | `recsys-recall/.../MultiChannelRecallService.java`；`recsys-feature/.../RedisFeatureService.java`；`recsys-rec-engine/.../ShadowRankRunner.java` | 两片分别验证主路径与非关键资源，避免笼统全仓性能重构 |
| F20 | `recsys-offline/.../GenAdCvrSamplesJob.java`、`DataQualityJob.java`；`recsys-offline/train/train_dfm.py`；模型 manifest 与门禁测试 | 复用已有特征/质量能力；真实与合成证据分开 |
| F21 | `recsys-offline/.../AbReportJob.java`、`AbStats.java`；`console/src/pages/reports/ReportViewer.tsx` 及相关报表组件 | 保持统计方法已有约束，先修数据口径 |
| F22 | CI、`console/e2e/`、新 `docs/qa/enterprise-reliability/`、监控告警和恢复 runbook | 明确实测 revision、环境、命令、事件/账本断言和未验证项 |

`...` 表示该模块现有 Java 包根，不是新建目录名；实施前按实际类路径定位。本轮评估已提供可点击的实际源码入口。

## 前端页面流、组件和状态

保留 `/recommend`、`/search`、`/search-ads`、`/feed`，运营仍用 `/advertiser/:id/report`、`/reports/:category`；不新增导航。复用 Ant Design Table/Tag/Alert、ItemCard、TracePanel、EmptyState、Skeletons、FunnelBand、CollapsibleCard 与现有请求/认证层。

```text
在线页面（显式启用当前来源的曝光 scope）
├─ 请求表单 → 新交付操作 → 不可变响应身份/资格快照
├─ ExposureScope
│  ├─ ItemCard / Feed 行 / 广告创意单元（保持纯展示）
│  │  └─ useVisibleExposure(ref, qualification, policy)
│  └─ ReceiptStatus（仅必要调试反馈，不逐条 toast）
├─ receiptQueue → exposure API → 逐项持久化结果/账务状态
└─ TracePanel / 历史 / 对比（脱敏、无采集副作用）
```

标准任务路径：获取结果 → 滚动到创意 → 满足版本化可见政策 → 稳定事件入有界队列 → 发送/查询结果 → 展示回执状态；点击引用同一资格，转化只在有授权的模拟/真实回调入口发生。报价叫“计价单价/竞价价格”，只有账本证据才展示“已确认消费”。

| 状态/边界 | 行为与验收 |
|---|---|
| 未返回/未挂载/无资格 | 不观察、不补造曝光；加载骨架不是创意 |
| 未达阈值/计时中 | 观察实际创意区域，表格整行交叉不等于创意可见；隐藏页按政策停止/重置 |
| 待发送/发送中/离线 | 一次生成不可变 eventId；存储、并发、大小、TTL 有上限；不存 Bearer |
| 结果未知 | 显示待确认，有限退避、同 ID 查询/重试，不显示成功或未计费的确定结论 |
| 已接受/重复已接受/等待依赖 | 分别显示回执结果和账务状态；停止重复上报或按契约查状态 |
| 拒绝/过期 | 原因可见，终止重试；预算已释放不能通过前端改时间补扣 |
| StrictMode/重挂载/滚出再滚入 | 同资格只产生一个曝光业务事实；不同合法交付同 ad/item 可分别接受 |
| 表单编辑/身份或环境切换 | 使用原响应快照，不能换成当前 userId；退出后冻结/清理旧队列，不用新 token 发旧主体事件 |
| 新请求/缓存重显/历史/对比 | 显式重跑真正请求新资格；缓存重显复用身份；历史/沙盘/报表不触发采集 |
| 排序/分页/卸载/pagehide | 展示位置不能篡改资格最终 slot；清理 observer/计时器；只补发已达标事件，不把 sendBeacon 当 ACK |

浏览器测量结合页面可见状态与单调时钟，但不是人类注意力证明。会话暂存仅是恢复优化；终端关闭/存储删除未提交事件的损失单独统计或说明，不能算后端“不漏”已兑现。

视觉方向沿用当前紧凑调试界面，增加行内状态和账务列即可。每个 UI 片验证桌面与项目现有窄屏断点；检查加载/空/错误/过期/待确认状态截图，并实际操作完整路径，不只检查页面能打开。签名票据不可出现在历史复制/下载和普通日志中。

## 验证、发布与回滚

完整测试矩阵见 [VALIDATION_PLAN](VALIDATION_PLAN.md)。关键事务必须真实 PostgreSQL；Kafka、Redis 恢复必须真实隔离组件；JS 单测不替代浏览器视口行为。校验两种 ad-serving 模式、同库和拆库数据源配置、新旧契约共存。必要测试未执行/被跳过视为未验证。

正式切换需逐账户/业务日 fence，核准期初余额并确认唯一收费权威；shadow 只作比较，不产生真实资金副作用。开启范围、负责人、指标、观察窗、放量/中止条件写入发布记录；阈值由 D0/演练确定，不在这里伪造。生产部署需另外明确授权。

回滚分三类：代码回退须兼容保留的新 schema/事件；业务停止通过关签发、保留在途预占和 Outbox 续办；资金修正通过审计关联的补偿流水。不能删除账本/队列，也不能回退为请求即曝光、Redis 失败放行收费。自然内容允许按其独立降级策略继续。

## 本轮完成定义

ER-DOC-01：评估讨论记录可追溯；ER-DOC-02：五目标计划、架构/契约提案、验证矩阵和独立评审完成，明确待决策与下一步。只标记这两个文档交付项完成；不将 ER-D0 或 ER-Sxx 标记 DONE/READY。进度与 Git 交付记录见根目录 CODEX_PROGRESS。
