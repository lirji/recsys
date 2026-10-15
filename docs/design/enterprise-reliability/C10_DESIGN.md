# C10：最终混排交付与可见回执

2026-10-15；ER-S10，依赖 S04/S07/S08 已交付，复用 S09。用户已授权实施全计划；本记录落实 PLAN F10 和既有五角色/独立前端审查，不新建路线或服务。默认仅 sandbox，旧 `/feed` API 与 UI 分支兼容。

## 目标与边界

自然/广告只在最终位置签发；混排/频控剔除项无预占、资格或回执。丢响应、准备部分失败、配置和候选变化后仍恢复原操作。自然结果不受广告纯候选故障阻断；准备未知不是预算拒绝。沿用自然图文与广告图文各自已批准政策，CNY/预算日/硬上限均不变。无新收费事件、CPA、真实来源、模型收益或生产部署。

## 取舍

| 问题 | 采用 | 未采用及原因 |
|---|---|---|
| 混排顺序 | 复用纯 `AdMixer`，输入不可变配置快照，先冻结整张计划再 prepare | 先调用旧广告 feed 会提前扣费；先签自然再移位会破坏资格 |
| 广告故障 | 纯候选/频控失败可冻结纯自然；冻结后的未知项保留原位置和 childId，返回 PREPARING 的部分快照 | 重新混排/补位会让原在途准备失去对应条目并改变自然 slot |
| 频控 | ad owner 读 sandbox 接受账本，当日按 Asia/Shanghai 的 acceptedAt 计数，候选过滤无写入 | 旧 Redis 响应计数不是可信曝光；engine 不能跨库读账本 |
| 自然分类 | 新增稳定 kind=FEED，仍使用 nq1 和已批准 natural-text-v1 | 假装独立 RECOMMEND/SEARCH 会混淆最终混排分母；不修改旧二进制码序 |
| 广告位置 | feed 总大小 1～50，广告配置位仅 1～10，沿用 C04 的资格/账本边界 | 不为默认 [2,6,10] 扩大旧票据/资金表范围；超界配置拒绝启用 |
| 页面 | `/feed` 默认关闭新分支；复用自然/广告卡片与各自队列 | 不在纯展示组件全局埋点；不增加导航或第二套采集系统 |

频控是候选时基于已接受事实的准入过滤，不是并发下严格频次预占；并发未曝光资格可以同时通过。硬预算仍由已有 reservation 保证。广告候选最多 10，频控 SQL 每个候选在各自上限处停止计数、一个有界查询；预算拒绝或未接受的预占不计频次。测试覆盖午夜归属与原预算日不同的情况。旧 uplift/control 写入不接此路径；实验控制完整归 S24，不将旧 assignment 当作本片 sandbox 证据。

## HTTP 与恢复

ADMIN `PUT/GET /api/sandbox/feed-deliveries/{operationId}`，engine owner。PUT 严格 JSON `{userId:string,size:integer,scene:string,query:string|null}`；userId 正 long 字符串，size 1～50，scene 非空 <=100，query null 或非空 <=500；不接受 slot、来源、价格、主体、bucket。原主体权限、同 ID 负载冲突、UUID 校验沿用 C08。

响应 `{operationId,trafficClass,state,userId,size,scene,query,policy,bucket,traceId,modelLineage,adDisposition,items}`。SANDBOX，modelLineage=UNVERIFIED；policy 为自然政策。`adDisposition`=NOT_REQUESTED/CANDIDATES_AVAILABLE/NO_ELIGIBLE_ADS/CANDIDATES_UNAVAILABLE，在计划冻结后不变；只是候选阶段结论，不代表费用或本次一定有广告。未冻结为 null；bucket/traceId 为空、items=[]。

`items` 每项 `{slot,kind,natural,ad}`，kind=NATURAL/AD，且仅对应载荷非空：natural 复用 C08 的完整 Entry（含同 slot）；ad 复用 C04 的 Item（含同 slot、childId、冻结 title 和 preparation）。位置从 1 连续且最多 size，itemId 全局唯一，广告位置不超过10。自然资格 kind=FEED、operationId/subject/user/scene/内容摘要/最终slot绑定。广告 preparation=null 为未知/未完成，不能展示创意或采集；QUALIFIED 才能显示签名快照并按原期采集；其他状态显示原因占位，不挪动或补齐后续位置。

PUT COMPLETE=200、PREPARING=202；GET 已存在=200（由 state 区分），未知=404；400/401/403/409/503 使用 `FEED_DELIVERY_<status>`。冻结后 owner 调用失败：保留已落库计划并返回 PREPARING，继续提供自然内容及已确认广告；落库/读取本身失败仍503。冻结前自然计算/内容失败503，不冒充空成功；正常无自然候选允许 COMPLETE 空。新操作开关关闭不影响已有操作恢复/查询。

原请求/首次配置、30秒数据库租约/fence、计划在 engine V003 `engine_feed_delivery`。plan 不超过4MiB；原请求16KiB、配置4KiB；短事务5秒。自然签发取DB毫秒时钟。首次 freeze 把最终自然资格、候选证明和最终位置一次提交；未提交的资格不对外交付。之后只允许原广告 preparation 从 null 到原结果，已有自然、广告身份、位置、政策、期限、分桶不可变；数据库触发器检查原上下文及单调结果，完成记录不可改删。完成条件是每个 AD 都有 preparation；全自然立即完成。失败不延长已签资格。

