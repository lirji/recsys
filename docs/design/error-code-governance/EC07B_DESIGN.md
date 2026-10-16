# EC07b 剩余错误展示与实验写入边界

依赖EC06本地验收完成后执行；仍在原PLAN EC07范围。路由、页面结构、业务状态和认证/续期/回执恢复流程不变；这是既有入口错误展示修正，不重开前端架构设计。

只读审计证据：ErrorBoundary把error.message/stack渲染给用户；CallbackPage透传未知OIDC异常；AbSignificancePanel透传原报表错误；ExperimentConsole展示r.reason，后端ExperimentAdminController两处把Redis异常getMessage放进200/ok:false。LoginPage将401/403提示改成无code字符串，tenantSelection保留自由message；若干复制失败/空搜索及SandboxSearchAdsConsole本地拒绝仍未接目录。清单暂存/tmp/recsys-ec07-{frontend,display}-inventory.txt，交付前将最终范围/证据写结果。

## 决策与文件范围

- 复用contracts/errors.json、ClientError、formatError、toApiError；新增必要本地码，已有认证/交付/回执码能准确表达时复用。内部Provider缺失等编程异常不逐个编造业务码，由渲染边界安全兜底。
- ErrorBoundary只展示目录安全提示和原重试按钮；栈不出现在产品页面，开发诊断保留console。CallbackPage保留state/code/grant的安全分类，展示固定码/文案，不展示原异常；原单飞/回跳保持。AbSignificancePanel走toApiError，保留报表失败不阻断放量的说明。
- 实验后端保留HTTP200、ok:false和reason字段，新增code并将reason固定为目录文案，说明写入状态待核对（SET后invalidate也可能失败）。前端只据目录/固定fallback展示，不拼接reason；坏/空ACK不能宣称写成功，逐变体保存保留停止边界、刷新实际结果。原ADMIN授权/Redis键/成功字段不变。涉及rec-engine/experiment/ExperimentAdminController及现有测试、console/api/experiment或types/ExperimentConsole和新增测试。
- LoginPage/useAuth/tenantSelection的用户可见失败接目录，安全区分租户空/配置缺失/不匹配；原租户检查与returnTo验证保持，不把配置名或异常原文当消息来源。
- 复制失败/空搜索、安全交付本地拒绝接目录，保留可用手动复制/原operationId恢复提示。内部队列异常若被Core稳定折叠为UNKNOWN，不能擅改重试或清队列；视实际展示复用安全码。
- 取消请求继续作为取消；通知入口跳过取消，避免错误toast或错误身份操作。认证401原续期一次/失败清会话、403权限提示和身份epoch隔离必须原样验证，不新增写重试。

## 验证、兼容与回退

先现有鉴权/错误/回执前端测试刻画，再补真实页面组件失败/重试/取消与登录回调安全展示；实验HTTP验证缺Redis/读写异常/成功响应及权限。旧reason-only响应与新code响应均可安全展示，code缺失或未知使用固定fallback。响应故障/空body/HTML/未知码/网络/401/403/503浏览器矩阵、截图与防回流及文档归EC07c，仍必须完成。

本片不改模型、SQL/schema、交易语义或部署配置。前端用户页不得显示堆栈/第三方异常；日志诊断和业务推荐reason不是错误展示，按实际职责保留。必要验证通过、结果/进度同步后正常commit/ff/push；回退会恢复泄漏及缺code，不能撤销已发生实验配置写入。持续授权，无需重复确认。

实测补充：新增逐变体失败测试发现快照到达至useEffect同步编辑态之间，保存可能把真实50/50权重当0/0提交。最小修正：编辑态未初始化时读当前快照作为显示/提交共同默认值；保留正常用户编辑与原保存顺序。测试继续要求实际请求50/50，不用等待掩盖该竞态。
