#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# recsys 本地整栈启动脚本(one-command 起全栈,避免每次手敲一堆 env 踩坑)。
#
# 用法:
#   scripts/dev-local.sh up            # 起全部后端服务(基础设施 → 8 个 app),staggered + 健康检查
#   scripts/dev-local.sh up rec-engine console   # 只起指定服务
#   scripts/dev-local.sh down          # 停全部(按端口杀)
#   scripts/dev-local.sh restart [svc...]        # 重启
#   scripts/dev-local.sh status        # 各服务健康表
#   scripts/dev-local.sh logs <svc>    # tail 某服务日志
#   scripts/dev-local.sh frontend      # 起 Vite(指向本机网关端口)
#   scripts/dev-local.sh infra         # 只确保 pg/redis 在跑
#
# 端口/连接默认按“标准端口”(8080-8090 / pg 5432 / redis 6379);
# 本机若与其它项目冲突,把覆盖值写进 scripts/dev-local.env(见 dev-local.env.example),脚本会自动 source。
#
# 关键坑(本脚本已内建规避,勿再手敲):
#   1. 必须用 bash(非 zsh):zsh 不对无引号变量分词,`env $VARSTR mvn` 会把整串塞进第一个 env。
#   2. gateway 也要 RECSYS_SECURITY_ENABLED=false,否则 /api/advertiser、/api/search-ads 等经网关 401。
#   3. rec-engine/advertiser/ad-serving 都要 PG_JDBC_URL/PG_DS1_JDBC_URL(ad 读侧 ShardingSphere 次数据源),
#      否则默认连 5432、健康聚合 DOWN、搜索广告标题为空。
#   4. staggered 启动(逐个等就绪)避免同时初始化打爆 pg 连接池 / docker 端口转发风暴。
# ---------------------------------------------------------------------------
set -o pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$REPO"

# ---------- 默认配置(标准端口;本机覆盖见 dev-local.env)----------
: "${PG_HOST:=127.0.0.1}"; : "${PG_PORT:=5432}"; : "${PG_DB:=recsys}"; : "${PG_DS1_DB:=recsys_ds1}"
: "${PG_USER:=recsys}"; : "${PG_PASSWORD:=recsys}"
: "${REDIS_HOST:=127.0.0.1}"; : "${REDIS_PORT:=6379}"
: "${GATEWAY_PORT:=8080}"; : "${REC_ENGINE_PORT:=8081}"; : "${BEHAVIOR_PORT:=8082}"
: "${ADVERTISER_PORT:=8083}"; : "${AD_SERVING_PORT:=8085}"; : "${CONTENT_PORT:=8086}"
: "${USER_PORT:=8087}"; : "${CONSOLE_PORT:=8090}"
: "${AD_SERVING_GRPC_PORT:=9095}"; : "${CONTENT_GRPC_PORT:=9096}"; : "${USER_GRPC_PORT:=9097}"
: "${PROMETHEUS_URL:=http://localhost:9090}"
: "${VITE_PORT:=5173}"
: "${PG_CONTAINER:=recsys-pg}"; : "${REDIS_CONTAINER:=recsys-redis}"
: "${LOG_DIR:=$REPO/.dev/logs}"

# 本机覆盖(端口冲突等)。gitignore,见 dev-local.env.example。
[ -f "$SCRIPT_DIR/dev-local.env" ] && . "$SCRIPT_DIR/dev-local.env"

# 派生:ShardingSphere 次数据源整条 URL 走单占位符(sharding.yaml 的 PG_JDBC_URL/PG_DS1_JDBC_URL)。
PG_JDBC_URL="${PG_JDBC_URL:-jdbc:postgresql://$PG_HOST:$PG_PORT/$PG_DB}"
PG_DS1_JDBC_URL="${PG_DS1_JDBC_URL:-jdbc:postgresql://$PG_HOST:$PG_PORT/$PG_DS1_DB}"

mkdir -p "$LOG_DIR"

if [ -z "${JAVA_HOME:-}" ] && command -v /usr/libexec/java_home >/dev/null 2>&1; then
  JAVA_HOME="$(/usr/libexec/java_home -v 21 2>/dev/null)"; export JAVA_HOME
fi

# 服务清单:name|module|httpPort|grpcPort(启动顺序;gateway 放最后,console 在其探测目标之后)
services() {
  cat <<EOF
rec-engine|recsys-rec-engine|$REC_ENGINE_PORT|
behavior|recsys-behavior|$BEHAVIOR_PORT|
content|recsys-content-service|$CONTENT_PORT|$CONTENT_GRPC_PORT
user|recsys-user-service|$USER_PORT|$USER_GRPC_PORT
advertiser|recsys-advertiser|$ADVERTISER_PORT|
ad-serving|recsys-ad-serving|$AD_SERVING_PORT|$AD_SERVING_GRPC_PORT
console|recsys-console|$CONSOLE_PORT|
gateway|recsys-gateway|$GATEWAY_PORT|
EOF
}

