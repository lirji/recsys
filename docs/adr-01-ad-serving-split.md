# ADR-01:广告在线服务拆分(ad-serving)

状态:**待决策**(设计已定稿;Phase 0 前后端分离 + Phase 1 契约/报表解耦已落地并验证)。

## 背景

架构评审(见对话记录)结论:自然推荐主链路维持模块化单体(保护在线/离线一致性);真正该动的是把
**广告"钱链路"从 rec-engine 进程剥离**——它有独立数据源(ShardingSphere)、独立可靠性诉求(计费/审计)、
独立变更节奏。这是 Phase 2。

## 落地时发现的真实耦合(比预想深)

读 `SearchAdsOrchestrator` 后确认:广告在线编排**深度复用 rec-engine 的整套 serving 栈**,不是一个自包含的 `recsys-ad` lib:

- pCTR/pCVR 来自 `RankRouter`(`recsys-rank`)——`recsys-ad` 自身**不算 pCTR**,分数由编排层注入。
- query 理解来自 `QueryUnderstandingService`(`recsys-query` 实现)。
- 排序需要 `recsys-feature` / `recsys-embedding`(以及 content/user 特征)。
- **广告分层 A/B**:`adVariant`/reserve-price 覆盖来自 rec-engine 自己的
  `com.recsys.recengine.experiment.ExperimentService`——这是 rec-engine 内部类,ad-serving 不能反向依赖 rec-engine。
- 广告纯逻辑(召回/相关性门槛/竞价/GSP/oCPC/校准/反作弊/DCO/pacing/GD/定向)在 `recsys-ad` [lib]。

**结论**:把 `SearchAdsOrchestrator` 搬进独立 ad-serving,等于把 query+rank+feature+embedding(+content/user)
这套 lib 一并搬进 ad-serving,并且必须先把 rec-engine 的 experiment 层下沉为共享 lib。这是"克隆半个 serving 栈 + 重构实验层",
而非"抽一个薄服务"。

## 目标设计(若执行)

**新模块 `recsys-ad-serving` [app :8084]**,依赖:`recsys-ad` + `recsys-ad-common` + `recsys-common` +
`recsys-query` + `recsys-rank` + `recsys-feature` + `recsys-embedding` + `recsys-content` + `recsys-user` +
新的 `recsys-experiment`(见下)。`AdShardingConfig` 随 `recsys-ad` 进入本进程(第二数据源从 rec-engine 移走)。
对外/对内接口:
- `GET  /api/search-ads`(整条搬迁自 rec-engine `SearchAdsController` + `SearchAdsOrchestrator`)
- `POST /api/ad/click`、`POST /api/ad/conversion`(计费归因随迁,`ad_event` 写读都在本进程内闭合)
- `POST /internal/ads/select`(供 rec-engine 混排 feed 取"已定价广告",入参 query/userId/slots/scene)

**共享实验层**:把 `com.recsys.recengine.experiment` 的 `ExperimentService`/`ExperimentDecision` 等下沉为新 lib
`recsys-experiment`(rec-engine 与 ad-serving 共用),或退而求其次:rec-engine 把算好的 `adBucket`+`reservePrice`
作为参数传进 `/internal/ads/select`(避免下沉,但 `/api/search-ads` 若也在 ad-serving 则仍需实验层)。

**rec-engine 改动**:
- 去掉 `recsys-ad` 依赖 → `AdShardingConfig` 不再装配 → **回归单数据源**(瘦身,坏味道 A 消除)。
- 删 `SearchAdsController` + `SearchAdsOrchestrator`(迁往 ad-serving)。
- `FeedOrchestrator`:改用 HTTP(RestClient)调 ad-serving `/internal/ads/select` 取广告;混排(`AdMixer`,纯函数)
  下沉到 `recsys-ad-common` 供 rec-engine 复用(rec-engine 有自然结果 + 拿到广告后本地混)。ad-serving 宕机/超时 → 走"无广告" feed 降级(复用现有 resilience4j 模式)。
- **experiment 层**若下沉为 `recsys-experiment`,rec-engine 改依赖它。

**网关**:`/api/ad/**`、`/api/search-ads/**` 从 rec-engine 路由移到 ad-serving:8084。

## 迁移步骤(每步可独立编译验收)

1. 下沉 experiment 层为 `recsys-experiment` lib(rec-engine 改依赖,行为不变)。
2. `AdMixer` 下沉到 `recsys-ad-common`。
3. 建 `recsys-ad-serving` 模块 + `AdServingApplication`,搬 `SearchAdsController`/`SearchAdsOrchestrator`,加 `/internal/ads/select`。
4. rec-engine 去 `recsys-ad` 依赖,`FeedOrchestrator` 改 HTTP 调 + 降级。
5. 网关路由改指向 ad-serving。
6. 全 reactor 编译 + **全栈运行时联调**(见风险)。

## 风险与为何建议缓做

- **触及钱链路且不可本地验证**:竞价/GSP/oCPC/校准/计费迁进程 + 加 HTTP 边界,`compile 通过 ≠ 计费正确`。
  本环境端口 8080–8083 被其它项目占用,无法起全栈联调,**Phase 2 只能编译验收**——对计费代码这是不可接受的验收标准。
- **净增运维复杂度**:多一个重服务(带自己的分片数据源),多一次热路径网络跳 + 部分失败组合。
- **架构评审本身建议**:"等广告有真实流量/独立团队再做"。当前是教学/脚手架阶段,广告无真实流量,拆分的收益(独立扩缩/故障隔离)尚未兑现。

## 建议

**缓做 Phase 2**,保留本 ADR 为可执行设计;等具备"全栈可运行时验证的环境 + 真实广告流量"再落地。Phase 0/1 已消除
"前端寄生 + 报表文件耦合 + 上帝契约模块"三处问题,并把 ad-serving 的拆分缝画硬(`recsys-ad`/`recsys-ad-common`
已是干净的广告 lib/契约边界),真正物理拆分时改动集中、风险可控。
