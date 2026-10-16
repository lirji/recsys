# CPC／CPA 前端接入执行方案（ADV-UI-02）

状态：APPROVED / IN_PROGRESS。2026-10-16。用户于本轮明确回复「可以修改」，最终方案已获批准；根 [AGENTS.md](../../../AGENTS.md) 要求五维规划、独立复审及最终方案批准后再改产品代码。本方案是本任务唯一执行顺序；业务/接口/组件/状态细节见 [FRONTEND_ARCHITECTURE](FRONTEND_ARCHITECTURE.md)，审查见 [REVIEW](REVIEW.md)。

## 范围与完成标准

1. 已完成前置归档：ADV-UI-01 最终 CI、Docker、验收写入 [RESULT](../../plans/advertiser-console-fixes/RESULT.md)，`5649929b` 已合并/push main；部署源码仍为 `d787dda0`，未重复部署。
2. 新增搜索广告 CPC/OCPC、CPA Sandbox 交付和两条件状态、结算查询；新增广告主详情 CPA 业务事件提交/查询。沿真实 C11/C12，无新业务后端 API。
3. 不改变现有五分钟窗口、可见政策、原预算日、权限边界、生产开关或 S13B～S25 暂停状态。新增 API helper 不能调用旧 `/api/ad/click`、`/api/ad/conversion`、`/api/ad/outcome` 作为失败回退。
4. 交付完成要求：实现/单测/真实浏览器+隔离PG/gRPC/权限边界/文档/Git均有证据。真实 SpiceDB 环境验收单列，未完成时不能称 CPA 在本机真实授权环境已可用。Docker 更新须绑定最终通过 CI 的源码与不可变镜像，不把编译通过当运行通过。

## 文件级计划

| 路径 | 具体变化 |
| --- | --- |
| `console/src/api/sandboxActions.ts`（新增）及同目录测试 | C11/C12 typed client、白名单校验、200/202/422处理；复用 scope/epoch HTTP 守卫；ID/金额保持字符串 |
| `console/src/config/sandboxActions.ts`（新增）、`.env.example`、`src/vite-env.d.ts` | CPC `VITE_SANDBOX_CLICK_ENABLED=false`、CPA `VITE_SANDBOX_CONVERSION_ENABLED=false`；独立于既有 CPM 开关。命名是本轮拟新增前端配置，不是已存在后端能力查询 |
| `console/src/exposure/actionReceiptQueue.ts`（新增）及测试 | 四协议统一有限队列、冻结意图、同ID恢复、独立结算刷新、白名单短期存储 |
| `console/src/exposure/identity.ts`及已有 identity/redaction 测试 | 新 action queue 与 click/conversion delivery session key 加入撤销；不放宽既有认证范围 |
| `console/src/hooks/useActionSettlement.ts`（新增） | 当前结算 GET，绑定 scope/qualification、响应顺序/版本，与新队列共用请求预算 |
| `console/src/components/exposure/ActionAdCreative.tsx`、`ActionReceiptStatus.tsx`、`SettlementStatus.tsx`（新增） | 复用真实测量与 AntD，事件首次决定/资金当前事实分别呈现；不动旧 CPM ReceiptStatus/队列 |
| `console/src/pages/online/SearchAdsConsole.tsx` | 模式入口，只挂载当前模式，旧模式默认选择行为保持 |
| `console/src/pages/online/SandboxActionAdsConsole.tsx`（新增） | 稳定交付、固定 slot、CPC 点击/CPA 表单入口、只读脱敏历史 |
| `console/src/components/adv/ConversionActorScope.tsx`（新增） | 独立 ADMIN/ADVERTISER scope，沿真实 token/epoch；不改 ExposureScope 的 ADMIN 默认限制 |
| `console/src/components/adv/SandboxConversionPanel.tsx`、`SandboxConversionForm.tsx`、`ConversionOutcome.tsx`（新增）及测试 | 人工证明/UTC 时间输入、冻结 event、查询原事件、权限错误、独立事件消费 |
| `console/src/pages/adv/AdvertiserDetail.tsx` | 插入 CPA 局部分区；不依赖旧 canEdit 证明严格转化权限 |
| `console/Dockerfile`、`console/README.md` | 明确构建期两开关默认 false；使用显式 build args，提供启动/禁用/恢复说明；不改用户脏 Compose |
| `recsys-ad-serving/src/test/java/com/recsys/adserving/grpc/ClickConversionBrowserFixtureTest.java`（新增） | test-only隔离宿主，复用 OnlineFixture 生产 Mapper/事务/TCP gRPC；候选来自独立数据库种子，输出只读对账 |
| `scripts/test-click-conversion-ui.py`、`console/playwright.click-conversion.config.ts`、`console/e2e-sandbox/click-conversion.spec.ts`（新增） | 独占loopback服务、两后端模式、真实浏览器证据、丢ACK与DB断言，检查宿主和浏览器退出码 |
| `recsys-gateway/src/test/java/com/recsys/gateway/security/SandboxGatewaySecurityTest.java` | 将 CPC/CPA GET/PUT 路径纳入既有角色边界参数回归 |
| `.github/workflows/ci.yml` | 独立新 browser job，避免挤满现有15分钟job；实际运行 opt-in宿主并上传证据 |
| 本目录 `IMPLEMENTATION_EVIDENCE.md`、`RESULT.md`（实现时新增）与当前根交接前缀 | 记录真实结果和限制，最终归档Git/CI/镜像；根暂停交接原文不变 |

