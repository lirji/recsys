# ADV-UI-02 独立方案审查

日期：2026-10-16。范围：[PLAN](PLAN.md) 与 [FRONTEND_ARCHITECTURE](FRONTEND_ARCHITECTURE.md)。本次审查不执行产品实现、业务写入或测试，不把设计审查 PASS 当实现/运行 PASS。

## 审查方式与结论

最终结论：PASS（方案审查时实施尚未开始；用户随后批准，当前实现与验收见 [RESULT](RESULT.md)）。方案由主 Agent 汇总五维只读分析后撰写；`click_conversion_reuse` 作为未参与方案编写的子 Agent 独立审查，并结合其已核对的源码事实给出结论。初轮无阻塞/高风险，发现一项中等事实错误 M01；主 Agent 修订后，独立审查者复核两个修订点并确认 PASS。

## Findings 与处理

| ID | 级别 | 原问题与场景 | 修订与证据 | 状态 |
| --- | --- | --- | --- | --- |
| M01 | 中 | 架构路由段误称 `/search-ads` 已有 ADMIN 路由约束，实施者可能据此限制整个 legacy 路由 | `console/src/router.tsx:76` 实际无 RequireRole；已改成保持原路由、仅新 Sandbox ExposureScope 关闭无权操作、服务端最终判权，不新增全页守卫 | 独立复核通过，关闭 |
| N01 | 验收细化 | UI/TracePanel脱敏不保证Playwright trace/HAR/网络body证据无资格证明 | A13及架构存储段新增禁录含证明请求或脱敏后归档，上传前核查；不扩大产品功能 | 已采纳 |

复核重点已覆盖：同ID与不可变载荷恢复、404语义、scope/epoch双检、CPA严格判权、WAITING首次决定和当前结算、本事件自身费用、人工证明来源与本地保留期限、统一资源预算、默认关闭及Docker可用性声明。

## 验证范围与剩余限制

- 五维分析与本审查均为只读源码/方案审查；审查当时尚无本轮实现证据；后续实际结果见 RESULT，不由本方案审查背书。
- 真PG/TCP gRPC既有后端验证可复用；新浏览器宿主仍需实现，不以旧CPM通过替代。
- strict guard的Mockito测试不证明真实SDK/判权服务/SpiceDB元组。只读Docker清单显示已有共享 `authz-spicedb`，不代表recsys专用授权环境或CPA关系已就绪；新任务需独立测试资源验证，不改共享schema/演示关系。
- 最终方案随后已获用户「可以修改」批准，本任务已进入实施；文档归档/审查不改变这一状态，也不恢复暂停的S13B～S25。

## 实施复核（02A／02B）

主Agent按已批准契约执行实现验收复核；这部分是源码/证据复核，不宣称又完成一次独立架构审查。检查稳定ID及不可变载荷、四类队列预算、scope/epoch与迟到响应、两种结算结构和自身费用、权限失败关闭、真实可见测量、证据脱敏。没有未关闭的实现阻断项。

失败检查与修复：CPC窄屏顶栏重复字段、CPA表单焦点与查询按钮可访问名称、夹具绝对RPC deadline、同event换内容、Python启动/清理异常只吞掉的证据缺口均已处理并通过相应回归；没有通过降低金额/权限断言关闭问题。390px resize须等待布局再拍摄，属于取证时序修正。

真实 SDK→权限服务→SpiceDB 已在独占测试资源验证，边界详见 RESULT。共享Docker的资金/授权配置未启用新能力，发布保持两flag=false；这是当前运行限制，不将隔离fixture成功描述为共享环境已可收费。既有17条ESLint warning（含新context导出1条）与未配置Java统一formatter/独立静态分析如实保留，没有新增依赖或迁移。