# ---------- 工具 ----------
tcp_up()  { (exec 3<>"/dev/tcp/$1/$2") 2>/dev/null && exec 3>&- && return 0 || return 1; }
port_pid() { lsof -nP -iTCP:"$1" -sTCP:LISTEN -t 2>/dev/null; }
health()  { curl -s -m 3 "http://localhost:$1/actuator/health" 2>/dev/null | sed -n 's/.*"status":"\([A-Z]*\)".*/\1/p' | head -1; }

ensure_infra() {
  echo "== 基础设施 =="
  if tcp_up "$PG_HOST" "$PG_PORT"; then echo "  ✓ postgres $PG_HOST:$PG_PORT"; else
    echo "  … postgres 未就绪,尝试 docker start $PG_CONTAINER"
    docker start "$PG_CONTAINER" >/dev/null 2>&1 || true; sleep 3
    tcp_up "$PG_HOST" "$PG_PORT" && echo "  ✓ postgres 已起" || { echo "  ✗ postgres $PG_HOST:$PG_PORT 连不上。请先起容器(见 docs/08-本地运行.md)"; return 1; }
  fi
  if tcp_up "$REDIS_HOST" "$REDIS_PORT"; then echo "  ✓ redis $REDIS_HOST:$REDIS_PORT"; else
    echo "  … redis 未就绪,尝试 docker start $REDIS_CONTAINER"
    docker start "$REDIS_CONTAINER" >/dev/null 2>&1 || \
      docker run -d --name "$REDIS_CONTAINER" --restart unless-stopped -p "$REDIS_PORT:6379" \
        -v recsys-redis-data:/data redis:7-alpine redis-server --appendonly yes >/dev/null 2>&1 || true
    sleep 2
    tcp_up "$REDIS_HOST" "$REDIS_PORT" && echo "  ✓ redis 已起" || echo "  ⚠ redis 连不上(Redis 相关功能会降级)"
  fi
}

# 组装某服务的 env 数组(bash 数组,天然规避 zsh 分词坑)
build_env() {
  local name=$1 httpPort=$2 grpcPort=$3
  ENVV=(
    PG_HOST="$PG_HOST" PG_PORT="$PG_PORT" PG_DB="$PG_DB" PG_USER="$PG_USER" PG_PASSWORD="$PG_PASSWORD"
    PG_DS1_DB="$PG_DS1_DB" PG_JDBC_URL="$PG_JDBC_URL" PG_DS1_JDBC_URL="$PG_DS1_JDBC_URL"
    REDIS_HOST="$REDIS_HOST" REDIS_PORT="$REDIS_PORT"
    RECSYS_SECURITY_ENABLED=false NACOS_DISCOVERY=false SPRING_CLOUD_NACOS_CONFIG_ENABLED=false
    SERVER_PORT="$httpPort"
  )
  [ -n "$grpcPort" ] && ENVV+=( GRPC_SERVER_PORT="$grpcPort" )
  case "$name" in
    gateway)
      ENVV+=( REC_ENGINE_PORT="$REC_ENGINE_PORT" BEHAVIOR_PORT="$BEHAVIOR_PORT"
              ADVERTISER_PORT="$ADVERTISER_PORT" CONSOLE_PORT="$CONSOLE_PORT" ) ;;
    console)
      ENVV+=( RECSYS_CONSOLE_URL="http://localhost:$CONSOLE_PORT" RECSYS_GATEWAY_URL="http://localhost:$GATEWAY_PORT"
              RECSYS_REC_ENGINE_URL="http://localhost:$REC_ENGINE_PORT" RECSYS_BEHAVIOR_URL="http://localhost:$BEHAVIOR_PORT"
              RECSYS_ADVERTISER_URL="http://localhost:$ADVERTISER_PORT" RECSYS_AD_SERVING_URL="http://localhost:$AD_SERVING_PORT"
              RECSYS_CONTENT_SERVICE_URL="http://localhost:$CONTENT_PORT" RECSYS_USER_SERVICE_URL="http://localhost:$USER_PORT"
              PROMETHEUS_URL="$PROMETHEUS_URL" ) ;;
  esac
}

