#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# recsys 本地整栈一键启动(全链路容器化)。
#
# 所有容器编排都归拢在 docker/ 目录下(docker/docker-compose.yml)。本脚本是它的一键封装:
# 用 docker compose 把「基础设施 + 8 个 Java 服务 + 前端」全部以容器方式起起来,并做健康检查。
#
# 用法:
#   scripts/dev-local.sh up                 # 起全栈(pg/redis/nacos + 8 app + 前端 nginx),缺镜像自动构建
#   scripts/dev-local.sh up rec-engine gateway   # 只起指定服务(compose 服务名)
#   scripts/dev-local.sh rebuild advertiser # 改了代码后:重建该服务镜像并重启(容器化下改代码需重建)
#   scripts/dev-local.sh build              # 只构建全部镜像(不启动)
#   scripts/dev-local.sh down               # 停并删容器(保留数据卷 pgdata)
#   scripts/dev-local.sh restart [svc...]   # 重启
#   scripts/dev-local.sh status             # compose ps + 网关健康
#   scripts/dev-local.sh logs <svc>         # tail 某服务日志(compose 服务名)
#   scripts/dev-local.sh infra              # 只起基础设施(postgres/redis/nacos)
#   scripts/dev-local.sh frontend           # 前端热开发:宿主机跑 Vite(指向容器网关端口),便于改前端
#   scripts/dev-local.sh obs                # 追加起观测栈(prometheus/grafana/tempo/alertmanager)
#   scripts/dev-local.sh authz              # 追加起判权栈(recsys 专属 SpiceDB,docs/09;判权服务在宿主机另起)
#
# 端口:默认走「标准端口」(网关 8080 / pg 5432 / redis 6379 / nacos 8848);仅这些对宿主暴露的端口
#   本机冲突时,把覆盖值写进 scripts/dev-local.env(见 dev-local.env.example,已 gitignore),脚本会 export
#   给 compose 的 ${VAR:-默认} 占位取用。容器**内部**互通固定用服务名+标准端口(不受宿主端口重映射影响)。
#
# 容器化 vs 宿主机 mvn:本脚本现在默认全链路容器化(改代码 → `rebuild <svc>` 重建镜像)。
#   若想后端在宿主机 mvn 热跑、只容器化基础设施,见 docs/08-本地运行.md 的「宿主机 mvn 模式」。
#
# 已在 docker/docker-compose.yml 里固化、无需再手敲的坑:
#   - 网关/各服务 RECSYS_SECURITY_ENABLED=false(否则 /api/advertiser 等经网关 401);
#   - rec-engine/advertiser/ad-serving 的 ShardingSphere 次/主数据源 PG_JDBC_URL/PG_DS1_JDBC_URL(指向 postgres 容器);
#   - postgres 首启自动建分库 ds_1(recsys_ds1)+ 分片骨架(recsys-offline/sql/04b_ds1_bootstrap.sql);
#   - depends_on + healthcheck 保证 pg/redis 就绪后再起 app(取代旧的 staggered 手工等待)。
# ---------------------------------------------------------------------------
set -o pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$SCRIPT_DIR/.." && pwd)"
COMPOSE_FILE="$REPO/docker/docker-compose.yml"
cd "$REPO"

# ---------- 对宿主暴露的端口 / 连接默认(标准端口;本机覆盖见 dev-local.env)----------
# 注:这些只影响「宿主机能从哪个端口访问容器」。容器之间用服务名+标准内部端口通信,不受影响。
: "${GATEWAY_PORT:=8080}"          # 统一入口(前端 /api 反代到此)
: "${PG_PORT:=5432}"               # 宿主访问容器 postgres(供离线作业 / psql)
: "${REDIS_PORT:=6379}"
: "${NACOS_PORT:=8848}"; : "${NACOS_GRPC_PORT:=9848}"
: "${CONSOLE_WEB_PORT:=9095}"      # 前端 nginx 容器（避开 Drools 网关 :8095）
: "${PROMETHEUS_PORT:=9090}"; : "${GRAFANA_PORT:=3001}"; : "${ALERTMANAGER_PORT:=9093}"; : "${KAFKA_PORT:=9092}"
: "${PG_DB:=recsys}"; : "${PG_DS1_DB:=recsys_ds1}"; : "${PG_USER:=recsys}"; : "${PG_PASSWORD:=recsys}"
: "${RECSYS_SECURITY_ENABLED:=false}"   # 本地免登录直连;生产置 true 并注入密钥
: "${VITE_PORT:=5173}"                    # frontend 子命令用(宿主机热开发)
: "${LOG_DIR:=$REPO/.dev/logs}"

