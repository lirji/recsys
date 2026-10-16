# CPC／CPA 前端接入设计（ADV-UI-02）

状态：PROPOSED，待最终方案批准。2026-10-16；源码基线 `d787dda0`，文档归档基线 `5649929b`。本文件记录设计与取舍，唯一实施顺序见 [PLAN](PLAN.md)。尚未改产品代码，尚未执行本轮实现验收。

## 目标、角色与业务完成标志

用户要求先归档 ADV-UI-01，再接入已有 C11/C12 新 CPC／CPA 后端。归档已完成；本轮独立接入 SANDBOX 前端，不恢复企业可靠性 S13B～S25。

- ADMIN 在 `/search-ads` 主动创建 CPC/OCPC 或 CPA 测试交付，查看真实资格、报价、可见观测、事件与结算；CPC 主动点击。
- ADMIN 或 ADVERTISER 在明确广告主范围内提交 CPA 测试业务事件或查询原事件。每次由服务端重新验证 advertiser `edit / fullyConsistent`；ADMIN、disabled、shadow 均不绕过。
- 交付 COMPLETE 只代表准备齐全；回执已接受只代表条件成立；只有服务端 CAPTURED、账本和实收字段才代表资格收费。CPA 本事件是否收费另以 `chargedForThisOutcome/chargedAmount` 为准。

非目标：Feed 新计费模式、资金运营/预算编辑/对账、生产收费、竞价/归因/风控规则、全局授权模式修改、新导航或依赖升级。Feed 当前仅有 CPM/自然装配，不假造 CPC/CPA Feed 契约。

## 五维只读审查与事实依据

根 [AGENTS.md](../../../AGENTS.md) 要求的五项审查均已完成：`click_conversion_requirements`（任务/流程）、`click_conversion_ux`（交互）、`click_conversion_architecture`（契约/状态所有权）、`click_conversion_reuse`（约束/复用）、`click_conversion_testing`（测试/风险）。全部只读，无产品改动。独立方案复审结果另见 [REVIEW](REVIEW.md)。

| 事实 | 代码或既有文档依据 |
| --- | --- |
| CPC、CPA 后端已完成；五分钟窗口与两条件顺序已批准 | [C11](../enterprise-reliability/C11_DESIGN.md)、[C12](../enterprise-reliability/C12_DESIGN.md)、[CONTRACTS](../enterprise-reliability/CONTRACTS.md) C11C2/C12C2 |
| 搜索页当前仅 legacy/CPM 整页开关，legacy 点击/转化不是新链路 | `console/src/pages/online/SearchAdsConsole.tsx:405`、`console/src/api/ads.ts:16` |
| 可复用冻结交付与恢复流程 | `console/src/pages/online/SandboxSearchAdsConsole.tsx:41,79,121,164` |
| 既有 ADMIN 范围不能直接承接广告主回调 | `console/src/components/exposure/ExposureScope.tsx:37` |
| 发送前已有真实 token scope/epoch 守卫，身份变化即撤销 | `console/src/api/client.ts:29`、`console/src/exposure/identity.ts:13,31` |
| 旧队列写死可见载荷、按 childId 去重、accepted 后禁止查询 | `console/src/exposure/receiptQueueCore.ts:51,110,167,225` |
| 旧状态组件 ACCEPTED 表示 CPM 已消费，不能套到两条件模式 | `console/src/components/exposure/ReceiptStatus.tsx:32` |
| CPA 强判权不是旧管理权限快照 | `recsys-advertiser/src/main/java/com/recsys/advertiser/authz/AdvertiserAuthz.java:83`、`billing/SandboxConversionController.java:42` |
| 原 WAITING 与当前 CAPTURED 可以同时为真 | `recsys-ad-serving/src/test/java/com/recsys/adserving/grpc/ClickOnlinePostgresTest.java:40`、`ConversionOnlinePostgresTest.java:65` |

## 决策记录