start_one() {
  local name=$1 module=$2 httpPort=$3 grpcPort=$4
  local log="$LOG_DIR/$name.log"
  if [ -n "$(port_pid "$httpPort")" ]; then echo "  • $name 已在 :$httpPort 运行,跳过"; return 0; fi
  build_env "$name" "$httpPort" "$grpcPort"
  echo "  ▸ 启动 $name ($module) :$httpPort${grpcPort:+ / gRPC :$grpcPort}"
  env "${ENVV[@]}" nohup mvn -q -pl "$module" spring-boot:run >"$log" 2>&1 &
  local ok=0
  for _ in $(seq 1 90); do
    grep -qE "Started .*Application|Netty started" "$log" 2>/dev/null && { ok=1; break; }
    grep -qE "APPLICATION FAILED|Application run failed|BUILD FAILURE|Error starting" "$log" 2>/dev/null && break
    sleep 2
  done
  if [ "$ok" != 1 ]; then echo "    ✗ $name 启动失败/超时,见 $log:"; tail -6 "$log"; return 1; fi
  local st=""; for _ in $(seq 1 15); do st="$(health "$httpPort")"; [ -n "$st" ] && break; sleep 1; done
  echo "    ✓ $name 就绪 (health=${st:-?})"
}

stop_one() {
  local name=$1 httpPort=$2
  local pid; pid="$(port_pid "$httpPort")"
  if [ -n "$pid" ]; then echo "  ✗ 停 $name (:$httpPort pid $pid)"; kill $pid 2>/dev/null; fi
}

cmd_up() {
  ensure_infra || return 1
  echo "== 启动后端(逐个 staggered)=="
  local want=("$@")
  while IFS='|' read -r name module httpPort grpcPort; do
    [ -z "$name" ] && continue
    if [ ${#want[@]} -gt 0 ]; then case " ${want[*]} " in *" $name "*) ;; *) continue;; esac; fi
    start_one "$name" "$module" "$httpPort" "$grpcPort"
  done < <(services)
  echo; cmd_status
  echo; echo "前端:scripts/dev-local.sh frontend  (Vite :$VITE_PORT → 网关 :$GATEWAY_PORT)"
}

cmd_down() {
  echo "== 停后端 =="
  while IFS='|' read -r name module httpPort grpcPort; do
    [ -z "$name" ] && continue; stop_one "$name" "$httpPort"
  done < <(services)
  # 兜底:清掉可能残留的 spring-boot:run 启动器
  pkill -f "spring-boot:run" 2>/dev/null && echo "  ✗ 清理残留 spring-boot:run 启动器" || true
}

cmd_status() {
  echo "== 健康 =="
  printf "  %-12s %-6s %s\n" "服务" "端口" "状态"
  while IFS='|' read -r name module httpPort grpcPort; do
    [ -z "$name" ] && continue
    local st="停"; [ -n "$(port_pid "$httpPort")" ] && st="$(health "$httpPort")"; [ -z "$st" ] && st="启动中?"
    printf "  %-12s :%-5s %s\n" "$name" "$httpPort" "$st"
  done < <(services)
}

cmd_logs() {
  local name=$1; [ -z "$name" ] && { echo "用法: dev-local.sh logs <svc>"; return 1; }
  tail -f "$LOG_DIR/$name.log"
}

cmd_frontend() {
  cd "$REPO/console"
  [ -d node_modules ] || npm install
  local pid; pid="$(port_pid "$VITE_PORT")"; [ -n "$pid" ] && { echo "停旧 Vite (:$VITE_PORT)"; kill $pid 2>/dev/null; sleep 1; }
  echo "起 Vite :$VITE_PORT,/api → http://localhost:$GATEWAY_PORT"
  RECSYS_GATEWAY="http://localhost:$GATEWAY_PORT" nohup npm run dev >"$LOG_DIR/frontend.log" 2>&1 &
  for _ in $(seq 1 20); do grep -qE "Local:|ready in" "$LOG_DIR/frontend.log" 2>/dev/null && break; sleep 1; done
  grep -E "Local:|ready in" "$LOG_DIR/frontend.log" | head -2
  echo "打开 http://localhost:$VITE_PORT"
}

case "${1:-}" in
  up|start)   shift; cmd_up "$@" ;;
  down|stop)  cmd_down ;;
  restart)    shift; cmd_down; sleep 2; cmd_up "$@" ;;
  status)     cmd_status ;;
  logs)       cmd_logs "${2:-}" ;;
  frontend|fe) cmd_frontend ;;
  infra)      ensure_infra ;;
  *) echo "用法: $0 {up|down|restart|status|logs <svc>|frontend|infra} [服务名...]"; echo "服务名: rec-engine behavior content user advertiser ad-serving console gateway"; exit 1 ;;
esac