# 本机覆盖(端口冲突等)。gitignore,见 dev-local.env.example。
# set -a:该文件里设的**所有**变量自动 export 给 compose 插值(否则新增变量——如 RECSYS_AUTHZ_MODE、
# CASDOOR_*——写了也传不到 docker compose 子进程,覆盖静默失效)。
if [ -f "$SCRIPT_DIR/dev-local.env" ]; then set -a; . "$SCRIPT_DIR/dev-local.env"; set +a; fi

# 把 compose 需要的变量 export 出去,供 docker-compose.yml 里的 ${VAR:-默认} 插值。
export GATEWAY_PORT PG_PORT REDIS_PORT NACOS_PORT NACOS_GRPC_PORT CONSOLE_WEB_PORT
export PROMETHEUS_PORT GRAFANA_PORT ALERTMANAGER_PORT KAFKA_PORT
export PG_DB PG_DS1_DB PG_USER PG_PASSWORD RECSYS_SECURITY_ENABLED

mkdir -p "$LOG_DIR"

# 默认全链路容器化:apps(8 个 Java 服务)+ console(前端 nginx)。
PROFILES=(--profile apps --profile console)

# compose 封装:统一带上 -f docker/docker-compose.yml(相对路径由 compose 按文件所在目录 docker/ 解析)
compose() { docker compose -f "$COMPOSE_FILE" "$@"; }

# ---------- 工具 ----------
health() { curl -s -m 3 "http://localhost:$1/actuator/health" 2>/dev/null | sed -n 's/.*"status":"\([A-Z]*\)".*/\1/p' | head -1; }

require_docker() {
  docker info >/dev/null 2>&1 && return 0
  echo "✗ Docker 未运行。请先启动 Docker Desktop(macOS: open -a Docker),再重试。"; return 1
}

wait_gateway() {
  echo -n "  等待网关就绪 (http://localhost:$GATEWAY_PORT/actuator/health) "
  local st=""
  for _ in $(seq 1 120); do
    st="$(health "$GATEWAY_PORT")"
    [ "$st" = UP ] && { echo " ✓ UP"; return 0; }
    echo -n "."; sleep 2
  done
  echo " ⚠ 未在 240s 内 UP(当前=${st:-无响应});镜像首次构建或 Nacos 启动较慢,用 status/logs 继续观察。"
}

# ---------- 命令 ----------
cmd_up() {
  require_docker || return 1
  echo "== 一键起全栈(容器化:基础设施 + 8 app + 前端;缺镜像自动构建)=="
  echo "   compose: $COMPOSE_FILE  profiles: apps + console"
  echo "   宿主暴露端口:网关 $GATEWAY_PORT / pg $PG_PORT / redis $REDIS_PORT / nacos $NACOS_PORT / 前端 $CONSOLE_WEB_PORT"
  compose "${PROFILES[@]}" up -d --remove-orphans "$@" || { echo "✗ compose up 失败(见上)"; return 1; }
  echo; wait_gateway
  echo; cmd_status
  echo
  echo "前端(容器,同源经网关):http://localhost:$CONSOLE_WEB_PORT"
  echo "前端(热开发,宿主 Vite):scripts/dev-local.sh frontend  → http://localhost:$VITE_PORT"
  echo "改了后端代码 → scripts/dev-local.sh rebuild <svc>(如 rec-engine/advertiser),重建镜像并重启。"
}

