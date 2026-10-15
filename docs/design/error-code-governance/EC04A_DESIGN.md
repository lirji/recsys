# EC04a：sandbox gRPC 服务端安全错误

基线 f2149d7fa9140e29d543ddd55d51752e3cc8b36b。父 EC04 分为服务端与内部认证、客户端/其他 RPC 边界、其余公开 HTTP/正常响应内错误；本片只完成第一部分，不缩减父范围。

## 已证实问题与目标

SandboxBudgetGrpcService 对 PayloadConflict/IllegalArgumentException 使用 ex.getMessage()；五个 sandbox 服务均原样转发 StatusRuntimeException，可能携带上游任意描述、cause 和 trailers。其他固定提示散落。InternalAuthGrpcServerInterceptor 认证拒绝尚无统一业务码。

在 contracts/errors.json 登记 RPC 专用码和固定安全文案。RPC 专用码的 httpStatus=0 表示没有直接 HTTP 状态；gRPC 原状态继续由调用边界决定。复用已有认证码。recsys-proto（已依赖 common 和 grpc-api）提供 GrpcErrors：描述为 [CODE] 固定提示，ASCII trailer recsys-error-code 仅携带稳定码，不增加 protobuf 字段/依赖。common 不依赖 gRPC。

只有本地工厂创建的受控异常可保留其细分目录码；其他 StatusRuntimeException 保留原 gRPC status，用本地安全分类/当前操作的未知结果提示重建，不转发原 description/cause/trailers。伪造目录名的 metadata 不使其获得可信异常身份。错误异常若携带 OK 则防守回退 INTERNAL，不能结束为成功。503/UNAVAILABLE/超时或其他依赖不明时仍提示保留原业务 ID 查询，不自动重试。

## 文件与兼容边界

- 修改目录/生成物及 NO_HTTP_STATUS 注释；增加 proto/grpc/GrpcErrors 和单测。
- 五个 SandboxBudget/Delivery/Impression/Click/ConversionGrpcService 的 error factory/catch；InternalAuthGrpcServerInterceptor 拒绝响应接入。
- 扩展现有内部身份单测和真实 TCP 测试，覆盖五服务、固定码、安全文案、恶意异常/metadata、状态和原 ID 恢复。

状态码、成功/业务拒绝结果、字段、精确金额、认证主体与资金状态机 UNCHANGED。gRPC description 归一安全文案并带码、增加 trailer 为 CHANGED / 向后兼容扩展；旧客户端仍按 status 工作。安全细节泄漏有意修复。配置校验文案、三个客户端、普通 AdServing/Content/User RPC 与 console 返回对象内错误属于父 EC04 后续，本片不声称覆盖。

## 验证与交付

复用 EC03 刚完成的 393 项真实资金链路；另先跑 InternalAuthGrpcServerInterceptorTest、Sandbox*GrpcTest、ClickOnlineBoundaryTest、ConversionOnlineBoundaryTest 保护原边界。实现后运行新 helper/传输测试、原 HTTP 兼容测试和完整资金链路，目录生成 check、全仓编译和 hygiene。全部本地 Maven 串行、独占组件；验证后单独交付。回退整片代码/目录即可，无数据迁移或补偿。
