# ER-S09：自然推荐与搜索可见曝光

2026-10-15；基线 `4b09d44e`，分支 `feat/natural-exposure-console`；[C09 决策与实施约束](C09_DESIGN.md)、[正式契约](CONTRACTS.md)。**DONE / PASS**。仅 sandbox、不计费，构建开关默认关闭；父计划仍未完成。

## 实现与范围复核

- `/recommend`、`/search` 显式新交付与原操作恢复。仅挂载当前开关分支，不调用旧推荐/曝光/点击链路兜底。推荐可选搜索词映射已有 C08 kind，搜索词必填；完整 long 字符串与请求/政策/最终位置/冻结正文校验，不使用后取详情改写已签内容。
- 共享 `ReceiptQueueCore<R>` 保留 S07 有界恢复和身份守卫；广告/自然使用独立协议校验和结果白名单。自然结果只确认 event/canonical receipt/时间，不伪造金额或账本。两队列各 100 条/512KiB/1h/2 并发/6 自动轮，总上界 200 条/1MiB/4 并发。
- `ExposureScope` 组合传入自然队列，默认广告消费者兼容。身份撤销立即清理两队列和三个页面意图，取消在途/拒绝迟到响应；不改同主体续期语义。页面刷新只 GET 原操作，未知用原 ID/body 恢复，不自动新建。
- 自然卡片观测冻结正文，原政策 50%/1s/hidden/pagehide/离屏/遮盖层重置；历史、对比与 Trace 只读脱敏，重跑是新意图。未完成时历史重跑按钮明确禁用。空/准备中用既有 EmptyState 图标和动作提示，窄屏无横向溢出。
- 浏览器宿主在 behavior 的 opt-in 测试类中，test-scope 复用现有 engine 模块；避免把 behavior 包加到 engine 宽扫描测试 classpath。生产依赖不变；无新外部依赖或中间件、无产品迁移。两个 owner 分别使用专属 PG schema/DataSource，真实服务/HTTP/约束；候选和内容来自隔离数据库种子，登录为测试适配器。

## 验收矩阵

| 验收 / 方法 | 结果与证据 |
|---|---|
| 金额/自然结果分型、同 ID 重试、恢复、隔离、上下限、未知结果 | 93 项前端单测 PASS（原 81 + 新 12）；`/tmp/recsys-s09-unit-final.log`。自然覆盖原 canonical、错误事实 ID 拒绝、到期后仅查、两个队列预算和退出；共享广告测试原样通过 |
| 大 ID、原请求/政策/内容完整性、HTTP 路径/422/身份守卫 | API 单测 PASS；真实浏览器确认 user=9223372036854775807、item=9007199254740993，无 JS 数值截断 |
| 自然浏览器与真实 PG | 5 项 PASS，`/tmp/recsys-s09-browser-final.log`；丢 ACK 原 ID 查询、刷新不重交付、编辑表单仍原主体、历史重跑新资格、历史/对比无回执、跨主体 403、搜索空结果与两页面意图隔离；宿主检查每条事实有待发 Outbox，published_at 全空 |
| 真实离屏与 hidden | 独占原生 Chromium/CDP，未覆写 visibilityState；实际离屏/后台零事实，恢复不足一秒仍零，重新连续达标才接受。共享 hook 原有 StrictMode/BFCache/离线/401 续期单测继续通过 |
| 受控异常契约 | 503/202 原参数恢复、未知 policy 不采集、进行中历史重跑禁用。明确是浏览器响应故障注入，不冒充后端自然产生了这些状态 |
| 广告共享消费者回归 | 4 项既有 Playwright + 临时 PG/Kafka 资金最终对账 PASS；`/tmp/recsys-s09-ad-browser-regression.log`。共享队列接口和业务断言未放宽 |
| 默认开关关闭/旧兼容 | 5 项浏览器 PASS（既有登录/预算/报价 3 + 新自然旧路由 2）；`/tmp/recsys-s09-legacy-browser-final.log`。旧 GET 被调用，新 sandbox 未调用；响应夹具仅验证旧 UI 契约 |
| 后端依赖/协议回归 | natural-exposure 全 50 项 PASS、0 跳过，含 23 项真实 PG；`/tmp/recsys-s09-natural-backend.log`。新测试依赖位置及宿主迁移/装配已实际验证 |
| 类型/构建/静态/格式 | typecheck、build PASS；ESLint 0 errors、原有 16 warnings；新增/提取文件已用既有 Prettier 格式化/检查，旧大页面只格式化新增块；未顺手全仓格式化 |
| Code Hygiene | IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS，`/tmp/recsys-er-s09-hygiene.json`；root 无 formatter 的已知限制。状态字面值已复用 DeliveryState；数值 advisory 为已批准队列/政策边界及测试超时，已复核 |

测试先后发现并修正：第一轮旧 API 监听误匹配 Vite `/src/api/*.ts`，改为锚定真实 URL pathname 后全部通过；测试宿主最初放 engine 会引入 behavior 扫描风险，交付前移到 behavior 测试侧并重跑。视觉复核发现空状态缺图标，已复用既有 Ant 图标补齐并重跑。最后状态字面值提取为既有 DeliveryState 常量，语义相同且重跑类型/93 单测/build；不把未运行的浏览器状态组合声明为已覆盖。

## 视觉与交互证据

`recsys-rec-engine/target/natural-browser-evidence/` 内七张截图逐张实际查看：

- `natural-recommend-desktop.png`：1280×900，原用户/内容快照/自然接受分区清晰，编辑后的输入不改响应上下文。
- `natural-search-narrow.png`、`natural-empty-narrow.png`：390×844，表单/卡片自然换行，ID/Trace 折行，实测 document.scrollWidth=390；空结果可重新交付。
- `natural-history.png`、`natural-compare.png`：抽屉/对比弹层，原参数、大 ID 和分数可读；实际打开、选择、对比、Escape 关闭和重跑均有浏览器断言。
- `natural-preparing.png`、`natural-policy-error.png`：202 保留恢复按钮，错误不变空成功、不出现可采集卡片；图标修正后再次查看。

截图用于当前 Ant Design/主题的实际可用性核对，不宣称完整无障碍认证。纯图文内容无外部图片依赖；很长正文可能因实际可见比例不足保持等待，不截断后宣称完整可见。

## 限制、回退与下一步

仅同 JVM 的两 owner HTTP/PG 联调，尚非生产 gateway/OIDC、多进程故障或正式流量验收。自然 Outbox 尚未发布 Kafka（S15）；不宣称 browser 关页零丢失、真人注意力证明或效果提升。真实收费/生产部署仍关闭。操作/事实保留期、CPA 窗口及容量/RTO/RPO 尚待后续决定。

开关与恢复步骤见 [自然曝光 runbook](NATURAL_EXPOSURE_RUNBOOK.md)。关闭新页面不能撤销已提交事实。用户六个既有文件和本地测试/忽略产物保留，不纳入本片；没有新增 worktree。Git 交付后核对远程 CI 并继续 ER-S10 的 feed 最终位置/资格与自然降级，不把 S09 完成当整体完成。

SKILL_HANDOFF: frontend-implementation → implementation-validation → update-progress-docs；slice=ER-S09；status=COMPLETED，gate=PASS；变更及可观察验收均在 C09，生产启用/部署未执行。
