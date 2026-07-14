# docker/ — 全部容器编排的单一入口

本仓库所有 Docker / Compose 编排文件都归拢在这个目录。之前散落在仓库根(`docker-compose.yml`、`Dockerfile`、`monitoring/`)的东西已统一搬到这里。

```
docker/
  docker-compose.yml         # 单一编排入口。profiles:(默认)pg+redis / full / apps / console / obs
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

手动 docker compose(从仓库根 `-f` 指向,或 `cd docker` 后直接跑):

```bash
cd docker
docker compose up -d                                   # 只起 postgres + redis
docker compose --profile full up -d                    # + kafka + nacos
docker compose --profile apps up -d                    # + 8 个 Java 服务(全链路容器化)
docker compose --profile console up -d                 # + 前端 nginx
docker compose --profile obs up -d                     # + prometheus/grafana/tempo/alertmanager
docker compose --profile apps --profile console up -d --build   # 全栈 + 强制重建
```

## 端口覆盖

所有**对宿主暴露**的端口都可用环境变量覆盖(容器内部端口固定,不受影响):

| 变量 | 默认 | 作用 |
|---|---|---|
| `GATEWAY_PORT` | 8080 | 统一入口(前端 /api 反代到此) |
| `CONSOLE_WEB_PORT` | 8095 | 前端 nginx |
| `PG_PORT` / `REDIS_PORT` | 5432 / 6379 | 容器 pg/redis 暴露(供离线作业 / psql) |
| `NACOS_PORT` / `NACOS_GRPC_PORT` | 8848 / 9848 | Nacos |
| `PROMETHEUS_PORT` / `GRAFANA_PORT` / `ALERTMANAGER_PORT` | 9090 / 3001 / 9093 | 观测 |
| `PG_DB` / `PG_DS1_DB` / `PG_USER` / `PG_PASSWORD` | recsys / recsys_ds1 / recsys / recsys | 数据库 |
| `RECSYS_SECURITY_ENABLED` | false(dev) | 本地免登录;生产置 true 并注入密钥 |

`scripts/dev-local.sh` 会从 `scripts/dev-local.env`(gitignore)读取覆盖并 export 给 compose。直接手动跑 compose 时,可在 `docker/.env`(gitignore)里放同名变量,compose 会自动读取。见 [`.env.example`](.env.example)。

## 与 k8s 的关系

`deploy/k8s/` 是生产部署模板(kustomize),复用同一个 `docker/Dockerfile` 构建的镜像。CI 见 `.github/workflows/release.yml`(打 tag 时用 `docker/Dockerfile` 为每个 app 构镜像推 GHCR)。
