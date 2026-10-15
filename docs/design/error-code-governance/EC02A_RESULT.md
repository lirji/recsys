# EC02a Servlet安全链错误序列化

本地DONE/PASS；EC02b Reactive网关仍READY，父EC02仍IN_PROGRESS。基线ca62a933de420d9257ce89ecc918d28affed0124。

InternalSecurityConfig使用共享ErrorCode与ApiError，并由服务现有ObjectMapper输出JSON，去除重复MDC/手写字符串转义实现。401继续UNAUTHENTICATED，403继续FORBIDDEN，状态/路径/traceId/时间戳保持。控制字符现在能被正确转义并解析，为有意修复；认证规则、令牌验证、角色边界和nosec语义保持。

platform显式声明原已传递的jackson-databind，实测仍为Boot管理的2.15.4，无版本升级。common无新增依赖，gateway响应式装配与安全链测试通过。没有数据或SQL变更。

验证：修改前复用EC01的完整71项模块测试；修改后common/platform/gateway共72项/13类，0失败错误跳过。新MDC控制字符往返测试覆盖引号、反斜线、换行、制表符和U+0001；匿名/篡改令牌401与越权403追加真实JSON断言。全仓24模块compile通过；dependency:tree验证jackson-databind:2.15.4:compile。Hygiene无阻断，IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS，统一Java formatter与独立Java静态分析未配置。

三个源文件：platform/pom.xml、InternalSecurityConfig.java、ServletSecuritySmokeTest.java；指纹`703a87c5bac5ac119b936dc51158983a07c139d5166607c0f34fc37c571ab1c8`，完整路径清单`/tmp/recsys-ec02a-source-paths.json`。

日志：`/tmp/recsys-ec02a-modules.log` SHA256 `4ecfdecb3360ab861ab8a0c74a7dcb207b771d53efc3ed20a779c712f9732ccd`；compile日志SHA256 `cdc21e4df9b65a5dd01bd7fa94d264552f6a8e240f64e046344a20c9152dc220`；另有dependency/hygiene日志。

可整体回退该批提交，无数据库迁移。继续EC02b，不能因Servlet通过而宣称网关错误体、限流429或全部业务错误已统一。
