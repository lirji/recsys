# EC01 公共错误契约与安全展示

状态：DONE/PASS（本地必需验证），整体错误治理仍IN_PROGRESS，后续EC02～EC08见PLAN。基线ea5fda51876352bc96d74bcfcef06c2273bdab9c，任务分支refactor/unified-error-codes。

## 实现与兼容

`contracts/errors.json`是唯一编辑入口，70个条目生成纯Java ErrorCode和TypeScript字典；生成器只用Python标准库，CI校验重复码、状态默认项、非法文案和生成物漂移。platform新增已存在的common契约依赖，不增加Spring MVC运行依赖。common仍无Web依赖，gateway响应式装配测试通过。

ApiError保留traceId/code/message/path/fields/timestamp及旧字符串工厂；新增枚举入口。GlobalExceptionHandler通用文案取目录，不直接返回IllegalArgumentException.message或ProblemDetail.detail。Spring ErrorResponse实现者的状态/Allow/Retry-After头得到保留。枚举code显式定义，不使用ordinal。

前端共用toApiError返回`[CODE] 安全文案`，既有页面调用点自动采用；403去重通知同步显示代码。已登记的八类业务503保留结果未知、原ID查询/重试提示。未知合规新code显示其代码并按HTTP状态回退；任意字符串/对象/HTML/SQL/堆栈均不展示。非法码和与HTTP状态冲突的已知码回退。traceId受格式/长度约束，URL元数据去掉凭据、查询与片段；网络、超时、取消分别处理，不修改原错误对象。

UNCHANGED：已有HTTP码、通用字符串code、响应字段、字段校验结构、401续期/跳登录、403去重键、取消及回执队列原始异常流。局部控制器和RPC映射本片未迁移；其旧码已经进入目录以兼容先上线的前端。

CHANGED：错误详情不再直接作为用户文案；JSON解析失败400、缺参数400、资源404、方法405、媒体415等框架错误不再被通用处理器吞成500；异常携带200也不能伪造成功。原未知非标准状态仍回退500。未知本地JS错误只显示安全兜底，具体可行动提示的迁移仍由EC07完成，不把此兜底视为全覆盖。

实现依据：Spring 6.1.6的[ErrorResponse接口](https://docs.spring.io/spring-framework/docs/6.1.6/javadoc-api/org/springframework/web/ErrorResponse.html)提供状态与响应头；NoResourceFound等实现该接口，未必继承ErrorResponseException。真实MVC测试验证该分类。

## 验证

- 修改前保护：Servlet/网关16项，0失败错误跳过，日志`/tmp/recsys-ec01-before.log`。
- 修改后专项：36项/6类，其中新增MVC错误契约20项；0失败错误跳过，`/tmp/recsys-ec01-after.log`。
- common/platform/gateway完整测试：71项/13类，0失败错误跳过，`/tmp/recsys-ec01-modules.log`；全仓24模块compile成功，`/tmp/recsys-ec01-compile.log`。
- 前端最终138项/20类（新增错误解析40项）全过，typecheck/build和4个变更TS文件Prettier/ESLint通过，`/tmp/recsys-ec01-frontend-{test,build}.log`。
- 目录3项Python测试含11类非法目录反例及缺失/篡改生成物反例；`--check`与py_compile通过。持久化守卫592生产Java/90XML/45历史SQL通过。
- 首轮前端取消测试暴露DOMException不一定是Error，已修改判断并复验；没有放宽期望。另增加5种非法HTTP状态回归，避免将已有响应误报网络失败。
- Hygiene：IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS，无阻断。TS执行既有Prettier/ESLint；仓库未配置统一Java formatter及独立Java静态分析，不能据TS检查宣称Java已做此类检查。受保护8文件指纹核对不变；根交接只改活跃前缀。

最终13个源码/配置/测试路径指纹（按路径排序，path+NUL+bytes+NUL）：`2747722c0efb27e12899049dfc292acc662a8b14a1fc8803e64d5aeaa013aaaa`。清单`/tmp/recsys-ec01-source-paths.json`，Git提交自身提供持久文件范围。

最终日志SHA256：modules `18c305843afd686b2c237bcac61dfae15b3abbca1ea0ace07000b2b7d8f1b0a4`；compile `ba49c47170a02402f918486b6b9039599c462fb2fc8b36967c72d41b3642d27b`；frontend-test `2dee14305e7ab4ca89c8755abf03c163514315dea968ab6f33d237f40ed6432b`；frontend-build `4b94bcf47a681bd690b6af154518f11e4a6418689d4e7d7683e305a6c9a2c69f`。

## 后续与回退

EC02 READY：统一Servlet安全链和Reactive网关错误边界，保留JWT/角色/租户/WWW-Authenticate语义。EC03～EC08待做，真实浏览器错误矩阵与最终全仓门禁仍须执行。无数据库迁移或业务写入变化，可整体回退本片提交；安全文案回退会恢复旧详情透传风险。当前SQL基线CI38785419045五项已全部SUCCESS，不代表尚未提交的EC01远程CI。
