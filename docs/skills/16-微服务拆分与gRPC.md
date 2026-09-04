# 微服务拆分与 gRPC

> 系统从"单体优先"演进出可独立部署的**微服务边界**，采用绞杀者(strangler-fig)模式保留 in-process 回滚缝。应用默认配置仍是 in-process，但 `docker compose --profile apps` 已显式将 ad/content/user 三条缝切到 gRPC。

## 1. 演进策略:绞杀者 + 一键回滚

- **DDD 按界限上下文拆**(~8 服务),不是"16 模块拆 16 服务"。
- 内部 **gRPC**(net.devh)、可选 **Nacos** 发现、**DB-per-service** 物理分库、**事件驱动读模型复制**(Kafka)。
- **配置默认在进程内托管**,通过 `*Gateway` 缝(in-process 默认 / grpc 可选)委托。`apps` 容器 profile 是已切流的另一种部署基线：`AD/CONTENT/USER_SERVING_MODE=grpc` + Nacos `discovery:///...`；单缝改回 `in-process` 即回滚。

## 2. 服务与端口

| 服务 | 模块 | HTTP | gRPC | 角色 |
|---|---|---|---|---|
| Gateway | recsys-gateway | 8080 | — | 边缘鉴权、限流;`apps` profile 唯一公网口 |
| Rec-Engine | recsys-rec-engine | 8081 | — | 编排;gRPC **客户端**;默认托管单体 |
| Behavior | recsys-behavior | 8082 | — | 行为接入 |
| Advertiser | recsys-advertiser | 8083 | — | 广告写侧;ShardingSphere;gRPC 客户端(报表) |
| **Ad-Serving** | recsys-ad-serving | **8085** | **9095** | 广告钱路服务(新) |
| **Content-Service** | recsys-content-service | **8086** | **9096** | item 读模型(新) |
| **User-Service** | recsys-user-service | **8087** | **9097** | app_user 兴趣(新) |
| Console-API | recsys-console | 8090 | — | 报表 BFF |
| Streaming | recsys-streaming | — | — | Flink 实时特征 |

新共享库:`recsys-proto`(契约)、`recsys-platform`(安全)、`recsys-ad-common`(`AdCatalogEvent`/`BidwordInvCodec`/`AdMixer` 共享内核)。

## 3. `*Gateway` 缝(绞杀者回滚的核心)

每个上下文一个接口 + 两个 `@ConditionalOnProperty` 实现,默认 in-process:

| Gateway | in-process(默认) | grpc | 开关 |
|---|---|---|---|
| `AdServingGateway` | `InProcessAdServingGateway`→`AdPipeline` | `GrpcAdServingGateway` `@GrpcClient("ad-serving")` | `recsys.ad.serving.mode` / `AD_SERVING_MODE` |
| `ContentGateway` | `InProcessContentGateway` | `GrpcContentGateway` | `recsys.content.serving.mode` |
| `UserGateway` | `InProcessUserGateway` | `GrpcUserGateway` | `recsys.user.serving.mode` |

- **`AdPipeline`(recsys-ad)是共享内核**——in-process 和 ad-serving 远程跑的是同一份代码,行为 golden-diff 等价。
- `GrpcAdServingGateway` 每个 RPC 包 `@CircuitBreaker`:searchAds→**无广告 feed**,click/conv→**只记日志**(计费幂等)。
- `apps` compose profile 设 `AD_SERVING_MODE=grpc` + `discovery:///recsys-ad-serving` → 容器化 profile **真正通过 Nacos 服务发现走 gRPC**；`static://ad-serving:9095` 是静态地址回退。

## 4. gRPC 契约(`recsys-proto`)

3 个版本化 `.proto`(`package *.v1`,`java_multiple_files`):
- **`ad_serving.proto AdServingService`**:`SearchAds`(请求带**已解析的** `StructuredQuery`+`ad_bucket`+`reserve_price`——**query 理解和实验分桶留在 rec-engine 单一事实源**)、`RecordClick`/`RecordConversion`、A7 独立转化事实 `RecordOutcome`、`GetAdEventStats`。`SponsoredAd` 16 字段 1:1。
- **`content.proto ContentService.BatchGetItems`**:展示时批量 hydrate(O(1)/请求);逐候选读仍在进程内。
- **`user.proto UserProfileService.GetInterests/UpdateInterests`**:仅冷启动。