不持跨 owner/远程调用事务。恢复先 GET child 原结果，未观察到才以原 offer/slot PUT，offer 过期沿用 owner 原结果或持久化 OFFER_EXPIRED。保存结果需自身仍有效的 fence 和旧 plan；旧执行器不能续写或释放新执行器租约。不承诺跨库原子性；暂时未交付的 held 由 S03 原到期回收。

## RPC、配置与兼容

`SandboxDeliveryService.FeedCandidates(CandidateQuery)->CandidateReply` 新增方法，proto 旧字段不改；同进程 `sandboxFeedCandidates` 调相同 owner helper。独立 engine 内部认证要求不变。旧 server UNIMPLEMENTED 或 owner 关闭/频控不可用：仅在冻结前自然降级；不能转调旧收费 API。prepare/get 复用 C04；已存在 gRPC deadline interceptor 保留，不添加叠加重试。

ad owner `recsys.ad.billing.feed-candidates-enabled=false`，启用需要 V007（两条用户/广告、用户/广告主/接受日索引及摘要校验）。频控从既有 `AdProperties.freq` 取启动快照，校验正上限；不用旧 Redis。engine `recsys.feed.delivery-enabled=false`、`admission-enabled=true`；自然 policy/key 显式复用 `recsys.natural` 类型配置。混排启动快照来自既有 AdLoad（enabled/slots/maxAds），每次操作同时冻结广告实验 bucket/reserve，后续变化不改原操作。无 query 或 AdLoad 关闭时不调用广告 owner。

升级顺序：common/behavior 先支持 FEED（旧 RECOMMEND/SEARCH 字节及查询仍兼容），ad V007 + 新 RPC，engine V003 + 新路径，最后仅获授权 sandbox 开关。旧 behavior 未识别 FEED 会拒绝，不会误收；旧自然交付 HTTP 仍只接 RECOMMEND/SEARCH。没有生产切换。本片不自动清理操作/票据；保留期、增长容量沿用待确认边界。

## 页面流程与组件

`FeedConsole` wrapper → LegacyFeedConsole 或 SandboxFeedConsole → ExposureScope（绑定同一身份的广告/自然两队列）→ 原请求表单/恢复状态 → 按固定 slot 的 NaturalExposureCard / SandboxAdCard / pending/rejected 占位 → Trace + HistoryDrawer/ResultDiff。

进入新分支不自动 PUT。用户显式新交付，先保存 feed 专属会话意图（1h/8KiB）再请求；刷新只 GET。pending 禁止新交付和历史重跑；恢复始终原 ID/body；已完成重跑创建新操作。输入编辑不改变旧卡片 user/资格。PREPARING 中的已冻结自然和已准备广告允许采集，未知广告仅占位。按钮/警告区解释恢复动作，费用只用广告回执结果；自然仍标不计费。未知/拒绝/过期不显示“0元成功”。

两条队列沿用各100条/512KiB/2并发/6自动轮；没有第三个无界队列。退出/身份切换清 feed 意图及已有两队列，迟到响应不落UI。历史/对比/Trace 均只读脱敏，遮盖时关闭测量；保留原 slot，不提供客户端排序。窄屏390与桌面1280复用现有布局，长ID折行。默认旧分支保持原行为；AppLayout 新分支隐藏可能误导的全局模拟参数。

## 文件与执行顺序

1. common kind 扩展与旧自然输入约束；ad V007、FeedCandidateRepository/Service/Configuration；proto 新方法、owner/in-process/grpc；对应真实PG和契约测试。
2. engine `feed/FeedDelivery{,Repository,Service,Configuration,Controller}.java`、V003；复用 AdMixer、NaturalDelivery.Content/Entry 与 C04准备。迁移/fence/唯一身份、丢响应与预算拒绝/自然降级、双模式测试。
3. console `api/feedExposure.ts`、新 SandboxFeedConsole、复用提取两种曝光卡片、组合scope；FeedConsole/env/AppLayout/identity；API/队列/UI测试。
4. behavior opt-in 浏览器宿主扩展或新增，仅隔离PG种子；Playwright feed 专项/legacy、脚本和CI。实际可见、混排剔除、恢复原位置、过期/隐藏/历史/身份与大ID；桌面/窄屏/占位截图审查。
5. 窄专项、共享广告/自然回归、完整构建（防 S09 reactor 回归），runbook/RESULT/状态，Git交付；继续 S11。

## 验收与回退

必需：实际 PG 原子冻结/并发领取/过期fence/原主体/同ID改负载；真实 ad owner 只有最终广告持有预算，只有可见回执收费；频控只读已接受账本并过滤；纯候选故障自然降级且原操作恢复不追加广告；prepare丢响应后改候选/配置仍同位置/child/金额/资格；失去fence不能提交；natural FEED资格持久化事实含正确最终位置；两模式行为相同；旧客户端/API兼容。

浏览器：混合列表两个队列分别确认，未达标/hidden/占位/历史无新事实；准备未知保留自然内容与恢复按钮，刷新查询不重算；预算拒绝不移动其他卡；同主体重跑新ID、异主体拒绝、退出清理；窄屏无横向溢出，加载/空/失败明确。机制与浏览器注入分别注明，不把注入响应称作后端真实故障证据。

回退先关闭新建 admission 或页面开关，保留查询、回执、预占回收及原操作恢复。关闭整套bean或回滚到不识别FEED的旧behavior会破坏在途恢复，必须等在途结束后再评估；不删除V003/V007或改已执行迁移。代码回退不能撤销自然事实或广告账本。无真实账户操作。
