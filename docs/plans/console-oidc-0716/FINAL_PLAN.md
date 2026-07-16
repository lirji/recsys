# console 接入 Casdoor OIDC 统一登录 — 实施计划 v1.1（已落地,历史存档）

> **状态(2026-07-16)**:本计划已实施完成——commit 87aa1142(console Casdoor OIDC,`VITE_AUTH_MODE=oidc`,默认 legacy 零行为变化)+ 01d251e8(修复 oidc 硬刷新掉登录态:守卫抢跑异步会话恢复)。以下为获批时的原始计划,仅作决策依据存档;现状以 docs/09 与代码为准。

> 2026-07-16，frontend-plan 工作流产出。五路并行勘察（需求流/UIUX/架构/仓库约束/测试风险）已综合；
> **v1.1 = 独立评审修订**（4 个阻塞项 + 可操作性建议全部吸收，评审结论"修订后可批"，修订记录见 §12）。
> 后端（网关 casdoor 边缘模式、Casdoor recsys 租户、SpiceDB 主体迁移）**已完成并 e2e 验证**（docs/09）；
> 本计划为纯前端（console/）+ 构建注入。**获批前不改任何代码。**

## 0. 决策记录（备选对比 → 拍板）

| # | 决策点 | 备选 | 拍板 + 理由 |
|---|---|---|---|
| D1 | OIDC 接入架构 | ①react-oidc-context+AuthBridge(照抄 auth-console)②裸 oidc-client-ts 嫁接进现有 useAuth ③仅覆盖 token 源 | **②**。console 的 `useAuth` Context 是全站身份契约（所有消费点经它），裸 UserManager 双驱动进现有 Provider = 单一状态源、仅 +1 依赖、契合"优先既有约定不引新抽象"。①要引 react-oidc-context+桥接层双状态源；③一致性最弱（模式判断散落）。工作量差距靠抄 auth-console 的 UserManager 配置与 401 单飞代码抹平 |
| D2 | token 存储 | ①写回 localStorage `recsys_token` 三键(下游零改)②oidc-client-ts 自管 sessionStorage + 模式感知取数 | **②**。①有 R1 残留混淆、轮换回写、XSS 面扩大三坑；②关标签即清、轮换由库管。"下游零改动"在 `useAuth().user` 层保证（角色消费点清单证实 UI 全走它），不必在 storage 层保证 |
| D3 | 静默续期 | ①refresh_token(scope offline_access)②prompt=none iframe | **①**。shared app 已开 refresh_token grant；免第三方 cookie 拦截、免 oidc-silent 路由与 main 短路。【假设 A1】Casdoor 对 PKCE public client 发 refresh_token——langchain4j 同 shared app 已跑通，e2e E4 复验，失败则回退② |
| D4 | 登录页双模式 UI | ①互斥单模式渲染 ②oidc+demo 并存 | **①**。两套账号体系并排易困惑；`VITE_AUTH_MODE=legacy`(默认)=现状逐字，`oidc`=单按钮态。不做运行时探测网关模式（R2 用文档+错误文案缓解，见 §5） |
| D5 | prod 环境注入 | ①Dockerfile build-arg(构建期烘焙) ②运行时 config.js+envsubst | **①**。贴合 Vite 模型、langchain4j 先例（compose build args）；缺点（换环境需重建镜像）当前可接受，②留作未来多环境需求 |
| D6 | 401 处理 | ①单飞 signinSilent 重试一次→失败清态跳登录 ②会话过期模态保 deep-link | **①**（auth-console 同款）。②(H8)列非目标，后续增强 |
| D7 | 角色来源 | access_token `groups` claim 前端解（admins→ADMIN、advertisers→ADVERTISER、其余→USER 兜底） | 与网关映射同源；三值枚举收敛防 `ROLE_META[unknown]` 渲染崩溃。已实测 access_token 带 groups |
| D8 | code→token 交换 | 浏览器直连 Casdoor token 端点(PKCE) | auth-console 与 langchain4j 同 dev Casdoor 已验证 CORS 可行【假设 A2，E1 复验】 |

## 1. Goals / Non-goals