| 决策 | 比较的方案 | 选择及代价 |
| --- | --- | --- |
| 入口 | 新顶层工作台；复用搜索广告与广告主详情 | 复用 `/search-ads` 和 `/advertiser/:id`。无需新菜单/路由，模式选择不发请求，隐藏模式不挂载。 |
| CPA 角色 | 仅 ADMIN 同页闭环；同时覆盖 ADVERTISER | 同页短表单 + 广告主详情内独立面板，共用业务表单/结果组件。额外角色与表单测试换取完整角色覆盖。 |
| CPA 权限提示 | 复用旧 permissions；新严格 capability API；真实提交/查询判权 | 不新增后端 API，不用旧快照宣称已授权。已登录相应角色可填写，清楚标明提交/查询时严格校验；403 后阻止自动再发，允许用户显式重新查询权限已恢复的原事件。 |
| 资格交接 | URL/router state/自动复制；内存与明确人工输入 | 同页表单仅内存传入已知资格；广告主面板允许用户输入从获授权 sandbox 业务系统取得的证明。无票据复制/分享/下载入口，不自动跨账号迁移。 |
| 回执实现 | 泛化旧 ReceiptQueueCore；新增本轮有界队列 | 新增一个四协议队列，保留旧 CPM/natural 核心及其行为；共用 identity、HTTP 守卫、测量 hook。多一点局部代码，减少旧资金链回归面。 |
| 恢复存储 | 全内存；短期会话恢复 | 沿既有 C07：不存 Bearer/refresh token；未确认且发送期限内的原资格载荷短期放同身份 sessionStorage，确认/拒绝/阻断/到期清 token；历史/Trace 一律脱敏。 |
| 结算刷新 | 把 accepted 当全部完成；无限轮询；独立有界 GET | 事件首次决定不改写，独立查当前结算。自动受总预算限制，仍可手动查询，已确认事件不重复 PUT。 |
| 启用 | 替换旧入口；独立默认关闭开关 | 新 CPC/CPA 前端各有默认 false 开关。旧 legacy/CPM 选择规则不变；新模式不降级到旧点击/转化。前端开关不是后端可用性证明。 |

上述可逆 UI 选择是本次建议，尚非用户逐项批准。不存在待重新决策的五分钟计费窗口、真实可见政策或竞价算法。

## 路由、页面流与组件树

`/search-ads` 宿主按现有开关保留原 legacy 或 CPM，按新开关增加 CPC/OCPC、CPA 两个模式。保持该路由现有访问规则，不新增整个搜索页的 ADMIN 路由守卫；新 Sandbox 模式通过 ADMIN ExposureScope 关闭未授权操作，并由服务端执行最终判权。切换模式取消测量和视图请求，不创建交付；只恢复同身份、同模式的原操作查询。

```text
SearchAdsConsole（模式入口，只挂载当前模式）
├─ 原 LegacySearchAdsConsole / SandboxSearchAdsConsole
└─ SandboxActionAdsConsole(mode=click|conversion)
   └─ ExposureScope（ADMIN，绑定新 action queue）
      ├─ PageHeader + Form（userId/query/slots/scene）
      ├─ 原操作栏（新交付、恢复、查询；稳定 operationId）
      ├─ Table（服务端固定 slot；无重排分页）
      │  ├─ ActionAdCreative（真实可见测量）
      │  ├─ ActionReceiptStatus（事件首次决定）
      │  ├─ SettlementStatus（当前资格结算与账本）
      │  └─ CPC 点击 / CPA「提交测试转化」Modal
      │     └─ SandboxConversionForm + ConversionOutcome
      └─ HistoryDrawer / TracePanel（只读、脱敏，不采集）

AdvertiserDetail /advertiser/:id
└─ SandboxConversionPanel（CPA 前端开关启用时显示）
   └─ ConversionActorScope（ADMIN/ADVERTISER；不是 edit 授权证明）
      ├─ 查询原事件（eventId）
      ├─ SandboxConversionForm（qualificationToken + occurredAt）
      └─ ConversionOutcome（事件自身费用 + 资格当前状态）
```

