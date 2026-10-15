# ER-S07：sandbox 可见曝光浏览器闭环

2026-10-15；基线 `3955e29f`；分支 `feat/sandbox-exposure-console`；[C07](CONTRACTS.md)。DONE / PASS，范围仅 sandbox 图文 CPM；整体企业可靠性计划未完成。

## 实现与边界

`/search-ads` 通过构建开关显式选择新分支。主动新交付冻结原参数，202/超时保留 operationId；实际创意 IntersectionObserver + 单调时钟测量，同一资格只生成一个稳定 receiptId。有界会话队列先持久化意图，未知先查原 ID，再在期限内重发原 body。金额采用服务端十进制字符串；报价、待确认与已确认消费分开显示。

发送前校验实际 Bearer 的 scope/epoch，401 续期重新校验；退出/跨主体撤销在途和恢复信息，同主体 OIDC 刷新/续期保留。历史、对比和原始 JSON 不采集；复制/下载递归脱敏资格/认证票据。复用现有 Ant Design 和历史/Trace 组件；仅 sandbox 窄屏隐藏重复的全局用户/场景输入，避免身份菜单溢出。不增加 npm 依赖，不修改旧收费后端或资金迁移。

## 验证证据

- `cd console && npm run typecheck && npm run test:run && npm run lint && npm run build`：81 项单测通过；新增 16 项队列/测量/身份/脱敏，另增 1 项 OIDC 刷新/主体事件回归。类型/默认构建通过，ESLint 0 error、16 warning（含新 ExposureScope 的既有模式 Fast Refresh 提示；非运行时失败）。保留既有 React act/Router 未来版本提示，不将其写成零警告。
- `cd console && npx playwright test --output ../recsys-rec-engine/target/legacy-browser-evidence`：3 项既有登录/预算/报价浏览器回归通过。旧登录 smoke 的宽泛按钮匹配命中三个演示账号，改为匹配 ADMIN 或 OIDC 登录入口；没有降低登录可见性断言。
- `python3 scripts/test-sandbox-exposure.py`：4 项浏览器验收、1 项真实宿主最终对账通过；日志 `/tmp/recsys-er-s07-browser-final.log` 及 target 中宿主日志。使用临时 PG 16 / Apache Kafka 3.7.1；生产 C04/C05 Controller、交付/签名/资金/Outbox/投影真实执行。候选种子写入数据库；查询理解/召回模型与测试认证为适配，不冒充生产认证、网关及模型链路。测试类 opt-in，普通 Maven 构建跳过，由独立 CI job 显式运行。

| 验收 | 方法及结果 |
|---|---|
| 低于阈值、未满一秒、hidden/pagehide/卸载 | 四项测量单测含 StrictMode、BFCache 顺序；原生 Chromium 实际离屏和后台标签页保持账本零，恢复不足一秒仍零、达标后计费；PASS |
| 丢 ACK / 刷新 | 真实 PUT 已完成后丢响应，GET 原 receiptId 恢复；刷新不新增 PUT，账本/投影均一笔 ¥0.010001；PASS |
| 受限队列 | 100 条/并发二条、六轮自动重试、过期只查询、原 ID 重试、离线恢复、禁用存储、非法快照、旧 ACK 丢弃；八项队列单测；PASS |
| 认证隔离 | 原表单 userId 编辑后仍原用户；legacy 切主体不恢复；OIDC bootstrap 不误清、同主体续期保留、跨主体立即撤销、发送守卫/续期重试守卫；PASS |
| 无旧收费及历史泄露 | 新分支零旧 GET/点击/转化请求；Trace 与真实下载文件无原票据；历史/对比展示不发新请求；PASS |
| 503 / 202 / 过期 / 空结果 | 单独标识的契约响应注入；始终按原 ID/body 恢复，COMPLETE 后才可新交付；过期/空不显示消费成功；PASS |
| 资金与事件闭环 | 真实 PG ledger、投影逐操作核对；结束时所有 ledger 有 projection、账户消费总额等于账本；PASS |
| 视觉与交互 | 1280×900 桌面及 390×844 窄屏、历史抽屉、只读对比、未知/准备/过期/空；八张当前截图已实际查看，操作恢复/编辑/滚动/下载/打开关闭/Escape/刷新；PASS |

截图位于 `recsys-rec-engine/target/browser-evidence/` 的两类用例目录，文件为 sandbox-confirmed-desktop、sandbox-narrow、sandbox-history、sandbox-compare、sandbox-unknown、sandbox-preparing、sandbox-expired、sandbox-empty.png。桌面保留紧凑表格，窄屏表格横向滚动且文档不溢出，身份菜单可见；抽屉/对比有明确只读范围。截图不包含有效资格票据。

## 发现与修正

- 测试宿主误触 Nacos import 检查：只在隔离宿主关闭；无共享配置修改。测试路由最初误拦 `/src/api/*.ts`，改为仅根 `/api/`。
- OIDC 启动清旧凭据原先连带撤销待确认任务，拆开清理职责；跨主体 loaded 在 React 更新前撤销，补回归。
- pagehide 可能早于 visibilitychange，增加独立挂起状态，pageshow 必须新采样；存储恢复只复制已知字段且验证阶段与结果一致。
- 首次窄屏截图暴露顶栏重复输入溢出，作当前页面最小适配。Ant 按钮中文空格、状态按钮选中后改名、侧栏 1px 分隔线导致测试定位失败，按真实可访问名称和布局修正，未改业务断言。
- 自动卫生检查要求稳定业务码：明确 typed const 的准备/交付/回执/队列状态，HTTP 码复用 Axios HttpStatusCode；无自造全局常量库。Code Hygiene 为 IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS，仅根后端缺 formatter；前端新文件及修改块已用现有 Prettier，既有共享文件未做无关全文件格式化。静态检查和 diff hygiene 通过。

## 限制与继续

客户端可见回执不是真人注意力或反作弊证明；终端关闭、存储删除与保留期限届满可丢失未提交/恢复信息。只在当前浏览器会话内恢复；不承诺生产 RTO/RPO、灾备或吞吐。生产网关/外部 IdP 的浏览器全链路本片未运行，权限边界沿用 S05 独立测试与本片身份守卫证据。真实账户与生产收费开关保持关闭。

[运行及恢复说明](SANDBOX_BUDGET_RUNBOOK.md) 已同步，CI 增加独立浏览器/PG/Kafka 门禁。继续 ER-S08：自然推荐/搜索资格签发与行为 owner 的幂等可见回执；不回到响应即曝光，不把本片完成当全部计划完成。

## CI 补充修复

首个远程 run 38673651376 的全后端构建、money-chain、扫描通过，console-ci 38673651354 通过；浏览器前三场景通过，第四场景在 Linux 下失败。已下载 error-context/trace，确认控件为 `button "loading 新交付" [disabled]`，是加载 SVG 参与可访问名导致原精确定位失败，并非业务放开新操作。

补丁给“新交付/恢复原操作”固定 aria-label，并通过 aria-busy 表达忙碌；保留原精确名称和 disabled 断言，没有放宽测试。补丁本地 typecheck、页面 ESLint、原四场景及真实 PG/Kafka 最终对账再次 PASS（/tmp/recsys-er-s07-a11y-browser.log）。视觉内容/布局未变，当前截图由同一测试重新产生。远程修复 run 需在交付后核对，不能把本地 PASS 当 CI 已恢复。