**Goals**：①`VITE_AUTH_MODE=oidc` 时 console 走 Casdoor 授权码+PKCE 统一登录（登录/回调/静默续期/登出）；②默认 `legacy` 逐字保留现状（引入即安全）；③`useAuth` 契约不变，下游消费点零改动；④prod 构建注入与 CI 全绿；⑤纯函数优先的测试覆盖。

**Non-goals**：网关/后端改动（已完成）；前端角色路由守卫（沿用"菜单全量+服务端 403 兜底"）；会话过期模态（H8）；多标签 BroadcastChannel 登出（H9，v2）；多租户切换（org 钉死 recsys）；运行时模式探测；自助注册。

## 2. 路由与页面流

- `/login`：`App.tsx` 守卫前特判（现状）。`legacy`=现表单+demo 行（零改动）；`oidc`=单按钮"使用统一身份登录"→`redirecting` 态→`signInOidc(returnTo)`。returnTo=`pathname+search+hash`——注意现状 `RequireAuth` 已存**完整 location**（`router.tsx:35`），缺口在 **LoginPage 读取侧**只取了 pathname（`LoginPage.tsx:271`），改读取处即可（评审修正②）。
- `/callback`（新）：`App.tsx` 守卫前特判（与 `/login` 同层，防守卫弹回死循环）。**StrictMode 双挂载防护（评审阻塞①）**：模块级单飞 guard（`let callbackOnce: Promise|null`），effect 双执行只消费一次 code/state，否则 dev 下首登必现 "No matching state" 错误——测试必须断言双调用只处理一次。处理中=极光背景+Spin"正在完成登录…"；成功=经 `useAuth.completeSignIn()` 收口（见 §3，确保 `user` 已 set 再导航，防 RequireAuth 弹回竞态——评审阻塞④）→`sanitizeReturnTo(returnTo)`→`navigate(returnTo||'/',{replace})`（回调后清 URL code/state）；失败=`Result status="error"`+人话原因（错误映射内联本组件）+"重新登录"按钮回 `/login`。
- 其余路由：`RequireAuth` 不变（未登录→`/login`）。nginx SPA 回退已覆盖 `/callback`，零 nginx 改动。

登出流（oidc，评审阻塞②修正）：`useAuth.logout` 单出口 → **直接 `signoutRedirect()`**（oidc-client-ts 内部自取 `id_token_hint` 并 removeUser，先手动 removeUser 会拿不到 hint 导致 end_session 失效）→ catch 失败兜底：`removeUser()+clearAuth()+setUser(null)+redirectToLogin()`（**经 `api/nav.ts` holder**，AuthProvider 在 Router 之外拿不到 useNavigate——评审修正③；`main.tsx` 保持不动成立）。oidc 模式下 AppLayout 的 `handleLogout` 不再补 `navigate('/login')`（避免与整页跳转竞态），导航全权归 `useAuth.logout`。【假设 A3】Casdoor end_session 可用（auth-console 先例，E6 复验）。

## 3. 组件树（改动面）

```
main.tsx                      不动(AuthProvider 位置不变;登出兜底走 api/nav.ts holder 而非 useNavigate)
App.tsx                       +/callback 特判
├─ LoginPage                  模式分叉:legacy=现状逐字 | oidc=单按钮态(复用 .lpa-btn 与背景壳)
├─ CallbackPage(新)           lpa 背景壳 + Spin | Result 错误态(错误映射内联);StrictMode 单飞 guard
└─ AppLayout
   └─ IdentitySwitcher        oidc: 隐藏 demo 切换行,面板=只读身份卡+登出(导航归 useAuth.logout);chip 显示不变
router.tsx                    RequireAuth 不变
auth/oidc.ts(新)              getUserManager() 惰性工厂(动态 import,legacy 构建不进首包)
                              + decodeJwtPayload + userFromCasdoorToken + sanitizeReturnTo
                              + resolveToken() 模式分派(oidc 绝不读 localStorage token — R1)
                              automaticSilentRenew=false:单实例、续期只由 401 单飞驱动(评审建议②,避免双续期路径)
hooks/useAuth.tsx             AuthProvider 双驱动:
                              - 契约扩展(评审阻塞③):保留 signIn(u,p)=legacy 专用;新增 signInOidc(returnTo)、
                                completeSignIn()(CallbackPage 收口:signinCallback→同步 setUser→返回 returnTo)、
                                mode 只读字段;logout 按模式分支;switchUser oidc 下 no-op(UI 已隐藏)
                              - oidc 事件订阅闭环(评审阻塞④):userLoaded→setUser(derived)、
                                userUnloaded/silentRenewError→setUser(null)+purge;unmount 时解除订阅
                              - bootstrap:getStoredUser(过期/坏 token→不建会话);oidc 初始化时 clearAuth() 清 legacy 三键(R1)
api/client.ts                 请求拦截 await resolveToken()(拦截器本就支持 async);
                              401: oidc→单飞 signinSilent+_retried 重试一次→失败 removeUser+setUser(null)+跳登录;legacy 现状
config/auth.ts(新)            唯一 env 出口(AUTH_MODE 非法值→warn+legacy)
```

