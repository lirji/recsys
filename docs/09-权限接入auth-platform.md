# 09 权限接入 auth-platform：广告主管理面的细粒度归属判权

> 2026-07-16 落地。解决 P0 安全层遗留的缺口：此前只有角色 RBAC（`hasAnyRole('ADVERTISER','ADMIN')`），
> **任一 ADVERTISER 角色可以读改任意广告主的广告/竞价词/创意**（`MethodSecurityConfig` 明确把 owner
> 归属校验留给领域层，而领域层没有做）。现接入**统一权限平台 auth-platform**（SpiceDB/ReBAC），按
> `advertiser:{id}` 作用域做归属判权。平台侧文档：auth-platform 仓库
> `docs/新项目接入指南.md`（本次接入即按它执行）与 `docs/平台能力总览.md`。

## 1. 模型（auth-platform 仓库 `schemas/recsys.zed`）

```
platform:recsys            # 全局唯一平台锚点
  admin / operator         # 平台管理员(administrate) / 运营(review 人审 + view_reports)
advertiser:{id}
  platform → platform:recsys
  owner: user              # 账户所有人
  member: user|group       # 投放团队成员
  manage = owner + platform->administrate
  edit   = member + manage
  view   = edit + platform->view_reports
```

**最小元组设计**：广告/竞价词/创意**不进 SpiceDB**——权限纯继承自 advertiser 作用域，服务端从
adId/bidwordId/creativeId 反查 `advertiser_id` 再判（广告 CRUD 高频，逐条双写放大且无独立授权语义）。
主体 = 内部令牌 subject（`X-Internal-Auth`，网关验终端 JWT 后下传）；未来接 Casdoor SSO 后主体切
Casdoor `sub`，需做一次 username→sub crosswalk。

## 2. 接入点（`recsys-advertiser`）

- 守卫 `authz/AdvertiserAuthz`：三态 `recsys.authz.mode`（env `RECSYS_AUTHZ_MODE`）——
  `disabled`（**默认**，零行为变化）/ `shadow`（判但只记 `[authz-shadow] would-deny` 日志）/
  `enforce`（deny→403；判权依赖故障→503 **fail-closed**，不静默放行也不折成 deny）。
- `AdvertiserService` 全部公开入口**先判权后变更**：
  - 开户 `createAdvertiser` → `platform.administrate`（开户是平台职能）+ **归属双写**（建号后写
    `owner=创建者` + `platform` 元组；enforce 下写失败随 `@Transactional` 回滚，不留无归属孤儿）；
  - 广告主读/改/报表 → `view`/`manage`/`view`；列表 → `checkBulk(view)` 过滤（shadow 只记会隐藏几条）；
  - 广告/竞价词/创意的建改删 → 反查归属后判 `edit`；读 → `view`；
  - 人审 `reviewAd` → `platform.review`（平台运营职能，广告主不能自审；`submit-review` 归广告主 `edit`）。
- 判读一致性：常规 `minimize_latency`；本实例归属双写后自动带 ZedToken（`at_least_as_fresh`），
  刚建的广告主创建者立刻可见。**外部授权**（经判权服务 grant 的元组）受 SpiceDB 量化窗口影响约数秒后
  可见——授权即时生效的流程应复用授权响应里的 ZedToken（见 auth-platform `CLAUDE.md` 一致性节）。
- SDK：`com.lrj.authz:auth-platform-sdk`（grpc-free 纯 HTTP，与本仓库 grpc 1.64 零冲突；Spring 依赖被
  根 pom BOM 钉到 3.2.5）。判权服务地址 `authz.client.server-url`（env `AUTHZ_SERVER_URL`，默认 :8210）。
- **SDK 获取（供奉依赖 vendored）**：SDK 及其兄弟件（父 pom / `auth-platform-protocol`）未发布到任何
  制品仓库，jar+pom 直接提交在 **`libs/authz/`**；根 `pom.xml` 的 `maven-install-plugin` 在 `initialize`
  阶段自动 `install-file` 进本地仓——**干净环境（CI / Dockerfile 内 mvn / 新克隆）无需预装即可构建**，
  且不走远程仓库、对 settings.xml 镜像免疫。SDK 升级 = 在 auth-platform 仓库重打包后替换 `libs/authz/`
  同名文件；日后 SDK 发布到可达仓库后删插件与 `libs/authz/` 即可。仅 §3 step 3 的判权服务
  （auth-platform-server）仍需从平台仓库构建。
- **判权栈 fail-closed 收紧（auth-platform 侧 2026-07-18）**：平台的 server/core 进一步收紧严格响应校验——
  server `check-bulk` 引擎响应缺任一资源即抛（不再 `Boolean.TRUE.equals(null)` 静默降级成 deny）、core
  `writeRelationships`/`deleteRelationships` 缺 ZedToken 即抛、`lookupResources` 缺 permissionship 即抛、流式响应含
  `error` 即抛。**对 recsys 的影响**：列表过滤 `checkBulk(view)` 与开户归属双写遇到后端故障/畸形响应会**冒泡成判权依赖故障**
  （enforce → 503 并随事务回滚 / shadow → 放行并 `[authz-shadow]` 告警），不再有任何静默降级路径——强化既有 fail-closed
  姿态，**recsys 侧零改动**。**SDK/protocol 主代码未变，`libs/authz/` 供奉件无需重打**；只需把 §3 step 3 的判权服务从
  auth-platform 仓库重建即可拾取该收紧（平台侧 20 测试类/117 `@Test` 全绿）。

## 3. dev 运行手册