**防腐层映射**:`AdProtoMapper`/`ContentProtoMapper`(null→空串/空列表;空 embedding⇒null)。parity 测试全绿。

**拦截器**:`GrpcDeadlineClientInterceptor`(每 call `withDeadlineAfter` 800ms)、`InternalAuthGrpcClientInterceptor`(签 HMAC token 进 metadata)、`InternalAuthGrpcServerInterceptor`(验签→`CALLER_SUBJECT`)。

## 5. Ad-Serving 内部(最深的一刀)

- `AdServingApplication` 扫 `com.recsys`,classpath 是 rec-engine 的**严格子集**(ad→rank→feature/content),故只装配广告 pipeline bean。
- **catalog 复制**:`AdCatalogEvent`(每广告快照,topic `ad-catalog-events`,keyed adId,LWW,携带 item embedding 为 pgvector `::text`)→ advertiser `AdCatalogEventPublisher`(开关 off)→ `AdCatalogEventConsumer`(autoStartup off)→ `AdServableRepository`(`adDbJdbc`,`@PostConstruct` 自建 `ad_servable`+`ad_embedding`+hnsw)+ `BidwordInvMaintainer`(维护 `bidword:inv`)。
- `ReplicaAdCatalogReader`(`catalog.source=replica`)替换默认 `ShardedAdCatalogReader`。

## 6. 事件驱动读模型(打破跨库耦合)

| 读模型 | 来源 | 同步方式 |
|---|---|---|
| `ad_servable`/`ad_embedding`/`bidword:inv` | ad_event/ad 目录 | Kafka `ad-catalog-events`(ad-serving 消费) |
| `seen:{user}` (Redis) | 行为 | Kafka `behavior-events`(rec-engine `SeenItemsConsumer`) |
| `recall:rt_hot`/`rt:user` | 行为 | Kafka `behavior-events`(streaming) |
| `behavior_log` | user_behavior | 批 `sync-behavior-log`(watermark) |
| `item_local` | item | 批 `sync-item-catalog` |
| `ad_event_log` | ad_event | 批 `sync-ad-event-log` |

**批 `sync-*` 作业是 CDC 替身**(教学态),不是真 CDC。

## 7. 服务通信图

```
Browser ─edge JWT─► gateway :8080 (剥离 Authorization, 签 X-Internal-Auth HMAC)
   static http:// (或 discovery 下 lb://)
   ├─► rec-engine :8081 ── *Gateway 缝(in-process 默认 | grpc)+ GrpcClientHardening(deadline+token+熔断)
   │       ├─gRPC 9095─► ad-serving :8085 (AdPipeline)
   │       ├─gRPC 9096─► content-service :8086
   │       └─gRPC 9097─► user-service :8087
   ├─► behavior :8082   ├─► advertiser :8083 ─gRPC 9095─► ad-serving GetAdEventStats
   └─► console :8090       (ad-serving 不经网关路由)
Kafka behavior-events ─► streaming + rec-engine SeenItemsConsumer
Kafka ad-catalog-events ─► ad-serving AdCatalogEventConsumer
```
**ad-serving 刻意不经网关路由**——广告计费仍走 rec-engine→gRPC,把开关留在一处。

## 8. 现状与边界

1. **两种运行基线并存**:应用 yml 缺省为 in-process；`docker/docker-compose.yml` 的 `apps` profile 已将 ad/content/user 设为 gRPC，Nacos 发现默认开启。k8s 基线则保守地保留 `AD_SERVING_MODE=in-process`，content/user 走 gRPC。
2. **gRPC 鉴权已闭环**:三个服务均用 `GrpcServerHardeningConfig`/`GlobalServerInterceptorConfigurer` 注册 `InternalAuthGrpcServerInterceptor`，`server-auth-required` 默认 true；幂等读另有瞬时重试与熔断。
3. **物理 DB-per-service 仍是能力态**:`AD_PG_DB`/`DERIVED_PG_DB`/`CONTENT_PG_DB`/`USER_PG_DB`/`BEHAVIOR_PG_DB` 未设时都回落共享 `recsys`；当前 `apps` profile 没有强制设置这些库名。
4. **事件驱动 catalog 是 opt-in**:advertiser publisher、ad-serving consumer 与 `catalog.source=replica` 默认不开；未激活时 ad-serving 仍通过共享/分片数据源读广告目录。

