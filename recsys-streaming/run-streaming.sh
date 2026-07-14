#!/usr/bin/env bash
# 本地 MiniCluster 嵌入式运行实时特征 Flink 作业。
# 前置:docker compose -f docker/docker-compose.yml --profile full up -d(Kafka)+ behavior 以 BEHAVIOR_USE_KAFKA=true 起。
# 用法:bash recsys-streaming/run-streaming.sh   [-- 透传 --window-min 10 --slide-sec 20 ...]
set -e
cd "$(dirname "$0")"

export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21)}"
MVN=/Users/liruijun/personal/devUtils/apache-maven-3.9.12/bin/mvn
JAR=target/recsys-streaming.jar

if [ ! -f "$JAR" ]; then
  echo "构建 fat jar..."
  (cd .. && "$MVN" -q -pl recsys-streaming -am package -DskipTests)
fi

# Flink 1.20 在 Java 21 下需放开若干 JDK 内部模块(否则 Kryo/内存管理反射报 InaccessibleObjectException)
exec "$JAVA_HOME/bin/java" \
  --add-opens=java.base/java.util=ALL-UNNAMED \
  --add-opens=java.base/java.lang=ALL-UNNAMED \
  --add-opens=java.base/java.lang.reflect=ALL-UNNAMED \
  --add-opens=java.base/java.io=ALL-UNNAMED \
  --add-opens=java.base/java.net=ALL-UNNAMED \
  --add-opens=java.base/java.nio=ALL-UNNAMED \
  --add-opens=java.base/sun.nio.ch=ALL-UNNAMED \
  --add-opens=java.base/java.util.concurrent=ALL-UNNAMED \
  --add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED \
  --add-opens=java.base/java.time=ALL-UNNAMED \
  -jar "$JAR" "$@"