## 4. 状态与边界（关键项）

| 状态/边界 | 行为 |
|---|---|
| 回调 state 不匹配/code 重放/用户拒绝 | 不建会话；错误态人话化+重新登录（E8/E9） |
| 坏 token/解不出 username+roles | **不建会话**视为未登录（H3/R4） |
| 续期失败/登出失败 | 必 `removeUser` purge sessionStorage，硬刷新不复活死会话（H2/R3） |
| 并发 401 | 单飞续期一次+`_retried` 防重放（H4/R6） |
| oidc 模式下 localStorage 残留 legacy token | **绝不发出**（resolveToken 模式分派+oidc 初始化时 `clearAuth()` 清三键，R1） |
| returnTo | sanitize：仅站内 `/` 开头路径，拒 `//`、`http(s)://`（H7/R5） |
| 模式错配（前 oidc+网关 legacy 或反之） | 不做运行时探测；登录失败文案引导核对 `RECSYS_EDGE_CASDOOR`/`VITE_AUTH_MODE` 成对开关（R2，文档写明） |
| Casdoor 不可达 | 登录发起失败可读错误+重试；bootstrap 不阻塞挂载（R8） |
| 关标签 sessionStorage 清空 | bootstrap 无 storedUser→未登录，不触发静默流（H5） |
| 深链接 | RequireAuth from(含 search/hash)→oidc state 往返→回调 sanitize 后精确恢复（E2） |
| StrictMode 双挂载（dev） | CallbackPage 模块级单飞 guard，双 effect 只消费一次 code（评审阻塞①） |
| 新标签页打开深链 | sessionStorage per-tab→该标签走一轮完整重定向（Casdoor 会话在则秒回）；属预期，文档写明（评审建议④a） |
| 非 localhost 的 http 部署 | `crypto.subtle` 不可用→PKCE 直接抛错（docker 用 IP 访问即触发）；错误文案提示"需 https 或 localhost"（评审建议④b） |
| 有效但错租户 token | 网关 401→跳登录→SSO 秒回→再 401 的循环体验；登录页对连续 401 给"该账号无权访问本系统(org 不符)"文案（评审建议④c，R2 同源） |

## 5. API/协议契约

- Casdoor（浏览器直连）：authority=`VITE_CASDOOR_ISSUER`(dev `http://localhost:8000`，discovery 自动)；client_id=`ragshared0client00000001-org-recsys`（依据：Casdoor shared-app 的 `-org-` 派生 client 机制，网关按 `<base>-org-*` 家族放行且 org 钉死 recsys——单租户前端故钉死派生 id，不做登录页输租户）；redirect=`origin+'/callback'`（**常量**，不做 env——nginx/白名单/App 特判三处语义已硬编码，评审可砍①）；scope=`openid profile offline_access`；PKCE S256；sessionStorage userStore；`loadUserInfo:false`；`automaticSilentRenew:false`（单实例+401 单飞驱动）。
- 网关（不变）：Bearer=Casdoor **access_token**（RS256，带 owner/groups，已实测）；401/403 语义不变。
- 身份派生：access_token `groups:["recsys/admins"|"recsys/advertisers"]`→短名→`ADMIN|ADVERTISER`，无匹配→`USER`；username=payload `name`。
- 新 env（全经 `config/auth.ts` 出口 + `vite-env.d.ts` 补类型）：`VITE_AUTH_MODE`(legacy 默认)、`VITE_CASDOOR_ISSUER`、`VITE_CASDOOR_CLIENT_ID`、`VITE_CASDOOR_SCOPE`(可选)、`VITE_CASDOOR_REDIRECT_PATH`(默认 `/callback`)。