1. 新交付：用户主动提交 → 冻结 mode、operationId 与输入 → PUT；202/超时保留原 ID，GET 确认/原载荷 PUT 恢复，不重新排名补位。只有原操作 COMPLETE 后才开启有意的新交付。
2. CPC：QUALIFIED 创意真实可见生成 VISIBLE；点击按钮显式生成 CLICK，允许任意顺序。两种事件各有稳定 receiptId，双击、StrictMode、恢复不重建事件。CLICK 固定观测 0/0/false。
3. CPA 同页：打开短表单时暂停底层可见计时；展示广告主/资格，eventId 默认生成且首次提交后冻结，occurredAt 默认本次 UTC 毫秒值，提交前可按真实业务输入修改。先转化后可见可通过提交后关闭表单继续真实观测完成，不伪造可见。
4. CPA 广告主面板：当前广告主 ID 来自精确路由；手工输入 opaque 证明与 UTC 时间，或只凭 eventId 查询原结果。用户须自行拥有证明；本轮不提供资格列表、跨身份导出或自动票据移交。输入来源是开放前提，不猜 token 归属。服务器确认归属；同 eventId 原结果可由当前获授权的另一主体显式查询。
5. 提交后锁定原 eventId/advertiserId/token/occurredAt；超时先 GET 原事件，允许条件下原 PUT。字段校验失败/依赖未知保留当前范围输入；401/403/身份切换按下文撤销规则清除证明，保留允许查询的非机密标识，界面说明需重新取得有效证明。新事件须另一次明确动作，不作为冲突/超时的自动恢复。关闭窗口只停止本地等待，不声称撤销业务效果。
6. 同页已知证明不渲染原文；人工输入证明默认使用遮蔽输入、关闭自动完成，不提供票据复制/下载。截图保留真实业务状态但不展示证明原文；表单本身不记入请求历史。

## API 映射（仅使用已存在的真实接口）

以下路径全部由现有服务实现，权威是 C11/C12 与源码；前端适配不得增加金额、subject、风控或接收时间参数。

| 资源 | 方法/路径 | 输入/返回 |
| --- | --- | --- |
| CPC 交付 | PUT/GET `/api/sandbox/click-ad-deliveries/{operationId}` | PUT `{userId:string,query,slots:number,scene}`；200 COMPLETE / 202 PREPARING |
| CPA 交付 | PUT/GET `/api/sandbox/conversion-ad-deliveries/{operationId}` | 同形，独立命名空间 |
| CPC 可见/点击 | PUT/GET `/api/sandbox/click-receipts/{receiptId}` | PUT `{qualificationToken,kind,visibleDurationMs,minVisibleRatioBps,pageVisible}`；返回 `{receipt,settlement}` |
| CPC 当前结算 | GET `/api/sandbox/click-settlements/{qualificationId}` | 完整 settlement |
| CPA 可见 | PUT/GET `/api/sandbox/conversion-impressions/{receiptId}` | PUT 同观测字段但无 kind；返回 `{receipt,settlement}` |
| CPA 当前结算 | GET `/api/sandbox/conversion-settlements/{qualificationId}` | 完整 settlement |
| CPA 业务转化 | PUT/GET `/api/advertiser/{advertiserId}/sandbox-conversions/{eventId}` | PUT 仅 `{qualificationToken,occurredAt}`；返回事件自身 + 精简 settlement |

源码入口：`recsys-rec-engine/.../delivery/Sandbox{ClickDelivery,ClickReceipt,ConversionDelivery,ConversionImpression}Controller.java`，`recsys-advertiser/.../billing/SandboxConversionController.java`。

