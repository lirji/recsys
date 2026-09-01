# docker/ — recsys 应用编排入口

本目录负责 recsys 应用镜像和应用 Compose。PostgreSQL、Redis、Kafka、Nacos、SpiceDB 与观测组件已迁移到相邻的 `../dev-infra`，由 `scripts/dev-local.sh` 统一联动。

```
docker/
  docker-compose.yml         # 应用入口。profiles:apps / console；legacy-* 仅用于基础设施回滚
  docker-compose.local.yml   # 本机观测栈覆盖(端口/抓取目标),gitignore
  Dockerfile                 # 参数化多模块镜像(build-arg MODULE/PORT),8 个 Spring Boot app 共用
  monitoring/                # prometheus / grafana / tempo / alertmanager 配置
  README.md
```

> postgres 的建库脚本(含分库 ds_1 引导 `04b_ds1_bootstrap.sql`)不在此目录,而在 `recsys-offline/sql/`
> —— 它整目录挂进 `/docker-entrypoint-initdb.d`。Docker Desktop(virtiofs)不支持把单个文件嵌套挂进已挂载的
> 目录,故 initdb 脚本必须与 schema 同处一个可整目录挂载的地方(`recsys-offline/sql/`)。

## 相对路径约定(重要)

Compose 里的相对路径按**本文件所在目录(`docker/`)** 解析,不是按你运行命令的 CWD:

- 构建上下文 `context: ..` = 仓库根(Dockerfile 需要全部 `pom.xml` + 源码);`dockerfile: docker/Dockerfile`。
- postgres 初始化:`../recsys-offline/sql`(整目录挂进 initdb;含 schema + 分库引导 `04b_ds1_bootstrap.sql`)。
- 观测配置:`./monitoring/...`。
- 前端:`context: ../console`。

`.dockerignore` 仍留在**仓库根**(它必须位于构建上下文根,否则 `.venv`/`target` 等会被打进构建上下文,拖慢构建)。

## 怎么起

一键(推荐,含本机端口冲突处理 + 健康检查):

```bash
scripts/dev-local.sh up          # 基础设施 + 8 app + 前端,全部容器化
```

推荐通过脚本启动，它会先启动 `dev-infra` 的 recsys 专用兼容栈：

```bash
scripts/dev-local.sh infra       # dev-infra:pgvector/redis/kafka/nacos
scripts/dev-local.sh up          # infra + 8 个 Java 服务 + 前端
scripts/dev-local.sh obs         # dev-infra:观测栈
scripts/dev-local.sh authz       # dev-infra:SpiceDB
scripts/dev-local.sh infra-stop  # 只停 recsys 基础设施，保留数据卷
```

## 端口覆盖

所有**对宿主暴露**的端口都可用环境变量覆盖(容器内部端口固定,不受影响):

| 变量 | 默认 | 作用 |
|---|---|---|
| `GATEWAY_PORT` | 8080 | 统一入口(前端 /api 反代到此) |
| `RECSYS_UI_PORT` | 见 `auth-platform/deploy/platform-ports.env` | 前端 nginx 与 Vite 共用的统一门户入口 |
| `PG_PORT` / `REDIS_PORT` | 55432 / 56379 | dev-infra recsys 专用 pg/redis 宿主端口 |
| `NACOS_PORT` / `NACOS_GRPC_PORT` | 58848 / 59848 | dev-infra recsys 专用 Nacos |
| `PROMETHEUS_PORT` / `GRAFANA_PORT` / `ALERTMANAGER_PORT` | 59090 / 53001 / 59093 | dev-infra recsys 观测栈 |
| `PG_DB` / `PG_DS1_DB` / `PG_USER` / `PG_PASSWORD` | recsys / recsys_ds1 / recsys / recsys | 数据库 |
| `RECSYS_SECURITY_ENABLED` | false(dev) | 本地免登录;生产置 true 并注入密钥 |

`scripts/dev-local.sh` 会从 `scripts/dev-local.env`(gitignore)读取业务/基础设施覆盖，再加载同级 `auth-platform/deploy/platform-ports.env`，因此 UI 端口不能被本地 env 覆盖。直接手动跑 Compose 时应使用 auth-platform 的 `deploy/platform-compose.sh recsys ...`。

## 与 k8s 的关系

`deploy/k8s/` 是生产部署模板(kustomize),复用同一个 `docker/Dockerfile` 构建的镜像。CI 见 `.github/workflows/release.yml`(打 tag 时用 `docker/Dockerfile` 为每个 app 构镜像推 GHCR)。
