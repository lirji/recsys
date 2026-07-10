# recsys 搜推广控制台 (console) — 独立前端

前后端分离后的**独立前端工程**:React 18 + TypeScript + Vite + Ant Design 5 + ECharts。是一个演示 / 调试台,把系统的搜索、推荐、广告三大能力 + 广告主后台 + 离线报表可视化出来。

> 与后端解耦:本工程有自己的构建/部署生命周期,不再由 Java 模块 `frontend-maven-plugin` 打进 jar。后端只提供 API。

## 架构:全站经网关 :8080 同源

前端用相对路径 `/api` 调后端,页面/静态资源/接口全部同源,**零 CORS**。

```
浏览器 → nginx(prod) 或 Vite:5173(dev)
  ├─ / , /assets/*                → 本工程静态资源
  └─ /api/**  ──反代──▶ 网关 :8080 (recsys-gateway)
        ├─ /api/recommend|search|search-ads|feed|query|user|ad|experiment/** → rec-engine:8081
        ├─ /api/behavior/**             → behavior:8082
        ├─ /api/advertiser/**           → advertiser:8083
        └─ /api/console/report/**       → recsys-console:8090  (离线报表读取)
```

## 本地开发 (dev)

```bash
# 先起后端(至少网关 :8080;按需起 rec-engine/behavior/advertiser/recsys-console)
cd console
npm install
npm run dev            # Vite :5173,/api 代理到 http://localhost:8080(RECSYS_GATEWAY 可覆盖)
# 打开 http://localhost:5173
```

dev 阶段浏览器只与 :5173 通信,Vite 把 `/api` 反代到网关。某后端未起时对应页面显示错误提示(优雅降级),不影响其它页面。

## 构建 / 部署 (prod)

构建产物输出到 `dist/`,由 **nginx 同源托管 + 反代 /api 到网关**(不再打进任何 jar)。

```bash
npm run build          # → dist/

# 方式一:容器化(推荐)
docker build -t recsys/console .
docker run -p 8095:80 -e RECSYS_GATEWAY=http://host.docker.internal:8080 recsys/console
# 打开 http://localhost:8095

# 方式二:docker compose(可选 profile)
docker compose --profile console up -d --build      # 前端在 :8095,反代到宿主机网关 :8080
```

`RECSYS_GATEWAY` 决定 nginx 把 `/api` 反代到哪:宿主机网关用 `http://host.docker.internal:8080`,同容器网络用 `http://gateway:8080`。见 nginx 模板 `default.conf.template` 与 `Dockerfile`。

## 页面

- **在线调试台**:`/recommend` `/search` `/search-ads` `/feed` `/query` `/experiment` `/user-interests`
- **广告主后台**:`/advertiser`(列表/建)→ 广告主详情 / 广告列表 / 广告详情(审核状态机 + 创意 + 竞价词)/ 投放报表
- **离线报表**:`/reports` 总览 → `/reports/:category` 按分类可视化(eval / ab-report / ad-report / data-quality / ad-quality)

## 目录

```
console/
├── Dockerfile               # node 构建 → nginx 托管
├── default.conf.template    # nginx 同源托管 + /api 反代(envsubst 注入 RECSYS_GATEWAY)
├── src/
│   ├── api/            # 后端接口封装 + DTO 的 TS 镜像(types.ts)
│   ├── components/     # explain(可解释性)/ adv(广告主)/ charts(ECharts)/ reports
│   ├── hooks/          # 全局 userId/scene
│   ├── pages/          # online / adv / reports 三组页面
│   ├── App.tsx / router.tsx / main.tsx
```