- 交付输入 userId 正整数文本且 ≤Java long 最大值；query 1～500、scene 1～100、slots 1～10。全程不把 ID 转为 JS Number；不依赖全局数值 userId 填充超过安全整数的 ID。
- 交付返回 operationId/trafficClass/state/userId/query/scene/slots/items；每个 item 有 slot、childId、精确字符串 ad/item/advertiser/creative ID、title、bidType、priceUnit、pricingVersion、preparation。CPC 允许 CPC/OCPC、PER_CLICK/CLICK_GSP_CNY6_V1；CPA 固定 CPA/PER_CONVERSION/CPA_GSP_CNY6_V1。
- preparation 可为 null；字段为 childId/status/amount/qualificationToken/expiresAt。状态 QUALIFIED/INSUFFICIENT_BUDGET/QUOTE_EXPIRED/ACCOUNT_MISSING/OFFER_EXPIRED；非 QUALIFIED 不采集。准备结果不包含当前 settlement，不能猜 budgetDate 或收费。
- 金额是六位十进制 CNY 字符串，原样展示，不求和混合计价单位、不用浮点重算；null、未知、`0.000000` 明确区分。
- 回执 200 或 422 都是有结构业务结果。CPC status 有 ACCEPTED_WAITING/ACCEPTED_CAPTURED/ALREADY_ACCEPTED/POLICY_NOT_MET/FRAUD_REJECTED/EXPIRED/NOT_HELD；CPA visible 无 FRAUD_REJECTED。
- 完整 settlement 包含 qualificationId/decision/budgetDate/quotedAmount/issuedAt/expiresAt/preparedAt/state/progress/version/visibleReceiptId/ledgerId/chargedAmount/closedAt/closeReason；CPC 另有 clickReceiptId，CPA 另有 outcomeId。state=HELD/CAPTURED/RELEASED/EXPIRED/REJECTED；progress 表达等待双条件/可见/点击或转化及终态。version 当前为 JSON number，只接受安全整数用于排序，否则拒绝该响应，不冒称字符串契约。
- CPA 业务结果包含 outcomeId/advertiserId/eventId/qualificationId/status/occurredAt/receivedAt/decidedAt/canonicalOutcomeId/chargedForThisOutcome/chargedAmount，以及精简 settlement(state/progress/budgetDate/quotedAmount/chargedAmount/ledgerId/closedAt)。它无 version，必须串行查同事件/按当前请求序号防旧响应覆盖。
- CPA outcome status=ACCEPTED_WAITING/ACCEPTED_CAPTURED/DUPLICATE_QUALIFICATION/OUTSIDE_ATTRIBUTION/EXPIRED/NOT_HELD。重复资格自身 false/null，即使内层已捕获；原 WAITING 的首次决定不变，后来原事件自身可变 true。occurredAt 必须 UTC Z、最多微秒，重试不可重采时间。
- 所有响应按白名单和关联键校验；operation/mode/child/kind/advertiser/event/qualification 不匹配、未知枚举、收费无账本等不作为成功。人工证明首次返回的 qualificationId 在验明 advertiser/event 后绑定，客户端不解码 opaque token 冒充验证。

## 状态所有权、恢复与资源边界

资金事实归后端；前端拥有表单草稿、不可变发送意图、请求状态、测量状态、带范围的结果缓存。新队列独立管理 CLICK_VISIBLE、CLICK_ACTION、CONVERSION_VISIBLE、CONVERSION_OUTCOME 四类，结算刷新不是再次提交。

