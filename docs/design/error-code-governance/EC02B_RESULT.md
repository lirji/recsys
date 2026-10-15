# EC02b 响应式网关错误边界

状态DONE/PASS（本地验证），依赖目录/前端修复提交8ff3c2d21eb93d4b086b3bc432f1acb1b94ebd34；两笔相邻正常交付。EC02a已在9198410f交付，父EC02本地验收完整，整体错误治理仍IN_PROGRESS。

## 行为与结构

GatewayErrorResponses集中序列化ApiError。GatewaySecurityErrorHandler复用Spring Security 6.2.4 Bearer入口/拒绝处理器的状态、机器错误码和scope；认证异常只保留受限error code、原状态与scope，动态description/uri不再回显。匿名401、invalid_request400、invalid_token401、角色不足403、有效JWT及关闭安全时permit-all保持。

GatewayExceptionHandler处理框架ErrorResponse、PublicErrorException与未知异常，保留HTTP状态/必要响应头，对外仅安全文案。common的PublicErrorException只接受目录码与内部cause，没有任意展示message入口；Servlet同步支持，纯契约层不依赖Web。登录无效、签名配置失败、身份传播失败分别有稳定码；内部签发失败终止转发，不能变成匿名继续。

EmptyGatewayErrorBodyFilter在setComplete前补齐空错误体，覆盖实际RequestRateLimiter的429及空key403。已调用writeWith/writeAndFlushWith的下游业务体保持；HEAD没有实体，已提交响应传播原异常，不追加JSON。此适配没有缓存/解析下游body，没有修改Redis限流决策或资金事务。

有意变化：原网关默认错误体/空拒绝体改为统一字段；认证头移除动态内部详情；登录签发失败保留内部cause并使用受控500码。兼容不变量：状态、Bearer机器分类、WWW-Authenticate（安全字段）、Allow/Retry-After/限流响应头、成功登录结构、角色/租户/JWT规则、原业务体均保持。

## 验证证据

- 修改前：新增GatewayProtocolCompatibilityTest在旧产品实现实跑4项，保护401/400/403/Bearer和实际限流器429不转发，`/tmp/recsys-ec02b-before.log`。
- 修改后：common/platform/gateway完整88项/16类，0失败错误跳过，`/tmp/recsys-ec02b-after-final.log`。包含实际GatewayApplication+Netty随机端口HTTP、登录/JWT角色校验、签发器故障注入、框架404/405/415/400、429/502/503/504、已有业务体、HEAD、原始响应已提交、身份签发失败不转发等。
- 限流决策测试使用实际RequestRateLimiterGatewayFilterFactory与受控RateLimiter返回值，验证响应链与拒绝不转发；没有将此测试宣称为真实Redis算法验收。CASDoor外部JWKS未在本片新增验收，原owner/audience/groups测试保持。
- 全仓24模块compile成功；前端139项/20类、typecheck/build、受影响TS的Prettier/ESLint通过。目录3项反例/漂移检查通过，597生产Java/90XML/45历史SQL守卫通过。
- 三套真实Chromium：自然5、混排5、广告4全部通过；各独占真实后端宿主1项通过。广告含账本、Outbox/Kafka投影及资金对账，自然/混排包含真实PG唯一事实及Outbox。浏览器旧提示回归由相邻EC07a修复，原ID/原参数恢复和未知政策不采集仍通过。
- 首轮新增测试误用当前AssertJ不存在的hasNullValue，改为读取AtomicReference后isNull，未放宽“不转发”断言。最终编译与全模块测试已通过。
- Hygiene无阻断，IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS；Java统一formatter与独立Java静态分析未配置。TS检查只代表受影响TS，不伪称Java静态分析通过。

本片15源清单`/tmp/recsys-ec02b-gateway-source-paths.json`，指纹`3d20e3e88597ab64047234539271a01b64d87aecd7fb8f67c0ce4780a2e2cb97`。与EC07a联合25源指纹`9c088cba3be9fa6710cd31aced9445c8f910717b92d2c3adf800451aa46b4a84`，验证后没有修改这些源文件。

日志SHA256：after-final `ef092751d0f87598a898cc279654346cf714de5d1220444ede966d324fcded33`；compile `111afba1a37268fe04c6dc9f65a63455bbe1d6fad995a24133c9593a01e6465f`；frontend-final `c9c197456c07db1e6dac3198d13523ea781462cbdc3706819c67aec1ea36cf45`；browser-natural `f42a236016ee345519e8f80de633420d905620e0b1b689d32604abee87e34564`；browser-feed `964abd44d1d198dd3111e7ed3e2e56c383a554b9d97c1d9cf139cc328512d832`；browser-ad `2217fca2f250b78882d5ff3a5d49d80b02e552fe75c5b79fcf2b3c0eeeab3f14`。日志均以`/tmp/recsys-ec02b-`开头，三个后端宿主另已复制为natural/feed/sandbox-backend.log。

## 后续与回退

下一步EC03：局部业务HTTP错误码映射与重复文案提取；EC04～EC08仍必需。当前远程9198410f CI38788884133包含已修复的自然提示失败，不能记成功；新提交的远程状态须单独核验。仅回退EC02b可恢复旧网关行为但保留前端受控提示修复；如回退目录提交，须先回退依赖它的EC02b。不涉及数据库/数据回退。
