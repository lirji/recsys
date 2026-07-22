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

**推荐:整栈一键起**(脚本会把后端 8 个 app + 前端 Vite 都拉起,并把 `/api` 指到本机网关端口,免手敲 env):

```bash
scripts/dev-local.sh up          # 起后端(仓库根执行;端口/连接见 scripts/dev-local.env)
scripts/dev-local.sh frontend    # 起 Vite:5173,/api → 本机网关端口(默认 8080,本机 9080)
# 打开 http://localhost:5173,用演示身份(admin)登录
```

只跑前端(后端已在别处起好):

```bash
cd console && npm install
# RECSYS_GATEWAY 指向你的网关地址;本机网关重映射到 9080 时务必带上,否则默认代理 8080(可能是别的进程 → 页面 500)
RECSYS_GATEWAY=http://localhost:9080 npm run dev   # Vite :5173
```

dev 阶段浏览器只与 :5173 通信,Vite 把 `/api` 反代到网关。某后端未起时对应页面显示错误提示(优雅降级),不影响其它页面。详见 `docs/08-本地运行.md`。

## 认证模式(VITE_AUTH_MODE,构建期烘焙)

| 模式           | 登录方式                                      | 网关侧开关(必须成对!)       |
| -------------- | --------------------------------------------- | --------------------------- |
| `legacy`(默认) | 演示账号(admin/advertiser/user,密码=用户名)   | `RECSYS_EDGE_CASDOOR=false` |
| `oidc`         | **Casdoor 统一登录**(确认租户后走授权码+PKCE) | `RECSYS_EDGE_CASDOOR=true`  |

```bash
# dev 起 oidc 模式(需 Casdoor :8000 + casdoor 模式网关;dev 账号 radmin/Radmin@12345、rowner1/Rowner1@12345)
VITE_AUTH_MODE=oidc RECSYS_GATEWAY=http://localhost:8080 npm run dev
```

oidc 会话由 oidc-client-ts 自管(sessionStorage,关标签即清、refresh_token 静默续期);角色从
access_token 的 `groups` 解(admins→管理员、advertisers→广告主,与网关映射同源);顶栏隐藏 demo
身份切换、登出走 Casdoor 单点登出。相关变量见 `.env.example`;模式错配(前 oidc+网关 legacy 或反之)
表现为一直 401,先核对两侧开关。设计与边界见 `docs/plans/console-oidc-0716/FINAL_PLAN.md`、`docs/09`。

### 公开能力门户入口

统一门户应链接到 Recsys 自己的租户登录路由，而不是直接拼 Casdoor authorize URL。正式入口使用：

```text
https://recsys.example.com/login?returnTo=%2Foverview
```

页面先让用户确认 Casdoor organization。Recsys 当前仍是单租户系统，只接受 `VITE_CASDOOR_ORGANIZATION`（默认 `recsys`），并要求 `VITE_CASDOOR_CLIENT_ID` 以 `-org-<organization>` 结尾；该值还必须与网关 `CASDOOR_ORG` 一致。其他租户尚未完成内部 token tenant claim 和数据隔离，页面会 fail-closed 阻止跳转，不能只改前端变量来“开启多租户”。

兼容旧书签的 `source=portal&auto=1` 分支仍会在 OIDC 模式下一次性调用既有 `signInOidc`，由当前 origin 保存 state/PKCE；统一门户的新 catalog 不再使用它。legacy、重复控制参数不会自动跳；`returnTo` 只接受站内单斜杠路径，非法值回退 `/overview`。聚焦回归：`npm run test:run -- src/auth/__tests__/tenantSelection.test.ts src/auth/__tests__/portalLaunch.test.ts src/pages/__tests__/LoginPage.portal.test.tsx`。

## 构建 / 部署 (prod)

构建产物输出到 `dist/`,由 **nginx 同源托管 + 反代 /api 到网关**(不再打进任何 jar)。

```bash
npm run build          # → dist/

# 方式一:容器化(推荐)
docker build -t recsys/console .
docker run -p 8095:80 -e RECSYS_GATEWAY=http://host.docker.internal:8080 recsys/console
# 打开 http://localhost:8095

# 方式二:docker compose(编排在 docker/;从仓库根 -f 指向,或 cd docker 后直接跑)
docker compose -f docker/docker-compose.yml --profile console up -d --build   # 前端 :8095,反代到网关
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