## 6. 文件级改动清单

**新增**：`console/src/config/auth.ts`、`src/auth/oidc.ts`(含 resolveToken，不再单列 session.ts——评审可砍⑤)、`src/pages/CallbackPage.tsx`、`src/auth/__tests__/oidc.test.ts`、`src/hooks/__tests__/useAuth.oidc.test.tsx`、`src/pages/__tests__/CallbackPage.test.tsx`(含 StrictMode 双调用断言)、`console/.env.example`。
**修改**：`package.json`+`package-lock.json`(+`oidc-client-ts@^3`，唯一新依赖)、`src/vite-env.d.ts`、`src/hooks/useAuth.tsx`、`src/api/client.ts`、`src/pages/LoginPage.tsx`、`src/components/AppLayout.tsx`、`src/App.tsx`、`console/Dockerfile`(+ARG/ENV)、`docker/docker-compose.yml`(console build.args + gateway 服务补 `RECSYS_EDGE_CASDOOR`/`CASDOOR_*` env 透传)、`console/README.md`、`docs/09-权限接入auth-platform.md`。
**不动**：`src/api/auth.ts`(legacy 全保留)、`router.tsx` RequireAuth、`main.tsx`、`vite.config.ts`(oidc 库经动态 import 自动分包，manualChunks 不加——评审可砍②)、nginx 模板、后端一切。
**砍掉**（评审可砍项采纳）：`VITE_CASDOOR_REDIRECT_PATH` env、`humanizeOidcCallbackError` 独立文件+专测(内联 CallbackPage)、LoginPage 组件测试(组件测试基建只花在 CallbackPage)、`auth/session.ts` 独立文件。

## 7. 实施步骤（依赖序）

- S1 env 基座：config/auth.ts + vite-env.d.ts + .env.example
- S2 oidc 核心：auth/oidc.ts（UserManager 配置抄 auth-console `oidcConfig.ts`）+ 纯函数 4 件套 + 单测（decodeJwtPayload/userFromCasdoorToken/sanitizeReturnTo/humanizeOidcCallbackError）
- S3 token 层：auth/session.ts(resolveToken) + client.ts 拦截器改造（401 单飞抄 auth-console `client.ts:22-48`）+ 单测（模式分派/R1 残留不发/单飞/重试）
- S4 状态层：useAuth.tsx 双驱动（bootstrap/events/signIn/logout/switchUser no-op）+ hook 级单测（vi.mock UserManager：回调建会话/坏 token 拒/removeUser on failure/bootstrap 不触发静默）
- S5 页面层：CallbackPage + App.tsx 特判 + LoginPage 分叉（returnTo 补 search/hash）
- S6 壳层：AppLayout IdentitySwitcher 模式条件渲染
- S7 构建注入：vite manualChunks + Dockerfile ARG + compose build.args + gateway env 透传
- S8 质量门：`npm run lint`+`typecheck`+`test:run`+`build` 全绿（CI 四步）+ `npm run format`
- S9 手工 e2e（真 Casdoor+网关 casdoor 模式+advertiser enforce；**前置检查**：本次 e2e 所用 origin 必须在 shared app redirectUris 白名单内，Vite 端口被占自动跳 5174 会静默出白名单——评审建议⑦）：E1 首登（dev StrictMode 下验证无双回调错误）/E2 深链/E3 硬刷新/E4 续期/E5 续期失败不复活/E6 登出无残留/E8 state 篡改/E9 code 重放/E11 跨 org 401（验证循环文案）/E12 回滚。**E4/E5 触发手段**（评审建议⑤，token 有效期 168h 等不到自然过期）：手改 sessionStorage 内 `oidc.user:*` 的 `expires_at` 为过去时间（触发续期），再停 Casdoor 容器（触发续期失败）。**回归**：rowner1 页面上只见名下 3 广告主、radmin 可开户人审（enforce 链路不回归）；legacy 四条手工路径（登录/切换/退出/401 跳转）逐字如常
- S10 提交（feat(console) 单层）+ docs 同步

## 8. 测试策略

