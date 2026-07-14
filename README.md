# recsys —— 推荐系统(生产导向脚手架)

基于 Java 21 + Spring Boot 3.2 的推荐系统,Maven 多模块单仓,面向生产标准但可本地一键跑通。

> 设计文档见上级目录 `../docs/`(技术栈 / 架构设计 / 关键技术点),执行计划见 `../PLAN.md`。

## 环境要求

| 依赖 | 版本 | 说明 |
|---|---|---|
| JDK | 21 | `/usr/libexec/java_home -v 21` |
| Maven | 3.9+ | |
| Docker | + Compose | 提供 postgres/redis 等中间件 |
| Gemini API Key | 可选 | 向量化用;未申请前留空不影响编译与骨架 |

## 模块结构

```
recsys/
├── recsys-common      # 共享契约:接口/DTO/常量(被所有模块依赖,改动需广播)
├── recsys-gateway     # 网关(:8080)                          [app]
├── recsys-rec-engine  # 推荐编排,对外主入口(:8081)            [app]
├── recsys-recall      # 多路召回                                [lib] Track B
├── recsys-rank        # 排序(规则/ONNX)                        [lib] Track C
├── recsys-feature     # 特征读写                                [lib] Track C
├── recsys-embedding   # 向量化(Gemini,可降级)                 [lib] Track A
├── recsys-content     # 物品元数据                              [lib] Track A
├── recsys-user        # 用户画像                                [lib]
├── recsys-behavior    # 行为采集(:8082)                       [app] Track E
├── recsys-offline     # 离线作业(导入/灌向量/CF/样本/双塔)     [app] Track A/E
│   └── sql/           # 数据库 schema(容器首启自动执行)
├── recsys-console     # 控制台后端 console-api(:8090)          [app] Track F
├── recsys-advertiser      # 广告主管理服务(:8083)                        [app]
├── recsys-ad-serving      # 广告投放内部服务(HTTP :8085 / gRPC :9095)    [app]
├── recsys-content-service # 内容内部服务(HTTP :8086 / gRPC :9096)        [app]
├── recsys-user-service    # 用户画像内部服务(HTTP :8087 / gRPC :9097)    [app]
├── recsys-streaming   # 实时特征 Flink 作业(本地 MiniCluster)  [app]
└── console/           # 控制台前端(独立 Vite 工程,nginx 同源托管)  [前端]
```

> 前后端分离:前端为仓库根 `console/`(React SPA,`npm run dev` / nginx),后端 `recsys-console` 只提供控制台 BFF 接口(离线报表读取等)。见 `console/README.md`。

> `[lib]` 为被依赖的计算/领域库(不打可执行 jar);`[app]` 为可执行服务。
> 单体起步:`rec-engine` 聚合各 `[lib]` 在一个进程内跑通;后续可平滑拆为独立微服务。

## 快速开始

```bash
# 1. 准备环境变量
cp .env.example .env        # 按需填写 GEMINI_API_KEY 等

# 2. 启动中间件(核心:postgres + redis;schema 自动建好)。容器编排统一在 docker/ 目录。
#   一键全栈容器化(基础设施 + 8 app + 前端,推荐):scripts/dev-local.sh up
#   或手动用 docker compose(从仓库根 -f 指向 docker/,或 cd docker 后直接跑):
docker compose -f docker/docker-compose.yml up -d
#   需要 kafka/nacos 时:docker compose -f docker/docker-compose.yml --profile full up -d
#   容器化全部后端服务(网关/编排/behavior/advertiser/console + 内部服务 ad-serving/content/user):
#     docker compose -f docker/docker-compose.yml --profile apps up -d   # 参数化 docker/Dockerfile 构建 fat jar,经 Nacos 互联
#   观测栈(Prometheus/Grafana/Alertmanager/Tempo):docker compose -f docker/docker-compose.yml --profile obs up -d

# 3. 设置 JDK 21 并构建
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
mvn clean install

# 4. (开发阶段)按需启动各服务,例如编排服务:
mvn -pl recsys-rec-engine spring-boot:run
```

## 端口一览

| 服务 | HTTP 端口 | gRPC 端口 | 说明 |
|---|---|---|---|
| gateway | 8080 | - | 统一 API 网关(南北向入口) |
| rec-engine | 8081 | - | 推荐编排,对外主入口 |
| behavior | 8082 | - | 行为采集 |
| advertiser | 8083 | - | 广告主管理(写侧) |
| ad-serving | 8085 | 9095 | 广告投放内部服务 |
| content-service | 8086 | 9096 | 内容内部服务(gRPC,HTTP 仅 actuator) |
| user-service | 8087 | 9097 | 用户画像内部服务(gRPC,HTTP 仅 actuator) |
| console-api | 8090 | - | 控制台 BFF(离线报表 + 系统总览) |
| postgres | 5432 | - | pgvector |
| redis | 6379 | - | |
| kafka(可选) | 9092 | - | `--profile full` |
| nacos(可选) | 8848 | - | `--profile full` |
| prometheus(obs) | 9090 | - | `--profile obs` |
| grafana(obs) | 3001 | - | `--profile obs` |

