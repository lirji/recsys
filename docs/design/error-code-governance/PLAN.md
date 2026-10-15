# 统一错误码、错误展示与魔法值治理

状态：IMPLEMENTING，依据用户追加目标及MyBatis迁移PLAN第10节持续授权执行，不再请求重复确认。基线`ea5fda51876352bc96d74bcfcef06c2273bdab9c`；独立分支`refactor/unified-error-codes`，原目录唯一worktree。

## 目标与约束

全项目用户可见错误提示进入稳定代码和安全文案目录，覆盖Servlet全局处理、安全链、Reactive网关、业务局部HTTP/gRPC边界、前端提示，以及离线/流式作业的失败与降级。减少错误码/状态/重复业务边界中的魔法字符串和数字。完成标准是实际失败链路使用目录并展示代码，不能只新增未使用的枚举或只改一个全局处理器。

保留HTTP/gRPC状态、已发布的字符串code、响应字段、幂等ID/查询恢复、认证续期与租户/主体边界。故意修复内部详情泄漏、假成功、缺检查仍通过和大ID失真时单独记录行为变化并验证。正常业务状态（如WAITING/REJECTED/202）不伪装成系统异常。不调整资金算法、事务传播、数据库schema、Redis原子性或暂停的企业可靠性任务；不机械提取每一个数字。

## 现状与决策

已有ApiError(traceId/code/message/path/fields/timestamp)，GlobalExceptionHandler重复通用文案并透传部分异常detail/message；11个业务控制器各有局部映射。gateway安全链仍默认响应，servlet手写JSON。toApiError丢弃code/traceId且可展示任意字符串/对象，约30个页面共用它。gRPC多条边界已具有安全恢复提示，必须保留其语义。

选择版本化错误目录`contracts/errors.json`为代码/文案的单一来源，标准库生成器生成纯Java枚举和TypeScript字典，CI检查生成物一致性与唯一性。common承载纯契约（不依赖Spring/Jackson），platform/gateway负责HTTP适配，业务模块保留状态分类。避免后端/前端手工维护两份目录；不新增运行时配置服务或错误目录接口。公开错误码使用显式字符串，禁止ordinal；既有code作为兼容别名保留，新增码按领域命名。

前端仅采用目录内的安全文案，对未知/畸形/HTML/SQL/堆栈响应使用安全fallback；统一展示“[错误码] 提示”，可保留受约束traceId用于排查。不把未经信任的message字段当展示来源。逐步迁移时旧后端/新前端和新后端/旧前端仍按原HTTP状态工作；未知新code在旧前端安全降级。

## 依赖有序实施切片

| 切片 | 文件范围与动作 | 验收 |
|---|---|---|
| EC01 | contracts/errors.json；scripts/generate_error_codes.py；common/error/ErrorCode；platform pom/ApiError/GlobalExceptionHandler；console/api/client.ts及error解析/生成字典；对应测试 | 保留旧字段/状态/代码；未知异常不泄漏；HTTP安全提示带code，当前页面共用入口实际生效；目录双端一致 |
| EC02 | platform/security/InternalSecurityConfig及gateway安全/Reactive错误处理、AuthController、身份传播失败路径 | 401/403/404/429/5xx统一结构/安全文案，JSON正确转义；原JWT/续期/权限和状态不变 |
| EC03 | engine delivery/exposure/feed、behavior NaturalReceipt、advertiser预算/转化局部ExceptionHandler及目录条目 | 11个控制器各自码族保持；503未知结果/原ID查询提示准确；状态分类、回滚和跨主体拒绝保持 |
| EC04 | ad-serving grpc、engine/advertiser gRPC客户端与业务校验出错入口、console/content/user等其余公开边界 | 明确业务错误码/文案映射；不透传任意RPC/SQL原因；真实TCP与HTTP对应，配置错误不混成用户权限失败 |
| EC05 | offline JobRunner及各Job用户可见失败/降级；streaming类目加载；公共作业错误目录 | 提取失败提示与有意义常量；未知/部分结果不能显示全成功；逐Job或共用路径小批验证，保留非用户诊断日志 |
| EC06 | DataQuality检查状态、AdReport未知计数、Attribution long身份、AdCalibrate前置校验；Quality/Bandit/FTRL/Multimodal/Ocpc发布或降级提示 | 修复已证实错误提示/类型问题；有意行为变化独立记录；真实PG/Redis故障和输出验证，禁止引入隐式重试或谎称多键原子 |
| EC07 | 前端共用错误展示的遗漏入口/取消与认证交互、真实浏览器失败矩阵、接口/CLI错误目录文档与防回流检查 | 页面实际显示code和安全文案，未知code/无body/网络/取消/401/403/503均明确，原回执恢复保持 |
| EC08 | 全仓验证、钱/自然门禁、三浏览器、目录和残留审查、文档/Git/最新CI | 逐条完成审计，全部用户可见错误入口有证据；目标不能在EC01或仅新增目录后标完成 |

每片先保护现有状态/边界，再实现新期望；失败先修当前片，所有本地Maven/浏览器脚本串行。依赖保持原版本，仅platform增加既有common契约依赖；生成器仅Python标准库，不加Web运行依赖到common/offline/Flink。接口码新增与未知fallback是兼容扩展，安全文案替换/假成功修复属于显式行为改进。

## 验证与发布

基线复用MP15：1049项/202类全仓、资金384、自然75、浏览器4+5+5、前端98均过；4按需skip已补跑。每片必要专项、源码指纹、hygiene和diff审查；最终完整门禁复验。正常任务提交/ff/push main已授权，不强推、不部署。保护原8未提交文件及暂停交接后缀；代码回退不代表撤销已发生的业务效果。
