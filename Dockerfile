# 参数化多模块镜像(E4)。一个 Dockerfile 服务所有 Spring Boot [app]:
#   docker build --build-arg MODULE=recsys-rec-engine --build-arg PORT=8081 -t recsys/rec-engine .
#   docker build --build-arg MODULE=recsys-advertiser  --build-arg PORT=8083 -t recsys/advertiser .
# 说明:多模块 reactor 用 `-pl <module> -am` 只构建目标模块及其依赖;ONNX 模型作为资源已打进 fat jar。
# 注:recsys-streaming(Flink)需额外 --add-opens,走 run-streaming.sh,不在本镜像范围。

# ---------- 构建阶段 ----------
FROM maven:3-eclipse-temurin-26 AS build
WORKDIR /build
# 先拷贝所有 pom 以利用依赖层缓存(源码变动不必重下依赖)
COPY pom.xml ./
COPY recsys-common/pom.xml recsys-common/
COPY recsys-ad-common/pom.xml recsys-ad-common/
COPY recsys-proto/pom.xml recsys-proto/
COPY recsys-gateway/pom.xml recsys-gateway/
COPY recsys-rec-engine/pom.xml recsys-rec-engine/
COPY recsys-query/pom.xml recsys-query/
COPY recsys-recall/pom.xml recsys-recall/
COPY recsys-rank/pom.xml recsys-rank/
COPY recsys-ad/pom.xml recsys-ad/
COPY recsys-ad-serving/pom.xml recsys-ad-serving/
COPY recsys-advertiser/pom.xml recsys-advertiser/
COPY recsys-feature/pom.xml recsys-feature/
COPY recsys-embedding/pom.xml recsys-embedding/
COPY recsys-content/pom.xml recsys-content/
COPY recsys-content-service/pom.xml recsys-content-service/
COPY recsys-user/pom.xml recsys-user/
COPY recsys-user-service/pom.xml recsys-user-service/
COPY recsys-behavior/pom.xml recsys-behavior/
COPY recsys-offline/pom.xml recsys-offline/
COPY recsys-console/pom.xml recsys-console/
COPY recsys-streaming/pom.xml recsys-streaming/
ARG MODULE=recsys-rec-engine
RUN mvn -B -q -pl ${MODULE} -am -DskipTests dependency:go-offline || true
# 再拷贝源码并打包目标模块(fat jar)
COPY . .
RUN mvn -B -q -pl ${MODULE} -am -DskipTests package \
    && cp ${MODULE}/target/${MODULE}-*.jar /build/app.jar

# ---------- 运行阶段 ----------
FROM eclipse-temurin:21-jre
ARG PORT=8080
WORKDIR /app
# 非 root 运行
RUN useradd -r -u 1001 appuser
COPY --from=build /build/app.jar app.jar
ENV JAVA_OPTS="" SERVER_PORT=${PORT}
EXPOSE ${PORT}
USER appuser
# k8s readiness/liveness 探针打 /actuator/health/{readiness,liveness}
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