精确测试文件名可在所在模块既有规范下微调；任何新增产品后端 API、数据库迁移、依赖或跨页面重构均不包含在该清单。公共错误如需新增客户端目录项，沿 `contracts/errors.json` 生成流程，不手改生成物或复用错误的语义码。

## 按依赖执行的三个切片

### ADV-UI-02A：CPC/OCPC 纵向闭环

状态：DONE / PASS，详见 [RESULT](RESULT.md)。

依赖：最终方案批准，已有 C11；分支 `feat/sandbox-click-conversion-ui`，基线 `5649929b`。

1. 先落实新 API 类型/响应关联校验与统一四协议队列的本轮必要基础。先实现 CPC 两事件，按协议定义为后续 CPA 扩展，不预建全局工作流框架。
2. 新 scope key、请求预算、冻结/恢复/撤销/异常处理到位；保留旧队列。CPC VISIBLE 与 CLICK 不共享 receiptId，CLICK 必须 0/0/false。
3. 接入搜索模式、新交付与创意测量；事件确认和当前结算分列。先完成代表路径并查看桌面/窄屏截图。
4. 新测试宿主先覆盖 CPC 两种顺序、一次收费、丢ACK恢复、授权边界；落证据。通过本切片验证后按完整逻辑单元提交，不等待再次询问是否继续。

### ADV-UI-02B：CPA 交付与两角色业务事件

状态：DONE / PASS，见 [RESULT](RESULT.md)。

依赖：02A共享协议基础，已有 C12；同任务分支连续执行。

1. 增加 CPA visible、业务转化与完整/精简 settlement 适配，严格区分自身费用与资格费用。
2. 搜索 CPA 模式内短表单使用当前 ADMIN 身份；广告主详情独立作用域允许 ADMIN/ADVERTISER 输入证明与查询原 event。不得自动迁移展示账号的票据/队列。
3. 单次意图冻结 eventId、advertiserId、token、occurredAt；重试不改时间。手工 opaque token 不解码猜权限，局部载荷保留上限明确；403/503不得显示成功。
4. 同一 production guard + 可控权限替身的隔离资金/HTTP/gRPC用例验证允许/拒绝/撤权/依赖故障；证据明确替身边界。另准备真实权限环境核验（见下）。
5. 扩展真实浏览器与DB断言：先转化/后可见及反序、原 WAITING 后实收、额外event重复资格自身不收费、原内容冲突。完整切片通过后提交并继续。