```bash
# 1) recsys 专属 SpiceDB(§B 每项目独立实例;datastore=postgres 持久化——recsys-postgres 的 spicedb 库,
#    migrate 由 compose 一次性 spicedb-migrate 完成;存量卷需先 docker exec recsys-postgres createdb -U recsys spicedb)
cd docker && docker compose --profile authz up -d spicedb          # :8544, key=recsys_dev_key

# 2) 灌模型 + 演示租户(在 auth-platform 仓库;dry-run 去掉 APPLY)
APPLY=1 bash deploy/recsys-authz-fixture.sh                        # 9 条强一致自校验

# 3) 起 recsys 专属判权服务(auth-platform 仓库构建产物)
SERVER_PORT=8210 SPICEDB_HTTP=http://localhost:8544 SPICEDB_KEY=recsys_dev_key \
  java -jar <auth-platform>/auth-platform-server/target/auth-platform-server-0.1.0-SNAPSHOT.jar

# 4) advertiser 开 shadow 观察(或 enforce 真拦)
RECSYS_AUTHZ_MODE=shadow AUTHZ_SERVER_URL=http://localhost:8210 mvn -pl recsys-advertiser spring-boot:run
```

**容器化跑法**（advertiser 跑在 compose 容器里,判权服务仍在宿主机）：compose 已给 advertiser 服务
内置 `RECSYS_AUTHZ_MODE`(默认 disabled)/`AUTHZ_SERVER_URL`(默认 `http://host.docker.internal:8210`,
经 `extra_hosts: host-gateway` 解析宿主机)/`AUTHZ_CLIENT_TOKEN` 三个环境变量——上面 step 1–3 照做后,
直接 `RECSYS_AUTHZ_MODE=shadow docker compose --profile apps up -d advertiser`(或写进
`scripts/dev-local.env` 再 `dev-local.sh rebuild advertiser`)即可接通;SpiceDB 也可用一键封装
`scripts/dev-local.sh authz` 起(等价 `--profile authz up -d`)。

授权管理（给用户授某广告主的 owner/member）：经判权服务
`POST :8210/v1/relationships`（TOUCH `advertiser:{id}#owner@user:{subject}`），或部署 auth-platform-admin
用其管控台。

## 4. 已验证（2026-07-16，全链路 live）

- 单测：`AdvertiserAuthzTest` 11 例（三态矩阵/403/503 fail-closed/列表过滤/归属双写+ZedToken）。
- e2e（真实 pg+redis+SpiceDB+判权服务+minted 内部令牌）：**enforce** 下无 token 401；owner2（同为
  ADVERTISER 角色）读/改别人广告主与广告/竞价词全部 403（旧 RBAC 下是 200！）；owner1 对自己的全通；
  列表过滤不含他人；开户非平台管理员 403；广告主自审 403 / 平台管理员人审 200。**shadow** 下同样的
  越权请求 200 放行 + 精确 `would-deny` 日志。
- 顺带修复：`04b_ds1_bootstrap.sql` 的 ds_1 分片表缺 A2/A3 列（`review_status` 等），路由到 ds_1 的
  广告 INSERT 必挂——已补齐并加平滑升级 ALTER。

## 5. 边界与后续

- **默认全关**（`disabled`），生产灰度顺序：shadow 观察 deny 样本 → 清理归属数据 → enforce。
- 网关粗粒度 RBAC（ADVERTISER/ADMIN 角色）保留为第一道闸，本判权是第二道（纵深防御）。
- 其余待接面：`/api/experiment/**`（仅角色 ADMIN，可提升为 platform.administrate）、console BFF 的
  用户 360/报表匿名 GET（网关层 permitAll，独立问题）、gRPC 内部服务（`CALLER_SUBJECT` 已传播，可在
  money path 复用同一守卫）。
- ~~身份侧后续~~ **网关侧已接 Casdoor（2026-07-16）**：`recsys.security.casdoor.enabled`（env
  `RECSYS_EDGE_CASDOOR`；Docker 编排默认开启，源码仍支持关闭回滚）开启后，边缘认证换 Casdoor JWKS(RS256) + iss 校验 +
  aud 家族校验（方案C `<base>-org-<owner>` 绑定）+ **org 钉死**（owner 必须 = `casdoor.organization`，
  跨租户 token 一律 401）；`groups`（`recsys/admins`→ADMIN、`recsys/advertisers`→ADVERTISER，可配）映射
  网关角色；内部令牌 subject 自动变 Casdoor `sub`，下游判权主体零改动跟随。租户开通：
  `TENANT=recsys bash casdoor-tenant-provision.sh`（dev 用户 radmin/Radmin@12345、rowner1/Rowner1@12345）。
  存量广告主已 backfill `platform` 归属边（32 个）。**前端 console 的 OIDC 登录已实现**（frontend-plan
  计划与评审记录：`docs/plans/console-oidc-0716/FINAL_PLAN.md`）：Docker 编排默认以 `VITE_AUTH_MODE=oidc`
  展示租户登录（授权码+PKCE），`/callback` 回调、refresh_token 静默续期
  （401 单飞）、Casdoor 单点登出、角色从 access_token `groups` 解（与网关映射同源）；oidc 会话存
  sessionStorage，legacy 残留 token 绝不误发。**前端/网关开关必须成对**：`VITE_AUTH_MODE=oidc` ⇄
  `RECSYS_EDGE_CASDOOR=true`（compose 侧 `CONSOLE_AUTH_MODE` + `RECSYS_EDGE_CASDOOR` 同调）。
  dev 全链路：`docker compose --profile authz up -d spicedb` → fixture → authz-server(:8210) →
  advertiser(enforce) → casdoor 模式网关 → `VITE_AUTH_MODE=oidc npm run dev`。
