# C14：Sandbox账户日资金运营只读展示

状态：五维只读分析与独立方案复审PASS，无新增阻断；C13C查询契约已于a3bfb3bb交付，实施依赖满足。属于用户于2026-10-16明确恢复的原S14/F13范围；原总体计划已批准，不新增资金操作。

## 目标、边界与决策

在既有 `/advertiser/:id/report` 查询单广告主、单原预算日的权威预算/预占/账户消费/可用额度、账本事实、Outbox积压和投影核对状态。金额全程六位字符串，观察时间、账户版本和投影最后应用时间分别解释。只读、不创建核查任务、不修复/冲正、不新建导航、不启用共享资金；S24实验报告重算不在本片。

五维分析：requirements、UI/UX、frontend architecture、repository constraints/reuse、testing/risks均只读完成。共同事实：旧 `/report` 聚合全期 `ad_event` 且以double消费两位展示；旧requireAdvertiser(view)受disabled/shadow影响；原React Query key仅广告主且切身份只invalidate；AdReportViz缺值转0。这些能力不能充当新账本与严格查看授权。

| 备选 | 结论 |
| --- | --- |
| 改造旧report数组为混合资金对象 | 破坏兼容并混淆统计口径，不采用 |
| 原路由增加独立账户日API/区域 | 复用布局且来源/失败相互独立，采用 |
| 展示C13B历史报告当实时状态 | 历史观察可能陈旧且需额外任务选择流，不采用 |
| 前端拼多个账户/投影查询 | 不能保证同快照且金额易失真，不采用 |

## 路由、组件与流程

保留列表/详情→`/advertiser/:id/report`；URL可携带`budgetDate=YYYY-MM-DD`。默认日期按Asia/Shanghai计算并明确显示；提交后冻结查询日期，草稿编辑不改变已显示快照范围。只有显式开启本片构建flag才挂载新区域。

```text
AdvertiserReport
├─ PageHeader
├─ SandboxBillingReportPanel（独立身份/日期读取）
│  ├─ 预算日输入、查询、刷新
│  ├─ SANDBOX / CNY / Asia/Shanghai 与观察时间
│  ├─ 账户四项金额、版本、资金核对和有方向差额
│  └─ 三族表：账本笔数/金额，待发/DEAD，投影笔数/金额/状态/最后应用时间
└─ 旧事件报表区域（独立加载，明确全期ad_event估计口径）
```

复用AntD Card/Table/Tag/Alert/Button、PageHeader、Skeleton、EmptyState、CollapsibleCard；不引入新状态/图表框架。表格仅容器内横滚，390px查询栏折行，六位金额不截断。

## 正式C14契约（2026-10-16绑定已交付C13C）

新增GET `/api/advertiser/{advertiserId}/sandbox-reconciliation/{budgetDate}`；advertiser经owner RPC读取C13A快照，不跨库。请求正long ID字符串、严格原预算日；固定SANDBOX/CNY。返回显式schemaVersion、scope、observedAt、nullable账户、fundsStatus、nullable差额、固定顺序三族及projectionStatus。金额字符串，ID/版本/计数亦按十进制字符串传到浏览器；不得将内部BigDecimal/long自动转JS number。ACCOUNT_MISSING为成功业务状态，金额null；缺表/依赖故障不能返回空对象。observedAt非全局水位，lastAppliedAt非全历史完成标志。

每次查询强制view/fullyConsistent；匿名401、deny403、依赖故障503；ADMIN/disabled/shadow均不绕过。复用CPA严格守卫的机制，独立命名只读方法，不复用edit权限。功能关闭需明确不可用；错误走统一目录，不透传RPC消息。C13C只读RPC可授权独立advertiser caller，不能因此赋予恢复写接口权限。

前端复用advertiserId精确校验、client.ts的signal和expectedExposureScope/epoch护栏；key绑定完整身份/epoch/advertiserId/预算日。不绑定ConversionActorScope队列。切范围立即隐藏旧数据、不keepPreviousData；401/403或切身份隐藏资金缓存并拒绝迟到响应。刷新保留旧快照时标明旧observedAt，失败不当当前真值。

## 状态与边界

- 首载骨架；账户不存在显示未建立账户，合法零显示0.000000，两者独立。
- 资金BALANCED/MISMATCH/ACCOUNT_MISSING与投影CAUGHT_UP/LAGGING/MISMATCH分别展示。资金平账、投影落后可同时成立；PUBLISHED不证明投影完成。
- 到期未释放HELD仍计预占，另列到期笔数。零额事件仍核对笔数；未知枚举/坏字段作为响应错误，不自动正常。
- 初次失败就近安全错误与重查；刷新失败旧结果必须标陈旧或隐藏；撤权/身份切换立即隐藏。
- 旧事件统计与离线CSV标明来源，未提供的窗口/水位写未知，不编造。仅改口径提示，不扩展离线统计模型。

## 文件级改动与依赖顺序

1. 等S13C owner查询RPC、精确映射及独立caller交付；冻结C14 HTTP/字段/错误目录。
2. advertiser新增billing/SandboxReconciliationController、GrpcSandboxReconciliationClient；AdvertiserAuthz新增严格view守卫；保持原CPA edit语义。统一错误目录按需要生成。
3. console新增api/sandboxReconciliation.ts（类型/严格校验/范围），components/adv/SandboxBillingReportPanel.tsx；若逻辑长度需要再提取专用hook。config/类型/.env.example/Dockerfile声明独立默认false构建开关。
4. AdvertiserReport组合新区域并使旧表独立加载；旧report请求支持signal/身份隔离；AdReportViz仅标真实来源及未知水位。
5. backend真实PG/HTTP/RPC/授权、前端组件、双模式浏览器/截图，现有CI纳入必要窄门禁；同步验收和运行说明后Git交付。

