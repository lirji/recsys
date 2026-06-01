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
├── recsys-offline     # 离线作业(导入/灌向量/CF/样本)         [app] Track A/E
│   └── sql/           # 数据库 schema(容器首启自动执行)
└── recsys-web         # 演示前端(:8090)                       [app] Track F
```

> `[lib]` 为被依赖的计算/领域库(不打可执行 jar);`[app]` 为可执行服务。
> 单体起步:`rec-engine` 聚合各 `[lib]` 在一个进程内跑通;后续可平滑拆为独立微服务。

## 快速开始

```bash
# 1. 准备环境变量
cp .env.example .env        # 按需填写 GEMINI_API_KEY 等

# 2. 启动中间件(核心:postgres + redis;schema 自动建好)
docker compose up -d
#   需要 kafka/nacos 时:docker compose --profile full up -d

# 3. 设置 JDK 21 并构建
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
mvn clean install

# 4. (开发阶段)按需启动各服务,例如编排服务:
mvn -pl recsys-rec-engine spring-boot:run
```

## 端口一览

| 服务 | 端口 |
|---|---|
| gateway | 8080 |
| rec-engine | 8081 |
| behavior | 8082 |
| web | 8090 |
| postgres | 5432 |
| redis | 6379 |
| kafka(可选) | 9092 |
| nacos(可选) | 8848 |

## 并行开发

各 Track 的范围、依赖、验收标准见 `../PLAN.md`。原则:
1. 只改本 Track 负责的模块目录;
2. 依赖 `recsys-common` 已定义的契约,不擅自改动(需改先广播);
3. 跨 Track 的下游依赖先 mock,Phase 2 集成时替换。

## 注意

- `.env` 含密钥,已被 `.gitignore` 忽略,**切勿提交**。
- 向量维度统一为 768(`recsys.embedding.dimension`),与 `item_embedding vector(768)` 一致;换模型需全量重灌向量。