### ADV-UI-02C：验收、文档与本机交付

依赖：02A/B完成。不恢复企业计划其他切片。

1. 前端全量单测/typecheck/build/相关lint/format，相关后端 money-chain 与 gateway 测试；新浏览器专项分别运行 in-process/gRPC。由于 identity 撤销key有变化，回归既有 CPM/自然/Feed 浏览器。
2. 检查默认关闭与启用构建、历史/Trace/下载脱敏、桌面/390px、键盘焦点、长ID、异常和身份切换；实际查看截图而非仅生成。
3. 真实授权验证：优先读取当前受控权限运行说明与可用实例，使用独立测试资源/广告主和已授权fixture，不改共享演示关系。验证 SDK→判权服务→SpiceDB 的 owner/editor允许、同角色他人拒绝、撤权后PUT/GET拒绝、隔离依赖不可用503。现有 Mockito 只能证明守卫调用与fail-closed，不计此项通过。无法隔离/缺凭据则明确阻塞运行验收，不放宽权限；独立产品实现和其他验证继续。
4. 通过适用 code-hygiene、源码边界/错误目录检查，归档结果与未覆盖项；按持续授权正常提交/合并/push main，核验精确提交CI，不强推、不混入脏文件。
5. 延续本会话已有本机 Docker 目标，代码/CI完成后构建console不可变镜像，记录源码SHA、开关与image ID，保留旧镜像。新的后端逻辑已存在，本轮不更改共享资金/授权开关；当前环境如未安装新能力，默认关闭部署只声明代码已发布。仅在目标环境实际具备所需sandbox/严格权限时启用并声明功能可用。隔离启用验证与现有本机运行状态分开报告，不能把fixture PASS冒充共享环境可用。无生产部署。

## 测试策略与验收矩阵

所有测试数据进隔离数据库，由真实API提供给前端；受控 HTTP 错误/响应注入明确标为 UI模拟，不作为事务/授权证据。fixture不连接共享业务PG/Kafka，不停止共享dev_infra，只清理本轮拥有的临时资源。

| 编号 | 可观察验收 | 证明层级 |
| --- | --- | --- |
| A01 | 缺省两新flags=false，旧模式可用且零新接口；显式启用后新模式不调用旧点击/转化/outcome | 单测+构建+浏览器网络断言 |
| A02 | 精确大ID、六位金额、0/null/未知各异；响应模式/对象错配拒绝，不显示成功 | API/组件单测，真HTTP数据 |
| A03 | 新交付冻结输入，202/丢ACK后原operation查询/恢复；表单改动、刷新不换业务意图 | 队列/页面+真浏览器PG |
| A04 | CPC先点击后可见/先可见后点击均最多一条ledger/outbox，OCPC单位正确；连点/StrictMode不加收费 | 两模式真实浏览器+PG/gRPC |
| A05 | CPA两个顺序均成立；原WAITING后自身变true；额外event DUPLICATE_QUALIFICATION自身false/null而资格可CAPTURED | 两模式真实浏览器+PG/gRPC |
| A06 | eventId/occurredAt/token冻结；同ID原负载恢复；变更原时间409、不自动新ID；错广告主拒绝 | 单测+真实HTTP/PG |
| A07 | 超时/503/离线为未知，GET404不代表未执行；过期后仍能查已提交原结果；422持久化拒绝如实呈现 | 队列测试+受控浏览器错误+真PG案例 |
| A08 | sub/issuer/tenant/authMode/origin/epoch隔离；旧成功/错误/finally不能污染新页；401续期重验scope | 单测+浏览器身份切换 |
| A09 | 四类合计100条/512KiB/1h、并发2/最多6轮；不支持存储/已满明确提示；终态/到期token清除 | 可控时钟队列测试 |
| A10 | hidden/离屏/遮盖/卸载重置，不补造可见；历史/对比/缓存只读展示不采集；原资格恢复可见必须重新实际测量且复用已有事件 | hook+浏览器真实滚动/visibility |
| A11 | ADMIN也须strict edit，disabled/shadow不能绕过；两角色可查原业务event；撤权403与SDK故障503 | 生产guard+替身测试；真实SpiceDB另列必要运行验收 |
| A12 | 两层金额不混淆；CAPTURED零元也有账本；原budgetDate展示；钱链家族互不污染，账户charged与账本一致 | 组件+真实隔离PG对账 |
| A13 | token不进URL/router state/history/Trace/日志/下载/Bearer业务存储；人工证明不自动跨身份；Playwright trace/HAR/body日志禁录或脱敏后才归档 | 脱敏/身份测试+浏览器实际输出+CI证据扫描 |
| A14 | 1440/390布局、长ID、等待/拒绝/未知、弹窗错误保留、键盘焦点返回均可用 | 当前截图实际查看+交互记录 |
| A15 | 旧CPM/自然/Feed与广告后台五项修复回归，无新依赖/迁移/范围泄漏 | 既有用例、diff、边界检查 |
| A16 | 最终源码CI、Git、镜像、健康/HTTP/UI与功能启用状态可追溯；不可用环境不报成功 | RESULT与本机部署证据 |

