# ADR-01：拆分广告在线服务（ad-serving）

状态：**已接受并实施（渐进式）**

最后核验：**2026-09-01**

## 背景

自然推荐主链路适合继续保持模块化单体，但广告「钱链路」具有独立的数据、可靠性、审计与发布节奏。原实现将 query 理解、实验分桶、广告召回、竞价、计费和事件写入全部放在 rec-engine 进程，难以独立扩缩容和做故障隔离。

同时，直接把 `SearchAdsOrchestrator` 整体搬走会复制 query/rank/feature/experiment 等半个 serving 栈，并打破 query 理解与实验分配的单一事实源。因此采用绞杀者模式，拆分 supplier 而不迁移对外编排入口。

## 决策

### 1. 对外入口与编排归属

- `GET /api/search-ads`、`GET /api/feed`、`POST /api/ad/click`、`POST /api/ad/conversion`、`POST /api/ad/outcome` 继续由 `recsys-rec-engine` 对外提供，网关路由仍指向 rec-engine。
- rec-engine 保留 `QueryUnderstandingService` 与 `ExperimentService`，生成已解析的 `StructuredQuery`、`adBucket` 与 `reservePrice`。
- `SearchAdsOrchestrator` 之后只通过 `AdServingGateway` 调用广告 supplier，不再感知广告召回/竞价/计费细节。

### 2. 共享内核与可回滚边界

- `recsys-ad` 中的 `AdPipeline` 是唯一广告在线内核，包含召回、相关性、定向、出价、拍卖、DCO/GD、曝光与计费。
- `recsys.ad.serving.mode=in-process` 时，`InProcessAdServingGateway` 在 rec-engine 内直调同一 `AdPipeline`；这是源码默认与应急回滚点。
- `recsys.ad.serving.mode=grpc` 时，`GrpcAdServingGateway` 调用独立 `recsys-ad-serving`；容器全栈默认选择该模式。
- 两种模式使用同一份领域内核，变更时不维护两套竞价/计费实现。

### 3. gRPC 契约

`recsys-proto/src/main/proto/ad_serving.proto` 发布版本化契约 `recsys.ad.v1.AdServingService`：

| RPC | 用途 | 重试/降级策略 |
|---|---|---|
| `SearchAds` | 执行广告管线并返回已定价广告 | 幂等读，瞬时错误可重试；失败返回空广告 |
| `RecordClick` | 反作弊、归因与点击计费 | 写路不自动重试；失败记告警 |
| `RecordConversion` | 转化回传与 CPA 相关处理 | 写路不自动重试；失败记告警 |
| `RecordOutcome` | A7 独立广告主 outcome，`eventId` 幂等 | 失败返回 `false` |
| `GetAdEventStats` | advertiser 按 adId 读 ad-serving 事件聚合 | 用于物理拆库后的报表边界 |

领域 record 与 proto 经 `AdProtoMapper` 转换，计费字段完整性由 `AdBillingProtoParityTest` 作为 money-chain gate。客户端统一加 deadline、瞬时读重试和熔断；服务端默认要求经 HMAC 签名的服务身份令牌。

### 4. 端口与服务发现

- `recsys-ad-serving` HTTP `:8085` 只承载 Actuator 健康/指标端点，不承载公开广告 REST API。
- 业务 gRPC 端口为 `:9095`。
- 容器全栈经 Nacos `discovery:///recsys-ad-serving` 发现；纯本地可使用 static target。

### 5. 数据所有权的渐进迁移

- 兼容默认 `recsys.ad.catalog.source=sharded`：ad-serving 仍可直读广告主分片目录，便于回滚。
- 能力态 `catalog.source=replica` + `catalog.consume=true`：advertiser 发布 `ad-catalog-events`，ad-serving 幂等维护自有 `ad_servable`/`ad_embedding` 副本，只保留可服务广告。
- `ad_event` 可由 ad-serving 自有数据源承载；advertiser 设 `recsys.ad.report.source=grpc` 后经 `GetAdEventStats` 读聚合，不再跨库直查。

这意味着「进程拆分」已是容器默认，而「目录/DB-per-service 完全切换」仍是可回滚的配置能力，不应误写为所有环境都已强制启用。

## 后果与取舍

### 收益

- 广告在线管线可独立发布、扩缩容和做健康探测。
- query 理解与实验分桶仍是 rec-engine 单一事实源，避免新建 `recsys-experiment` 和重复 serving 栈。
- in-process/gRPC 共用 `AdPipeline`，一个开关即可回滚，迁移期风险可控。
- 目录事件副本与 gRPC 报表契约为最终 DB-per-service 提供明确路径。

### 代价与剩余风险

- gRPC 模式新增一次热路网络跳转、服务发现和部分失败组合。
- `RecordClick`/`RecordConversion` 失败当前只记告警，不自动重试；真实计费环境仍需 outbox/重放/对账机制，不能把「HTTP 返回成功」等同于计费必然落库。
- 目录 replica 模式引入最终一致性，需监控 consumer lag、幂等失败与副本数量。
- 当前安全令牌证明调用服务，不传播终端用户主体；用户级 ReBAC 不得从 gRPC `CALLER_SUBJECT` 推断。

## 验证与回滚

- 契约：`AdProtoMapperTest`、`AdBillingProtoParityTest`、`ContentProtoMapperTest`。
- 安全/弹性：`InternalAuthGrpcServerInterceptorTest`、`GrpcRetryablePredicateTest`、`GrpcAdServingGatewayFallbackTest`。
- 目录副本：`AdCatalogEventRoundTripTest`。
- 业务内核：`recsys-ad` 的竞价、拍卖、DCO、DFM、Uplift 定向测试。
- 运行时回滚：将 `AD_SERVING_MODE` 切回 `in-process`；如 replica/独立报表源异常，分别切回 `AD_CATALOG_SOURCE=sharded` 与数据库报表源。
