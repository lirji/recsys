# 控制台前端(console)镜像:多阶段 —— node 构建 SPA → nginx 同源托管 + 反代 /api。
#   docker build -t recsys/console ./console
#   docker run -p 8095:80 -e RECSYS_GATEWAY=http://host.docker.internal:8080 recsys/console

# ---------- 构建阶段 ----------
FROM node:20-alpine AS build
WORKDIR /app
# 认证模式(构建期烘焙,Vite 的 VITE_* 是编译期内联;改模式需重建镜像):
#   oidc(默认)=Casdoor 租户登录;legacy=应急回滚演示登录(网关须同步切换,成对开关)
ARG VITE_AUTH_MODE=oidc
ARG VITE_CASDOOR_ISSUER=http://localhost:8000
ARG VITE_CASDOOR_CLIENT_ID=ragshared0client00000001-org-recsys
ARG VITE_CASDOOR_SCOPE="openid profile offline_access"
ENV VITE_AUTH_MODE=$VITE_AUTH_MODE \
    VITE_CASDOOR_ISSUER=$VITE_CASDOOR_ISSUER \
    VITE_CASDOOR_CLIENT_ID=$VITE_CASDOOR_CLIENT_ID \
    VITE_CASDOOR_SCOPE=$VITE_CASDOOR_SCOPE
COPY package.json package-lock.json* ./
RUN npm ci || npm install
COPY . .
RUN npm run build

# ---------- 运行阶段 ----------
FROM nginx:1.27-alpine
# 网关地址(容器内默认打宿主机上的 gateway:8080);nginx 镜像启动时 envsubst 注入到 default.conf.template
ENV RECSYS_GATEWAY=http://host.docker.internal:8080
COPY default.conf.template /etc/nginx/templates/default.conf.template
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