主要命令（实施后执行，当前不是已通过记录）：

```bash
# console/ 内
npm run typecheck
npm run test:run
npm run build
# 使用本次精确文件清单运行 ESLint/Prettier；全量 lint 按 CI 既有规则。
```

```bash
# 仓库根目录
mvn -B -pl recsys-rec-engine,recsys-ad-serving,recsys-advertiser,recsys-gateway -am test -Dgroups=money-chain -Dsurefire.failIfNoSpecifiedTests=false
python3 scripts/test-click-conversion-ui.py
python3 scripts/test-sandbox-exposure.py
python3 scripts/test-natural-exposure.py
python3 scripts/test-feed-exposure.py
python3 scripts/check_persistence_boundaries.py
python3 scripts/generate_error_codes.py --check
python3 scripts/check_public_errors.py
```

新脚本是本轮待实现路径；两模式都实际启动，缺浏览器/容器或宿主失败即不通过，不能通过skip宣称完成。相关Java test-only新增注释说明隔离/失败窗口；若创建fixture表，表和字段同样写中文注释。

## 风险、回退与交付保护

- **重复扣费/串资格**：按协议/对象固定ID、冻结载荷、响应关联校验、真DB唯一账本断言；任何新模式不回退legacy。
- **误报费用**：首次决定/当前资格/事件自身三层分开；未知不当零，客户端时钟不决定资金终态。
- **授权误用**：不从旧permissions、ADMIN或持有token推断edit；未知/拒绝不自动重发。真实权限环境缺失如实阻塞可用性声明。
- **旧曝光回归**：不改旧队列/测量核心；新增 identity key 做既有三套浏览器回归。新旧后台预算分别列明。
- **开关与Docker漂移**：构建期开关写入证据，后端是否安装/准入另核对；404不能冒称关闭。回退console镜像前保留待确认ID，并查询可能已经提交的原业务结果；关闭前端仅停止新增UI事件，不撤销已发生费用，不停止后端在途恢复。
- **回退**：关新UI开关或恢复旧console不可变镜像；无产品数据库迁移。已发送事件只可查询/按后端既有机制处理，不承诺镜像回退撤销费用；不清空数据库/队列或重置Git。
- **用户改动**：保护根交接暂停后缀及既有7项文件、`console/test-results/`；只stage本任务路径。原目录单任务分支，无新worktree、无强制清理。最终检查未提交/未跟踪/忽略证据保留原因。

## 批准记录

用户于本轮明确回复「可以修改」，批准最终方案。按02A→02B→02C连续推进，不再按子任务请求“继续”。如果实际契约发生变更、需要新增资金操作或缺必要权限环境，再记录具体问题并请求所缺信息。