纯函数优先（console 惯例）：S2/S3 单测；hook 级用 `vi.hoisted`+`vi.mock`（langchain4j 惯例迁移）；组件测仅 CallbackPage/LoginPage 两个关键路径（`render`+`MemoryRouter`，repo 首例组件测试）。手工 e2e 清单见 S9（测试代理 E1-E12 全集，剔除 E7 多标签=非目标）。

## 9. 验收标准

1. 默认构建（无 env）：legacy 四条手工路径（demo 登录/身份切换/退出/401 跳登录）逐一验证如常；现有 3 个测试+新增测试全绿；默认构建产物不含 oidc-client-ts chunk（动态 import 验证，评审建议⑥可证伪化）。
2. `VITE_AUTH_MODE=oidc`+网关 casdoor：S9 手工清单全过；radmin/rowner1 真实登录进 console，广告主后台 enforce 过滤与 403 行为与 curl e2e 一致。
3. CI 四步全绿；`package-lock.json` 同步；无新 ESLint error。
4. R1 单测：oidc 模式残留 legacy token 不被发出。
5. 回滚验证：env 切回 legacy 重构建=完全恢复现状（E12）。

## 10. 风险与回滚

风险矩阵沿用测试代理 R1-R12（缓解已内嵌上文）。Top3：R1 残留 token（resolveToken 分派+初始化清理+单测）、R2 模式错配（前后端开关成对，文档+错误文案）、R3/R4 死会话/幽灵登录（removeUser+空身份拒建会话+单测强断言）。
**回滚**：`VITE_AUTH_MODE=legacy` 重构建 + 网关 `RECSYS_EDGE_CASDOOR=false`，即回现状；oidc 残留在 sessionStorage（关标签即清）且 legacy 不读，零污染；legacy 三键由 legacy `login()` 覆盖写，最坏多一次 401 跳登录。

## 11. 假设与待澄清（不臆造）

- 【A1】Casdoor 对 PKCE public client 发 refresh_token（同 shared app langchain4j 已跑通；E4 复验，失败回退 iframe 方案）。
- 【A2】浏览器可直连 Casdoor token 端点（CORS；两个参考实现先例；E1 复验）。
- 【A3】end_session+post_logout_redirect 可用（auth-console 先例；E6 复验）。
- 【A4】oidc 模式隐藏 demo 身份切换（真实用户不可切身份）；如需"切账号"=登出→Casdoor 重登。
- 【A5】不做前端角色菜单过滤（沿用现状全量菜单+403 兜底）。
- 待产品确认（不阻塞 dev 实施）：生产 Casdoor 域名/HTTPS/回调白名单（上线前登记）。

## 12. v1.1 修订记录（独立评审闭环）

评审结论"需修订后再批"，4 个阻塞项 + 建议/可砍项处置如下：
- **阻塞①** StrictMode 双回调（dev 首登必现）→ CallbackPage 模块级单飞 guard + 测试断言（§2/§6）。
- **阻塞②** 登出顺序 bug（先 removeUser 会丢 id_token_hint，E6 必挂）→ 先 `signoutRedirect()`（库内部自清），失败才兜底手动清（§2）。
- **阻塞③** signIn 契约断链 → 契约扩展：`signIn(u,p)` legacy 专用不动，新增 `signInOidc(returnTo)`/`completeSignIn()`/`mode`；Goal③ 措辞修正为"`user` 消费点零改动"（§3）。
- **阻塞④** 登录/登出状态同步未闭环 → `completeSignIn()` 收口（setUser 后才导航）+ 事件订阅清单（userLoaded/userUnloaded/silentRenewError）+ 401 失败路径驱动 setUser(null)（§2/§3）。
- 事实修正②③：returnTo 缺口在 LoginPage 读取侧（非 RequireAuth）；登出兜底走 `api/nav.ts` holder（AuthProvider 在 Router 外）。
- 建议采纳：①UserManager 惰性工厂+动态 import（legacy 首包零污染）②automaticSilentRenew=false 单实例③AppLayout 登出导航归 useAuth④边界表 +3 场景⑤E4/E5 触发手段⑥验收 9.1 可证伪化⑦e2e origin 白名单前置检查。
- 可砍采纳：REDIRECT_PATH env、manualChunks、humanize 独立文件、LoginPage 组件测试、session.ts 独立文件——共减 2 文件 2 配置项。