## 9. gRPC vs OpenFeign(东西向选型对照)

> 常见追问:「服务间调用为什么用 gRPC,不用 OpenFeign/REST?」——先厘清**二者不在同一抽象层**:gRPC 是"协议 + 框架"(HTTP/2 + Protobuf,端到端、跨语言、自带 server/client 与代码生成);OpenFeign 只是**声明式 HTTP 客户端**(仅调用方语法糖,服务端仍是普通 Spring MVC Controller,底层还是 REST/JSON)。放一起比,只因二者都回答"服务 A 怎么调 B"。一句话:**gRPC 是协议+框架,Feign 只是客户端语法糖**。

| 维度 | gRPC(本项目东西向) | OpenFeign |
|---|---|---|
| 定位 | 端到端 RPC 框架 | 声明式 HTTP 客户端(仅 client) |
| 传输 | HTTP/2(二进制帧、多路复用、长连接) | 默认 HTTP/1.1(可配 H2) |
| 序列化 | Protobuf(二进制、紧凑快) | 通常 JSON(文本、可读) |
| 契约 | `.proto` + 代码生成,**强 schema**(见 §4) | Java 接口 + 注解,无独立 IDL |
| 跨语言 | 一等公民(Go/Java/Python…) | 仅 JVM |
| 流式 | 4 种(一元 / 服务端流 / 客户端流 / 双向) | 无原生流式,纯请求-响应 |
| 性能 | 高,适合内部高频低延迟 | 一般,够多数业务 |
| 可调试 | 二进制,需 grpcurl 等工具 | 人可读,可直接 curl / 抓包 |
| 浏览器 | 需 gRPC-Web + 代理 | 天然可用(就是 REST) |
| Spring Cloud 生态 | 发现 / LB / 熔断需自接 | 无缝(LoadBalancer/Sentinel 开箱) |

- **本项目为什么东西向选 gRPC**:rec-engine→ad-serving 是**内部、高频(每次 feed 混排都拉广告)、延迟敏感**路径,二进制 + HTTP/2 长连接比 HTTP/JSON 省序列化与连接开销;`.proto` 强契约天然契合"query 理解 / 实验分桶留 rec-engine 单一事实源"的边界(见 §4)。
- **什么时候 Feign/REST 更合适**:管理面 / 低频 / 要人可读的调用——如 `recsys-advertiser` 写侧、对外接口。本项目对外仍走 gateway 的 REST,只把核心钱路的内部调用切 gRPC——**混用是常态**。
- **代价**:gRPC 的服务发现 / 负载均衡 / 鉴权需要显式接入。本项目已接 `static://`/`discovery:///` 目标、Nacos 客户端 LB、deadline、HMAC 客户端/服务端拦截器和熔断；运维复杂度仍高于 Feign + Spring Cloud LoadBalancer。这正是"**gRPC 用生态成本换性能**"的具体体现。

## 10. 面试要点

- **gRPC vs Feign**:gRPC 是协议+框架、Feign 只是 HTTP 客户端;东西向高频低延迟选 gRPC(性能),管理面/对外选 REST/Feign(生态与可读),混用是常态(见 §9)。
- **绞杀者模式**:新旧并存,`*Gateway` 缝一键切换,渐进拆分、可回滚,而非大爆炸重写。
- **为什么 query 理解/实验留 rec-engine**:单一事实源,ad-serving 只收已解析结果,避免多处解析不一致。
- **DB-per-service 怎么解耦读**:事件驱动读模型复制(Kafka)+ 批 sync 替身,打破跨库 JOIN。
- **gRPC 韧性**:deadline + 熔断 + 降级(无广告 feed / 只记日志)。
- **gRPC 服务端鉴权已闭环**(2026-07 硬化):三个 gRPC 服务经 `GrpcServerHardeningConfig`(`GlobalServerInterceptorConfigurer`)注册 `InternalAuthGrpcServerInterceptor`,`server-auth-required` 默认 true——面试可讲"net.devh 服务端拦截器必须全局注册,只写类不注册=客户端签、服务端不验的假安全"这个坑。