- scope 复用 origin/authMode/issuer/sub/tenant，epoch 在切换开始撤销。发送前和接收后双检；同主体正常续期可恢复，换主体/租户即清队列、草稿、历史与内存证明，取消请求。广告主切换不得使用新路径发送旧命令；旧结果不能覆盖新面板。
- 四类合计最多100条/512KiB/保留1小时；事件发送/恢复与结算刷新共用最多2个在途请求、自动最多6轮，指数退避上限30秒。手动查询每记录单飞并受并发限额，不重置自动次数。交付 GET/PUT 每模式单飞，非后台无限轮询。
- 旧 CPM/natural 各自最多2个后台回执，本轮不改；三组后台回执最大6个，并非全站2个。页面交付/其他普通查询不计入这一回执预算。
- 索引为协议+资格+kind，或 advertiserId+eventId；每模式独立交付 intent。不同协议即使用同 UUID 也不能串路由。sessionStorage 结构/长度/时间/范围严格校验，禁止载入 Bearer/任意字段；异常条目不发送。
- 有效原载荷只用于未知结果 GET404 后按原 ID PUT。已确认、拒绝、401/403/409阻断或本地期限到期清 token；仍可手动 GET 原结果。自动预算耗尽保留待确认，不换 ID；本地到期只代表停止发送，不代表服务端 EXPIRED/未收费。
- 交付来源的发送截止取服务端 expiresAt。人工输入 opaque token 无可信到期元数据：不解析 token，未提交草稿仅内存；首次冻结后本地重发期限最多5分钟、短期会话载荷最多同期限，服务端可能更早拒绝。该上限是本地保留/恢复预算，不宣称资格仍有效或续期。原事件 ID 最多保留1小时供查询。
- 已确认 WAITING 停止 PUT，仍允许查资格/原事件。完整 settlement 按 version 防倒退；CPA 精简结果按请求序号。所有缓存带 scope/mode/对象键；对同资格/事件查询串行或丢弃旧请求结果，不能靠组件卸载顺序碰运气。
- 资格 token 不进入 URL/router state/query key/历史/日志/剪贴板/导出；复用 `redactExposureSecrets`、HistoryDrawer/TracePanel 的脱敏输出。测试默认不录制含证明请求的 trace/HAR/body日志，需排错时只保留脱敏摘要；上传CI证据前检查这些载体，页面脱敏不等于网络取证脱敏。临时会话存储只用于前述原载荷，终态与身份切换清除。会话关闭/清存储可能丢待发送事件，不承诺浏览器零丢失。

## 交互状态与展示

| 场景 | 显示与允许动作 |
| --- | --- |
| 未发起/加载/202 | 未发起提示；当前操作 ID 与准备中；原请求恢复/查询，不生成新 ID |
| COMPLETE 空集/准备拒绝 | 真空结果或每行预算不足/账户缺失/过期原因，不显示假 ¥0，不发事件 |
| 等待可见/计时/测量不可用/队列满 | 原因就地提示；测量不支持时不假造 VISIBLE；点击可先发生，不等待可见才能点 |
| 条件已接受 | 分别显示可见/点击或转化首次决定；未结算不显示“消费成功” |
| CAPTURED | 服务端金额、账本 ID、原预算日；零元捕获同样有账本 |
| CPA 重复资格 | 本事件未收费 + 关联资格当前状态分栏；不能混用两层 chargedAmount |
| 401/403 | 身份撤销/权限拒绝；自动提交停止，恢复必须重新通过当前身份与真实服务端判权 |
| 404 | “当前未查询到原结果，或此环境尚未安装该能力”；不断言未执行，不回退 legacy |
| 409/422 | 冲突保留原意图和查询入口；422按持久化业务拒绝显示并停止重发 |
| 503/离线/超时 | 结果待确认、原 ID、查询/有界恢复；错误不冒充未收费 |
| 已关闭/过期/身份变化 | 停止新事件，保留有权限的查询；本地取消不等于业务撤销 |

## 视觉与验收方向

沿现有 Ant Design、`theme/tokens.ts`、`antdTheme.ts`、`ACCENTS.ad`、PageHeader、Alert、Tag、Form、Table；不新增品牌/主题、图库或图表。桌面1440px、窄屏390px均验收；沿表内横滚而非全页溢出，主要按钮换行可见，长 ID 可换行/复制，金额文字保精度。

搜索结果按创意、单次报价、条件、当前结算、动作组织，服务端 slot 顺序固定。CPA 短表单用现有 Modal 焦点管理，状态/错误在弹窗内保留；广告主面板常驻原详情，不挤入编辑广告主弹窗。提交后仍显示结果，不只 Toast 后关闭；关闭回到触发按钮。首个代表路径为 CPC 创建→真实可见/点击→结算，随后复用样式完成 CPA 两角色路径。

浏览器证据必须实际打开 CPA 表单和查询结果、查看截图，并记录键盘/焦点、输入恢复、窄屏、未知/拒绝/零元/重复状态。设计推演不声称用户测试或视觉已通过。
