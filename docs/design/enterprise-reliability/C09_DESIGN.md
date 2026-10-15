# C09：自然推荐与搜索浏览器接入

ER-S09；依赖 S07/S08 DONE；沿 PLAN 的四路由规划和 REVIEW 的五角色/独立审查执行，不重开总体设计。自然政策由用户明确批准，采用 C08-C。当前仅 /recommend、/search；feed 留 S10，点击/转化留后续契约。

## 目标与边界

显式新交付 → 原操作恢复 → 冻结文本的真实可见测量 → 自然回执 → 唯一自然事实/Outbox。仅 ADMIN sandbox、不计费。默认构建开关 `VITE_NATURAL_EXPOSURE_SANDBOX_ENABLED=false`，关闭保持旧页面。开启不挂载旧请求、旧手动曝光/点击或详情 hook；缺能力不降级调用旧接口。

推荐页空搜索词 kind=RECOMMEND/query=null；有词 kind=SEARCH；搜索页 SEARCH 且词必填。user/item 用十进制字符串，完整正 long 范围。用户主动新交付才创建 UUID；表单编辑不修改已发原请求；刷新只 GET 同身份原操作，未知/202 用原 ID 原 body PUT 恢复，未完成不能新建。两个页面分别保存一个同源 session 意图（不含票据，1h/8KiB）；路径切换不把搜索意图当推荐操作。完整响应须与原请求、SANDBOX、natural-text-v1 对齐才可采集。

## 决策与组件

| 选择 | 理由与代价 |
|---|---|
| 复用 useVisibleExposure 原政策 | 政策元组一致，真实 DOM、hidden/pagehide/离屏重置已有验证；新政策不自动套用 |
| 抽取现有恢复队列算法，两个类型化协议适配器 | 两种回执真实变化点，避免复制幂等/身份恢复算法；自然结果不伪造钱/ledger，广告接口保持兼容并回归 |
| 两个独立有界队列 | 防自然突发挤占广告已知恢复容量；各 100 条/512KiB/2 并发/6 自动轮/1h，总上界 200 条/1MiB/4 并发；在线恢复和身份撤销覆盖两者 |
| 新自然文本卡片，复用 Ant Card/Tag/主题/RecallTags | 旧 ItemCard 依赖 number/后取详情，不将其强转为新资格内容；直接渲染签名冻结 title/category/description，无页面 Mock |
| ExposureScope 组合注入队列 | 默认仍广告，新增自然传入；同一真实 Bearer scope/epoch 守卫不变 |

组件树：路由包装（开关）→ SandboxNaturalConsole(kind) → ExposureScope(naturalQueue) → 原操作表单 + 自然内容卡片(useVisibleExposure) + NaturalReceiptStatus + 只读 TracePanel / HistoryDrawer / ResultDiff。无新导航、依赖或设计体系。桌面卡片正文与状态同行，窄屏自动换行、长 ID/正文折行；测量只覆盖可见的冻结内容区，不覆盖 debug/status。

## 状态与契约

API 原样采用 C08-B，不改后端协议。HTTP 202/PREPARING 与 503 未知保留原操作；400/403/409 错误显示具体状态，不能当空列表；COMPLETE 空为真实无结果。回执 ACCEPTED/ALREADY_ACCEPTED 显示已接受和 canonical ID/time，无金额；POLICY_NOT_MET/EXPIRED 为持久拒绝；超时/404 未知、同 ID 查询/重试，过期后只查。不支持观察/存储失败/队列满明确提示。资格到期不重新生成。完整旧结果仍能恢复显示，已过期不新发。

历史/对比/Trace 均只读脱敏，打开遮盖层停当前采集，关闭重新计时；历史“重跑”明确新交付，复用原参数但生成新 UUID。身份切换立刻撤销队列和两个页面意图、取消请求、丢弃迟到响应；同主体续期保留恢复。自然队列仅保留已知结果字段；成功后去掉票据，不存 Bearer。客户端不能证明真人或保证关页零丢失。

## 文件与执行顺序

1. `api/naturalExposure.ts` 类型/真实路径/响应检查；`exposure/receiptQueueCore.ts` + 广告兼容 wrapper + 自然 wrapper；Scope 与 identity 最小接线。
2. `components/exposure/NaturalReceiptStatus.tsx`、`pages/online/SandboxNaturalConsole.tsx`；原 Recommend/Search 包装，AppLayout 窄屏复用，env 类型/说明。
3. Vitest：两协议结果校验/同 ID 未知恢复/隔离/队列边界、自然契约大 ID/政策/内容/原请求；共享广告回归。
4. 隔离 PG 种子 + 真实 engine/behavior 服务 HTTP 浏览器宿主（测试登录适配器明确标识）；Playwright 实际滚动/hidden/刷新/丢 ACK/历史/切主体/新操作、桌面窄屏与错误空状态截图查看。必要 test-scope 模块复用不变生产依赖/数据库所有权。
5. 类型、格式/lint、测试/build、适用旧页面回归；写 ER-S09_RESULT 和进度后按授权正常 Git 交付，继续 S10。

## 验收、风险与回退

每资格最多一自然事实和一待发 Outbox；恢复/刷新不重算交付，编辑表单不改已签主体；新操作有新资格；隐藏/离屏不足一秒无事实；历史/对比不产生新交付或采集；自然请求不调用旧曝光/收费路径。真实后端验证不冒充生产 gateway/IdP 或 Kafka 投递（S15）。大文本测量区域过高可能无法达到 50%，如实保持等待，不截断内容却仍声称全内容可见。

开关默认 false；回退关闭新页面，服务端继续原结果查询及在途处理；关闭页面不撤销已提交事实。保留既有用户脏文件。浏览器存储有容量/生命周期边界，不能宣称事件零丢失。服务端开关/迁移/生产启用不在本片。
