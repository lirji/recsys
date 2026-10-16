# ADV-UI-02 独立方案审查

日期：2026-10-16。范围：[PLAN](PLAN.md) 与 [FRONTEND_ARCHITECTURE](FRONTEND_ARCHITECTURE.md)。本次审查不执行产品实现、业务写入或测试，不把设计审查 PASS 当实现/运行 PASS。

## 审查方式与结论

最终结论：PASS（方案审查，实施尚未开始）。方案由主 Agent 汇总五维只读分析后撰写；`click_conversion_reuse` 作为未参与方案编写的子 Agent 独立审查，并结合其已核对的源码事实给出结论。初轮无阻塞/高风险，发现一项中等事实错误 M01；主 Agent 修订后，独立审查者复核两个修订点并确认 PASS。

## Findings 与处理

| ID | 级别 | 原问题与场景 | 修订与证据 | 状态 |
| --- | --- | --- | --- | --- |
| M01 | 中 | 架构路由段误称 `/search-ads` 已有 ADMIN 路由约束，实施者可能据此限制整个 legacy 路由 | `console/src/router.tsx:76` 实际无 RequireRole；已改成保持原路由、仅新 Sandbox ExposureScope 关闭无权操作、服务端最终判权，不新增全页守卫 | 独立复核通过，关闭 |
| N01 | 验收细化 | UI/TracePanel脱敏不保证Playwright trace/HAR/网络body证据无资格证明 | A13及架构存储段新增禁录含证明请求或脱敏后归档，上传前核查；不扩大产品功能 | 已采纳 |

复核重点已覆盖：同ID与不可变载荷恢复、404语义、scope/epoch双检、CPA严格判权、WAITING首次决定和当前结算、本事件自身费用、人工证明来源与本地保留期限、统一资源预算、默认关闭及Docker可用性声明。

## 验证范围与剩余限制

- 五维分析与本审查均为只读源码/方案审查；尚无本轮产品代码、UI截图或新CPC/CPA前端测试结果。
- 真PG/TCP gRPC既有后端验证可复用；新浏览器宿主仍需实现，不以旧CPM通过替代。
- strict guard的Mockito测试不证明真实SDK/判权服务/SpiceDB元组。只读Docker清单显示已有共享 `authz-spicedb`，不代表recsys专用授权环境或CPA关系已就绪；新任务需独立测试资源验证，不改共享schema/演示关系。
- 最终方案批准仍待用户。本次根AGENTS要求的是批准后实施；文档归档/审查不改变这一状态，也不恢复暂停的S13B～S25。