> **内部服务化模块**(`ad-serving`/`content-service`/`user-service`):以 gRPC 对内提供能力,rec-engine 默认走 `in-process`(单体)不依赖它们;设 `AD_SERVING_MODE=grpc` / `CONTENT_SERVING_MODE=grpc` / `USER_SERVING_MODE=grpc` 才切到 gRPC 调用。三者同时暴露 HTTP `/actuator/{health,prometheus}` 供健康探测与 Prometheus 抓取(需 `scanBasePackages` 含 `com.recsys.platform` 启用平台安全链,并在 `recsys.security.permit-paths` 放行 `/actuator/prometheus`)。
>
> ```bash
> # 按需单独起某个内部服务(示例:内容服务)
> mvn -pl recsys-content-service spring-boot:run   # HTTP :8086 + gRPC :9096
> mvn -pl recsys-user-service    spring-boot:run   # HTTP :8087 + gRPC :9097
> mvn -pl recsys-ad-serving      spring-boot:run   # HTTP :8085 + gRPC :9095
> ```

## 在线观测性(Prometheus + Grafana)

推荐链路的在线指标经 Micrometer 暴露在各服务的 `/actuator/prometheus`,由 Prometheus 抓取、Grafana 看板呈现。

```bash
# 1. 起观测栈(默认不启动,profile=obs)。Java 服务跑在宿主机,容器内经 host.docker.internal 抓取
docker compose -f docker/docker-compose.yml --profile obs up -d
# 2. 正常起 rec-engine(:8081)+ behavior(:8082)
mvn -pl recsys-rec-engine spring-boot:run     # 另开终端
mvn -pl recsys-behavior   spring-boot:run
# 3. 打开 Grafana → 看板 "Recsys 在线观测"
open http://localhost:3001     # admin/admin,数据源+看板已预置
```

**核心指标**(`recsys.*`,Prometheus 中下划线命名):

| 指标 | 含义 |
|---|---|
| `recsys_recommend_duration_seconds`(Timer,带直方图) | 编排端到端延迟,tag `rank`/`cold`/`outcome`,可算 P50/P95/**P99** |
| `recsys_recommend_cache_total{result}` | 结果缓存命中/未命中 → 命中率 |
| `recsys_recommend_empty_total` / `recsys_recommend_seen_cleared_total` | 空召回 / 已看过滤把召回池清空的异常计数 |
| `recsys_exposure_total{recall,rank,rerank,cold}` | 分桶曝光物品数(CTR 分母) |
| `recsys_click_total{recall,rank,rerank,...}` | 分桶点击数(CTR 分子) |
| `recsys_rank_total{requested,served,reason}` | 排序策略命中/回退;**模型回退率** = `served=rule` 占 `requested=onnx\|deepfm` 的比例,`reason` 区分 `not_ready`(模型没加载)/`empty`(返回空) |

**在线分桶 CTR** = `recsys_click_total / recsys_exposure_total`(按 `rank`/`recall` 聚合),与离线 `ab-report` 作业互补——一个实时、一个 T+1 精算。点击的分桶归因:曝光时编排层把 `expo:{user}:{item}=bucket` 写入 Redis(短 TTL),行为服务收到点击时回查回填,因此**客户端不传 bucket 也能正确归因**(服务端为准)。

## 实时特征(Flink,本地 MiniCluster)

`recsys-streaming` 消费 Kafka `behavior-events` 行为流,近实时算两类特征写 Redis,与离线 T+1 作业互补:
实时热度 ZSet `recall:rt_hot`(在线 `HotRecaller` 优先读它,缺失回落离线 `recall:hot`)、用户实时类目偏好 `rt:user:{id}`。

```bash
# 1. 起 Kafka(profile=full)。注:用官方 apache/kafka 镜像(Bitnami 旧 tag 已下架)
docker compose -f docker/docker-compose.yml --profile full up -d kafka
# 2. behavior 以 Kafka 模式起(投递行为到 behavior-events,不可用时自动降级入库)
BEHAVIOR_USE_KAFKA=true mvn -pl recsys-behavior spring-boot:run
# 3. 跑 Flink 实时作业(脚本含 Java 21 所需 --add-opens;首次自动打 fat jar)
bash recsys-streaming/run-streaming.sh --window-min 10 --slide-sec 20
# 4. 打点行为 → 观察实时热度
curl -XPOST localhost:8082/api/behavior -H 'Content-Type: application/json' \
  -d '{"userId":1,"itemId":2959,"action":"CLICK","scene":"feed"}'
docker exec recsys-redis redis-cli zrevrange recall:rt_hot 0 -1 withscores
```

## 并行开发

各 Track 的范围、依赖、验收标准见 `../PLAN.md`。原则:
1. 只改本 Track 负责的模块目录;
2. 依赖 `recsys-common` 已定义的契约,不擅自改动(需改先广播);
3. 跨 Track 的下游依赖先 mock,Phase 2 集成时替换。

## 注意

- `.env` 含密钥,已被 `.gitignore` 忽略,**切勿提交**。
- 向量维度统一为 768(`recsys.embedding.dimension`),与 `item_embedding vector(768)` 一致;换模型需全量重灌向量。
