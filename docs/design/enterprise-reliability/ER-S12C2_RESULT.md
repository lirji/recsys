# ER-S12C2：CPA稳定交付与广告主授权回调

状态：DONE / PASS。本片属于原S12/F11，父S12必需闭环已完成；S13～S25仍按原DAG继续。

## 实现与边界

- engine primary V005保存固定CONVERSION整页/位置/租约fence，adDb V015保存原准备请求/结果。报价/预占分步可恢复，丢响应、暂停新签发、原结果恢复不重定价、不延长五分钟期限；共用账户硬预算。
- 新conversion.v1 RPC及engine ADMIN交付/可见HTTP；可见与转化可任意先到。RPC展示组限定独立engine，业务转化组限定独立advertiser，缺认证/通用caller/错caller/nosec不能绕过。展示gateway没有业务转化方法，不回退旧Redis/outcome。
- advertiser PUT/GET回调每次执行edit/fullyConsistent，包括查询/重试、ADMIN、旧authz disabled/shadow；失去权限403，缺依赖或故障503，身份服务端注入。另一当前有权主体可恢复同业务内容，原actor审计不变。
- 原Outcome决定与当前结算分离；重复资格事件自身始终非计费，原WAITING事件在曝光捕获后可显示自身实收。金额六位字符串/大ID字符串/UTC微秒，token和actor不返回普通状态视图。
- 响应核验：advertiser核对advertiserId/eventId；CPA engine核对childId/receiptId/qualificationId及CPA类型。损坏或身份不符的owner响应保持503未知，不能伪造成功或误判客户端400。
- 新旧三类准备/交付bean共存并使用显式Qualifier。ad-serving仅test scope复用advertiser test-support包与项目既有maven-jar-plugin执行，无新第三方版本、基础设施或生产依赖边；详见TECH_SELECTION。

## 验证证据

最后源码工作树指纹：`12dc4ace6f0df1430185201e3af27f69220dbc777bfcbc2ebd93778dc33d30f3`。算法为排序路径+NUL+原文件内容+NUL的SHA256，31路径保存在`/tmp/recsys-s12c2-source-paths.json`；后续文档更新不影响此源码指纹。

| 验证 | 命令/范围 | 结果 |
|---|---|---|
| 完整钱链路 | `mvn -B -e -pl recsys-rec-engine,recsys-ad-serving,recsys-advertiser,recsys-gateway -am test -Dgroups=money-chain -Dsurefire.failIfNoSpecifiedTests=false` | 355项/67类，0失败/错误/跳过；`/tmp/recsys-s12c2-money.log` |
| 全仓其余验证 | `mvn -B -e verify -DexcludedGroups=money-chain` | 328项/81类，0失败/错误，4个既有按需fixture跳过；`/tmp/recsys-s12c2-nonmoney.log` |
| 最终源码专项 | ConversionPreparationPostgresTest(8)、ConversionDeliveryOperationPostgresTest(8)、AdvertiserConversionAuthzTest(9)、SandboxConversionDelivery/ImpressionControllerTest(6)、ConversionOnlineBoundaryTest(4)、ConversionOnlinePostgresTest(10) | 45项/7类，0失败/错误/跳过；`/tmp/recsys-s12c2-targeted.log` |
| 广告浏览器 | `python3 scripts/test-sandbox-exposure.py` | 4项PASS；真实PG/Kafka；`/tmp/recsys-s12c2-browser-ad.log` |
| 自然浏览器 | `python3 scripts/test-natural-exposure.py` | 5项PASS；真实PG；`/tmp/recsys-s12c2-browser-natural.log` |
| 混排浏览器 | `python3 scripts/test-feed-exposure.py` | 5项PASS；真实PG与双模式交付；`/tmp/recsys-s12c2-browser-feed.log` |
| Code Hygiene | base=f23ca416，批准既有测试边recsys-advertiser和maven-jar-plugin | IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS；`/tmp/recsys-er-s12c2-hygiene.json` |

完整钱链路后按规范检查将两处字面量比较改用既有HttpStatus/BidType枚举；最终45项专项重新编译并验证这版源码。非钱链路4项跳过分别为PostgresIntegrationGateTest和3个专用浏览器宿主；后3项通过上述显式脚本实际执行，不用默认skip当浏览器通过。

真实数据库测试覆盖三类装配共存、原请求冲突/并发/过期/被释放、两条件顺序、资金与Outbox一次效果、跨主体/广告主拒绝、业务提交后丢响应、撤权后不能恢复、另一有权成员恢复、重复转化自身金额null。RPC测试是真实TCP加内部认证拦截器；HTTP为真实Controller/Authz/Client的MockMvc，使用测试认证principal。权限SDK与候选/实验使用隔离替身，不声称真实SpiceDB关系数据或JWT签发端到端验证。

## 审查与限制

- 按C12C2正式契约复核了scope、错误状态、主体来源、锁/事务边界、三模式隔离、恢复与默认关闭。没有旧业务资金调用作为fallback；没有在数据库事务中调用权限依赖或RPC。
- 规范检查初次阻止两处magic比较和未登记插件执行，已修正/登记并重新通过；没有放宽测试、删除断言或绕过门禁。
- 项目未配置统一formatter，沿周边样式；独立Java静态分析未配置。没有为本片引入新工具。
- 内部caller沿现有受控认证凭据；不宣称共用凭据泄漏后的服务级强隔离。终端可见声明不是反作弊完整性证明。容量/RTO/RPO、正式账户期初/来源未获事实证明，不能称生产收费就绪。
- 默认新能力关闭。停新签发/条件用admission开关；保留旧义务查询、到期回收及可靠事件排空。迁移expand-only，旧程序不能接管新义务，回滚须先排空，不能只回退镜像宣称资金恢复。

## 交付与后续

本批基线f23ca416，原目录feat/sandbox-conversion-settlement。全部本地门禁已通过，将按用户持续授权正常提交、合并并推送origin/main；不部署生产。七项用户文件与本地数据保留，不stage用户Compose/运行说明/脚本/PROGRESS.md/C15A草案/console测试输出。

下一步原S13：账户预算日对账、可查询未决、有界检查点与受控续办；不是自动改账。父S12完成不代表整个企业可靠性计划完成。

Git实际结果：已正常提交b8cf737b15ef41b063c97bb4d7146df6a4168947，ff合并并推送origin/main；下一片在原目录feat/sandbox-billing-reconciliation继续。远程CI38705469195全部五门禁SUCCESS，已核对实际job结论。