## 测试与接受标准

- 真实三族同账户数据用ConversionEventsFixture/ReconciliationPostgresTest，不能把ClickConversionBrowserFixture的两个独立schema冒充共享账户。API与UI逐字段比对；读取前后账本/预占/Outbox无变化。
- Java：所有authz mode严格view，匿名/无归属ADMIN/撤权/SDK缺失，拒绝先于owner调用；TCP RPC与deadline、非法范围、不可用和精确映射。
- 前端：0.000001/大额/长ID/负差额，加载/空账户/空日/投影落后/差异/错误恢复，切日期/身份/广告主及迟到响应。金额不Number相加，状态不从时间推导。
- 真实权限复用test-click-conversion-authz.py的独占服务和SpiceDB机制，核对实际schema后增加view-only主体、无归属、撤权/服务中断；不能假定关系名viewer或以edit替代view。
- Playwright真实HTTP宿主与PG，对应in-process/grpc两模式；默认关闭零新请求；1440px与390px实际查看截图、键盘查询/刷新、无页面横向溢出。普通路由替身只证明展示契约。
- 验收记录revision、运行命令、通过数、依赖替身边界和截图；必需项未执行不能DONE。

## 风险与回退

主要风险为旧统计冒充账本、观察时间冒充全局水位、金额精度损失和跨身份缓存。以上独立来源、字符串契约、严格授权及身份隔离分别防护。回退关闭新前后端读取入口/恢复兼容代码，保留S13表、报告和资金义务；不删数据、不停止在途结算，不自动生产部署。

独立复审补充：实施前明确前后端开关键/关闭行为、完整DTO/可空及错误映射；响应返回后再次校验身份epoch，不能仅依赖发送前护栏；绑定精确测试/CI入口。均属原范围内收口，无需重开总体规划。

## 实施冻结

- 分支feat/sandbox-reconciliation-report，基线a3bfb3bb，原目录；只处理本片路径。五维及独立计划复审已完成，不重开总体规划。
- 前端构建开关`VITE_SANDBOX_RECONCILIATION_ENABLED=false`；后端`recsys.advertiser.sandbox-reconciliation-enabled=false`与`sandbox-enabled`共同为true才安装controller/client。关闭时无该HTTP映射，404；关闭前端不发新请求。Gateway沿已有advertiser路由转发，无新权限旁路。
- HTTP响应固定schemaVersion=1、trafficClass=SANDBOX、currency=CNY、scope={advertiserId:string,budgetDate:string}、observedAt:ISO UTC、account:nullable{limitAmount,heldAmount,chargedAmount,availableAmount,version}（均string）、fundsStatus、heldDelta/chargedDelta:nullable string、families三族。Family每个字段与C13A同名：全部计数及金额为string，family/projectionStatus为固定枚举，summaryPresent为boolean，oldestPendingAt/lastAppliedAt为nullable ISO UTC。ID/计数/版本不转Number；金额只显示服务端精确值，不在前端相加或重算权威结果。
- 每次HTTP要求已认证ADVERTISER或ADMIN角色且严格view/fullyConsistent；ADMIN也必须有关系。匿名401/拒绝403用统一目录，格式错误RECONCILIATION_INVALID_ARGUMENT/400；授权依赖异常RECONCILIATION_AUTHZ_UNAVAILABLE/503；任意owner RPC拒绝、关闭或不可用统一RECONCILIATION_UNAVAILABLE/503，不能误报终端403；上游坏字段或范围错误RECONCILIATION_RESPONSE_INVALID/503。响应Cache-Control:no-store，错误只取目录。
- 前端异步身份只复用useAuth、resolveToken与identity.ts纯scope/epoch方法，不绑定事件队列；请求发送前及返回后核对scope/epoch。新旧报表查询缓存均带身份/epoch/广告主，新增部分再带预算日；新范围立即清旧显示，错误/刷新期间隐藏旧资金数据，权限拒绝移除当前范围缓存，身份变化撤销旧响应。
- 日期草稿和已提交日期分离，Asia/Shanghai默认今天；预算日写入URL query，非法日期不发送。未建立账户不显示四项零额，三族事实仍展示；表格保留全部核对字段的展开明细；1440与390视口验收，表内横滚。
- 复用C13C2 ReconciliationOnlineFixture的独占单schema三族迁移及原公共资金用例生成测试事实（与C1 ConversionEventsFixture相同资金规则），新增真实HTTP宿主与严格view权限fixture。脚本test-sandbox-reconciliation.py及test-reconciliation-authz.py；Playwright测试进入sandbox-exposure-browser-gate，独占授权外部jar/schema保持显式环境输入，不能伪造默认授权通过。
- 视觉沿当前AntD工作台/主题token，来源/观察时间文案为业务判断信息；无新品牌或第二套组件。新增视图未冒称用户已逐像素审样，仅按已批准范围及既有产品风格实现。

## 实施与验证说明

真实只读路径没有engine调用，因此in-process/grpc两种宿主配置均走相同的advertiser→owner TCP契约；测试准确证明该路径与engine选择无关，不额外声称engine双模式写链覆盖。后者已有S11/S12独立验收。截图实际查看及最终证据见ER-S14_RESULT。
