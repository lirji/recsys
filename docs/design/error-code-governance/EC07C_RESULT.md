# EC07c 浏览器矩阵、认证取消、文档和守卫验收

状态：DONE/PASS（本地）；与EC07b共同43源码指纹`a5faabcfc8af49487446c499faf081ebf7df79a5be80dd69bd0901f815cda849`。原PLAN范围内，不重做前端架构；全任务EC08仍待最终门禁。

- Chromium9项：503已知码、未知码、空500、HTML502、网络中断、403均安全显示并刷新恢复；401清除legacy凭据回登录；旧reason-only拒绝和坏ACK不显示成功、刷新配置后可继续；渲染异常只显示UI_RENDER_FAILED，保留重试与修复后刷新。
- 原ErrorBoundary组件测试另证resetKey导航恢复；OIDC回调StrictMode只执行一次，state/code/未知异常/非Error及ClientError安全显示和重新登录保持。
- 完整HTTP适配器验证取消无通知/续期/清会话、续期失败/再次401只清会话一次且最多重发一次；真实silentRenew实现的并发单飞及失败后再续期由UserManager替身验证。已有主体/epoch变化不重发旧回执测试保持。不是外部身份提供方的在线验收。
- 全前端202项/31文件，最终13认证及build通过；真实浏览器9项最终全过。第一次空body预期错误写成INTERNAL_ERROR，已依实际目录默认改INTERNAL_SERVER_ERROR。初次实验通知截图捕获进入动画，加入300ms等待后重跑并实际查看，错误码和核对真实配置提示完整可见。
- 已查看5类截图：安全503桌面/390px窄屏、渲染边界、实验恢复状态及最终实验写失败通知。窄屏scrollWidth<=390无横向溢出。四类原截图在/tmp/recsys-ec07bc-browser-final，最终写失败图在/tmp/recsys-ec07bc-browser-verified；日志/tmp/recsys-ec07bc-browser-verified.log。所有API拦截隔离；Redis真实效果由B后端测试证明，不把UI fixture冒充数据库验收。
- scripts/check_public_errors.py拦截已知JSX/通知直接异常属性与登录/实验/渲染原漏洞形式；7反例/6合法形式及当前源码扫描加入现有目录测试，共10测试通过，CI已有入口同步。它是有界守卫，不宣称全变量别名的数据流分析。内部日志、目录选择器及业务推荐reason保持。
- README链接和docs/14-错误码与故障处理.md已同步HTTP/gRPC/CLI/对象内错误、取消、未知结果及新增目录步骤；目录是唯一权威，不复制256项制造双份来源。

无新运行依赖、SQL/schema/部署。完整资金/自然/混排真实浏览器与最终CI在EC08继续。源码与日志摘要、格式/静态工具限制及手工hygiene见EC07B_RESULT。
