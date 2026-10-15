# EC04b：sandbox 客户端错误与兼容提示

基线 bf078299023ec5d4f2bd4036b9648cf4eee26667；继续已批准父 EC04。本片覆盖三个 sandbox RPC 客户端、必要的内部调用方区分、预算 HTTP 提示与共用前端解析。普通 AdServing/Content/User RPC 和 console 正常对象内错误由后续 EC04c/d 完成，不缩减范围。

## 证据与决策

- GrpcSandboxBudgetClient 的原 commandId 恢复提示被 GlobalExceptionHandler 与前端的安全泛化丢失；只提取客户端字符串不能完成用户可见闭环。
- GrpcAdServingGateway 多处原样抛出 RPC 异常，其他客户端重复固定文案；内部认证失败被预算/转化客户端当成用户403。
- 当前公开预算错误使用 HTTP 状态名作为 code，需要保留其 code 和响应字段，同时恢复目录内的上下文说明。

目录条目允许显式 wireCode 兼容引用：唯一的目录 code 表示受控文案，wireCode 引用同一HTTP状态的既有非别名条目。仅新预算上下文条目使用它；禁止循环、跨状态、无HTTP状态和默认项别名。Java生成 wireCode()（原条目等于code()），ApiError.of(ErrorCode)采用wireCode；TypeScript生成按旧wire code分组的候选文案。前端只在实际状态、旧code和完整文案均匹配目录候选时选用本地目录文本，任意/附加/畸形message仍不展示；响应文本只作精确选择器，不作展示源。

platform提供仅接受ErrorCode的PublicHttpException（继承ResponseStatusException保留既有调用方类型契约）；全局处理器只从其目录码构造ApiError，忽略可变ProblemDetail或任意cause。其他ResponseStatusException继续安全泛化。预算客户端使用有wireCode的目录项，转化使用原CONVERSION码族；现有局部Controller仍识别ResponseStatusException，不将已知400变503。

内部gRPC认证只验证服务身份，不是HTTP用户身份。UNAUTHENTICATED在预算/转化客户端改为依赖503；PERMISSION_DENIED只有带明确内部caller码时改依赖503，领域证明失败仍403，旧/未知/不匹配metadata保持原分类。四个engine服务的caller拒绝新增RPC_INTERNAL_CALLER_FORBIDDEN，预算保留已有码RPC_BUDGET_CALLER_FORBIDDEN。客户端仅识别单一允许码且匹配Status，不透传对端描述或任意metadata。旧客户端仍按原Status工作；新客户端对未携带caller码的旧服务保持原PERMISSION_DENIED分类。

engine保留已有领域异常类型、NOT_FOUND查询空值和写入未知语义，其他RPC错误经GrpcErrors重建安全文本；输入校验与响应解码失败不能互相混淆。现有CPA响应格式错误继续503；若补齐其他族的同类解码错误分类，作为CHANGED明确测试，不修改资金或返回身份校验规则。

## 兼容与验证

UNCHANGED：成功/已持久化拒绝对象、HTTP字段与既有code、gRPC状态、幂等/查询ID、资金算法、事务/SQL/重试策略。CHANGED：内部服务身份失败从用户权限错误纠正为依赖失败；预算安全恢复文案在HTTP和前端恢复；未知RPC原描述不再透传。别名扩展支持新旧前后端共存：旧前端仍安全使用旧code默认文案，新前端可展示目录内上下文说明。

先运行当前全局/客户端/HTTP契约保护；实现后验证别名目录拒绝歧义与防漂移、MVC安全上下文、前端精确匹配/恶意文本/真实状态、RPC到HTTP的真实TCP身份分类、超时后按原命令查询。再串行完整资金/自然回归、全仓编译和三浏览器，前端测试/类型/构建及受影响格式检查；结果、指纹、hygiene和独立提交交付。不新增依赖，不触及暂停任务。

实施确认：CPM候选/准备、曝光回执、CPC候选/准备/回执/结算回复的解码IllegalArgumentException改为依赖未知，合法输入的12条读写路径专项保护；不改变既有CPA响应身份检查。内部caller拒绝码只在原PERMISSION_DENIED处增加分类信息。原115条目录及8个码族逐项保持。
