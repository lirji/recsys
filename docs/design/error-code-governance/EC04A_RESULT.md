# EC04a sandbox gRPC 服务端错误治理

状态：DONE / PASS；Hygiene 为 IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS。基线 f2149d7fa9140e29d543ddd55d51752e3cc8b36b；正式范围见 EC04A_DESIGN.md，父 EC04 仍 IN_PROGRESS。

## 实现和行为

五个 sandbox gRPC 服务及内部认证拦截器接入 GrpcErrors。目录新增 27 个 RPC 专用码（总 115），httpStatus=0 明确不直接用于 HTTP。gRPC 状态仍由业务边界选择，描述使用 [CODE] 目录安全文案，trailer recsys-error-code 只传稳定 ASCII 码。纯公共目录没有新增 gRPC/Spring 依赖，适配位于已有 proto 模块。

预算 IllegalArgumentException/PayloadConflict 不再输出 ex.getMessage()。五服务不再原样转发 StatusRuntimeException：本地私有受控异常保留目录码并重建 metadata；其他来源保留 gRPC 状态，重新选择目录内安全提示，丢弃原描述/cause/trailers。伪造合法目录名或篡改本地异常的可变 metadata 都不能夹带私有详情。OK 异常回退 INTERNAL。

UNCHANGED：原 gRPC 状态、成功及持久化业务拒绝对象、精确金额/身份、回执和命令恢复、授权、资金逻辑和事务。CHANGED：description 带稳定码、安全归一，增加 trailer；认证拒绝也有安全中文目录提示。预算任意校验详情和原样下游异常泄漏有意移除。未知结果仍按原 ID 查询，不自动重试、不声称失败已回滚。

## 验证

| 检查 | 结果 |
|---|---|
| 改前 RPC/认证/客户端状态保护 | PASS，23 项 / 6 类，0 失败/错误/跳过 |
| 改后 helper/认证/真实TCP/原边界与HTTP兼容 | PASS，41 项 / 10 类，0 失败/错误/跳过 |
| 真实 TCP 专项 | 上述 41 项含五服务各 1 项参数化测试，覆盖匿名、404、非法参数、冲突、未知结果、伪造描述/metadata、超时、限流 |
| 前端 | 139 项 / 20 文件通过；tsc+Vite build 通过；受影响生成 TS 的 Prettier/ESLint 通过 |
| 目录生成 | Python 原 5 项与 --check 通过 |
| SQL 边界 | 599 production Java / 90 Mapper XML / 45 历史 SQL 通过 |
| 完整资金链路 | PASS，398 项 / 71 类，0 失败/错误/跳过 |
| 全仓编译 | PASS，24 模块 |
| Hygiene | 无阻断，IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS |

真实 TCP 测试的服务依赖使用 mock，验证传输/错误边界，事务证明由独占 Testcontainers 的原资金套件承担。helper 单测遍历全部 gRPC 状态，验证原状态保留（OK 防守除外）、cause/额外 trailer 清除及本地可变 metadata 篡改恢复。未改前端页面行为；React act、Router future flag 等既有测试警告不记为新产品缺陷。

## 源码与审查

14 源文件清单 `/tmp/recsys-ec04a-source-paths.json`；路径 NUL 内容 NUL 排序 SHA-256：`3e53b6c2f0c172a26ec30802ca3018c6c45511e3c4465507378ecf98c11f6f25`。包括目录/生成器/双端生成物、GrpcErrors/测试、认证拦截器/测试、五服务、五服务 TCP 契约测试。

五服务公开失败已无 withDescription 原文或 observer.onError(ex) 透传。protobuf schema、资金 SQL/数据结构、部署配置、客户端重试策略均未改，无新增依赖。目录状态为 0 的错误不能直接写入 HTTP 响应；后续客户端映射必须使用实际 HTTP 契约，不能把 RPC 数字码当 HTTP 状态。

## 后续与限制

本片本地验收已完成，允许独立提交、正常 ff/push；新提交远程 CI 单独核验。父 EC04 的三个客户端、普通 AdServing/Content/User RPC、其余公开 HTTP/正常对象内错误仍待处理；EC05～08 继续。EC03已交付 f2149d7f；旧 bb0b CI38791578351 因工作流 cancel-in-progress 取消 build，其他四门成功，不称全绿。最终验收仍须等待最终 SHA 的完整 CI。

## 质量限制与可追溯证据

Hygiene 未发现阻断或魔法值 advisory。Java统一formatter/独立静态分析未配置，未冒充执行；TS生成文件的Prettier/ESLint已真实执行。验证后14项源指纹未变；原88错误条目和8族元数据逐项比较保持，只有27个新RPC条目及NO_HTTP_STATUS注释扩展。

本地命令（全部Maven串行）：

```sh
mvn -B -pl recsys-ad-serving -am test '-Dtest=InternalAuthGrpcServerInterceptorTest,Sandbox*GrpcTest,ClickOnlineBoundaryTest,ConversionOnlineBoundaryTest' -Dsurefire.failIfNoSpecifiedTests=false
mvn -B -pl recsys-ad-serving -am test '-Dtest=GrpcErrorsTest,InternalAuthGrpcServerInterceptorTest,Sandbox*GrpcTest,SandboxGrpcErrorContractTest,ClickOnlineBoundaryTest,ConversionOnlineBoundaryTest,BusinessErrorContractTest' -Dsurefire.failIfNoSpecifiedTests=false
mvn -B -pl recsys-rec-engine,recsys-ad-serving,recsys-advertiser,recsys-gateway -am test -Dgroups=money-chain -Dsurefire.failIfNoSpecifiedTests=false
mvn -B -DskipTests compile
# console目录
npm run test:run
npm run build
npx prettier --check src/api/generated/errorCodes.ts
npx eslint src/api/generated/errorCodes.ts
```

日志 SHA-256；frontend-tool-output 是会话24878后续stdout原文（不含首段启动横幅），该会话exit=0：

- `/tmp/recsys-ec04a-before.log`：`4b462afdc9dbcf30575bc5b29812988d9aef905b9cefc69b3f9412c8d374b401`
- `/tmp/recsys-ec04a-after.log`：`44f9e439feac93b0048d969fd7e42597d46dd63ead66e650750ff528f38d29cd`
- `/tmp/recsys-ec04a-money.log`：`734a69c5ee818a9be1fe51079350b1bade99a8ff308030e54111cdef427f5208`
- `/tmp/recsys-ec04a-compile.log`：`0d6481e5016f5c0ce1b77fd3834be5c8894a8f05bbcf1f6a34bd546424a18013`
- `/tmp/recsys-ec04a-frontend-tool-output.log`：`9e8da97db7231fde8fcc7a706807c5c7776f6c077332314c2ad69e610b609644`
- `/tmp/recsys-ec04a-hygiene.json`：`44fd7fb84ec310ff33697056c9dc5b3a965c8e8cd5985c4273b33263bafb1962`