cmd_build()   { require_docker || return 1; echo "== 构建镜像 =="; compose "${PROFILES[@]}" build "$@"; }
cmd_rebuild() {
  require_docker || return 1
  [ $# -eq 0 ] && { echo "用法: dev-local.sh rebuild <svc...>(compose 服务名,如 rec-engine advertiser)"; return 1; }
  echo "== 重建并重启: $* =="
  compose "${PROFILES[@]}" up -d --build "$@" && { echo; wait_gateway; }
}

cmd_down() {
  require_docker || return 1
  echo "== 停并删容器(保留数据卷 pgdata / grafana-data)=="
  # down 需带上全部 profile 才能覆盖各分层的容器;不加 -v,数据保留
  compose --profile apps --profile console --profile full --profile obs --profile authz down --remove-orphans
}

cmd_status() {
  require_docker || return 1
  echo "== 容器状态 =="
  compose ps --format 'table {{.Service}}\t{{.Status}}\t{{.Ports}}' 2>/dev/null || compose ps
  echo "== 网关健康 =="
  local st; st="$(health "$GATEWAY_PORT")"
  echo "  gateway :$GATEWAY_PORT → ${st:-无响应}"
}

cmd_logs() {
  local name=$1; [ -z "$name" ] && { echo "用法: dev-local.sh logs <svc>(compose 服务名,如 rec-engine/advertiser/console-api)"; return 1; }
  compose logs -f --tail=120 "$name"
}

cmd_infra() {
  require_docker || return 1
  echo "== 只起基础设施(postgres + redis + nacos)=="
  compose up -d postgres redis
  compose --profile full up -d nacos
  cmd_status
}

cmd_obs() {
  require_docker || return 1
  echo "== 追加观测栈(prometheus/grafana/tempo/alertmanager)=="
  compose --profile obs up -d
  echo -n "  等待 Tempo 就绪 "
  for _ in $(seq 1 30); do
    curl -fsS http://localhost:3200/ready >/dev/null 2>&1 && { echo "✓"; break; }
    echo -n "."; sleep 1
  done
  # 只重建带 OTLP exporter 的服务配置，不重建镜像；无 obs 时默认采样为 0，避免后台持续报连接失败。
  TRACING_SAMPLE=1.0 OTLP_ENDPOINT=http://tempo:4318/v1/traces \
    compose --profile apps up -d gateway rec-engine ad-serving content user
  echo "  Grafana → http://localhost:$GRAFANA_PORT (admin/admin)"
}

# 判权栈(docs/09):recsys 专属 SpiceDB(postgres datastore,先跑 migrate 再 serve)。
# 判权服务 auth-platform-server(:8210)从 auth-platform 仓库在宿主机起,advertiser 容器经
# host.docker.internal 访问;接通判权设 RECSYS_AUTHZ_MODE=shadow|enforce(dev-local.env)后 rebuild advertiser。
cmd_authz() {
  require_docker || return 1
  echo "== 追加判权栈(spicedb-migrate → spicedb;--profile authz)=="
  compose --profile authz up -d
  echo "  SpiceDB → grpc localhost:\${RECSYS_SPICEDB_GRPC_PORT:-50052} / http localhost:\${RECSYS_SPICEDB_HTTP_PORT:-8544}"
  echo "  判权服务(auth-platform-server :8210)需在宿主机另行启动,见 docs/09-权限接入auth-platform.md"
}

# 前端热开发:宿主机跑 Vite(改前端即时热更),/api 反代到容器网关的宿主暴露端口。
cmd_frontend() {
  cd "$REPO/console"
  [ -d node_modules ] || npm install
  local pid; pid="$(lsof -nP -iTCP:"$VITE_PORT" -sTCP:LISTEN -t 2>/dev/null)"
  [ -n "$pid" ] && { echo "停旧 Vite (:$VITE_PORT)"; kill $pid 2>/dev/null; sleep 1; }
  echo "起 Vite :$VITE_PORT,/api → http://localhost:$GATEWAY_PORT(容器网关)"
  RECSYS_GATEWAY="http://localhost:$GATEWAY_PORT" nohup npm run dev >"$LOG_DIR/frontend.log" 2>&1 &
  for _ in $(seq 1 20); do grep -qE "Local:|ready in" "$LOG_DIR/frontend.log" 2>/dev/null && break; sleep 1; done
  grep -E "Local:|ready in" "$LOG_DIR/frontend.log" | head -2
  echo "打开 http://localhost:$VITE_PORT"
}

case "${1:-}" in
  up|start)    shift; cmd_up "$@" ;;
  build)       shift; cmd_build "$@" ;;
  rebuild)     shift; cmd_rebuild "$@" ;;
  down|stop)   cmd_down ;;
  restart)     shift; cmd_down; sleep 2; cmd_up "$@" ;;
  status|ps)   cmd_status ;;
  logs)        cmd_logs "${2:-}" ;;
  infra)       cmd_infra ;;
  obs)         cmd_obs ;;
  authz)       cmd_authz ;;
  frontend|fe) cmd_frontend ;;
  *) echo "用法: $0 {up|down|restart|status|logs <svc>|rebuild <svc>|build|infra|obs|authz|frontend} [服务名...]"
     echo "服务名(compose): gateway rec-engine behavior advertiser ad-serving content user console-api console postgres redis nacos spicedb"
     exit 1 ;;
esac
